package com.m1racle.reader.pdfsplit.verify

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.DocumentsContract
import android.util.Log
import com.m1racle.reader.pdfsplit.split.LoadMode
import com.m1racle.reader.pdfsplit.split.PdfBoxSetup
import com.m1racle.reader.pdfsplit.split.PdfRangeSplitter
import com.m1racle.reader.pdfsplit.split.SplitDiagnostics
import com.m1racle.reader.pdfsplit.split.SplitEvent
import com.m1racle.reader.pdfsplit.split.SplitOutput
import com.m1racle.reader.pdfsplit.split.SplitPart
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSObject
import com.tom_roush.pdfbox.cos.COSStream
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionGoTo
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionURI
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageDestination
import com.tom_roush.pdfbox.rendering.PDFRenderer
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.IdentityHashMap
import kotlin.math.abs

// ───────────────────────────── evidence model ──────────────────────────────

/** One resource dictionary entry, as PDFBox sees it after dereferencing. */
internal data class ResourceStatus(
    val category: String,
    val name: String,
    val objectNumber: Long,
    /** OK / NULL / MISSING / ERR:<exception> */
    val status: String,
)

/** Everything a page is allowed to differ on, captured on both sides. */
internal data class PageSignature(
    val geometry: String,
    val contentSha: String,
    val contentBytes: Long,
    val resources: String,
    val resourceStatuses: List<ResourceStatus>,
    val annots: String,
    val annotCount: Int,
    val links: String,
    val linkCount: Int,
    val textSha: String,
)

/** A source object that did not resolve on the strict parse. */
internal class ObjectFinding(
    val objectNumber: Long,
    val category: String,
    val name: String,
    val status: String,
) {
    val pages: MutableSet<Int> = LinkedHashSet()
    val drawPages: MutableSet<Int> = LinkedHashSet()
    val key: String = "$objectNumber|$category|$name"
}

internal data class RenderDiff(
    val pageNumber1: Int,
    val diffPixels: Int,
    val maxChannelDiff: Int,
    val inkLenient: Int,
    val inkStrict: Int,
    val error: String?,
)

internal data class PageDiff(
    val partIndex: Int,
    val startPage1: Int,
    val endPage1: Int,
    val pageOffset: Int,
    val pageNumber1: Int,
    val fields: List<String>,
    val lenient: PageSignature?,
    val strict: PageSignature?,
    val render: RenderDiff?,
    val note: String?,
)

internal data class SplitRun(
    val outputs: List<SplitOutput>,
    val error: String?,
    val ms: Long,
    val failedParts: List<String>,
)

data class VerifyReport(
    val summaryText: String,
    val jsonText: String,
    val reportUri: String?,
    val localReportPath: String,
    val comparedPages: Int,
    val identicalPages: Int,
    val weakPages: Int,
    val diffPages: Int,
    val strictSplitFailed: Boolean,
    val findings: Int,
)

/**
 * A1 correctness harness. See [SplitVerifier] for the run order; this class is
 * the report writer plus the small shared helpers.
 */
internal class SplitVerifier(private val context: Context) {

    private val resolver = context.contentResolver

    private companion object {
        const val TAG = "M1VERIFY"
        /** The 21 in-use xref entries with offset 0 found on 2026-11-08. */
        val KNOWN_ZERO_OFFSET_OBJECTS = longArrayOf(
            1690, 2642, 3074, 3076, 3084, 3087, 3092, 3094, 3095, 3103, 3105,
            3106, 3114, 3119, 3124, 3136, 3139, 3143, 3147, 3148, 3156,
        )
        val RESOURCE_CATEGORIES = listOf(
            COSName.XOBJECT,
            COSName.FONT,
            COSName.EXT_G_STATE,
            COSName.COLORSPACE,
            COSName.PATTERN,
            COSName.SHADING,
            COSName.PROPERTIES,
        )
        /** A pixel counts as different when any channel moves more than this. */
        const val RENDER_PIXEL_THRESHOLD = 8
    }

    // ───────────────────────────── entry point ──────────────────────────────

