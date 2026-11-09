package com.m1racle.reader.pdfsplit.split

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.SystemClock
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSObject
import com.tom_roush.pdfbox.cos.COSStream
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.io.RandomAccessBufferedFileInputStream
import com.tom_roush.pdfbox.io.ScratchFile
import com.tom_roush.pdfbox.pdfparser.PDFParser
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDResources
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Xref policy for [PdfBoxSetup.open] / [PdfBoxSetup.load].
 *
 * PDFBox's default is lenient: when a single cross-reference entry cannot be
 * dereferenced it distrusts the whole table and rescans the file byte by byte.
 * On a 988 MB / 420-page textbook that is the difference between 60,391 ms and
 * 2,730 ms (measured 2026-11-09 on a Xiaomi 25098PN5AC), and it is the single
 * slowest thing this tool does.
 */
enum class LoadMode {
    /**
     * PDFBox default. Always succeeds eventually, at the cost of a full-file
     * brute-force rescan whenever the xref table has any defective entry.
     */
    LENIENT,

    /**
     * `isLenient = false`. Fast, but a defective table is rejected outright.
     * Used as the first attempt by [AUTO], and directly by the A1 harness.
     */
    STRICT,

    /**
     * Try [STRICT], prove the page tree is still readable, and fall back to
     * [LENIENT] if either step fails. This is the production policy.
     */
    AUTO,
}

/**
 * Outcome of the pre-flight page walk. [fatal] is null when the fast parse is
 * safe to hand to the caller; [softMisses] counts references that resolve to
 * nothing on both parsers and are therefore not a strict-specific risk.
 */
internal data class PageTreeProbe(
    val fatal: String?,
    val softMisses: Int,
    val softSamples: List<String>,
)

/** Which path a document actually came in on, plus what it cost. */
internal data class LoadOutcome(
    val document: PDDocument,
    /** The mode that produced this document: always [LoadMode.STRICT] or [LoadMode.LENIENT]. */
    val mode: LoadMode,
    val loadMs: Long,
    /** Wall time of the strict attempt, or -1 when it was never tried. */
    val strictMs: Long,
    val xref: XrefReport?,
    /** Human-readable account of the decision, for logcat and the UI. */
    val note: String,
) {
    val usedFastPath: Boolean get() = mode == LoadMode.STRICT
}

/**
 * Process-wide PDFBox bootstrap for the standalone split tool.
 *
 * Two deliberate choices keep large documents safe:
 *  - [memorySetting] pins PDFBox to an app-owned scratch directory, so a 1 GB
 *    PDF never becomes a 1 GB heap allocation.
 *  - [stageSource] resolves external-storage documents onto their real file so
 *    PDFBox can use random access. Only provider-backed URIs are copied once
 *    into app storage.
 */
internal object PdfBoxSetup {
    private const val SCRATCH_DIR_NAME = "pdfbox-scratch"
    private const val SOURCE_DIR_NAME = "pdf-split-source"
    private const val COPY_BUFFER_BYTES = 256 * 1024

    /** How deep a Form XObject chain is followed before giving up. */
    private const val MAX_FORM_DEPTH = 2

    /** Soft misses listed individually; the rest are only counted. */
    private const val SOFT_SAMPLE_LIMIT = 8

    /** Per-page content-stream budget for the draw-operator scan. */
    private const val MAX_CONTENT_SCAN = 1 shl 20

    /**
     * Resource categories whose entries are resolved on the fast path. A page
     * whose artwork lives behind a defective object shows up here, which is the
     * one failure mode a fast parse can hide silently.
     */
    private val PAGE_RESOURCE_KEYS = listOf(
        COSName.XOBJECT,
        COSName.FONT,
        COSName.EXT_G_STATE,
        COSName.COLORSPACE,
        COSName.PATTERN,
        COSName.SHADING,
        COSName.PROPERTIES,
    )

    private val ready = AtomicBoolean(false)

    @Volatile
    private var appContext: Context? = null

