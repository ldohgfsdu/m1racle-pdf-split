package com.m1racle.reader.pdfsplit.split

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.provider.DocumentsContract
import android.util.Log
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

data class SplitOutput(
    val displayName: String,
    val uri: Uri,
    val pageCount: Int,
    val startPage1: Int = 0,
    val endPage1: Int = 0,
)

/** Per-task lifecycle, surfaced to the UI so every row shows its real stage. */
enum class TaskStage {
    QUEUED,
    WRITING,
    CHECKING,
    SAVING,
    DONE,
}

data class TaskSnapshot(
    val index: Int,
    val startPage1: Int,
    val endPage1: Int,
    val name: String,
    val pageCount: Int,
    val stage: TaskStage,
    val pageInPart: Int,
) {
    /**
     * Writing dominates the wall clock; checking and saving are reported as
     * stages instead of invented percentages, so the bar never lies.
     */
    val fraction: Float
        get() = when (stage) {
            TaskStage.QUEUED -> 0f
            TaskStage.WRITING ->
                if (pageCount <= 0) 0f else 0.78f * (pageInPart.toFloat() / pageCount)
            TaskStage.CHECKING -> 0.88f
            TaskStage.SAVING -> 0.94f
            TaskStage.DONE -> 1f
        }
}

sealed interface SplitEvent {
    /** Only emitted while a provider-backed source is copied into app storage. */
    data class Staging(val copiedBytes: Long, val totalBytes: Long) : SplitEvent

    data object Opening : SplitEvent

    data class Tasks(
        val tasks: List<TaskSnapshot>,
        val overallFraction: Float,
        val finishedParts: Int,
        val totalParts: Int,
    ) : SplitEvent
}

class PdfSplitFailure(message: String) : IOException(message)

/**
 * Lightweight, parseable timing traces. `adb logcat -s M1PDF` gives per-part
 * phase costs plus process CPU time and die temperature, so speed claims can be
 * checked against a real device instead of inferred from a stopwatch.
 */
internal object SplitDiagnostics {
    private const val TAG = "M1PDF"

    fun log(message: String) {
        Log.i(TAG, message)
    }

    /** utime + stime from /proc/self/stat, in milliseconds. */
    fun cpuMs(): Long = runCatching {
        val stat = File("/proc/self/stat").readText()
        val fields = stat.substring(stat.lastIndexOf(')') + 2).trim().split(' ')
        val ticks = (fields[11].toLongOrNull() ?: 0L) + (fields[12].toLongOrNull() ?: 0L)
        ticks * 10L // Linux USER_HZ is 100 on Android
    }.getOrDefault(0L)

    /** Cumulative byte counters from /proc/self/io; diff two snapshots to attribute load cost. */
    data class IoSnapshot(
        val rchar: Long,
        val wchar: Long,
        val readBytes: Long,
        val writeBytes: Long,
    )

    fun ioSnapshot(): IoSnapshot = runCatching {
        var rchar = 0L
        var wchar = 0L
        var readBytes = 0L
        var writeBytes = 0L
        File("/proc/self/io").forEachLine { line ->
            val value = line.substringAfter(':').trim().toLongOrNull() ?: 0L
            when {
                line.startsWith("rchar") -> rchar = value
                line.startsWith("wchar") -> wchar = value
                line.startsWith("read_bytes") -> readBytes = value
                line.startsWith("write_bytes") -> writeBytes = value
            }
        }
        IoSnapshot(rchar, wchar, readBytes, writeBytes)
    }.getOrDefault(IoSnapshot(0, 0, 0, 0))

    fun heapUsedMb(): Long {
        val runtime = Runtime.getRuntime()
        return (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
    }

    /** Cumulative ART GC counters (process-wide); diff two samples for a window. */
    fun gcStat(): String = runCatching {
        val count = android.os.Debug.getRuntimeStat("art.gc.gc-count") ?: "?"
        val timeMs = android.os.Debug.getRuntimeStat("art.gc.gc-time") ?: "?"
        val blockedMs = android.os.Debug.getRuntimeStat("art.gc.blocking-gc-time") ?: "?"
        "gcCount=$count gcTimeMs=$timeMs gcBlockedMs=$blockedMs"
    }.getOrDefault("gcCount=? gcTimeMs=? gcBlockedMs=?")

    fun tempCelsius(): String = runCatching {
        val zones = File("/sys/class/thermal").listFiles().orEmpty()
            .filter { it.name.startsWith("thermal_zone") }
            .mapNotNull { zone ->
                runCatching {
                    File(zone, "temp").readText().trim().toIntOrNull()
                        ?.takeIf { it > 0 }?.div(1000.0)
                }.getOrNull()
            }
        if (zones.isEmpty()) "?" else String.format("%.1f", zones.max())
    }.getOrDefault("?")
}

/**
 * PDFBox serializes a part in one long blocking `save()` call. Wrapping the
 * output stream turns every write into a cancellation checkpoint, so pressing
 * 取消 aborts a 400 MB write immediately instead of after it finishes.
 */
private class CancelableSink(
    private val delegate: OutputStream,
    private val job: Job?,
) : OutputStream() {
    private fun abortIfCancelled() {
        if (job?.isActive == false) throw CancellationException("拆分已取消")
    }

    override fun write(b: Int) {
        abortIfCancelled()
        delegate.write(b)
    }

    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        abortIfCancelled()
        delegate.write(bytes, offset, length)
    }

