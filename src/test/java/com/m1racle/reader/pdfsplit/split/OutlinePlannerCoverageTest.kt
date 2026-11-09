package com.m1racle.reader.pdfsplit.split

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Page ownership for chapter splits: with every chapter selected, each page of
 * the book lands in exactly one file; unselected chapters stay out.
 */
class OutlinePlannerCoverageTest {

    /**
     * Same tree as fixture `01-bookmarks-20p.pdf` used in the UX review:
     *   Chapter 1 (p1) > 1.1 (p2), 1.2 (p4)
     *   Chapter 2 (p6) > 2.1 (p7), 2.2 (p9)
     *   Chapter 3 (p11)
     *   Chapter 4 (p16) > 4.1 (p17)
     */
    private val book = listOf(
        OutlineNode(0, "Chapter 1 Intro", 1, -1, 1),
        OutlineNode(1, "1.1 Basics", 2, 0, 2),
        OutlineNode(2, "1.2 More", 2, 0, 4),
        OutlineNode(3, "Chapter 2 Method", 1, -1, 6),
        OutlineNode(4, "2.1 Setup", 2, 3, 7),
        OutlineNode(5, "2.2 Steps", 2, 3, 9),
        OutlineNode(6, "Chapter 3 Result", 1, -1, 11),
        OutlineNode(7, "Chapter 4 End", 1, -1, 16),
        OutlineNode(8, "4.1 Notes", 2, 7, 17),
    )

    /** What 「按整本目录逐章拆」 selects: level 2 plus top chapters without children. */
    private val wholeBook = setOf(1, 2, 4, 5, 6, 8)

    private fun ranges(parts: List<SplitPart>) = parts.map { it.startPage1..it.endPage1 }

    private fun assertEveryPageOnce(parts: List<SplitPart>, total: Int) {
        val pages = parts.flatMap { it.startPage1..it.endPage1 }
        assertEquals("no page twice", pages.size, pages.toSet().size)
        assertEquals("every page", (1..total).toList(), pages.sorted())
    }

    @Test
    fun `whole book split puts every page in exactly one file`() {
        val parts = OutlinePlanner.plan(book, wholeBook, 20, "book.pdf")
        assertEveryPageOnce(parts, 20)
        assertEquals(listOf(1..3, 4..5, 6..8, 9..10, 11..15, 16..20), ranges(parts))
    }

    @Test
    fun `parent title page opens its first section, not the previous one`() {
        val parts = OutlinePlanner.plan(book, wholeBook, 20, "book.pdf")
        fun owner(page: Int) = parts.single { page in it.startPage1..it.endPage1 }.outputName
        assertEquals("1.1 Basics.pdf", owner(1))
        assertEquals("2.1 Setup.pdf", owner(6))
        assertEquals("4.1 Notes.pdf", owner(16))
        assertEquals("1.2 More.pdf", owner(5))
        assertEquals("Chapter 3 Result.pdf", owner(15))
    }

    @Test
    fun `top level split still tiles the book`() {
        val parts = OutlinePlanner.plan(book, setOf(0, 3, 6, 7), 20, "book.pdf")
        assertEquals(listOf(1..5, 6..10, 11..15, 16..20), ranges(parts))
    }

    @Test
    fun `an unselected chapter is left out instead of joining the one before`() {
        // Chapter 4 unchecked: Chapter 3 must stop at 15, not run to the end.
        val parts = OutlinePlanner.plan(book, setOf(0, 3, 6), 20, "book.pdf")
        assertEquals(listOf(1..5, 6..10, 11..15), ranges(parts))
    }

    @Test
    fun `a later section does not swallow an unselected earlier sibling`() {
        // Only 2.2 selected: the chapter title page and 2.1 belong to 2.1, not 2.2.
        val parts = OutlinePlanner.plan(book, setOf(5), 20, "book.pdf")
        assertEquals(listOf(9..10), ranges(parts))
    }

    @Test
    fun `pages before the first bookmark become their own file`() {
        val shifted = book.map { it.copy(startPage1 = it.startPage1 + 4) }
        val parts = OutlinePlanner.plan(shifted, wholeBook, 24, "教材.pdf")
        assertEveryPageOnce(parts, 24)
        assertEquals(1..4, ranges(parts).first())
        assertEquals("教材_0001-0004.pdf", parts.first().outputName)
        assertEquals((1..parts.size).toList(), parts.map { it.index })
    }

    @Test
    fun `front matter is not added when the plan starts later in the book`() {
        val shifted = book.map { it.copy(startPage1 = it.startPage1 + 4) }
        val parts = OutlinePlanner.plan(shifted, setOf(6), 24, "教材.pdf")
        assertEquals(listOf(15..19), ranges(parts))
    }

    @Test
    fun `opening pages pass down through several unselected levels`() {
        val deep = listOf(
            OutlineNode(0, "Part I", 1, -1, 1),
            OutlineNode(1, "Chapter 1", 2, 0, 2),
            OutlineNode(2, "1.1", 3, 1, 3),
            OutlineNode(3, "1.2", 3, 1, 6),
            OutlineNode(4, "Part II", 1, -1, 9),
            OutlineNode(5, "Chapter 2", 2, 4, 9),
            OutlineNode(6, "2.1", 3, 5, 10),
        )
        val parts = OutlinePlanner.plan(deep, setOf(2, 3, 6), 12, "b.pdf")
        assertEveryPageOnce(parts, 12)
        assertEquals(listOf(1..5, 6..8, 9..12), ranges(parts))
    }

    @Test
    fun `bookmarks out of page order never produce overlapping files`() {
        val messy = listOf(
            OutlineNode(0, "A", 1, -1, 10),
            OutlineNode(1, "A.1", 2, 0, 3),
            OutlineNode(2, "B", 1, -1, 5),
        )
        val parts = OutlinePlanner.plan(messy, setOf(1, 2), 12, "b.pdf")
        val pages = parts.flatMap { it.startPage1..it.endPage1 }
        assertEquals(pages.size, pages.toSet().size)
        assertTrue(pages.all { it in 1..12 })
    }

    @Test
    fun `bookmarks beyond the page count are ignored`() {
        val parts = OutlinePlanner.plan(book, wholeBook, 12, "book.pdf")
        assertEveryPageOnce(parts, 12)
        assertEquals(11..12, ranges(parts).last())
    }

    @Test
    fun `whole book selection is the chapter level plus childless top chapters`() {
        val choice = OutlinePlanner.wholeBookSelection(book)
        assertEquals(2, choice.level)
        assertEquals(wholeBook, choice.selected)
    }

    @Test
    fun `chapters whose sections point past the last page are still split`() {
        // Found by OutlinePlannerPropertyTest (seed 391): a 5-page file whose only
        // level-2 bookmark points at page 7 used to leave nothing selected.
        val broken = listOf(
            OutlineNode(0, "N0", 1, -1, 4),
            OutlineNode(1, "N1", 2, 0, 7),
        )
        val choice = OutlinePlanner.wholeBookSelection(broken, 5)
        val parts = OutlinePlanner.plan(broken, choice.selected, 5, "b.pdf")
        assertEveryPageOnce(parts, 5)
        assertEquals(listOf(1..3, 4..5), ranges(parts))
    }
}
