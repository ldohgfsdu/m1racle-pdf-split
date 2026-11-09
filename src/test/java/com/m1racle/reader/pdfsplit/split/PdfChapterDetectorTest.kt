package com.m1racle.reader.pdfsplit.split

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfChapterDetectorTest {

    private fun heading(page: Int, title: String, size: Float = 21f) =
        PdfChapterDetector.PageHeading(
            page1 = page,
            title = title,
            titleSize = size,
            bodySize = 10f,
            chars = 400,
        )

    @Test
    fun `chapters run from one heading page to the page before the next`() {
        val chapters = PdfChapterDetector.buildChapters(
            totalPages = 66,
            headings = listOf(
                heading(1, "Apple 人机界面指南", 29f),
                heading(4, "1 入门与平台设计"),
                heading(8, "2 基础设计"),
            ),
        )

        assertEquals(3, chapters.size)
        assertEquals(1, chapters[0].startPage1)
        assertEquals(3, chapters[0].endPage1)
        assertEquals(4, chapters[1].startPage1)
        assertEquals(7, chapters[1].endPage1)
        assertEquals(8, chapters[2].startPage1)
        assertEquals(66, chapters[2].endPage1)
        assertEquals(66, chapters.sumOf { it.pageCount })
    }

    @Test
    fun `pages that produced no heading simply extend the previous chapter`() {
        val chapters = PdfChapterDetector.buildChapters(
            totalPages = 30,
            headings = listOf(
                heading(1, "第一章"),   // pages 3-29 produced no heading
                heading(2, "第二章"),
            ),
        )
        assertEquals(2, chapters.size)
        assertEquals(1, chapters[0].startPage1)
        assertEquals(1, chapters[0].endPage1)
        assertEquals(30, chapters[1].endPage1)
    }

    @Test
    fun `blank titles are ignored`() {
        val chapters = PdfChapterDetector.buildChapters(
            totalPages = 10,
            headings = listOf(heading(1, ""), heading(5, "真标题")),
        )
        assertEquals(1, chapters.size)
        assertEquals(5, chapters.first().startPage1)
        assertEquals(10, chapters.first().endPage1)
    }

    @Test
    fun `no headings means no chapters`() {
        assertTrue(PdfChapterDetector.buildChapters(100, emptyList()).isEmpty())
    }

    @Test
    fun `confidence reflects how much bigger the heading is than body text`() {
        val chapters = PdfChapterDetector.buildChapters(
            totalPages = 10,
            headings = listOf(heading(1, "大标题", 29f)),
        )
        assertEquals(2.9f, chapters.first().confidence, 0.01f)
    }
}