    suspend fun verify(
        sourceUri: Uri,
        outputTreeUri: Uri,
        partPages: Int,
        renderCompare: Boolean,
        renderDpi: Int,
        onLog: suspend (String) -> Unit,
    ): VerifyReport = withContext(Dispatchers.IO) {
        val startedAt = SystemClock.elapsedRealtime()
        val journal = StringBuilder()

        suspend fun note(message: String) {
            val line = "[${(SystemClock.elapsedRealtime() - startedAt) / 1000}s] $message"
            journal.append(line).append('\n')
            Log.i(TAG, line)
            onLog(line)
        }

        PdfBoxSetup.ensureInit(context)

        // ── 0. 源文件落成本地随机访问文件 ────────────────────────────────
        note("准备源文件（provider 需复制一次）")
        val staged = PdfBoxSetup.stageSource(context, sourceUri) { copied, total ->
            if (total > 0 && copied % (256L * 1024 * 1024) < (256L * 1024)) {
                Log.i(TAG, "staging $copied / $total")
            }
        }
        val sourceFile = staged.file
        note("源文件=${sourceFile.name} bytes=${sourceFile.length()} 临时副本=${staged.temporary}")
        val localUri = Uri.fromFile(sourceFile)

        try {
            // ── 1. 两种 xref 策略各加载一次 ─────────────────────────────
            var lenientMs = -1L
            var lenientCpuMs = -1L
            var lenientPages = -1
            var lenientError: String? = null
            runCatching {
                val cpu0 = SplitDiagnostics.cpuMs()
                val t0 = SystemClock.elapsedRealtime()
                PdfBoxSetup.load(sourceFile, LoadMode.LENIENT).use { doc ->
                    lenientPages = doc.numberOfPages
                }
                lenientMs = SystemClock.elapsedRealtime() - t0
                lenientCpuMs = SplitDiagnostics.cpuMs() - cpu0
            }.onFailure { lenientError = describe(it) }
            note("lenient 加载 ${lenientMs}ms cpu=${lenientCpuMs}ms pages=$lenientPages error=$lenientError")

            var strictMs = -1L
            var strictCpuMs = -1L
            var strictPages = -1
            var strictError: String? = null
            val findings = LinkedHashMap<String, ObjectFinding>()
            var brokenPages = 0
            runCatching {
                val cpu0 = SplitDiagnostics.cpuMs()
                val t0 = SystemClock.elapsedRealtime()
                PdfBoxSetup.load(sourceFile, LoadMode.STRICT).use { doc ->
                    strictPages = doc.numberOfPages
                    strictMs = SystemClock.elapsedRealtime() - t0
                    strictCpuMs = SplitDiagnostics.cpuMs() - cpu0
                    note("strict 打开成功 ${strictMs}ms pages=$strictPages，开始对象清单审计")
                    brokenPages = auditSource(doc, findings)
                }
            }.onFailure {
                strictError = describe(it)
                if (strictMs < 0) strictMs = SystemClock.elapsedRealtime() - startedAt
            }
            note(
                "strict 加载 ${strictMs}ms pages=$strictPages error=$strictError " +
                    "不可解析资源条目=${findings.size} 受影响页=$brokenPages",
            )

            val pageCount = when {
                lenientPages > 0 -> lenientPages
                strictPages > 0 -> strictPages
                else -> throw IOException("两种策略都无法打开该 PDF")
            }

            // ── 2. 同一页码规划，两种策略各拆一遍 ───────────────────────
            val parts = buildParts(pageCount, partPages)
            note("页码规划=${parts.size} 段（每段≤$partPages 页）总页=$pageCount")

            note("开始 lenient 拆分（生产路径，concurrency=1）")
            val lenientRun = runSplit(
                localUri, outputTreeUri, pageCount, parts, LoadMode.LENIENT, "L", onLog,
            )
            note(
                "lenient 拆分 ${lenientRun.ms}ms 段=${lenientRun.outputs.size}/${parts.size} " +
                    "error=${lenientRun.error}",
            )

            note("开始 strict 拆分（候选路径）")
            var strictRun = runSplit(
                localUri, outputTreeUri, pageCount, parts, LoadMode.STRICT, "S", onLog,
            )
            if (strictRun.error != null && strictRun.outputs.isEmpty()) {
                note("strict 整本失败，改为逐段重试以定位失败页段")
                strictRun = runSplitPerPart(
                    localUri, outputTreeUri, pageCount, parts, "S", strictRun.error, onLog,
                )
            }
            note(
                "strict 拆分 ${strictRun.ms}ms 段=${strictRun.outputs.size}/${parts.size} " +
                    "error=${strictRun.error} 失败段=${strictRun.failedParts}",
            )

            // ── 3. 逐段逐页比对落盘产物 ─────────────────────────────────
            note("开始逐页比对（几何/内容流/资源/注释/链接/文本层）")
            val pageDiffs = ArrayList<PageDiff>()
            var comparedPages = 0
            var identicalPages = 0
            var weakPages = 0
            var renderAttempts = 0
            var renderFailures = 0

            for (index in parts.indices) {
                currentCoroutineContext().ensureActive()
                val part = parts[index]
                val lenientOut = lenientRun.outputs.firstOrNull { it.startPage1 == part.startPage1 }
                val strictOut = strictRun.outputs.firstOrNull { it.startPage1 == part.startPage1 }
                if (lenientOut == null || strictOut == null) {
                    pageDiffs += PageDiff(
                        partIndex = index,
                        startPage1 = part.startPage1,
                        endPage1 = part.endPage1,
                        pageOffset = -1,
                        pageNumber1 = part.startPage1,
                        fields = listOf("part-missing"),
                        lenient = null,
                        strict = null,
                        render = null,
                        note = "lenientPart=${lenientOut != null} strictPart=${strictOut != null}",
                    )
                    continue
                }
                openDocument(lenientOut.uri).use { lenientDoc ->
                    openDocument(strictOut.uri).use { strictDoc ->
                        val lenientCount = lenientDoc.numberOfPages
                        val strictCount = strictDoc.numberOfPages
                        if (lenientCount != strictCount) {
                            pageDiffs += PageDiff(
                                partIndex = index,
                                startPage1 = part.startPage1,
                                endPage1 = part.endPage1,
                                pageOffset = -1,
                                pageNumber1 = part.startPage1,
                                fields = listOf("page-count"),
                                lenient = null,
                                strict = null,
                                render = null,
                                note = "lenientPages=$lenientCount strictPages=$strictCount",
                            )
                        }
                        val lenientText = textDigests(lenientDoc)
                        val strictText = textDigests(strictDoc)
                        val lenientBuilder = PageSignatureBuilder(lenientDoc)
                        val strictBuilder = PageSignatureBuilder(strictDoc)
                        val lenientRenderer = if (renderCompare) PDFRenderer(lenientDoc) else null
                        val strictRenderer = if (renderCompare) PDFRenderer(strictDoc) else null
                        val shared = minOf(lenientCount, strictCount)
                        for (offset in 0 until shared) {
                            currentCoroutineContext().ensureActive()
                            val pageNumber = part.startPage1 + offset
                            val left = lenientBuilder.build(offset, lenientText[offset] ?: "-")
                            val right = strictBuilder.build(offset, strictText[offset] ?: "-")
                            // Guard against a false "identical": if neither side could
                            // produce content/resource evidence for this page, equality
                            // of two empty strings means nothing.
                            if (left.contentSha.startsWith("ERR:") ||
                                right.contentSha.startsWith("ERR:") ||
                                (left.resources.isEmpty() && right.resources.isEmpty())
                            ) {
                                weakPages += 1
                            }
                            val fields = diffFields(left, right).toMutableList()
                            var render: RenderDiff? = null
                            if (renderCompare && lenientRenderer != null && strictRenderer != null) {
                                renderAttempts += 1
                                render = compareRender(
                                    lenientRenderer, strictRenderer, offset, renderDpi, pageNumber,
                                )
                                if (render.error != null) renderFailures += 1
                                if (render.error == null && render.diffPixels > 0) fields += "render"
                            }
                            comparedPages += 1
                            if (fields.isEmpty()) {
                                identicalPages += 1
                            } else {
                                pageDiffs += PageDiff(
                                    partIndex = index,
                                    startPage1 = part.startPage1,
                                    endPage1 = part.endPage1,
                                    pageOffset = offset,
                                    pageNumber1 = pageNumber,
                                    fields = fields,
                                    lenient = left,
                                    strict = right,
                                    render = render,
                                    note = null,
                                )
                            }
                        }
                        Log.i(TAG, "compared part=${index + 1} pages=$shared diffs=${pageDiffs.size}")
                    }
                }
                onLog(
                    "[比对] 段 ${index + 1}/${parts.size} 累计差异页=" +
                        "${pageDiffs.count { it.pageOffset >= 0 }}",
                )
            }
            note(
                "比对完成 比对页=$comparedPages 完全一致=$identicalPages 差异页=" +
                    "${pageDiffs.count { it.pageOffset >= 0 }} 证据不足页=$weakPages " +
                    "渲染尝试=$renderAttempts 渲染失败=$renderFailures",
            )

            // ── 4. 输出报告 ────────────────────────────────────────────
            val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
                .format(java.util.Date())
            val renderSummary = RenderSummary(renderCompare, renderDpi, renderAttempts, renderFailures)
            val summary = buildSummaryText(
                stamp = stamp,
                sourceFile = sourceFile,
                staged = staged.temporary,
                pageCount = pageCount,
                partPages = partPages,
                lenientMs = lenientMs,
                lenientCpuMs = lenientCpuMs,
                lenientPages = lenientPages,
                lenientError = lenientError,
                strictMs = strictMs,
                strictCpuMs = strictCpuMs,
                strictPages = strictPages,
                strictError = strictError,
                parts = parts,
                lenientRun = lenientRun,
                strictRun = strictRun,
                comparedPages = comparedPages,
                identicalPages = identicalPages,
                weakPages = weakPages,
                pageDiffs = pageDiffs,
                findings = findings.values.toList(),
                brokenPages = brokenPages,
                render = renderSummary,
                journal = journal.toString(),
            )
            val json = buildJson(
                stamp = stamp,
                sourceFile = sourceFile,
                pageCount = pageCount,
                parts = parts,
                lenientMs = lenientMs,
                lenientCpuMs = lenientCpuMs,
                lenientPages = lenientPages,
                lenientError = lenientError,
                strictMs = strictMs,
                strictCpuMs = strictCpuMs,
                strictPages = strictPages,
                strictError = strictError,
                lenientRun = lenientRun,
                strictRun = strictRun,
                comparedPages = comparedPages,
                identicalPages = identicalPages,
                weakPages = weakPages,
                pageDiffs = pageDiffs,
                findings = findings.values.toList(),
                render = renderSummary,
            )

            val reportName = "A1-验证报告-$stamp"
            val written = writeReport(outputTreeUri, reportName, summary, json)
            val localDir = File(context.getExternalFilesDir(null), "a1-verify")
            localDir.mkdirs()
            val localTxt = File(localDir, "$reportName.txt")
            val localJson = File(localDir, "$reportName.json")
            runCatching { localTxt.writeText(summary) }
            runCatching { localJson.writeText(json) }
            note("报告已写入 ${written ?: "（SAF 写入失败，仅本地）"} 本地=$localTxt")

            VerifyReport(
                summaryText = summary,
                jsonText = json,
                reportUri = written,
                localReportPath = localTxt.absolutePath,
                comparedPages = comparedPages,
                identicalPages = identicalPages,
                weakPages = weakPages,
                diffPages = pageDiffs.count { it.pageOffset >= 0 },
                strictSplitFailed = strictRun.error != null,
                findings = findings.size,
            )
        } finally {
            if (staged.temporary) sourceFile.delete()
        }
    }