    /**
     * A source every worker can open independently. [temporary] sources are an
     * app-owned copy and must be deleted by the caller.
     */
    data class StagedSource(val file: File, val temporary: Boolean)

    fun ensureInit(context: Context): Boolean {
        if (ready.get()) return true
        return synchronized(this) {
            if (ready.get()) return true
            val resolved = context.applicationContext
            scratchDir(resolved)
            PDFBoxResourceLoader.init(resolved)
            appContext = resolved
            ready.set(true)
            true
        }
    }

    /**
     * Temp-file-only, deliberately.
     *
     * `setupMixed(128 MB)` looked better on small samples (132 MB book: 253 ms
     * vs 779 ms) and on a synthetic 926 MB file (3.0 s vs 4.1 s), but on the
     * real 988 MB / 420-page textbook it is *slower*: 104.3 s vs 91.9 s, both
     * pure CPU (page-tree construction is 2 ms of that). The real book is the
     * product target, so it wins.
     */
    fun memorySetting(): MemoryUsageSetting {
        val context = appContext ?: throw IllegalStateException("PDF 组件尚未初始化")
        return MemoryUsageSetting.setupTempFileOnly().setTempDir(scratchDir(context))
    }

    /** Lenient load, kept for call sites that genuinely want PDFBox's default. */
    fun load(file: File): PDDocument {
        if (!ready.get()) throw IllegalStateException("PDF 组件尚未初始化")
        return PDDocument.load(file, memorySetting())
    }

    fun load(file: File, mode: LoadMode): PDDocument = open(file, mode).document

    /**
     * Opens [file] and reports which strategy won.
     *
     * [LoadMode.AUTO] is a two-stage bet. Strict parsing is ~21x cheaper on a
     * damaged xref table, but it buys that by refusing to repair anything, so a
     * page pointing at an object that is only reachable through the lenient
     * rescan would come back empty instead of throwing. The bet is settled by
     * walking the page tree and resolving every content stream and resource
     * entry before the caller ever sees the document; if anything lenient
     * parsing would have repaired fails to resolve, the whole attempt is
     * discarded and the file is re-opened leniently.
     */
    fun open(file: File, mode: LoadMode = LoadMode.AUTO): LoadOutcome {
        if (!ready.get()) throw IllegalStateException("PDF 组件尚未初始化")
        if (mode != LoadMode.AUTO) {
            val started = SystemClock.elapsedRealtime()
            val document = if (mode == LoadMode.STRICT) loadStrict(file) else load(file)
            val elapsed = SystemClock.elapsedRealtime() - started
            return LoadOutcome(
                document = document,
                mode = mode,
                loadMs = elapsed,
                strictMs = if (mode == LoadMode.STRICT) elapsed else -1L,
                xref = null,
                note = mode.name.lowercase(Locale.ROOT),
            )
        }

        val scanStarted = SystemClock.elapsedRealtime()
        val xref = runCatching { PdfStructureScanner.scan(file) }.getOrNull()
        val scanMs = SystemClock.elapsedRealtime() - scanStarted

        // A missing or unreadable scan is not "no bad objects" — it is "no idea".
        // The probe still runs, but every unresolved reference is then treated as
        // fatal instead of being assumed merely dangling.
        val veto = FastPathGuard.xrefVeto(xref)

        var strictFailure: String? = null
        val strictStarted = SystemClock.elapsedRealtime()
        val strictDocument = try {
            loadStrict(file)
        } catch (failure: Throwable) {
            strictFailure = describe(failure)
            null
        }
        val strictMs = SystemClock.elapsedRealtime() - strictStarted

        if (strictDocument != null) {
            val probe = try {
                probePageTree(
                    document = strictDocument,
                    badObjects = xref?.badObjectNumbers?.toSet() ?: emptySet(),
                    canVet = veto == null,
                )
            } catch (failure: Throwable) {
                PageTreeProbe(describe(failure), 0, emptyList())
            }
            if (probe.fatal == null) {
                return LoadOutcome(
                    document = strictDocument,
                    mode = LoadMode.STRICT,
                    loadMs = strictMs,
                    strictMs = strictMs,
                    xref = xref,
                    note = buildString {
                        append("strict")
                        append(" scanMs=").append(scanMs)
                        if (xref != null) {
                            append(" zeroOffset=").append(xref.zeroOffsetEntries)
                            append(" badHeader=").append(xref.badHeaderEntries)
                            append(" entries=").append(xref.inspectedEntries)
                        }
                        if (veto != null) {
                            append(" unvetted=").append(veto)
                        }
                        if (probe.softMisses > 0) {
                            append(" softMiss=").append(probe.softMisses)
                            append(" first=").append(probe.softSamples.firstOrNull() ?: "")
                        }
                    },
                )
            }
            runCatching { strictDocument.close() }
            strictFailure = "页面不可达：${probe.fatal}"
        }

        val lenientStarted = SystemClock.elapsedRealtime()
        val document = load(file)
        return LoadOutcome(
            document = document,
            mode = LoadMode.LENIENT,
            loadMs = SystemClock.elapsedRealtime() - lenientStarted,
            strictMs = strictMs,
            xref = xref,
            note = buildString {
                append("lenient fallback=").append(strictFailure ?: "unknown")
                append(" scanMs=").append(scanMs)
                if (xref != null) append(" ").append(xref.summary())
            },
        )
    }

