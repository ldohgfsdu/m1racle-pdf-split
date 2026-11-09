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
 * node clears its ancestors and its descendants. Ranges then follow the selected
 * order, each ending right before the next selected anchor.
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

    fun plan(
        nodes: List<OutlineNode>,
        selected: Set<Int>,
        totalPages: Int,
        sourceDisplayName: String,
    ): List<SplitPart> {
        if (nodes.isEmpty() || selected.isEmpty() || totalPages <= 0) return emptyList()
        val byIndex = nodes.associateBy { it.index }
        val anchors = selected
            .mapNotNull { byIndex[it] }
            .filter { it.startPage1 in 1..totalPages }
            .sortedWith(compareBy({ it.startPage1 }, { it.depth }))
            .distinctBy { it.startPage1 }
        if (anchors.isEmpty()) return emptyList()

        val base = PageRangeParser.sanitizeBase(sourceDisplayName)
        val used = mutableSetOf<String>()
        return anchors.mapIndexedNotNull { position, anchor ->
            val endPage1 = (anchors.getOrNull(position + 1)?.startPage1 ?: (totalPages + 1)) - 1
            if (endPage1 < anchor.startPage1) return@mapIndexedNotNull null
            val planned = PageRangeParser.planName(
                base = base,
                start = anchor.startPage1,
                end = endPage1,
                explicitName = anchor.title,
                used = used,
            )
            SplitPart(
                index = position + 1,
                startPage1 = anchor.startPage1,
                endPage1 = endPage1,
                outputName = planned.name,
                sourceLine = anchor.index,
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