    // ───────────────────────────── source audit ─────────────────────────────

    /**
     * Walks every page of the strict parse and records resource entries that do
     * not resolve. For each such entry the page content stream (plus resolvable
     * form XObjects, depth 2) is searched for a `/Name Do` invocation, which is
     * what separates "dangling reference nobody draws" from "page loses art".
     */
    private fun auditSource(doc: PDDocument, findings: MutableMap<String, ObjectFinding>): Int {
        val pageCount = doc.numberOfPages
        var brokenPages = 0
        for (pageIndex in 0 until pageCount) {
            val page = runCatching { doc.getPage(pageIndex) }.getOrNull() ?: continue
            val entries = resourceEntries(page)
            val broken = entries.filter { it.status != "OK" }
            if (broken.isEmpty()) continue
            brokenPages += 1
            val contentText = pageContentText(page)
            for (entry in broken) {
                val key = "${entry.objectNumber}|${entry.category}|${entry.name}"
                val finding = findings.getOrPut(key) {
                    ObjectFinding(entry.objectNumber, entry.category, entry.name, entry.status)
                }
                finding.pages += pageIndex + 1
                if (contentText != null && invokes(contentText, entry.name)) {
                    finding.drawPages += pageIndex + 1
                }
            }
        }
        Log.i(
            TAG,
            "audit pages=$pageCount brokenPages=$brokenPages findings=${findings.size}",
        )
        return brokenPages
    }