    /**
     * Walks every page and forces every content stream and resource entry to
     * materialise, so a fast parse cannot hand back a document with holes.
     *
     * The hard question is what counts as a hole. Three different things can
     * come back empty, and they mean different things:
     *
     *  - The page tree fails, or a content stream will not decode: the parse is
     *    broken. Fatal.
     *  - A reference points at an object whose xref offset is one of the entries
     *    [badObjects] reports as unusable: lenient parsing would have found that
     *    object by rescanning the file, strict will not. Fatal — this is exactly
     *    the silent-loss case the fast path must not ship.
     *  - A reference points at an object the file never declares at all. Both
     *    parsers return null here, so strict is not losing anything lenient
     *    would have kept. Counted and reported, not fatal.
     *
     * The third case is not hypothetical: the 988 MB textbook has a page whose
     * `/KSPX1` names object 3148, which no xref entry ever declared. Treating
     * that as fatal throws away a 21x speed-up for a resource no content stream
     * ever draws — A1 proved the page renders byte-identically either way
     * (2026-11-09, docs/a1-result-20261109.md).
     */
    internal fun probePageTree(
        document: PDDocument,
        badObjects: Set<Int>,
        canVet: Boolean = true,
    ): PageTreeProbe {
        val pages = document.pages
        val count = pages.count
        if (count <= 0) return PageTreeProbe("页树为空", 0, emptyList())

        val state = ProbeState(badObjects, canVet)
        for (index in 0 until count) {
            inspectPage(pages[index], index, state)
            if (state.fatal != null) break
        }
        return state.toResult()
    }

