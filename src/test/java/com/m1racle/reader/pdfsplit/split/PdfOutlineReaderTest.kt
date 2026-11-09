package com.m1racle.reader.pdfsplit.split

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfOutlineReaderTest {

    @Test
    fun `a chapter ends where the next one starts`() {
        val chapters = PdfOutlineReader.buildChapters(
            totalPages = 20,
            bookmarks = listOf(
                "第一章 心肺听诊" to 0,
                "第二章 普通X线影像诊断" to 3,
                "第三章 CT影像诊断" to 8,
                "第四章 超声诊断" to 12,
                "第五章 心电图" to 19,
            ),
        )

        assertEquals(5, chapters.size)
        assertEquals(1, chapters[0].startPage1)
        assertEquals(3, chapters[0].endPage1)
        assertEquals(4, chapters[1].startPage1)
        assertEquals(8, chapters[1].endPage1)
        assertEquals(13, chapters[3].startPage1)
        assertEquals(19, chapters[3].endPage1)
        assertEquals(20, chapters[4].startPage1)
        assertEquals(20, chapters[4].endPage1)
        assertEquals(20, chapters.sumOf { it.pageCount })
    }

    @Test
    fun `ignores bookmarks outside the page tree`() {
        val chapters = PdfOutlineReader.buildChapters(
            totalPages = 10,
            bookmarks = listOf("坏条目" to -1, "越界" to 99, "正常" to 4),
        )
        assertEquals(1, chapters.size)
        assertEquals(5, chapters.first().startPage1)
        assertEquals(10, chapters.first().endPage1)
    }

    @Test
    fun `keeps the first bookmark when two share a page`() {
        val chapters = PdfOutlineReader.buildChapters(
            totalPages = 10,
            bookmarks = listOf("甲" to 2, "乙" to 2, "丙" to 6),
        )
        assertEquals(2, chapters.size)
        assertEquals("甲", chapters[0].title)
        assertEquals(3, chapters[0].startPage1)
        assertEquals(6, chapters[0].endPage1)
    }

    @Test
    fun `returns nothing for a pdf without bookmarks`() {
        assertTrue(PdfOutlineReader.buildChapters(66, emptyList()).isEmpty())
    }
}