    /** Page content plus form XObject streams, decoded, as latin-1 text. */
    private fun pageContentText(page: PDPage): String? {
        val builder = StringBuilder()
        val pageBytes = runCatching {
            if (!page.hasContents()) ByteArray(0) else page.contents.use { it.readBytes() }
        }.getOrNull() ?: return null
        builder.append(String(pageBytes, Charsets.ISO_8859_1))
        // One level of forms is enough for the observed defects and keeps the
        // audit linear in the number of pages.
        val resources = runCatching { page.resources }.getOrNull() ?: return builder.toString()
        val xObjects = runCatching {
            resources.cosObject.getDictionaryObject(COSName.XOBJECT) as? COSDictionary
        }.getOrNull() ?: return builder.toString()
        var visited = 0
        for (key in xObjects.keySet()) {
            if (visited >= 8) break
            val stream = runCatching { xObjects.getDictionaryObject(key) as? COSStream }.getOrNull()
                ?: continue
            val isForm = runCatching {
                stream.getCOSName(COSName.SUBTYPE) == COSName.FORM
            }.getOrDefault(false)
            if (!isForm) continue
            val bytes = runCatching { stream.createInputStream().use { it.readBytes() } }.getOrNull()
                ?: continue
            visited += 1
            builder.append('\n').append(String(bytes, Charsets.ISO_8859_1))
        }
        return builder.toString()
    }

    private fun invokes(content: String, name: String): Boolean {
        val pattern = Regex("(?<![A-Za-z0-9])/" + Regex.escape(name) + "\\s+Do(?![A-Za-z0-9])")
        return pattern.containsMatchIn(content)
    }

    /** Resource entries of one page, dereferenced through PDFBox. */
    private fun resourceEntries(page: PDPage): List<ResourceStatus> {
        val resources = runCatching { page.resources }.getOrNull() ?: return emptyList()
        val dict = runCatching { resources.cosObject }.getOrNull() ?: return emptyList()
        val out = ArrayList<ResourceStatus>()
        for (category in RESOURCE_CATEGORIES) {
            val sub = runCatching {
                dict.getDictionaryObject(category) as? COSDictionary
            }.getOrNull() ?: continue
            for (key in sub.keySet()) {
                val raw = sub.getItem(key)
                val objectNumber = (raw as? COSObject)?.objectNumber ?: -1L
                val resolved = runCatching { sub.getDictionaryObject(key) }
                val status = when {
                    resolved.isFailure ->
                        "ERR:" + (resolved.exceptionOrNull()?.javaClass?.simpleName ?: "?")
                    resolved.getOrNull() == null -> if (raw == null) "MISSING" else "NULL"
                    else -> "OK"
                }
                out += ResourceStatus(category.name, key.name, objectNumber, status)
            }
        }
        return out
    }

    // ───────────────────────────── split runs ───────────────────────────────

    private fun buildParts(pageCount: Int, partPages: Int): List<SplitPart> {
        val size = partPages.coerceIn(1, pageCount)
        val parts = ArrayList<SplitPart>()
        var start = 1
        while (start <= pageCount) {
            val end = minOf(start + size - 1, pageCount)
            parts += SplitPart(
                index = parts.size,
                startPage1 = start,
                endPage1 = end,
                outputName = String.format(java.util.Locale.US, "p%03d-%03d.pdf", start, end),
            )
            start = end + 1
        }
        return parts
    }