    private fun inspectPage(page: PDPage, index: Int, state: ProbeState) {
        val cos = page.cosObject

        // /Contents is either one stream or an array of streams, and every
        // element has to exist and decode.
        val contentsItem = runCatching { cos.getItem(COSName.CONTENTS) }.getOrNull()
        val contents = runCatching { cos.getDictionaryObject(COSName.CONTENTS) }.getOrElse { failure ->
            state.fail("第 ${index + 1} 页 /Contents 无法解析：${describe(failure)}")
            return
        }

        // Decoding the content stream is both an integrity check and the only way
        // to learn which XObject names this page actually paints.
        var evidence = DrawingEvidence.UNREAD
        if (contents == null) {
            if (cos.containsKey(COSName.CONTENTS)) {
                val bad = badReferenceNumber(contentsItem, state)
                if (bad != null) {
                    state.fail(
                        "第 ${index + 1} 页 /Contents 引用了坏对象 $bad" +
                            "（宽松解析会重扫找到，严格解析不会）",
                    )
                    return
                }
                state.soft("第 ${index + 1} 页 /Contents")
            } else {
                // No /Contents key at all: the page paints nothing, so no XObject
                // can be drawn by it. That is a sound "nothing drawn", unlike an
                // unreadable content stream.
                evidence = DrawingEvidence(emptySet(), complete = true)
            }
        } else {
            val read = runCatching { readDrawings(contentStreams(contents), MAX_CONTENT_SCAN) }
                .getOrElse { failure ->
                    state.fail("第 ${index + 1} 页内容流无法解码：${describe(failure)}")
                    return
                }
            evidence = read
        }

        val resources = runCatching { page.resources }.getOrElse { failure ->
            state.fail("第 ${index + 1} 页资源字典无法解析：${describe(failure)}")
            return
        } ?: return

        for (category in PAGE_RESOURCE_KEYS) {
            val dictionary = resources.cosObject.getDictionaryObject(category) as? COSDictionary
                ?: continue
            inspectResourceDictionary(dictionary, index, category, state, evidence, depth = 0)
            if (state.fatal != null) return
        }
    }

    /**
     * Reads every content stream up to [budget] bytes and turns it into
     * [DrawingEvidence].
     *
     * Hitting the budget is *not* a reason to stop quietly: the evidence is
     * marked incomplete, and an incomplete reading may never downgrade a broken
     * XObject to harmless. A page that draws its last image after 1 MiB is
     * exactly the case this protects.
     */
    private fun readDrawings(streams: List<COSStream>, budget: Int): DrawingEvidence {
        val builder = StringBuilder()
        var truncated = false
        for (stream in streams) {
            val remaining = budget - builder.length
            if (remaining <= 0) {
                truncated = true
                break
            }
            val payload = readStreamText(stream, remaining)
            builder.append(payload)
            if (payload.length >= remaining) truncated = true
        }
        return FastPathGuard.scanDrawings(builder, truncated)
    }

