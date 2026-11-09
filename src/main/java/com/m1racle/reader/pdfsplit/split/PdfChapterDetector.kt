package com.m1racle.reader.pdfsplit.split

import android.content.Context
import android.net.Uri
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** A chapter found by scanning page text, not by trusting the bookmark tree. */
data class DetectedChapter(
    val startPage1: Int,
    val endPage1: Int,
    val title: String,
    val titleSize: Float,
    val bodySize: Float,
) {
    val pageCount: Int get() = endPage1 - startPage1 + 1
    /** Bigger headings are far more likely to be real chapter starts. */
    val confidence: Float get() = if (bodySize <= 0f) 1f else titleSize / bodySize
}

data class ChapterDetection(
    val chapters: List<DetectedChapter>,
    val totalPages: Int,
    val textPages: Int,
) {
    /** Scanned image PDFs expose no text, so heading detection cannot work. */
    val hasTextLayer: Boolean
        get() = totalPages > 0 && textPages >= (totalPages * 0.2f).toInt().coerceAtLeast(1)
}

/**
 * Finds chapter headings from the page text itself.
 *
 * Why not the outline: bookmarks are often missing, and when they exist their
 * page numbers can be stale after re-pagination. Scanning the text finds the
 * heading where it physically is.
 *
 * Rule, learned from real files: a heading is the largest text near the top of
 * a page and is clearly larger than that page's body text. Chapters run from one
 * heading page to the page before the next heading.
 */
object PdfChapterDetector {

    private const val BATCH_PAGES = 40
    private const val MIN_CHARS_PER_PAGE = 20
    private const val HEADING_SIZE_RATIO = 1.35f
    private const val HEADING_TOP_FRACTION = 0.35f
    private const val MAX_HEADING_CHARS = 40

    suspend fun detect(
        context: Context,
        uri: Uri,
        onProgress: suspend (scannedPages: Int, totalPages: Int) -> Unit,
    ): ChapterDetection = withContext(Dispatchers.IO) {
        val staged = PdfBoxSetup.stageSource(context, uri) { _, _ -> }
        try {
            PdfBoxSetup.load(staged.file).use { document ->
                val totalPages = document.numberOfPages
                if (totalPages == 0) {
                    return@use ChapterDetection(emptyList(), 0, 0)
                }
                val headings = ArrayList<PageHeading>()
                var scanned = 0
                var page = 1
                while (page <= totalPages) {
                    currentCoroutineContext().ensureActive()
                    val endPage = minOf(page + BATCH_PAGES - 1, totalPages)
                    val collector = HeadingCollector(document, headings)
                    collector.sortByPosition = true
                    collector.startPage = page
                    collector.endPage = endPage
                    collector.getText(document)
                    scanned = endPage
                    onProgress(scanned, totalPages)
                    page = endPage + 1
                }

                val textPages = headings.count { it.chars >= MIN_CHARS_PER_PAGE }
                ChapterDetection(
                    chapters = buildChapters(totalPages, headings),
                    totalPages = totalPages,
                    textPages = textPages,
                )
            }
        } finally {
            if (staged.temporary) staged.file.delete()
        }
    }

    internal fun buildChapters(
        totalPages: Int,
        headings: List<PageHeading>,
    ): List<DetectedChapter> {
        if (totalPages <= 0) return emptyList()
        val anchors = headings
            .filter { it.title.isNotBlank() }
            .sortedBy { it.page1 }
            .distinctBy { it.page1 }
        if (anchors.isEmpty()) return emptyList()

        return anchors.mapIndexedNotNull { index, anchor ->
            val endPage1 = (anchors.getOrNull(index + 1)?.page1 ?: (totalPages + 1)) - 1
            if (endPage1 < anchor.page1) {
                null
            } else {
                DetectedChapter(
                    startPage1 = anchor.page1,
                    endPage1 = endPage1,
                    title = anchor.title,
                    titleSize = anchor.titleSize,
                    bodySize = anchor.bodySize,
                )
            }
        }
    }

    data class PageHeading(
        val page1: Int,
        val title: String,
        val titleSize: Float,
        val bodySize: Float,
        val chars: Int,
    )

    /**
     * Collects one candidate heading per page by watching each page's text as
     * PDFBox streams it, so the whole book is parsed in a single pass.
     */
    private class HeadingCollector(
        private val pdf: PDDocument,
        private val sink: MutableList<PageHeading>,
    ) : PDFTextStripper() {

        private var activePage = -1
        private val lines = ArrayList<Line>()

        override fun writeString(text: String?) {
            // Default implementation writes into the output writer; unused here.
        }

        override fun writeString(text: String, textPositions: MutableList<TextPosition>) {
            val page = currentPageNo
            if (page != activePage) {
                flush()
                activePage = page
            }
            val size = textPositions.maxOfOrNull { it.fontSizeInPt } ?: 0f
            val clean = text.replace(Regex("\\s+"), " ").trim()
            if (clean.isEmpty()) return
            val y = textPositions.firstOrNull()?.yDirAdj ?: 0f
            lines += Line(clean, size, y)
        }

        override fun writePage() {
            flush()
        }

        private fun flush() {
            if (lines.isEmpty()) {
                return
            }
            val page = if (activePage > 0) activePage else return
            val chars = lines.sumOf { it.text.length }
            sink += PageHeading(
                page1 = page,
                title = pickHeading(page, lines),
                titleSize = bestSize(),
                bodySize = bodySize(),
                chars = chars,
            )
            lines.clear()
        }

        private var lastTitleSize = 0f

        private fun bestSize(): Float = lastTitleSize

        private fun bodySize(): Float {
            val weight = HashMap<Int, Int>()
            lines.forEach { line ->
                weight.merge(Math.round(line.size * 2), line.text.length, Int::plus)
            }
            val key = weight.maxByOrNull { it.value }?.key ?: 0
            return key / 2f
        }

        private fun pickHeading(page: Int, pageLines: List<Line>): String {
            if (pageLines.sumOf { it.text.length } < MIN_CHARS_PER_PAGE) {
                lastTitleSize = 0f
                return ""
            }
            val body = bodySize()
            val pageHeight = runCatching {
                pdf.getPage(page - 1).mediaBox.height
            }.getOrDefault(842f)

            val best = pageLines
                .filter { it.size >= body * HEADING_SIZE_RATIO }
                .filter { it.y <= pageHeight * HEADING_TOP_FRACTION }
                .filter { it.text.length in 2..MAX_HEADING_CHARS }
                .maxWithOrNull(compareBy({ it.size }, { -it.y }))

            lastTitleSize = best?.size ?: 0f
            return best?.text.orEmpty()
        }

        private data class Line(val text: String, val size: Float, val y: Float)
    }
}
