package com.m1racle.reader.pdfsplit.split

/**
 * One bookmark, flattened with its tree position kept.
 *
 * Only the start page is stored: a chapter's range depends on which chapters are
 * actually selected, so it cannot be decided here.
 */
data class OutlineNode(
    val index: Int,
    val title: String,
    val depth: Int,
    val parentIndex: Int,
    val startPage1: Int,
)

/**
 * Turns a bookmarked outline into split parts.
 *
 * Selecting a parent and one of its children at the same time would emit the
 * same pages twice, so selection is exclusive along the ancestor chain: picking a
 * node clears its ancestors and its descendants.
 *
 * Page ownership (what [plan] guarantees):
 *
 * A node's own span runs from its start page up to the page before the next
 * bookmark that starts later and is not one of its descendants (the end of the
 * book for the last one). Pages are never shared: if two spans would overlap,
 * the earlier part keeps the page.
 *
 * 1. 整本逐章（选择来自 [wholeBookSelection]）——每一页恰好归属一个文件：
 *    - 第一个书签之前的页（封面、版权页、前言等）单独成为一个文件，名称为
 *      「书名_0001-000N.pdf」，不会被丢掉；
 *    - 父章节自己的标题页（父章节起始页到第一个子章节之前的页）归入它的第一个
 *      子章节，而不是单独生成一个只有一两页的文件；
 *    - 每个选中的章节延伸到下一个章节开始之前，最后一章到全书末尾。
 *    这一点对“书签起始页按阅读顺序排列”的目录成立（真实书籍都是这样），由
 *    OutlinePlannerPropertyTest 的随机目录树验证。
 *
 * 2. 只勾选部分章节——只输出被勾选章节自己的页：
 *    - 章节到它自己的范围结束为止（下一个非子孙书签之前），不会因为后面的章节
 *      没勾选就一路延伸到全书末尾；
 *    - 未勾选父章节的标题页同样归入它勾选了的第一个子章节；
 *    - 只有当勾选范围从第一个书签开始时（即勾选了第一章，或第一章的第一个子
 *      章节），才附带一个前置页文件；否则前置页与此次选择无关，不输出。
 */
object OutlinePlanner {

    fun toggle(
        selected: Set<Int>,
        target: Int,
        nodes: List<OutlineNode>,
    ): Set<Int> {
        val byIndex = nodes.associateBy { it.index }
        if (!byIndex.containsKey(target)) return selected

        if (target in selected) {
            return selected - target
        }

        val blocked = HashSet<Int>()
        // Ancestors
        var cursor = byIndex[target]?.parentIndex ?: -1
        var guard = 0
        while (cursor >= 0 && guard < 1000) {
            blocked += cursor
            cursor = byIndex[cursor]?.parentIndex ?: -1
            guard += 1
        }
        // Descendants
        val children = HashMap<Int, MutableList<Int>>()
        nodes.forEach { node ->
            if (node.parentIndex >= 0) {
                children.getOrPut(node.parentIndex) { ArrayList() } += node.index
            }
        }
        val stack = ArrayDeque<Int>()
        stack.addAll(children[target].orEmpty())
        while (stack.isNotEmpty()) {
            val current = stack.removeFirst()
            blocked += current
            stack.addAll(children[current].orEmpty())
        }
        return (selected - blocked) + target
    }

    /** True when the node has a selected ancestor, so it is already covered. */
    fun isCoveredByAncestor(
        selected: Set<Int>,
        target: Int,
        nodes: List<OutlineNode>,
    ): Boolean {
        val byIndex = nodes.associateBy { it.index }
        var cursor = byIndex[target]?.parentIndex ?: -1
        var guard = 0
        while (cursor >= 0 && guard < 1000) {
            if (cursor in selected) return true
            cursor = byIndex[cursor]?.parentIndex ?: -1
            guard += 1
        }
        return false
    }

    /** The chapter level chosen for “整本逐章” and the nodes selected at it. */
    data class WholeBookChoice(val level: Int, val selected: Set<Int>)

    /**
     * “逐章” means the chapter level, not every last sub-section: a textbook
     * with 600 bookmarks would otherwise produce hundreds of tiny files.
     * Prefer level 2 (e.g. ST01-CH03), fall back to level 1; level-1 chapters
     * without children (e.g. 附录) are selected too so their pages are not lost.
     *
     * Bookmarks pointing past the last page are ignored, and a chapter whose
     * children all point past it counts as childless; otherwise a broken
     * bookmark could leave the whole book unselected.
     */
    fun wholeBookSelection(nodes: List<OutlineNode>, totalPages: Int = Int.MAX_VALUE): WholeBookChoice {
        val usable = nodes.filter { it.startPage1 in 1..totalPages }
        val choice = selectChapterLevel(usable)
        return if (choice.selected.isEmpty() && usable.isEmpty()) selectChapterLevel(nodes) else choice
    }