    private fun inspectResourceDictionary(
        dictionary: COSDictionary,
        pageIndex: Int,
        category: COSName,
        state: ProbeState,
        evidence: DrawingEvidence,
        depth: Int,
    ) {
        for (key in dictionary.keySet()) {
            val item = runCatching { dictionary.getItem(key) }.getOrNull()
            val value = try {
                dictionary.getDictionaryObject(key)
            } catch (failure: Throwable) {
                state.fail("第 ${pageIndex + 1} 页 ${category.name}/$key 无法解析：${describe(failure)}")
                return
            }
            if (value == null) {
                val bad = badReferenceNumber(item, state)
                if (bad != null) {
                    // `severityForUnresolved` is what keeps "unpainted" from
                    // being concluded out of an incomplete reading: a truncated
                    // content stream, an unpaired Do operator or an escaped name
                    // all turn this into a fatal instead of a soft miss.
                    val severity = FastPathGuard.severityForUnresolved(
                        isXObject = category == COSName.XOBJECT,
                        name = key.name,
                        evidence = evidence,
                    )
                    if (severity != MissSeverity.FATAL) {
                        // Declared, unresolvable, and provably never painted: no
                        // content stream says `/Name Do`, so neither parser turns
                        // this into pixels. The 988 MB textbook has exactly this
                        // case on page 15 (`/KSPX1` → 3148) and A1 proved the
                        // page renders byte-identically under both parsers.
                        state.soft(
                            "第 ${pageIndex + 1} 页 ${category.name}/$key 引用了坏对象 $bad，但未被绘制",
                        )
                        continue
                    }
                    state.fail(
                        "第 ${pageIndex + 1} 页 ${category.name}/$key 引用了坏对象 $bad" +
                            if (evidence.complete) {
                                "（内容流确实绘制了它，严格解析拿不到 = 缺图）"
                            } else {
                                "（内容流未能完整读取，无法证明它没被绘制）"
                            },
                    )
                    return
                }
                if (!state.canVet) {
                    // No trustworthy bad-object list: an unresolved reference
                    // cannot be told apart from "referenced a defective offset",
                    // so the benign reading must not be assumed.
                    state.fail(
                        "第 ${pageIndex + 1} 页 ${category.name}/$key 解析为空，且结构扫描不可用" +
                            "（无法判断它是否属于坏记录）",
                    )
                    return
                }
                state.soft("第 ${pageIndex + 1} 页 ${category.name}/$key")
                continue
            }

            // A Form XObject carries its own resources *and* its own content
            // stream. Its drawings live there, not on the page, so descending
            // without reading them would classify anything the form paints as
            // unpainted — a false negative one level down.
            val stream = value as? COSStream
            if (category == COSName.XOBJECT && stream != null && depth < MAX_FORM_DEPTH) {
                val subtype = runCatching {
                    stream.getDictionaryObject(COSName.SUBTYPE) as? COSName
                }.getOrNull()
                if (subtype == COSName.FORM) {
                    val nested = runCatching { PDResources(stream).cosObject }.getOrNull()
                    if (nested != null) {
                        val formEvidence = FastPathGuard.combine(
                            evidence,
                            runCatching { readDrawings(listOf(stream), MAX_CONTENT_SCAN) }
                                .getOrDefault(DrawingEvidence.UNREAD),
                        )
                        for (inner in PAGE_RESOURCE_KEYS) {
                            val innerDictionary = nested.getDictionaryObject(inner) as? COSDictionary
                                ?: continue
                            inspectResourceDictionary(
                                innerDictionary,
                                pageIndex,
                                inner,
                                state,
                                formEvidence,
                                depth + 1,
                            )
                            if (state.fatal != null) return
                        }
                    }
                }
            }
        }
    }

    /** Object number of an indirect reference, but only when the scanner flagged it. */
    private fun badReferenceNumber(item: COSBase?, state: ProbeState): Int? {
        val reference = item as? COSObject ?: return null
        val number = runCatching { reference.objectNumber.toInt() }.getOrNull() ?: return null
        return number.takeIf { it in state.bad }
    }

    /** Flattens `/Contents` into the streams it actually holds. */
    private fun contentStreams(contents: COSBase): List<COSStream> = when (contents) {
        is COSStream -> listOf(contents)
        is COSArray -> contents.mapNotNull { element ->
            val resolved = (element as? COSObject)?.let { reference ->
                runCatching { reference.getObject() }.getOrNull()
            } ?: element
            resolved as? COSStream
        }

        else -> emptyList()
    }

    /** Decodes up to [limit] bytes of a content stream as Latin-1 text. */
    private fun readStreamText(stream: COSStream, limit: Int): String {
        if (limit <= 0) return ""
        val sink = ByteArrayOutputStream(minOf(limit, 64 * 1024))
        stream.createInputStream().use { input ->
            val buffer = ByteArray(32 * 1024)
            var total = 0
            while (total < limit) {
                val read = input.read(buffer, 0, minOf(buffer.size, limit - total))
                if (read < 0) break
                sink.write(buffer, 0, read)
                total += read
            }
        }
        return String(sink.toByteArray(), Charsets.ISO_8859_1)
    }

    private class ProbeState(
        private val badObjects: Set<Int>,
        val canVet: Boolean = true,
    ) {
        var fatal: String? = null
            private set

        private var softMisses = 0
        private val softSamples = ArrayList<String>(SOFT_SAMPLE_LIMIT)

        val bad: Set<Int> get() = badObjects

        fun fail(message: String) {
            if (fatal == null) fatal = message
        }

        fun soft(message: String) {
            softMisses++
            if (softSamples.size < SOFT_SAMPLE_LIMIT) softSamples.add(message)
        }

        fun toResult() = PageTreeProbe(fatal, softMisses, softSamples.toList())
    }

