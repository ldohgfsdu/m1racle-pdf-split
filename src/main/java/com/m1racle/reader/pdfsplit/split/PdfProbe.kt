package com.m1racle.reader.pdfsplit.split

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Cheap facts about a PDF that the UI shows before the user does anything. */
data class PdfExtras(
    val hasOutline: Boolean,
    val pageLabels: List<String>?,
    /** Which load path produced these facts, for the diagnostics line. */
    val loadNote: String = "",
    val loadMs: Long = -1L,
    /** True when the strict parse won, i.e. no full-file rescan was needed. */
    val fastLoad: Boolean = false,
    val xrefEntries: Int = 0,
    val xrefZeroOffsets: Int = 0,
)

/**
 * One PDFBox open for both facts, because opening a 139 MB scan twice costs the
 * user a visible pause. Failures are swallowed: extras are decoration, not a
 * precondition for splitting.
 *
 * The open goes through [LoadMode.AUTO]: on a book whose xref table has a
 * handful of defective entries this is 57,537 ms → 2,730 ms, and an unreachable
 * page silently costs the fast attempt and nothing else.
 */
object PdfProbe {

    suspend fun probe(context: Context, uri: Uri): PdfExtras? = withContext(Dispatchers.IO) {
        runCatching {
            PdfBoxSetup.ensureInit(context)
            val probeStart = SystemClock.elapsedRealtime()
            val staged = PdfBoxSetup.stageSource(context, uri) { _, _ -> }
            val stageMs = SystemClock.elapsedRealtime() - probeStart
            val stagedBytes = staged.file.length()
            var loadMs = -1L
            var loadCpuMs = -1L
            var extrasMs = -1L
            var xrefLine = "xref=none"
            var decision = "unknown"
            try {
                val cpuBefore = SplitDiagnostics.cpuMs()
                val loadStart = SystemClock.elapsedRealtime()
                PdfBoxSetup.open(staged.file, LoadMode.AUTO).let { outcome ->
                    loadMs = SystemClock.elapsedRealtime() - loadStart
                    loadCpuMs = SplitDiagnostics.cpuMs() - cpuBefore
                    decision = outcome.note
                    xrefLine = outcome.xref?.summary() ?: "xref=unavailable"
                    outcome.document.use { document ->
                        val extrasStart = SystemClock.elapsedRealtime()
                        val hasOutline = runCatching {
                            document.documentCatalog.documentOutline
                        }.getOrNull()?.let { outline ->
                            runCatching { outline.firstChild }.getOrNull() != null
                        } ?: false
                        val labels = PdfPageLabels.extract(document)
                        extrasMs = SystemClock.elapsedRealtime() - extrasStart
                        PdfExtras(
                            hasOutline = hasOutline,
                            pageLabels = labels,
                            loadNote = "${outcome.mode.name.lowercase()}: ${outcome.note}",
                            loadMs = outcome.loadMs,
                            fastLoad = outcome.usedFastPath,
                            xrefEntries = outcome.xref?.inspectedEntries ?: 0,
                            xrefZeroOffsets = outcome.xref?.zeroOffsetEntries ?: 0,
                        )
                    }
                }
            } finally {
                if (staged.temporary) staged.file.delete()
                Log.i(
                    "M1PDF",
                    "probe stageMs=$stageMs loadMs=$loadMs loadCpuMs=$loadCpuMs " +
                        "extrasMs=$extrasMs decision=[$decision] $xrefLine " +
                        "heapUsedMB=${SplitDiagnostics.heapUsedMb()} " +
                        SplitDiagnostics.gcStat() + " " +
                        "totalMs=${SystemClock.elapsedRealtime() - probeStart} " +
                        "fileBytes=$stagedBytes",
                )
            }
        }.getOrNull()
    }
}