    private fun selectChapterLevel(nodes: List<OutlineNode>): WholeBookChoice {
        val level = if (nodes.any { it.depth == 2 }) 2 else 1
        val parents = nodes.map { it.parentIndex }.toSet()
        val atLevel = nodes.filter { it.depth == level }
        val orphanTops = nodes.filter { it.depth < level && it.index !in parents }
        val chosen = (atLevel + orphanTops)
            .distinctBy { it.startPage1 }
            .ifEmpty { nodes.filter { it.depth == 1 } }
        return WholeBookChoice(level = level, selected = chosen.map { it.index }.toSet())
    }

    fun plan(
        nodes: List<OutlineNode>,
        selected: Set<Int>,
        totalPages: Int,
        sourceDisplayName: String,
    ): List<SplitPart> {
        if (nodes.isEmpty() || selected.isEmpty() || totalPages <= 0) return emptyList()
        // Bookmarks pointing outside the document cannot own pages.
        val valid = nodes.filter { it.startPage1 in 1..totalPages }
        if (valid.isEmpty()) return emptyList()
        val byIndex = valid.associateBy { it.index }
        val allByIndex = nodes.associateBy { it.index }
        val children = HashMap<Int, MutableList<OutlineNode>>()
        valid.forEach { node ->
            if (node.parentIndex >= 0) children.getOrPut(node.parentIndex) { ArrayList() } += node
        }

        fun isDescendant(candidate: OutlineNode, ancestor: OutlineNode): Boolean {
            var cursor = candidate.parentIndex
            var guard = 0
            while (cursor >= 0 && guard < 1000) {
                if (cursor == ancestor.index) return true
                cursor = allByIndex[cursor]?.parentIndex ?: -1
                guard += 1
            }
            return false
        }

        // Rule: an unselected parent's title pages go to its first child. Walk up
        // while the node is the first child (by start page) of an unselected
        // parent that starts earlier; this can pass through several levels.
        fun effectiveStart(node: OutlineNode): Int {
            var start = node.startPage1
            var current = node
            var guard = 0
            while (guard < 1000) {
                guard += 1
                val parent = byIndex[current.parentIndex] ?: break
                if (parent.index in selected) break
                val first = children[parent.index].orEmpty()
                    .minWithOrNull(compareBy({ it.startPage1 }, { it.index })) ?: break
                if (first.index != current.index) break
                if (parent.startPage1 >= start) break
                start = parent.startPage1
                current = parent
            }
            return start
        }

        // Rule: a node's own span ends right before the next bookmark that starts
        // later and is not inside it, so an unselected sibling stops it.
        fun endOf(node: OutlineNode): Int {
            val next = valid
                .filter { it.startPage1 > node.startPage1 && !isDescendant(it, node) }
                .minOfOrNull { it.startPage1 }
            return (next ?: (totalPages + 1)) - 1
        }

        val anchors = selected
            .mapNotNull { byIndex[it] }
            .filterNot { isCoveredByAncestor(selected, it.index, nodes) }
            .map { node -> Triple(node, effectiveStart(node), endOf(node)) }
            .sortedWith(compareBy({ it.second }, { it.first.startPage1 }, { it.first.depth }))
        if (anchors.isEmpty()) return emptyList()

        val ranges = ArrayList<Triple<OutlineNode?, Int, Int>>()
        var claimedUntil = 0
        anchors.forEach { (node, start, end) ->
            val from = maxOf(start, claimedUntil + 1)
            if (from > end) return@forEach
            ranges += Triple(node, from, end)
            claimedUntil = end
        }
        if (ranges.isEmpty()) return emptyList()

        // Rule: front matter (pages before the first bookmark) is emitted only
        // when the plan starts at the first bookmark, i.e. the selection covers
        // the beginning of the book.
        val firstBookmark = valid.minOf { it.startPage1 }
        if (firstBookmark > 1 && ranges.first().second == firstBookmark) {
            ranges.add(0, Triple(null, 1, firstBookmark - 1))
        }

        val base = PageRangeParser.sanitizeBase(sourceDisplayName)
        val used = mutableSetOf<String>()
        return ranges.mapIndexed { position, (node, start, end) ->
            val planned = PageRangeParser.planName(
                base = base,
                start = start,
                end = end,
                explicitName = node?.title,
                used = used,
            )
            SplitPart(
                index = position + 1,
                startPage1 = start,
                endPage1 = end,
                outputName = planned.name,
                sourceLine = node?.index ?: 0,
            )
        }
    }

    /** All leaf-level chapter ranges at the requested depth, used for “全部拆分”. */
    fun planAtDepth(
        nodes: List<OutlineNode>,
        depth: Int,
        totalPages: Int,
        sourceDisplayName: String,
    ): List<SplitPart> {
        val wanted = nodes.filter { it.depth == depth }
        if (wanted.isEmpty()) return emptyList()
        return plan(
            nodes = nodes,
            selected = wanted.map { it.index }.toSet(),
            totalPages = totalPages,
            sourceDisplayName = sourceDisplayName,
        )
    }
}
