package com.m1racle.reader.pdfsplit.bench

import android.app.Activity
import android.os.Bundle
import android.os.Debug
import android.os.SystemClock
import android.util.Log
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.io.RandomAccessBufferedFileInputStream
import com.tom_roush.pdfbox.io.ScratchFile
import com.tom_roush.pdfbox.pdfparser.PDFParser
import com.tom_roush.pdfbox.pdmodel.PDDocument
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Debug-only load benchmark.
 *
 * Purpose: settle one question with numbers instead of guesses —
 * does `MemoryUsageSetting.setupTempFileOnly()` (what [com.m1racle.reader.pdfsplit.split.PdfBoxSetup]
 * uses today) make `PDDocument.load` pay for copying the whole file into the
 * cache scratch directory, and does `setupMixed(mainMemory)` avoid it?
 *
 * Launched by adb, never by a user:
 *   adb shell am start -n com.m1racle.reader.pdf/com.m1racle.reader.pdfsplit.bench.LoadBenchActivity \
 *       --es path /data/data/com.m1racle.reader.pdf/files/bench.pdf --ei concurrency 0
 *
 * concurrency = 0  -> sequential sweep of memory settings, two rounds
 * concurrency = N  -> N simultaneous loads with the current (temp-file-only) setting
 */
class LoadBenchActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val defaultPath = File(filesDir, "bench.pdf").absolutePath
        val path = intent.getStringExtra(EXTRA_PATH) ?: defaultPath
        val concurrency = intent.getIntExtra(EXTRA_CONCURRENCY, 0)
        Thread({
            val started = SystemClock.elapsedRealtime()
            try {
                run(path, concurrency)
            } catch (failure: Throwable) {
                Log.e(TAG, "BENCH_FAIL path=$path", failure)
            } finally {
                Log.i(TAG, "BENCH_DONE totalMs=${SystemClock.elapsedRealtime() - started}")
                finish()
            }
        }, "m1bench").start()
    }

    private fun run(path: String, concurrency: Int) {
        PDFBoxResourceLoader.init(applicationContext)
        val useFuse = intent.getBooleanExtra(EXTRA_FUSE, false)
        val file = if (useFuse) stageToExternal(path) else File(path)
        if (!file.isFile) {
            Log.e(TAG, "BENCH_FAIL missing file=${file.absolutePath}")
            return
        }
        val scratch = File(cacheDir, SCRATCH_DIR_NAME).apply { mkdirs() }
        Log.i(
            TAG,
            "BENCH_START file=${file.name} bytes=${file.length()} " +
                "path=${file.absolutePath} source=$path " +
                "heapMaxMB=${Runtime.getRuntime().maxMemory() / MB} " +
                "scratch=$scratch concurrency=$concurrency",
        )
        val only = intent.getStringExtra(EXTRA_SETTING)
        val label = intent.getStringExtra(EXTRA_LABEL) ?: file.nameWithoutExtension
        val trace = intent.getBooleanExtra(EXTRA_TRACE, false)
        val sampleStacks = intent.getBooleanExtra(EXTRA_SAMPLE, false)
        val strict = intent.getBooleanExtra(EXTRA_STRICT, false)
        if (!only.isNullOrBlank()) {
            // One setting only: the real 988 MB book costs about a minute per
            // load, so a full sweep would take far too long to be usable.
            measureOne(
                file = file,
                scratch = scratch,
                name = only,
                round = 1,
                setting = settingFor(only, scratch),
                label = label,
                trace = trace,
                sampleStacks = sampleStacks,
                strict = strict,
            )
        } else if (concurrency > 0) {
            concurrentLoads(file, scratch, concurrency)
        } else {
            sequentialSweep(file, scratch)
        }
    }

    /**
     * Copies the private-directory sample onto external storage (FUSE) once, so
     * the same bytes can be loaded through the /sdcard path the app really uses
     * when SAF resolves a document to its real file.
     */
    private fun stageToExternal(path: String): File {
        val source = File(path)
        val target = File(getExternalFilesDir(null), "bench-external.pdf")
        if (target.isFile && target.length() == source.length()) return target
        val start = SystemClock.elapsedRealtime()
        source.inputStream().use { input ->
            target.outputStream().use { output -> input.copyTo(output, 256 * 1024) }
        }
        Log.i(
            TAG,
            "BENCH_STAGE externalCopyMs=${SystemClock.elapsedRealtime() - start} " +
                "bytes=${target.length()} path=${target.absolutePath}",
        )
        return target
    }

    /**
     * Two rounds with reversed order: the source file is small enough to end up
     * in the page cache after round 1, so a fixed order would hand an unfair
     * advantage to whichever setting runs later.
     */
    private fun settingFor(name: String, scratch: File): MemoryUsageSetting = when (name) {
        "tempFileOnly" -> tempFileOnly(scratch)
        "mixed64" -> mixed(scratch, 64 * MB)
        "mixed128" -> mixed(scratch, 128 * MB)
        "mixed256" -> mixed(scratch, 256 * MB)
        "mixed512" -> mixed(scratch, 512 * MB)
        else -> error("未知内存设置 $name")
    }

    private fun sequentialSweep(file: File, scratch: File) {
        val settings = listOf(
            "tempFileOnly" to { tempFileOnly(scratch) },
            "mixed64" to { mixed(scratch, 64 * MB) },
            "mixed128" to { mixed(scratch, 128 * MB) },
            "mixed256" to { mixed(scratch, 256 * MB) },
            "mixed512" to { mixed(scratch, 512 * MB) },
        )
        for (round in 1..2) {
            val ordered = if (round == 1) settings else settings.reversed()
            for ((name, factory) in ordered) {
                measureOne(file, scratch, name, round, factory())
            }
        }
    }

    private fun measureOne(
        file: File,
        scratch: File,
        name: String,
        round: Int,
        setting: MemoryUsageSetting,
        label: String = "",
        trace: Boolean = false,
        sampleStacks: Boolean = false,
        strict: Boolean = false,
    ) {
        clearScratch(scratch)
        val cpuBefore = Debug.threadCpuTimeNanos()
        val start = SystemClock.elapsedRealtime()
        var pages = -1
        var traceFile: File? = null
        // Independent 10 ms stack sampler. ART's sampling trace is the primary
        // evidence; this runs alongside it because the trace's per-sample frame
        // ordering is not documented, and a second, self-written source keeps a
        // parsing mistake from silently becoming a conclusion.
        val sampler = if (sampleStacks) StackSampler(Thread.currentThread(), SAMPLE_INTERVAL_MS) else null
        if (trace) {
            val target = File(getExternalFilesDir(null), "trace-$label.trace")
            target.parentFile?.mkdirs()
            if (target.exists()) target.delete()
            traceFile = target
            Debug.startMethodTracingSampling(
                target.absolutePath,
                TRACE_BUFFER_BYTES,
                SAMPLE_INTERVAL_US.toInt(),
            )
            Log.i(
                TAG,
                "M1BENCH trace start label=$label path=${target.absolutePath} " +
                    "bufferMB=${TRACE_BUFFER_BYTES / MB} intervalUs=$SAMPLE_INTERVAL_US",
            )
        }
        sampler?.start()
        try {
            val opened = if (strict) loadStrict(file, scratch) else PDDocument.load(file, setting)
            opened.use { document ->
                // loadMs covers PDFParser only; pagesMs covers building the page
                // tree. Splitting them tells us whether the cost is parsing or
                // page-tree construction.
                val loadMs = SystemClock.elapsedRealtime() - start
                val pagesStart = SystemClock.elapsedRealtime()
                pages = document.numberOfPages
                val pagesMs = SystemClock.elapsedRealtime() - pagesStart
                val elapsed = SystemClock.elapsedRealtime() - start
                val cpuAfter = Debug.threadCpuTimeNanos()
                val scratchBytes = scratchBytes(scratch)
                // Sample while the document is still open: closing deletes the
                // scratch file, which is exactly the cost we want to see.
                Log.i(
                    TAG,
                    "M1BENCH mode=$name round=$round loadMs=$loadMs pagesMs=$pagesMs " +
                        "totalMs=$elapsed pages=$pages strict=$strict " +
                        "scratchMB=${scratchBytes / MB} " +
                        "scratchBytes=$scratchBytes " +
                        "threadCpuMs=${if (cpuAfter < 0 || cpuBefore < 0) -1 else (cpuAfter - cpuBefore) / 1_000_000} " +
                        "heapUsedMB=${usedHeapMb()}",
                )
            }
        } catch (failure: Throwable) {
            // A main-memory budget the heap cannot honour must not abort the
            // sweep; record it and keep measuring the remaining settings.
            Log.e(
                TAG,
                "M1BENCH mode=$name round=$round loadMs=${SystemClock.elapsedRealtime() - start} " +
                    "FAILED ${failure.javaClass.simpleName}: ${failure.message}",
            )
        } finally {
            if (pages < 0) {
                Log.w(TAG, "M1BENCH mode=$name round=$round didNotOpen")
            }
            sampler?.let { writeSampler(label, name, it.stop()) }
            if (trace) {
                runCatching { Debug.stopMethodTracing() }
                    .onFailure { Log.w(TAG, "M1BENCH trace stop failed: ${it.javaClass.simpleName}") }
                Log.i(
                    TAG,
                    "M1BENCH trace stop label=$label bytes=${traceFile?.length() ?: -1} " +
                        "wallMs=${SystemClock.elapsedRealtime() - start}",
                )
            }
        }
    }

    private fun concurrentLoads(file: File, scratch: File, concurrency: Int) {
        clearScratch(scratch)
        val gate = CountDownLatch(1)
        val peakScratch = AtomicLong(0)
        val durations = arrayOfNulls<Long>(concurrency)
        val sampler = Thread {
            while (!Thread.currentThread().isInterrupted) {
                val bytes = scratchBytes(scratch)
                peakScratch.accumulateAndGet(bytes) { a, b -> maxOf(a, b) }
                try {
                    Thread.sleep(200)
                } catch (interrupted: InterruptedException) {
                    return@Thread
                }
            }
        }.apply { isDaemon = true; start() }

        val wallStart = SystemClock.elapsedRealtime()
        val threads = (0 until concurrency).map { index ->
            Thread({
                gate.await()
                val start = SystemClock.elapsedRealtime()
                try {
                    PDDocument.load(file, tempFileOnly(scratch)).use { document ->
                        durations[index] = SystemClock.elapsedRealtime() - start
                        Log.i(
                            TAG,
                            "M1BENCH worker=$index pages=${document.numberOfPages} " +
                                "loadMs=${durations[index]}",
                        )
                    }
                } catch (failure: Throwable) {
                    Log.e(TAG, "M1BENCH worker=$index FAILED", failure)
                }
            }, "bench-$index").apply { start() }
        }
        gate.countDown()
        threads.forEach { it.join() }
        val wallMs = SystemClock.elapsedRealtime() - wallStart
        sampler.interrupt()
        Log.i(
            TAG,
            "M1BENCH concurrent n=$concurrency wallMs=$wallMs " +
                "loads=${durations.joinToString(",") { it?.toString() ?: "x" }} " +
                "peakScratchMB=${peakScratch.get() / MB}",
        )
    }

    /**
     * Fixed-interval stack sampler for one target thread.
     *
     * Attribution follows the usual sampling convention: the top frame of a
     * sample owns the interval (self time), every frame in the sample owns it
     * for inclusive time. Numbers are sample weights, not exact durations.
     */
    private class StackSampler(private val target: Thread, private val intervalMs: Long) {
        class Snapshot(
            val samples: Int,
            val windowMs: Long,
            val self: Map<String, Int>,
            val inclusive: Map<String, Int>,
            val stacks: Map<String, Int>,
        )

        private val alive = AtomicBoolean(true)
        private val sampleCount = AtomicInteger(0)
        private val selfCounts = ConcurrentHashMap<String, Int>()
        private val inclusiveCounts = ConcurrentHashMap<String, Int>()
        private val stackCounts = ConcurrentHashMap<String, Int>()
        private var worker: Thread? = null
        private var startedAt = 0L

        fun start() {
            startedAt = SystemClock.elapsedRealtime()
            worker = Thread({
                while (alive.get()) {
                    val frames = try {
                        target.stackTrace
                    } catch (ignored: Throwable) {
                        emptyArray<StackTraceElement>()
                    }
                    sampleCount.incrementAndGet()
                    if (frames.isNotEmpty()) {
                        selfCounts.merge(frames[0].toString(), 1, Int::plus)
                        for (frame in frames) inclusiveCounts.merge(frame.toString(), 1, Int::plus)
                        stackCounts.merge(
                            frames.joinToString(" \u2190 ") { it.toString() },
                            1,
                            Int::plus,
                        )
                    }
                    try {
                        Thread.sleep(intervalMs)
                    } catch (interrupted: InterruptedException) {
                        return@Thread
                    }
                }
            }, "m1sampler").apply {
                isDaemon = true
                start()
            }
        }

        fun stop(): Snapshot {
            alive.set(false)
            worker?.interrupt()
            return Snapshot(
                samples = sampleCount.get(),
                windowMs = SystemClock.elapsedRealtime() - startedAt,
                self = selfCounts.toMap(),
                inclusive = inclusiveCounts.toMap(),
                stacks = stackCounts.toMap(),
            )
        }
    }

    private fun writeSampler(label: String, mode: String, snapshot: StackSampler.Snapshot) {
        val total = snapshot.samples.coerceAtLeast(1)
        fun ms(count: Int): Long = count * SAMPLE_INTERVAL_MS
        val lines = mutableListOf<String>()
        lines += "# label=$label mode=$mode intervalMs=$SAMPLE_INTERVAL_MS " +
            "samples=${snapshot.samples} windowMs=${snapshot.windowMs}"
        lines += "# selfMs and inclMs are sample weights (count x interval), not exact durations"
        lines += "[SELF]"
        snapshot.self.entries.sortedByDescending { it.value }.take(25).forEach { (frame, count) ->
            lines += String.format(
                Locale.US,
                "selfMs=%d pct=%.1f n=%d %s",
                ms(count),
                count * 100.0 / total,
                count,
                frame,
            )
        }
        lines += "[INCLUSIVE]"
        snapshot.inclusive.entries.sortedByDescending { it.value }.take(25).forEach { (frame, count) ->
            lines += String.format(
                Locale.US,
                "inclMs=%d pct=%.1f n=%d %s",
                ms(count),
                count * 100.0 / total,
                count,
                frame,
            )
        }
        lines += "[STACKS]"
        snapshot.stacks.entries.sortedByDescending { it.value }.take(15).forEach { (stack, count) ->
            lines += String.format(Locale.US, "n=%d pct=%.1f %s", count, count * 100.0 / total, stack)
        }
        val target = File(getExternalFilesDir(null), "sampler-$label-$mode.txt")
        target.parentFile?.mkdirs()
        target.writeText(lines.joinToString("\n"))
        Log.i(
            TAG,
            "M1BENCH sampler label=$label mode=$mode samples=${snapshot.samples} " +
                "windowMs=${snapshot.windowMs} file=${target.absolutePath}",
        )
        snapshot.self.entries.sortedByDescending { it.value }.take(5).forEach { (frame, count) ->
            Log.i(TAG, "M1BENCH hot pct=${String.format(Locale.US, "%.1f", count * 100.0 / total)} $frame")
        }
    }

    /**
     * Same bytes, same scratch setting, but with `isLenient = false`.
     *
     * Rationale: PDFBox's `checkXrefOffsets()` only runs in lenient mode. In
     * lenient mode a single undecodable xref entry makes the *whole* table count
     * as untrusted and triggers `bfSearchForObjects()`, which rescans every byte
     * of the file. Loading strictly skips that validation, so the two timings
     * tell us whether the repair path is the cost.
     */
    private fun loadStrict(file: File, scratch: File): PDDocument {
        val source = RandomAccessBufferedFileInputStream(file)
        val scratchFile = ScratchFile(MemoryUsageSetting.setupTempFileOnly().setTempDir(scratch))
        try {
            // (source, password, keyStore, alias, scratchFile): nulls = no password, no keystore
            val parser = PDFParser(source, null, null, null, scratchFile)
            parser.isLenient = false
            parser.parse()
            return parser.pdDocument
        } catch (failure: Throwable) {
            runCatching { source.close() }
            runCatching { scratchFile.close() }
            throw failure
        }
    }

    private fun tempFileOnly(scratch: File): MemoryUsageSetting =
        MemoryUsageSetting.setupTempFileOnly().setTempDir(scratch)

    private fun mixed(scratch: File, mainMemoryBytes: Long): MemoryUsageSetting =
        MemoryUsageSetting.setupMixed(mainMemoryBytes).setTempDir(scratch)

    private fun clearScratch(scratch: File) {
        scratch.listFiles()?.forEach { it.deleteRecursively() }
    }

    private fun scratchBytes(scratch: File): Long =
        scratch.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    private fun usedHeapMb(): Long {
        val runtime = Runtime.getRuntime()
        return (runtime.totalMemory() - runtime.freeMemory()) / MB
    }

    private companion object {
        const val TAG = "M1BENCH"
        const val SCRATCH_DIR_NAME = "pdfbox-scratch"
        const val EXTRA_PATH = "path"
        const val EXTRA_CONCURRENCY = "concurrency"
        const val EXTRA_SETTING = "setting"
        const val EXTRA_FUSE = "fuse"
        const val EXTRA_LABEL = "label"
        const val EXTRA_TRACE = "trace"
        const val EXTRA_SAMPLE = "sample"
        const val EXTRA_STRICT = "strict"
        const val MB = 1024L * 1024L
        const val SAMPLE_INTERVAL_MS = 10L
        const val SAMPLE_INTERVAL_US = SAMPLE_INTERVAL_MS * 1000L
        /** ART's documented guidance is a short trace; a 64 MB sampling buffer
         *  holds 10 ms samples for a couple of minutes without truncation. */
        const val TRACE_BUFFER_BYTES = 64 * 1024 * 1024
    }
}
