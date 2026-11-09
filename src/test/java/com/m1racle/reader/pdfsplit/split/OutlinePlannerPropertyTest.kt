package com.m1racle.reader.pdfsplit.split

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Property tests over random bookmark trees (fixed seeds, reproducible).
 *
 * No formal-verification counterexample was found in the repository or the
 * hand-over notes, so these generalise the hand-written cases instead.
 */
class OutlinePlannerPropertyTest {

    /** A tree whose start pages follow reading order, like a real book. */
    private fun orderedTree(random: Random, totalPages: Int): List<OutlineNode> {
        val nodes = ArrayList<OutlineNode>()
        var page = 1 + random.nextInt(0, 4) // sometimes front matter
        fun grow(depth: Int, parent: Int) {
            val count = random.nextInt(1, 5)
            repeat(count) {
                if (page > totalPages + 2) return
                val index = nodes.size
                nodes += OutlineNode(index, "N$index", depth, parent, page)
                val hasKids = depth < 4 && random.nextInt(3) == 0
                // A parent may share its first page with its first child or have its own title pages.
                if (!hasKids || random.nextBoolean()) page += random.nextInt(0, 4)
                if (hasKids) grow(depth + 1, index) else page += 1
            }
        }
        grow(1, -1)
        return nodes
    }

    /** Arbitrary tree: start pages in any order, some outside the document. */
    private fun messyTree(random: Random, totalPages: Int): List<OutlineNode> {
        val size = random.nextInt(1, 25)
        val nodes = ArrayList<OutlineNode>()
        repeat(size) { index ->
            val parent = if (index == 0 || random.nextInt(3) == 0) -1 else random.nextInt(index)
            val depth = if (parent < 0) 1 else nodes[parent].depth + 1
            nodes += OutlineNode(index, "M$index", depth, parent, random.nextInt(-2, totalPages + 4))
        }
        return nodes
    }

    private fun pages(parts: List<SplitPart>) = parts.flatMap { it.startPage1..it.endPage1 }

    @Test
    fun `whole book selection on ordered trees covers each page exactly once`() {
        repeat(2000) { seed ->
            val random = Random(seed)
            val total = random.nextInt(1, 120)
            val nodes = orderedTree(random, total)
            val choice = OutlinePlanner.wholeBookSelection(nodes, total)
            val parts = OutlinePlanner.plan(nodes, choice.selected, total, "b.pdf")
            if (nodes.none { it.startPage1 in 1..total }) {
                assertTrue("seed $seed", parts.isEmpty())
                return@repeat
            }
            assertEquals("seed $seed nodes=$nodes parts=$parts", (1..total).toList(), pages(parts).sorted())
            assertEquals("seed $seed", (1..parts.size).toList(), parts.map { it.index })
        }
    }

    @Test
    fun `selecting every node of one level on ordered trees covers each page exactly once`() {
        // Top level always tiles the book; deeper levels tile it when no
        // shallower chapter is childless (the whole-book rule adds those).
        repeat(1000) { seed ->
            val random = Random(10_000 + seed)
            val total = random.nextInt(1, 120)
            val nodes = orderedTree(random, total)
            if (nodes.none { it.startPage1 in 1..total }) return@repeat
            val top = nodes.filter { it.depth == 1 }.map { it.index }.toSet()
            val parts = OutlinePlanner.plan(nodes, top, total, "b.pdf")
            assertEquals("seed $seed", (1..total).toList(), pages(parts).sorted())
        }
    }

    @Test
    fun `any selection on any tree never repeats a page or leaves the document`() {
        repeat(3000) { seed ->
            val random = Random(50_000 + seed)
            val total = random.nextInt(1, 60)
            val nodes = if (random.nextBoolean()) messyTree(random, total) else orderedTree(random, total)
            var selected = emptySet<Int>()
            repeat(random.nextInt(0, nodes.size + 1)) {
                selected = OutlinePlanner.toggle(selected, random.nextInt(nodes.size), nodes)
            }
            val parts = OutlinePlanner.plan(nodes, selected, total, "b.pdf")
            val covered = pages(parts)
            assertEquals("seed $seed overlap", covered.size, covered.toSet().size)
            assertTrue("seed $seed range", covered.all { it in 1..total })
            assertTrue("seed $seed empty part", parts.all { it.endPage1 >= it.startPage1 })
            assertEquals("seed $seed names", parts.size, parts.map { it.outputName }.toSet().size)
        }
    }

    @Test
    fun `a selected chapter on an ordered tree gets exactly its own pages`() {
        // Selective rule: a lone chapter stops before the next bookmark that is
        // not inside it, never at the end of the book (unless it is the last).
        repeat(1500) { seed ->
            val random = Random(90_000 + seed)
            val total = random.nextInt(2, 120)
            val nodes = orderedTree(random, total).filter { it.startPage1 in 1..total }
            if (nodes.isEmpty()) return@repeat
            val target = nodes[random.nextInt(nodes.size)]
            fun isDesc(n: OutlineNode): Boolean {
                var c = n.parentIndex
                while (c >= 0) { if (c == target.index) return true; c = nodes.firstOrNull { it.index == c }?.parentIndex ?: -1 }
                return false
            }
            val nextOutside = nodes.filter { it.startPage1 > target.startPage1 && !isDesc(it) }.minOfOrNull { it.startPage1 }
            val expectedEnd = (nextOutside ?: (total + 1)) - 1
            val parts = OutlinePlanner.plan(nodes, setOf(target.index), total, "b.pdf")
                .filter { it.outputName == "N${target.index}.pdf" }
            assertEquals("seed $seed", 1, parts.size)
            assertEquals("seed $seed", expectedEnd, parts.single().endPage1)
            assertTrue("seed $seed", parts.single().startPage1 <= target.startPage1)
        }
    }
}
