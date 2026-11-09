package com.m1racle.reader.pdfsplit.split

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Cheap facts about a PDF that the UI shows before the user does anything. */
data class PdfExtras(
    val hasOutline: Boolean,
    val pageLabels: List<String>?,
)

/**
 * One PDFBox open for both facts, because opening a 139 MB scan twice costs the
 * user a visible pause. Failures are swallowed: extras are decoration, not a
 * precondition for splitting.
 */
object PdfProbe {

    suspend fun probe(context: Context, uri: Uri): PdfExtras? = withContext(Dispatchers.IO) {
        runCatching {
            PdfBoxSetup.ensureInit(context)
            val probeStart = android.os.SystemClock.elapsedRealtime()
            val staged = PdfBoxSetup.stageSource(context, uri) { _, _ -> }
            val stageMs = android.os.SystemClock.elapsedRealtime() - probeStart
            val stagedBytes = staged.file.length()
            var loadMs = -1L
            var loadCpuMs = -1L
            var loadReadBytes = -1L
            var loadWriteBytes = -1L
            var loadRchar = -1L
            var loadWchar = -1L
            var extrasMs = -1L
            try {
                val ioBefore = SplitDiagnostics.ioSnapshot()
                val cpuBefore = SplitDiagnostics.cpuMs()
                val loadStart = android.os.SystemClock.elapsedRealtime()
                PdfBoxSetup.load(staged.file).use { document ->
                    loadMs = android.os.SystemClock.elapsedRealtime() - loadStart
                    val ioAfter = SplitDiagnostics.ioSnapshot()
                    loadCpuMs = SplitDiagnostics.cpuMs() - cpuBefore
                    loadReadBytes = ioAfter.readBytes - ioBefore.readBytes
                    loadWriteBytes = ioAfter.writeBytes - ioBefore.writeBytes
                    loadRchar = ioAfter.rchar - ioBefore.rchar
                    loadWchar = ioAfter.wchar - ioBefore.wchar
                    val extrasStart = android.os.SystemClock.elapsedRealtime()
                    val hasOutline = runCatching {
                        document.documentCatalog.documentOutline
                    }.getOrNull()?.let { outline ->
                        runCatching { outline.firstChild }.getOrNull() != null
                    } ?: false
                    val labels = PdfPageLabels.extract(document)
                    extrasMs = android.os.SystemClock.elapsedRealtime() - extrasStart
                    PdfExtras(hasOutline = hasOutline, pageLabels = labels)
                }
            } finally {
                if (staged.temporary) staged.file.delete()
                // Probe cost is measured separately from the split run: they are
                // two independent full loads of the same document.
                android.util.Log.i(
                    "M1PDF",
                    "probe stageMs=$stageMs loadMs=$loadMs loadCpuMs=$loadCpuMs " +
                        "loadReadBytes=$loadReadBytes loadWriteBytes=$loadWriteBytes " +
                        "loadRchar=$loadRchar loadWchar=$loadWchar " +
                        "extrasMs=$extrasMs heapUsedMB=${SplitDiagnostics.heapUsedMb()} " +
                        SplitDiagnostics.gcStat() + " " +
                        "totalMs=${android.os.SystemClock.elapsedRealtime() - probeStart} " +
                        "fileBytes=$stagedBytes",
                )
            }
        }.getOrNull()
    }
}
