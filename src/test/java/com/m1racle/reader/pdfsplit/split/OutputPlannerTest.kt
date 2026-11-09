package com.m1racle.reader.pdfsplit.split

import org.junit.Assert.assertEquals
import org.junit.Test

class OutputPlannerTest {

    private fun part(index: Int, name: String) = SplitPart(
        index = index,
        startPage1 = (index - 1) * 50 + 1,
        endPage1 = index * 50,
        outputName = name,
    )

    @Test
    fun `keeps names that do not clash with the folder`() {
        val plan = OutputPlanner.avoidExisting(
            parts = listOf(part(1, "a.pdf"), part(2, "b.pdf")),
            existingLowercaseNames = setOf("c.pdf"),
        )
        assertEquals(0, plan.conflicts)
        assertEquals(listOf("a.pdf", "b.pdf"), plan.parts.map { it.outputName })
    }

    @Test
    fun `renames a file that already exists in the folder`() {
        val plan = OutputPlanner.avoidExisting(
            parts = listOf(part(1, "心肺听诊.pdf")),
            existingLowercaseNames = setOf("心肺听诊.pdf"),
        )
        assertEquals(1, plan.conflicts)
        assertEquals("心肺听诊_2.pdf", plan.parts.first().outputName)
    }

    @Test
    fun `skips every suffix already taken`() {
        val plan = OutputPlanner.avoidExisting(
            parts = listOf(part(1, "心肺听诊.pdf")),
            existingLowercaseNames = setOf("心肺听诊.pdf", "心肺听诊_2.pdf", "心肺听诊_3.pdf"),
        )
        assertEquals(1, plan.conflicts)
        assertEquals("心肺听诊_4.pdf", plan.parts.first().outputName)
    }

    @Test
    fun `keeps the batch itself unique while avoiding the folder`() {
        val plan = OutputPlanner.avoidExisting(
            parts = listOf(part(1, "x.pdf"), part(2, "x_2.pdf")),
            existingLowercaseNames = setOf("x.pdf"),
        )
        val names = plan.parts.map { it.outputName }
        assertEquals(names.size, names.map { it.lowercase() }.distinct().size)
        assertEquals("x_2.pdf", names[0])
        assertEquals("x_2_2.pdf", names[1])
    }

    @Test
    fun `comparison ignores case because the provider does`() {
        val plan = OutputPlanner.avoidExisting(
            parts = listOf(part(1, "Chapter.PDF")),
            existingLowercaseNames = setOf("chapter.pdf"),
        )
        assertEquals(1, plan.conflicts)
        assertEquals("Chapter_2.pdf", plan.parts.first().outputName)
    }
}
