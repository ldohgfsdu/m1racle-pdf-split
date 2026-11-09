package com.m1racle.reader.pdfsplit.split

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OutlinePlannerTest {

    /**
     * 20-page book:
     *   ST01 (p1, depth 1)
     *     ST01-CH01 心肺听诊 (p1, depth 2)
     *     ST01-CH02 X线 (p6, depth 2)
     *   ST02 (p12, depth 1)
     *     ST02-CH01 一般检查 (p12, depth 2)
     */
    private val nodes = listOf(
        OutlineNode(0, "ST01 实践技能", 1, -1, 1),
        OutlineNode(1, "ST01-CH01_心肺听诊", 2, 0, 1),
        OutlineNode(2, "ST01-CH02_普通X线", 2, 0, 6),
        OutlineNode(3, "ST02 体格检查", 1, -1, 12),
        OutlineNode(4, "ST02-CH01_一般检查", 2, 3, 12),
    )

    @Test
    fun `planning children yields one range per chapter`() {
        val parts = OutlinePlanner.plan(
            nodes = nodes,
            selected = setOf(1, 2, 4),
            totalPages = 20,
            sourceDisplayName = "贺银成.pdf",
        )
        assertEquals(3, parts.size)
        assertEquals(1, parts[0].startPage1)
        assertEquals(5, parts[0].endPage1)
        assertEquals(6, parts[1].startPage1)
        assertEquals(11, parts[1].endPage1)
        assertEquals(12, parts[2].startPage1)
        assertEquals(20, parts[2].endPage1)
        assertEquals("ST01-CH01_心肺听诊.pdf", parts[0].outputName)
    }

    @Test
    fun `selecting a parent clears its selected children`() {
        val afterParent = OutlinePlanner.toggle(setOf(1, 2, 4), 0, nodes)
        // 0 becomes the selection, its children are dropped, unrelated 4 stays.
        assertEquals(setOf(0, 4), afterParent)
    }

    @Test
    fun `selecting a child clears its selected parent`() {
        val afterChild = OutlinePlanner.toggle(setOf(0, 4), 2, nodes)
        assertEquals(setOf(2, 4), afterChild)
    }

    @Test
    fun `toggle removes a node that is already selected`() {
        val selected = OutlinePlanner.toggle(setOf(2, 4), 2, nodes)
        assertEquals(setOf(4), selected)
    }

    @Test
    fun `a child under a selected parent is reported as covered`() {
        assertTrue(OutlinePlanner.isCoveredByAncestor(setOf(0), 2, nodes))
        assertFalse(OutlinePlanner.isCoveredByAncestor(setOf(1), 2, nodes))
    }

    @Test
    fun `parent and child never both contribute pages`() {
        val parts = OutlinePlanner.plan(
            nodes = nodes,
            selected = setOf(0, 1, 2),
            totalPages = 20,
            sourceDisplayName = "book.pdf",
        )
        val covered = parts.flatMap { it.startPage1..it.endPage1 }
        assertEquals(covered.size, covered.distinct().size)
    }

    @Test
    fun `planning at depth two covers the whole document when children tile it`() {
        val parts = OutlinePlanner.planAtDepth(nodes, 2, 20, "book.pdf")
        assertEquals(3, parts.size)
        assertEquals(20, parts.sumOf { it.pageCount })
    }

    @Test
    fun `duplicate chapter titles get distinct file names`() {
        val duplicated = listOf(
            OutlineNode(0, "病例分析", 1, -1, 1),
            OutlineNode(1, "病例分析", 1, -1, 10),
        )
        val parts = OutlinePlanner.plan(duplicated, setOf(0, 1), 20, "book.pdf")
        assertEquals(2, parts.size)
        assertEquals("病例分析.pdf", parts[0].outputName)
        assertEquals("病例分析_2.pdf", parts[1].outputName)
    }

    @Test
    fun `nothing selected means no output`() {
        assertTrue(OutlinePlanner.plan(nodes, emptySet(), 20, "book.pdf").isEmpty())
    }
}