    /** Mirrors [PDDocument.load] but with `isLenient = false`. */
    fun loadStrict(file: File): PDDocument {
        val source = RandomAccessBufferedFileInputStream(file)
        val scratchFile = ScratchFile(memorySetting())
        var document: PDDocument? = null
        try {
            // (source, password, keyStore, alias, scratchFile): nulls = no password, no keystore
            val parser = PDFParser(source, null, null, null, scratchFile)
            parser.isLenient = false
            parser.parse()
            document = parser.pdDocument
            return document ?: throw IOException("严格解析未产出文档")
        } catch (failure: Throwable) {
            runCatching { document?.close() }
            runCatching { source.close() }
            runCatching { scratchFile.close() }
            throw failure
        }
    }

    private fun describe(failure: Throwable): String {
        val message = failure.message?.takeIf { it.isNotBlank() }
        return if (message != null) {
            "${failure.javaClass.simpleName}: $message"
        } else {
            failure.javaClass.simpleName
        }
    }

    /**
     * Returns a local file for [uri]. A random-access file is returned as-is; a
     * provider stream is copied exactly once into app storage and then shared by
     * all workers, which is far cheaper than staging it once per worker.
     */
    suspend fun stageSource(
        context: Context,
        uri: Uri,
        onProgress: suspend (copiedBytes: Long, totalBytes: Long) -> Unit,
    ): StagedSource {
        ensureInit(context)
        resolveLocalFile(context, uri)?.let { return StagedSource(it, temporary = false) }

        val directory = File(context.cacheDir, SOURCE_DIR_NAME)
        if (!directory.isDirectory && !directory.mkdirs()) {
            throw IOException("无法创建源文件缓存目录")
        }
        val target = File(directory, "source-${System.currentTimeMillis()}.pdf")
        val totalBytes = runCatching {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
        }.getOrNull()?.takeIf { it > 0 } ?: -1L

        val input = context.contentResolver.openInputStream(uri)
            ?: throw IOException("无法打开所选 PDF")
        input.use { source ->
            FileOutputStream(target).use { sink ->
                val buffer = ByteArray(COPY_BUFFER_BYTES)
                var copied = 0L
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = source.read(buffer)
                    if (read < 0) break
                    sink.write(buffer, 0, read)
                    copied += read
                    onProgress(copied, totalBytes)
                }
                sink.flush()
            }
        }
        return StagedSource(target, temporary = true)
    }

    private fun scratchDir(context: Context): File {
        val directory = File(context.cacheDir, SCRATCH_DIR_NAME)
        if (!directory.isDirectory && !directory.mkdirs()) {
            throw IOException("无法创建 PDF 临时目录：$directory")
        }
        val probe = File.createTempFile("probe-", ".tmp", directory)
        if (!probe.delete()) throw IOException("PDF 临时目录不可写：$directory")
        return directory
    }

    /**
     * Maps an external-storage SAF document onto its real file. Returns null for
     * provider-backed URIs (cloud, downloads cache, third-party apps).
     */
    fun resolveLocalFile(context: Context, uri: Uri): File? {
        if (uri.scheme?.lowercase(Locale.ROOT) == "file") {
            return uri.path?.let(::File)?.takeIf { it.isFile && it.canRead() }
        }
        if (uri.authority != "com.android.externalstorage.documents") return null
        val documentId = runCatching {
            android.provider.DocumentsContract.getDocumentId(uri)
        }.getOrNull() ?: return null
        val separator = documentId.indexOf(':')
        if (separator <= 0) return null
        val volume = documentId.substring(0, separator)
        val relative = documentId.substring(separator + 1)
        val root = when {
            volume.equals("primary", ignoreCase = true) -> Environment.getExternalStorageDirectory()
            volume.isBlank() -> return null
            else -> File("/storage/$volume")
        }
        val candidate = File(root, relative)
        return candidate.takeIf { it.isFile && it.canRead() }
    }
}