    private suspend fun runSplit(
        sourceUri: Uri,
        outputTreeUri: Uri,
        pageCount: Int,
        parts: List<SplitPart>,
        mode: LoadMode,
        prefix: String,
        onLog: suspend (String) -> Unit,
    ): SplitRun {
        val named = parts.mapIndexed { index, part ->
            part.copy(index = index, outputName = "$prefix-${part.outputName}")
        }
        val started = SystemClock.elapsedRealtime()
        var error: String? = null
        var produced: List<SplitOutput> = emptyList()
        var lastReported = -1
        try {
            produced = PdfRangeSplitter(context, mode).split(
                sourceUri = sourceUri,
                outputTreeUri = outputTreeUri,
                expectedPageCount = pageCount,
                parts = named,
                deepVerify = false,
                concurrency = 1,
                onEvent = { event ->
                    if (event is SplitEvent.Tasks && event.finishedParts != lastReported) {
                        lastReported = event.finishedParts
                        if (lastReported == named.size ||
                            (lastReported > 0 && lastReported % 5 == 0)
                        ) {
                            onLog("[$prefix] 已落盘 $lastReported/${named.size} 段")
                        }
                    }
                },
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            error = describe(failure)
        }
        return SplitRun(produced, error, SystemClock.elapsedRealtime() - started, emptyList())
    }

    /**
     * Whole-book strict failed; retrying one part at a time keeps a single bad
     * page range from erasing the evidence for the other 400 pages. Each part
     * costs one extra strict load, which is exactly what makes this a fallback.
     */
    private suspend fun runSplitPerPart(
        sourceUri: Uri,
        outputTreeUri: Uri,
        pageCount: Int,
        parts: List<SplitPart>,
        prefix: String,
        firstError: String?,
        onLog: suspend (String) -> Unit,
    ): SplitRun {
        val started = SystemClock.elapsedRealtime()
        val outputs = ArrayList<SplitOutput>()
        val failed = ArrayList<String>()
        for ((index, part) in parts.withIndex()) {
            currentCoroutineContext().ensureActive()
            // runSplit adds the "S-" prefix itself; pass the bare name here.
            val single = listOf(part.copy(index = 0))
            val one = runSplit(sourceUri, outputTreeUri, pageCount, single, LoadMode.STRICT, prefix, onLog)
            if (one.error == null) {
                outputs += one.outputs
            } else {
                failed += "${part.startPage1}-${part.endPage1}: ${one.error}"
            }
            onLog("[$prefix] 逐段重试 ${index + 1}/${parts.size} 失败=${failed.size}")
        }
        val error = if (failed.isEmpty()) firstError else failed.joinToString(" | ")
        return SplitRun(outputs, error, SystemClock.elapsedRealtime() - started, failed)
    }

    // ───────────────────────────── page evidence ────────────────────────────

    private fun openDocument(uri: Uri): PDDocument {
        val input = resolver.openInputStream(uri) ?: throw IOException("无法打开 $uri")
        return input.use { PDDocument.load(it, PdfBoxSetup.memorySetting()) }
    }

    private fun textDigests(doc: PDDocument): Map<Int, String> = runCatching {
        val stripper = PageTextDigestStripper()
        stripper.startPage = 1
        stripper.endPage = doc.numberOfPages
        stripper.getText(doc)
        stripper.digests()
    }.getOrDefault(emptyMap())

    private fun diffFields(left: PageSignature, right: PageSignature): List<String> {
        val fields = ArrayList<String>(6)
        if (left.geometry != right.geometry) fields += "geometry"
        if (left.contentSha != right.contentSha || left.contentBytes != right.contentBytes) {
            fields += "content"
        }
        if (left.resources != right.resources) fields += "resources"
        if (left.annots != right.annots || left.annotCount != right.annotCount) fields += "annots"
        if (left.links != right.links || left.linkCount != right.linkCount) fields += "links"
        if (left.textSha != right.textSha) fields += "text"
        return fields
    }

    private fun compareRender(
        lenientRenderer: PDFRenderer,
        strictRenderer: PDFRenderer,
        pageIndex: Int,
        dpi: Int,
        pageNumber1: Int,
    ): RenderDiff {
        var left: Bitmap? = null
        var right: Bitmap? = null
        try {
            val leftBitmap = lenientRenderer.renderImageWithDPI(pageIndex, dpi.toFloat())
            left = leftBitmap
            val rightBitmap = strictRenderer.renderImageWithDPI(pageIndex, dpi.toFloat())
            right = rightBitmap
            if (leftBitmap.width != rightBitmap.width || leftBitmap.height != rightBitmap.height) {
                return RenderDiff(
                    pageNumber1 = pageNumber1,
                    diffPixels = -1,
                    maxChannelDiff = -1,
                    inkLenient = -1,
                    inkStrict = -1,
                    error = "size ${leftBitmap.width}x${leftBitmap.height} " +
                        "vs ${rightBitmap.width}x${rightBitmap.height}",
                )
            }
            val width = leftBitmap.width
            val height = leftBitmap.height
            val leftPixels = IntArray(width * height)
            val rightPixels = IntArray(width * height)
            leftBitmap.getPixels(leftPixels, 0, width, 0, 0, width, height)
            rightBitmap.getPixels(rightPixels, 0, width, 0, 0, width, height)
            var diffPixels = 0
            var maxDiff = 0
            var inkLeft = 0
            var inkRight = 0
            for (i in leftPixels.indices) {
                val a = leftPixels[i]
                val b = rightPixels[i]
                var delta = 0
                for (shift in intArrayOf(0, 8, 16)) {
                    val diff = abs(((a ushr shift) and 0xFF) - ((b ushr shift) and 0xFF))
                    if (diff > delta) delta = diff
                }
                if (delta > RENDER_PIXEL_THRESHOLD) diffPixels += 1
                if (delta > maxDiff) maxDiff = delta
                if (((a ushr 8) and 0xFF) < 245) inkLeft += 1
                if (((b ushr 8) and 0xFF) < 245) inkRight += 1
            }
            return RenderDiff(pageNumber1, diffPixels, maxDiff, inkLeft, inkRight, null)
        } catch (failure: Throwable) {
            return RenderDiff(pageNumber1, -1, -1, -1, -1, describe(failure))
        } finally {
            left?.recycle()
            right?.recycle()
        }
    }

    // ───────────────────────────── report writing ───────────────────────────

    private fun writeReport(tree: Uri, name: String, text: String, json: String): String? {
        val parent = runCatching {
            DocumentsContract.buildDocumentUriUsingTree(
                tree,
                DocumentsContract.getTreeDocumentId(tree),
            )
        }.getOrNull() ?: return null
        val textUri = runCatching {
            DocumentsContract.createDocument(resolver, parent, "text/plain", "$name.txt")
        }.getOrNull()
        if (textUri != null) {
            runCatching { resolver.openOutputStream(textUri, "wt")?.use { it.write(text.toByteArray()) } }
        }
        runCatching {
            val jsonUri = DocumentsContract.createDocument(
                resolver, parent, "application/json", "$name.json",
            ) ?: return@runCatching
            resolver.openOutputStream(jsonUri, "wt")?.use { it.write(json.toByteArray()) }
        }
        return textUri?.toString()
    }

    private fun buildSummaryText(
        stamp: String,
        sourceFile: File,
        staged: Boolean,
        pageCount: Int,
        partPages: Int,
        lenientMs: Long,
        lenientCpuMs: Long,
        lenientPages: Int,
        lenientError: String?,
        strictMs: Long,
        strictCpuMs: Long,
        strictPages: Int,
        strictError: String?,
        parts: List<SplitPart>,
        lenientRun: SplitRun,
        strictRun: SplitRun,
        comparedPages: Int,
        identicalPages: Int,
        weakPages: Int,
        pageDiffs: List<PageDiff>,
        findings: List<ObjectFinding>,
        brokenPages: Int,
        render: RenderSummary,
        journal: String,
    ): String {
        val out = StringBuilder()
        val diffPages = pageDiffs.filter { it.pageOffset >= 0 }
        out.appendLine("M1racle PDF 拆分 · A1 正确性验证报告")
        out.appendLine("=".repeat(64))
        out.appendLine("生成时间   : $stamp")
        out.appendLine("设备       : ${Build.MANUFACTURER} ${Build.MODEL} / Android ${Build.VERSION.RELEASE}")
        out.appendLine("引擎       : pdfbox-android 2.0.27.0（LENIENT=生产 / STRICT=候选）")
        out.appendLine("源文件     : ${sourceFile.name}  bytes=${sourceFile.length()}  pages=$pageCount")
        out.appendLine("源文件来源 : ${if (staged) "SAF 临时副本" else "本地文件直读"}")
        out.appendLine()
        out.appendLine("一、加载（同一文件、同一 scratch 设置，唯一变量 isLenient）")
        out.appendLine("  lenient : ${lenientMs}ms  cpu=${lenientCpuMs}ms  pages=$lenientPages  error=${lenientError ?: "无"}")
        out.appendLine("  strict  : ${strictMs}ms  cpu=${strictCpuMs}ms  pages=$strictPages  error=${strictError ?: "无"}")
        if (lenientMs > 0 && strictMs > 0) {
            out.appendLine("  倍率    : ${String.format(java.util.Locale.US, "%.2f", lenientMs.toDouble() / strictMs)}x")
        }
        out.appendLine()
        out.appendLine("二、拆分（生产 splitter，每段≤$partPages 页，concurrency=1，deepVerify=false）")
        out.appendLine("  lenient : ${lenientRun.outputs.size}/${parts.size} 段  ${lenientRun.ms}ms  error=${lenientRun.error ?: "无"}")
        out.appendLine("  strict  : ${strictRun.outputs.size}/${parts.size} 段  ${strictRun.ms}ms  error=${strictRun.error ?: "无"}")
        if (strictRun.failedParts.isNotEmpty()) {
            out.appendLine("  strict 失败段：")
            strictRun.failedParts.forEach { out.appendLine("    - $it") }
        }
        out.appendLine()
        out.appendLine("三、逐页比对（比对对象=落盘产物，重新打开后取指纹）")
        out.appendLine("  比对页数=$comparedPages  完全一致=$identicalPages  差异页=${diffPages.size}")
        out.appendLine("  证据不足页=$weakPages（两侧都无法取出内容流/资源，此时“一致”不代表内容正确）")
        out.appendLine("  字段：geometry 页面几何 / content 内容流 SHA-256 / resources 资源字典 /")
        out.appendLine("        annots 注释 / links 内部链接与外部链接 / text 文本层")
        out.appendLine()
        out.appendLine("四、差异页清单")
        if (diffPages.isEmpty() && pageDiffs.isEmpty()) {
            out.appendLine("  （无）")
        } else {
            pageDiffs.take(300).forEach { diff ->
                if (diff.pageOffset < 0) {
                    out.appendLine("  [段 ${diff.partIndex + 1} 页 ${diff.startPage1}-${diff.endPage1}] ${diff.fields} ${diff.note ?: ""}")
                } else {
                    out.appendLine(
                        "  第 ${diff.pageNumber1} 页  段${diff.partIndex + 1}  字段=${diff.fields} " +
                            "render=${diff.render?.let { "diffPx=${it.diffPixels} max=${it.maxChannelDiff} ink=${it.inkLenient}/${it.inkStrict}" } ?: "-"}",
                    )
                }
            }
            if (pageDiffs.size > 300) out.appendLine("  …… 另有 ${pageDiffs.size - 300} 条（见 JSON）")
        }
        out.appendLine()
        out.appendLine("五、strict 侧不可解析资源（源文件对象号 / 类别 / 名称）")
        if (findings.isEmpty()) {
            out.appendLine("  （无：strict 解析下所有页面的资源条目都可解析）")
        } else {
            out.appendLine("  受影响页数=$brokenPages  条目数=${findings.size}")
            findings.sortedBy { it.objectNumber }.forEach { finding ->
                out.appendLine(
                    "  obj=${finding.objectNumber} ${finding.category}/${finding.name} " +
                        "状态=${finding.status} 引用页=${finding.pages.take(12)}" +
                        "${if (finding.pages.size > 12) "…(${finding.pages.size})" else ""} " +
                        "Do实际绘制页=${finding.drawPages.take(12)}",
                )
            }
        }
        out.appendLine()
        out.appendLine("六、历史已知 21 条 in-use offset=0 对象核对（本次 strict 解析结果）")
        for (number in KNOWN_ZERO_OFFSET_OBJECTS) {
            val related = findings.filter { it.objectNumber == number }
            if (related.isEmpty()) {
                out.appendLine("  obj=$number  未出现在不可解析清单（或被其它对象号承载）")
            } else {
                related.forEach { finding ->
                    out.appendLine(
                        "  obj=$number  ${finding.category}/${finding.name} 状态=${finding.status} " +
                            "引用页=${finding.pages.size} 绘制页=${finding.drawPages.size}",
                    )
                }
            }
        }
        out.appendLine()
        out.appendLine("七、渲染像素比对")
        if (!render.enabled) {
            out.appendLine("  本次未开启（开关关闭）。建议在电脑上用独立渲染器对 L-/S- 产物做全量比对。")
        } else {
            out.appendLine(
                "  dpi=${render.dpi} 比对页=${render.attempts} 渲染失败=${render.failures}" +
                    "（失败页在四、中标注 render 字段为空但页面字段可能一致）",
            )
            out.appendLine("  注意：此处的渲染器是 PDFBox 自身，只作一阶校验；独立渲染器复核在 PC 上执行。")
        }
        out.appendLine()
        out.appendLine("八、产物")
        out.appendLine("  L-* 段：${lenientRun.outputs.size} 个；S-* 段：${strictRun.outputs.size} 个，均在所选输出目录")
        out.appendLine("  比对口径：页数→几何→内容流摘要→资源字典→注释/链接→文本层" +
            if (render.enabled) "→渲染像素" else "")
        out.appendLine()
        out.appendLine("九、运行日志")
        journal.lines().forEach { if (it.isNotBlank()) out.appendLine("  $it") }
        out.appendLine()
        out.appendLine("说明：本文件只罗列证据，不下“通过/不通过”结论；结论按差异页清单与资源清单逐条判定。")
        return out.toString()
    }

    private fun buildJson(
        stamp: String,
        sourceFile: File,
        pageCount: Int,
        parts: List<SplitPart>,
        lenientMs: Long,
        lenientCpuMs: Long,
        lenientPages: Int,
        lenientError: String?,
        strictMs: Long,
        strictCpuMs: Long,
        strictPages: Int,
        strictError: String?,
        lenientRun: SplitRun,
        strictRun: SplitRun,
        comparedPages: Int,
        identicalPages: Int,
        weakPages: Int,
        pageDiffs: List<PageDiff>,
        findings: List<ObjectFinding>,
        render: RenderSummary,
    ): String {
        val root = JSONObject()
        root.put("generatedAt", stamp)
        root.put("device", "${Build.MANUFACTURER} ${Build.MODEL} / Android ${Build.VERSION.RELEASE}")
        root.put(
            "source",
            JSONObject()
                .put("name", sourceFile.name)
                .put("bytes", sourceFile.length())
                .put("pages", pageCount),
        )
        root.put(
            "load",
            JSONObject()
                .put(
                    "lenient",
                    JSONObject().put("wallMs", lenientMs).put("cpuMs", lenientCpuMs)
                        .put("pages", lenientPages).put("error", lenientError ?: JSONObject.NULL),
                )
                .put(
                    "strict",
                    JSONObject().put("wallMs", strictMs).put("cpuMs", strictCpuMs)
                        .put("pages", strictPages).put("error", strictError ?: JSONObject.NULL),
                ),
        )
        root.put(
            "split",
            JSONObject()
                .put(
                    "lenient",
                    JSONObject().put("parts", lenientRun.outputs.size)
                        .put("totalParts", parts.size).put("wallMs", lenientRun.ms)
                        .put("error", lenientRun.error ?: JSONObject.NULL),
                )
                .put(
                    "strict",
                    JSONObject().put("parts", strictRun.outputs.size)
                        .put("totalParts", parts.size).put("wallMs", strictRun.ms)
                        .put("error", strictRun.error ?: JSONObject.NULL)
                        .put("failedParts", JSONArray(strictRun.failedParts)),
                ),
        )
        root.put(
            "compare",
            JSONObject().put("comparedPages", comparedPages)
                .put("identicalPages", identicalPages)
                .put("weakEvidencePages", weakPages)
                .put("diffPages", pageDiffs.count { it.pageOffset >= 0 })
                .put("renderEnabled", render.enabled)
                .put("renderDpi", render.dpi)
                .put("renderAttempts", render.attempts)
                .put("renderFailures", render.failures),
        )
        root.put(
            "parts",
            JSONArray().apply {
                parts.forEach { part ->
                    put(
                        JSONObject().put("index", part.index).put("startPage1", part.startPage1)
                            .put("endPage1", part.endPage1).put("pages", part.pageCount),
                    )
                }
            },
        )
        root.put(
            "pageDiffs",
            JSONArray().apply {
                pageDiffs.forEach { diff ->
                    put(
                        JSONObject()
                            .put("partIndex", diff.partIndex)
                            .put("startPage1", diff.startPage1)
                            .put("endPage1", diff.endPage1)
                            .put("pageOffset", diff.pageOffset)
                            .put("pageNumber1", diff.pageNumber1)
                            .put("fields", JSONArray(diff.fields))
                            .put("note", diff.note ?: JSONObject.NULL)
                            .put("lenient", signatureJson(diff.lenient))
                            .put(
                                "strict",
                                signatureJson(diff.strict),
                            )
                            .put(
                                "render",
                                diff.render?.let { r ->
                                    JSONObject().put("diffPixels", r.diffPixels)
                                        .put("maxChannelDiff", r.maxChannelDiff)
                                        .put("inkLenient", r.inkLenient)
                                        .put("inkStrict", r.inkStrict)
                                        .put("error", r.error ?: JSONObject.NULL)
                                } ?: JSONObject.NULL,
                            ),
                    )
                }
            },
        )
        root.put(
            "unresolvedResources",
            JSONArray().apply {
                findings.sortedBy { it.objectNumber }.forEach { finding ->
                    put(
                        JSONObject().put("objectNumber", finding.objectNumber)
                            .put("category", finding.category)
                            .put("name", finding.name)
                            .put("status", finding.status)
                            .put("pages", JSONArray(finding.pages.toList()))
                            .put("drawPages", JSONArray(finding.drawPages.toList())),
                    )
                }
            },
        )
        root.put(
            "knownZeroOffsetObjects",
            JSONArray().apply {
                KNOWN_ZERO_OFFSET_OBJECTS.forEach { number ->
                    val related = findings.filter { it.objectNumber == number }
                    put(
                        JSONObject().put("objectNumber", number)
                            .put("unresolved", related.isNotEmpty())
                            .put("categories", JSONArray(related.map { it.category }))
                            .put("pages", JSONArray(related.flatMap { it.pages }.distinct()))
                            .put("drawPages", JSONArray(related.flatMap { it.drawPages }.distinct())),
                    )
                }
            },
        )
        return root.toString(2)
    }

    private fun signatureJson(signature: PageSignature?): Any {
        if (signature == null) return JSONObject.NULL
        return JSONObject()
            .put("geometry", signature.geometry)
            .put("contentSha", signature.contentSha)
            .put("contentBytes", signature.contentBytes)
            .put("resources", signature.resources)
            .put("annotCount", signature.annotCount)
            .put("annots", signature.annots)
            .put("linkCount", signature.linkCount)
            .put("links", signature.links)
            .put("textSha", signature.textSha)
    }

    // ───────────────────────────── helpers ─────────────────────────────────

    private fun describe(failure: Throwable): String =
        "${failure.javaClass.simpleName}: ${failure.message ?: ""}"

    private fun digestOf(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    // ───────────────────────────── inner builders ───────────────────────────

    /** Page fingerprints taken from one document. */
    private inner class PageSignatureBuilder(private val document: PDDocument) {

        private val indexOf = IdentityHashMap<COSDictionary, Int>()

        init {
            val count = document.numberOfPages
            for (index in 0 until count) {
                runCatching { indexOf[document.getPage(index).cosObject] = index }
            }
        }

        fun build(pageIndex: Int, textSha: String): PageSignature {
            val page = document.getPage(pageIndex)
            val geometry = runCatching {
                listOf(
                    page.mediaBox,
                    page.cropBox,
                    page.bleedBox,
                    page.trimBox,
                    page.artBox,
                    page.userUnit,
                    page.rotation,
                ).joinToString("|")
            }.getOrElse { "ERR:" + describe(it) }

            val content = runCatching { contentInfo(page) }
            val contentSha = content.map { it.first }.getOrElse { "ERR:" + describe(it) }
            val contentBytes = content.map { it.second }.getOrDefault(-1L)

            val statuses = runCatching { resourceEntries(page) }.getOrDefault(emptyList())
            val resourceString = statuses.joinToString(";") { entry ->
                "${entry.category}/${entry.name}=${entry.status}" +
                    if (entry.objectNumber >= 0) "#${entry.objectNumber}" else ""
            }

            var annotCount = 0
            var linkCount = 0
            val annots = ArrayList<String>()
            val links = ArrayList<String>()
            runCatching {
                val annotations = page.annotations
                annotCount = annotations.size
                for (annotation in annotations) {
                    annots += runCatching { annotation.subtype }.getOrDefault("?")
                    if (annotation is PDAnnotationLink) {
                        linkCount += 1
                        links += linkTarget(annotation)
                    }
                }
            }.onFailure { annots += "ERR:" + describe(it) }

            return PageSignature(
                geometry = geometry,
                contentSha = contentSha,
                contentBytes = contentBytes,
                resources = resourceString,
                resourceStatuses = statuses,
                annots = annots.sorted().joinToString(","),
                annotCount = annotCount,
                links = links.sorted().joinToString(","),
                linkCount = linkCount,
                textSha = textSha,
            )
        }

        private fun linkTarget(annotation: PDAnnotationLink): String {
            val action = runCatching { annotation.action }.getOrNull()
            return when (action) {
                null -> "none"
                is PDActionGoTo -> {
                    val destination = runCatching { action.destination }.getOrNull()
                    if (destination is PDPageDestination) {
                        val target = runCatching { destination.page }.getOrNull()
                        val index = target?.let { indexOf[it.cosObject] } ?: -1
                        if (index >= 0) {
                            "goto:$index"
                        } else {
                            "goto#pageNumber:${runCatching { destination.pageNumber }.getOrDefault(-1)}"
                        }
                    } else {
                        "goto:other"
                    }
                }
                is PDActionURI -> "uri:${runCatching { action.uri }.getOrDefault("?")}"
                else -> "action:${action.javaClass.simpleName}"
            }
        }

        private fun contentInfo(page: PDPage): Pair<String, Long> {
            if (!page.hasContents()) return digestOf(ByteArray(0)) to 0L
            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L
            page.contents.use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                    total += read
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) } to total
        }
    }

    /** Collects extracted text per page so the text layer can be diffed. */
    private class PageTextDigestStripper : PDFTextStripper() {
        private val buffers = HashMap<Int, StringBuilder>()

        override fun writeString(text: String) {
            val page = runCatching { currentPageNo }.getOrDefault(0)
            buffers.getOrPut(page) { StringBuilder() }.append(text)
        }

        fun digests(): Map<Int, String> = buffers.mapValues { (_, builder) ->
            MessageDigest.getInstance("SHA-256").digest(builder.toString().toByteArray())
                .joinToString("") { "%02x".format(it) }
        }
    }
}

internal data class RenderSummary(
    val enabled: Boolean,
    val dpi: Int,
    val attempts: Int,
    val failures: Int,
)
