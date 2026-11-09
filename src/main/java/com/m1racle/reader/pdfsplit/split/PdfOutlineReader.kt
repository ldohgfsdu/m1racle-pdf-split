package com.m1racle.reader.pdfsplit.split

import android.content.Context
import android.net.Uri
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionGoTo
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A chapter taken from the PDF's own bookmark tree, so name and page agree. */
data class OutlineChapter(
    val title: String,
    val startPage1: Int,
    val endPage1: Int,
) {
    val pageCount: Int get() = endPage1 - startPage1 + 1
}

/**
 * Reads the embedded bookmark outline and turns it into page ranges.
 *
 * Names and page numbers come from the same source, which is the only way to
 * get a guaranteed one-to-one pairing. A chapter ends where the next one starts.
 */
object PdfOutlineReader {

    /**
     * @return chapters sorted by page, or an empty list when the PDF has no
     * bookmark outline (or none of its entries resolve to a page).
     */
    suspend fun read(context: Context, uri: Uri): List<OutlineChapter> =
        withContext(Dispatchers.IO) {
            val staged = PdfBoxSetup.stageSource(context, uri) { _, _ -> }
            try {
                PdfBoxSetup.load(staged.file).use { document ->
                    buildChapters(document.numberOfPages, collectBookmarks(document))
                }
            } finally {
                if (staged.temporary) staged.file.delete()
            }
        }

    /**
     * Pure range math, split out so it is testable without a device.
     * A chapter ends where the next one starts; the last one runs to the end.
     */
    internal fun buildChapters(
        totalPages: Int,
        bookmarks: List<Pair<String, Int>>,
    ): List<OutlineChapter> {
        if (totalPages <= 0 || bookmarks.isEmpty()) return emptyList()
        val anchors = bookmarks
            .filter { it.second in 0 until totalPages }
            .sortedBy { it.second }
            .distinctBy { it.second }
        if (anchors.isEmpty()) return emptyList()

        return anchors.mapIndexedNotNull { index, (title, startPage0) ->
            val endPage0 = (anchors.getOrNull(index + 1)?.second ?: totalPages) - 1
            if (endPage0 < startPage0) {
                null
            } else {
                OutlineChapter(
                    title = title,
                    startPage1 = startPage0 + 1,
                    endPage1 = endPage0 + 1,
                )
            }
        }
    }

    private fun collectBookmarks(document: PDDocument): List<Pair<String, Int>> {
        val root = runCatching {
            document.documentCatalog.documentOutline
        }.getOrNull() ?: return emptyList()

        val collected = ArrayList<Pair<String, Int>>()
        val visited = HashSet<Int>()

        fun walk(item: PDOutlineItem) {
            if (!visited.add(System.identityHashCode(item))) return
            val title = item.title?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
            val page = resolvePageIndex(document, item)
            if (title.isNotEmpty() && page != null) {
                collected += title to page
            }
            var child = runCatching { item.firstChild }.getOrNull()
            var guard = 0
            while (guard < MAX_OUTLINE_NODES) {
                val current = child ?: break
                walk(current)
                child = runCatching { current.nextSibling }.getOrNull()
                guard += 1
            }
        }

        var node = runCatching { root.firstChild }.getOrNull()
        var guard = 0
        while (guard < MAX_OUTLINE_NODES) {
            val current = node ?: break
            walk(current)
            node = runCatching { current.nextSibling }.getOrNull()
            guard += 1
        }
        return collected
    }

    /**
     * Flattens the bookmark tree while keeping depth and parent, so the UI can
     * offer level selection and per-chapter checkboxes for arbitrary depth.
     */
    suspend fun readTree(context: Context, uri: Uri): List<OutlineNode> =
        withContext(Dispatchers.IO) {
            val staged = PdfBoxSetup.stageSource(context, uri) { _, _ -> }
            try {
                PdfBoxSetup.load(staged.file).use { document -> collectNodes(document) }
            } finally {
                if (staged.temporary) staged.file.delete()
            }
        }

    private fun collectNodes(document: PDDocument): List<OutlineNode> {
        val root = runCatching {
            document.documentCatalog.documentOutline
        }.getOrNull() ?: return emptyList()

        val collected = ArrayList<OutlineNode>()
        val visited = HashSet<Int>()

        fun walk(item: PDOutlineItem, depth: Int, parentIndex: Int) {
            if (collected.size >= MAX_OUTLINE_NODES) return
            if (!visited.add(System.identityHashCode(item))) return

            val title = item.title?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
            val page0 = resolvePageIndex(document, item)
            val selfIndex = if (title.isNotEmpty() && page0 != null) {
                collected += OutlineNode(
                    index = collected.size,
                    title = title,
                    depth = depth,
                    parentIndex = parentIndex,
                    startPage1 = page0 + 1,
                )
                collected.size - 1
            } else {
                // An entry without a usable page still owns its children; keep
                // them attached to the nearest valid parent.
                parentIndex
            }

            var child = runCatching { item.firstChild }.getOrNull()
            var guard = 0
            while (guard < MAX_OUTLINE_NODES) {
                val current = child ?: break
                walk(current, depth + 1, selfIndex)
                child = runCatching { current.nextSibling }.getOrNull()
                guard += 1
            }
        }

        var node = runCatching { root.firstChild }.getOrNull()
        var guard = 0
        while (guard < MAX_OUTLINE_NODES) {
            val current = node ?: break
            walk(current, 1, -1)
            node = runCatching { current.nextSibling }.getOrNull()
            guard += 1
        }
        return collected
    }

    /**
     * Real PDFs usually store the destination as a page *object reference*, for
     * which [PDPageDestination.getPageNumber] returns -1. The page object has to
     * be resolved against the page tree and its index looked up.
     */
    private fun resolvePageIndex(document: PDDocument, item: PDOutlineItem): Int? {
        fun indexOf(destination: PDDestination?): Int? {
            val pageDestination = destination as? PDPageDestination ?: return null
            val page = runCatching { pageDestination.page }.getOrNull()
            if (page != null) {
                val index = runCatching { document.pages.indexOf(page) }.getOrDefault(-1)
                if (index >= 0) return index
            }
            val explicit = runCatching { pageDestination.pageNumber }.getOrDefault(-1)
            return if (explicit >= 0) explicit else null
        }

        runCatching { indexOf(item.destination) }.getOrNull()?.let { return it }
        return runCatching {
            val action = item.action
            if (action is PDActionGoTo) indexOf(action.destination) else null
        }.getOrNull()
    }

    private const val MAX_OUTLINE_NODES = 5000
}