    override fun flush() {
        abortIfCancelled()
        delegate.flush()
    }

    override fun close() {
        delegate.close()
    }
}

/**
 * Concurrent page-range splitter for large PDFs.
 *
 * Concurrency is per output part: N workers pull part indices off a queue, so
 * with concurrency 3 the ranges 61-70, 71-86 and 87-120 really are written at
 * the same time. Each worker owns its own [PDDocument] over the same staged
 * file, so the source object graph is never shared across threads.
 *
 * The part is streamed straight into the SAF document. Page import is the only
 * full pass; the geometry check runs against the in-memory document that was
 * just built, and a disk read-back only happens when deep verification is on.
 */
class PdfRangeSplitter(
    private val context: Context,
    /**
     * A1 verification harness only. The default keeps production on the lenient
     * path exactly as shipped; the strict path exists so the "fast load" claim
     * can be checked against real page output before it is adopted.
     */
    private val loadMode: LoadMode = LoadMode.LENIENT,
) {

    private val appContext = context.applicationContext

    suspend fun split(
        sourceUri: Uri,
        outputTreeUri: Uri,
        expectedPageCount: Int,
        parts: List<SplitPart>,
        deepVerify: Boolean,
        concurrency: Int,
        onEvent: suspend (SplitEvent) -> Unit,
    ): List<SplitOutput> = withContext(Dispatchers.IO) {
        require(parts.isNotEmpty()) { "没有要拆分的页段" }
        require(expectedPageCount > 0) { "源 PDF 没有可拆分的页" }
        parts.forEach { part ->
            require(part.startPage1 >= 1) { "页码从 1 开始" }
            require(part.endPage1 <= expectedPageCount) {
                "第 ${part.index} 段结束页超出文档总页数"
            }
            require(part.startPage1 <= part.endPage1) { "第 ${part.index} 段起止页颠倒" }
            require(part.outputName.endsWith(".pdf", ignoreCase = true)) {
                "第 ${part.index} 段文件名必须以 .pdf 结尾"
            }
        }
        require(parts.map { it.outputName.lowercase() }.distinct().size == parts.size) {
            "存在重名文件，请先修改文件名"
        }

        val totalPages = parts.sumOf { it.pageCount }
        val createdUris = Collections.synchronizedList(ArrayList<Uri>(parts.size))
        val results = arrayOfNulls<SplitOutput>(parts.size)
        // Longest-part-first scheduling. With page-order dispatch the 165-page
        // chapter starts only after nine small parts; by then every other worker
        // is idle and that one file alone decides the wall clock, which is why
        // raising concurrency from 4 to 6 changed nothing in the field test.
        val dispatchOrder = parts.indices.sortedByDescending { parts[it].pageCount }
        val nextSlot = AtomicInteger(0)
        val finishedParts = AtomicInteger(0)
        val parentJob = currentCoroutineContext()[Job]
        val runStart = SystemClock.elapsedRealtime()
        val cpuStart = SplitDiagnostics.cpuMs()
        SplitDiagnostics.log(
            "run start parts=${parts.size} pages=$totalPages " +
                "concurrency=$concurrency deepVerify=$deepVerify loadMode=$loadMode " +
                "cpuMs=$cpuStart",
        )

        // Snapshot table shared with the reporter coroutine.
        val table = ConcurrentHashMap<Int, TaskSnapshot>()
        parts.forEachIndexed { index, part ->
            table[index] = TaskSnapshot(
                index = index,
                startPage1 = part.startPage1,
                endPage1 = part.endPage1,
                name = part.outputName,
                pageCount = part.pageCount,
                stage = TaskStage.QUEUED,
                pageInPart = 0,
            )
        }

        fun publish(index: Int, stage: TaskStage, pageInPart: Int) {
            val current = table[index] ?: return
            table[index] = current.copy(stage = stage, pageInPart = pageInPart)
        }

        suspend fun emitTasks() {
            val snapshot = parts.indices.map { index ->
                table[index] ?: return@map TaskSnapshot(
                    index = index,
                    startPage1 = parts[index].startPage1,
                    endPage1 = parts[index].endPage1,
                    name = parts[index].outputName,
                    pageCount = parts[index].pageCount,
                    stage = TaskStage.QUEUED,
                    pageInPart = 0,
                )
            }
            val weighted = snapshot.sumOf { (it.fraction * it.pageCount).toDouble() }
            onEvent(
                SplitEvent.Tasks(
                    tasks = snapshot,
                    overallFraction = if (totalPages == 0) {
                        0f
                    } else {
                        (weighted / totalPages).toFloat().coerceIn(0f, 1f)
                    },
                    finishedParts = finishedParts.get(),
                    totalParts = parts.size,
                ),
            )
        }

        var succeeded = false
        var staged: PdfBoxSetup.StagedSource? = null
        try {
            val stageStart = SystemClock.elapsedRealtime()
            PdfBoxSetup.ensureInit(appContext)
            staged = PdfBoxSetup.stageSource(appContext, sourceUri) { copied, total ->
                onEvent(SplitEvent.Staging(copied, total))
            }
            val sourceFile = staged.file
            SplitDiagnostics.log("stage ms=${SystemClock.elapsedRealtime() - stageStart}")
            onEvent(SplitEvent.Opening)
            emitTasks()

            coroutineScope {
                val reporter = launch {
                    while (isActive) {
                        emitTasks()
                        delay(REPORT_INTERVAL_MS)
                    }
                }
                try {
                    val workerCount = concurrency.coerceIn(1, parts.size)
                    val workers = (0 until workerCount).map { workerId ->
                        async(Dispatchers.IO) {
                            // Diagnosis only: separate CPU from real disk I/O so a
                            // "load is slow" claim can say *why* it is slow.
                            val ioBefore = SplitDiagnostics.ioSnapshot()
                            val cpuBefore = SplitDiagnostics.cpuMs()
                            val loadStart = SystemClock.elapsedRealtime()
                            PdfBoxSetup.load(sourceFile, loadMode).use { source ->
                                val loadMs = SystemClock.elapsedRealtime() - loadStart
                                val ioAfter = SplitDiagnostics.ioSnapshot()
                                SplitDiagnostics.log(
                                    "load worker=$workerId ms=$loadMs " +
                                        "cpuMs=${SplitDiagnostics.cpuMs() - cpuBefore} " +
                                        "readBytes=${ioAfter.readBytes - ioBefore.readBytes} " +
                                        "writeBytes=${ioAfter.writeBytes - ioBefore.writeBytes} " +
                                        "rchar=${ioAfter.rchar - ioBefore.rchar} " +
                                        "wchar=${ioAfter.wchar - ioBefore.wchar} " +
                                        "fileBytes=${sourceFile.length()} " +
                                        "heapUsedMB=${SplitDiagnostics.heapUsedMb()} " +
                                        "heapMaxMB=${Runtime.getRuntime().maxMemory() / (1024 * 1024)} " +
                                        SplitDiagnostics.gcStat(),
                                )
                                if (source.numberOfPages != expectedPageCount) {
                                    throw PdfSplitFailure(
                                        "源 PDF 页数已变化：界面上是 $expectedPageCount，" +
                                            "实际 ${source.numberOfPages}",
                                    )
                                }
                                while (true) {
                                    currentCoroutineContext().ensureActive()
                                    val slot = nextSlot.getAndIncrement()
                                    if (slot >= dispatchOrder.size) break
                                    val index = dispatchOrder[slot]
                                    val part = parts[index]
                                    val partStart = SystemClock.elapsedRealtime()
                                    val uri = processPart(
                                        source = source,
                                        part = part,
                                        taskIndex = index,
                                        outputTreeUri = outputTreeUri,
                                        deepVerify = deepVerify,
                                        sinkJob = parentJob,
                                        onStage = { stage, pageInPart ->
                                            publish(index, stage, pageInPart)
                                        },
                                        onCreate = { created -> createdUris.add(created) },
                                    )
                                    results[index] = SplitOutput(
                                        displayName = part.outputName,
                                        uri = uri,
                                        pageCount = part.pageCount,
                                        startPage1 = part.startPage1,
                                        endPage1 = part.endPage1,
                                    )
                                    publish(index, TaskStage.DONE, part.pageCount)
                                    finishedParts.incrementAndGet()
                                    SplitDiagnostics.log(
                                        "part=$index worker=$workerId pages=${part.pageCount} " +
                                            "totalMs=${SystemClock.elapsedRealtime() - partStart}",
                                    )
                                    emitTasks()
                                }
                            }
                        }
                    }
                    workers.awaitAll()
                } finally {
                    reporter.cancel()
                }
            }

            succeeded = true
            results.map { requireNotNull(it) { "拆分结果不完整" } }
        } finally {
            val cleanupStart = SystemClock.elapsedRealtime()
            // The user must see "已取消" immediately; cleanup still runs, but in
            // a non-cancellable context so it cannot be cut short by the very
            // cancellation that triggered it.
            withContext(NonCancellable) {
                if (!succeeded) {
                    synchronized(createdUris) {
                        createdUris.forEach { uri ->
                            runCatching {
                                DocumentsContract.deleteDocument(appContext.contentResolver, uri)
                            }
                        }
                    }
                }
                staged?.takeIf { it.temporary }?.file?.delete()
            }
            SplitDiagnostics.log(
                "run end totalMs=${SystemClock.elapsedRealtime() - runStart} " +
                    "cpuMs=${SplitDiagnostics.cpuMs() - cpuStart} " +
                    "cleanupMs=${SystemClock.elapsedRealtime() - cleanupStart} " +
                    "finished=${finishedParts.get()}/${parts.size} " +
                    "tempC=${SplitDiagnostics.tempCelsius()}",
            )
        }
    }

    private suspend fun processPart(
        source: PDDocument,
        part: SplitPart,
        taskIndex: Int,
        outputTreeUri: Uri,
        deepVerify: Boolean,
        sinkJob: Job?,
        onStage: (TaskStage, Int) -> Unit,
        onCreate: (Uri) -> Unit,
    ): Uri {
        currentCoroutineContext().ensureActive()
        onStage(TaskStage.WRITING, 0)
        val outputUri = createOutputDocument(outputTreeUri, part.outputName)
        onCreate(outputUri)

        val rawSink = appContext.contentResolver.openOutputStream(outputUri, "wt")
            ?: throw PdfSplitFailure("无法写入 ${part.outputName}")
        CancelableSink(rawSink, sinkJob).use { stream ->
            writePart(
                source = source,
                part = part,
                taskIndex = taskIndex,
                deepVerify = deepVerify,
                onStage = onStage,
                sink = stream,
            )
        }

        if (deepVerify) {
            onStage(TaskStage.CHECKING, part.pageCount)
            verifyOnDisk(source, outputUri, part)
        }
        onStage(TaskStage.SAVING, part.pageCount)
        return outputUri
    }

    /**
     * Streams the part directly into the SAF document. Page import is CPU bound
     * (deep resource cloning); the geometry check reuses the in-memory document
     * that was just serialized instead of re-reading the file.
     */
    private suspend fun writePart(
        source: PDDocument,
        part: SplitPart,
        taskIndex: Int,
        deepVerify: Boolean,
        onStage: (TaskStage, Int) -> Unit,
        sink: OutputStream,
    ) {
        val started = SystemClock.elapsedRealtime()
        PDDocument(PdfBoxSetup.memorySetting()).use { destination ->
            var pageInPart = 0
            for (pageIndex in part.start0..part.end0) {
                currentCoroutineContext().ensureActive()
                val sourcePage = source.getPage(pageIndex)
                val importedPage = destination.importPage(sourcePage)
                preserveGeometry(sourcePage, importedPage)
                materializeInheritedResources(sourcePage, importedPage)
                pageInPart += 1
                onStage(TaskStage.WRITING, pageInPart)
            }
            val importMs = SystemClock.elapsedRealtime() - started
            currentCoroutineContext().ensureActive()
            PageLinkIsolation.isolate(source, destination, part.start0)
            val linkMs = SystemClock.elapsedRealtime() - started - importMs

            verifyInMemory(
                source = source,
                built = destination,
                part = part,
                deepVerify = deepVerify,
            )
            val verifyMs = SystemClock.elapsedRealtime() - started - importMs - linkMs

            currentCoroutineContext().ensureActive()
            onStage(TaskStage.SAVING, part.pageCount)
            destination.save(sink)
            sink.flush()
            val saveMs = SystemClock.elapsedRealtime() - started - importMs - linkMs - verifyMs
            SplitDiagnostics.log(
                "phase part=$taskIndex pages=${part.pageCount} " +
                    "importMs=$importMs linkMs=$linkMs verifyMs=$verifyMs saveMs=$saveMs",
            )
        }
    }

    /**
     * [PDDocument.importPage] re-creates the page dictionary but not always the
     * effective boxes: inherited MediaBox/CropBox/Rotation would fall back to
     * A4/zero, which silently resizes the whole part.
     */
    private fun preserveGeometry(sourcePage: PDPage, importedPage: PDPage) {
        importedPage.mediaBox = sourcePage.mediaBox
        importedPage.cropBox = sourcePage.cropBox
        importedPage.bleedBox = sourcePage.bleedBox
        importedPage.trimBox = sourcePage.trimBox
        importedPage.artBox = sourcePage.artBox
        importedPage.rotation = sourcePage.rotation
        importedPage.userUnit = sourcePage.userUnit
    }

    /** Resources inherited from the page tree must be attached per page. */
    private fun materializeInheritedResources(sourcePage: PDPage, importedPage: PDPage) {
        if (
            !sourcePage.cosObject.containsKey(COSName.RESOURCES) &&
            sourcePage.resources != null
        ) {
            importedPage.resources = sourcePage.resources
        }
    }

    private suspend fun verifyInMemory(
        source: PDDocument,
        built: PDDocument,
        part: SplitPart,
        deepVerify: Boolean,
    ) {
        if (built.numberOfPages != part.pageCount) {
            throw PdfSplitFailure(
                "生成页数不符：${part.outputName}，期望 ${part.pageCount}，" +
                    "实际 ${built.numberOfPages}",
            )
        }
        repeat(part.pageCount) { offset ->
            currentCoroutineContext().ensureActive()
            val sourcePage = source.getPage(part.start0 + offset)
            val outputPage = built.getPage(offset)
            if (geometrySignature(sourcePage) != geometrySignature(outputPage)) {
                throw PdfSplitFailure(
                    "第 ${offset + 1} 页尺寸或旋转不一致：${part.outputName}",
                )
            }
            if (deepVerify && contentDigest(sourcePage) != contentDigest(outputPage)) {
                throw PdfSplitFailure("第 ${offset + 1} 页内容不一致：${part.outputName}")
            }
        }
    }

    /** Deep verification only: prove the bytes on disk open and match again. */
    private suspend fun verifyOnDisk(source: PDDocument, outputUri: Uri, part: SplitPart) {
        val input = appContext.contentResolver.openInputStream(outputUri)
            ?: throw PdfSplitFailure("无法回读 ${part.outputName}")
        input.use { stream ->
            PDDocument.load(stream, PdfBoxSetup.memorySetting()).use { readback ->
                if (readback.numberOfPages != part.pageCount) {
                    throw PdfSplitFailure(
                        "落盘页数不符：${part.outputName}，期望 ${part.pageCount}，" +
                            "实际 ${readback.numberOfPages}",
                    )
                }
                repeat(part.pageCount) { offset ->
                    currentCoroutineContext().ensureActive()
                    val sourcePage = source.getPage(part.start0 + offset)
                    val outputPage = readback.getPage(offset)
                    if (contentDigest(sourcePage) != contentDigest(outputPage)) {
                        throw PdfSplitFailure(
                            "落盘后第 ${offset + 1} 页内容不一致：${part.outputName}",
                        )
                    }
                }
            }
        }
    }

    private fun geometrySignature(page: PDPage): String = listOf(
        page.mediaBox,
        page.cropBox,
        page.bleedBox,
        page.trimBox,
        page.artBox,
        page.userUnit,
        page.rotation,
    ).joinToString("|")

    private fun contentDigest(page: PDPage): String {
        val digest = MessageDigest.getInstance("SHA-256")
        if (page.hasContents()) {
            page.contents.use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun createOutputDocument(outputTreeUri: Uri, displayName: String): Uri {
        val resolver = appContext.contentResolver
        val parentDocumentUri = DocumentsContract.buildDocumentUriUsingTree(
            outputTreeUri,
            DocumentsContract.getTreeDocumentId(outputTreeUri),
        )
        return DocumentsContract.createDocument(
            resolver,
            parentDocumentUri,
            "application/pdf",
            displayName,
        ) ?: throw PdfSplitFailure("无法在所选目录创建 $displayName")
    }

    private companion object {
        const val REPORT_INTERVAL_MS = 120L
    }
}
