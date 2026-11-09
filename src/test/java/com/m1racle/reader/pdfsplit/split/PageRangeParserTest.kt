package com.m1racle.reader.pdfsplit.split

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PageRangeParserTest {

    /** The two-column paste from the real 贺银成 PDF, use verbatim. */
    private val pageColumn = """
        71-86
        87-91
        92-93
        94-140
        141-142
        143-307
        308-315
        316-320
        321-340
        341-354
        355-363
        364-372
    """.trimIndent()

    private val nameColumn = """
        26贺银成实践技能应试指南_ST01-CH01_心肺听诊
        26贺银成实践技能应试指南_ST01-CH02_普通X线影像诊断
        26贺银成实践技能应试指南_ST01-CH03_CT影像诊断
        26贺银成实践技能应试指南_ST01-CH04_超声诊断
        26贺银成实践技能应试指南_ST01-CH05_心电图
        26贺银成实践技能应试指南_ST01-CH06_医学人文素养
        26贺银成实践技能应试指南_ST01-CH07_病史采集答题技巧
        26贺银成实践技能应试指南_ST01-CH08_病史采集
        26贺银成实践技能应试指南_ST01-CH09_病例分析答题技巧
        26贺银成实践技能应试指南_ST01-CH10_病例分析
        26贺银成实践技能应试指南_ST02-CH01_一般检查
        26贺银成实践技能应试指南_ST02-CH02_头颈部检查
        26贺银成实践技能应试指南_ST02-CH03_胸部检查
        26贺银成实践技能应试指南_ST02-CH04_腹部检查
        26贺银成实践技能应试指南_ST02-CH05_脊柱四肢、肛门与神经系统检查
        26贺银成实践技能应试指南_ST03-CH01_手术人员的术前准备
        26贺银成实践技能应试指南_ST03-CH02_外科小手术
        26贺银成实践技能应试指南_ST03-CH03_临床常用操作
        26贺银成实践技能应试指南_ST03-CH04_临床常用穿刺术
        26贺银成实践技能应试指南_ST03-CH05_急救技术
        26贺银成实践技能应试指南_ST03-CH06_穿、脱隔离衣
    """.trimIndent()

    @Test
    fun `pairs a page column with a name column when the counts match`() {
        val names = nameColumn.lines().take(12).joinToString("\n")
        val outcome = PageRangeParser.parseBlock(
            text = pageColumn + "\n" + names,
            pageCount = 400,
            sourceDisplayName = "26贺银成实践技能应试指南.pdf",
        )

        assertEquals(12, outcome.parts.size)
        assertEquals(12, outcome.pairedNames)
        assertEquals(0, outcome.unusedNames)
        assertTrue(!outcome.hasBlockingError)
        // Ranges 71-372 do not cover 1-70 or 373-400; that is reported as a gap
        // warning, which is expected here and must not be confused with a
        // pairing failure.
        assertTrue(outcome.warnings.none { it.contains("数量不一致") })

        val first = outcome.parts.first()
        assertEquals(71, first.startPage1)
        assertEquals(86, first.endPage1)
        assertEquals(
            "26贺银成实践技能应试指南_ST01-CH01_心肺听诊.pdf",
            first.outputName,
        )

        val last = outcome.parts.last()
        assertEquals(364, last.startPage1)
        assertEquals(372, last.endPage1)
        assertEquals(
            "26贺银成实践技能应试指南_ST02-CH02_头颈部检查.pdf",
            last.outputName,
        )
    }

    /**
     * The user's real paste: 12 page ranges but 21 chapter names. Zipping them
     * would attach name #13 to range #13 that does not exist and shift
     * everything, so the parser must refuse rather than guess.
     */
    @Test
    fun `refuses to zip unequal columns instead of mislabelling every file`() {
        val outcome = PageRangeParser.parseBlock(
            text = pageColumn + "\n" + nameColumn,
            pageCount = 400,
            sourceDisplayName = "26贺银成实践技能应试指南.pdf",
        )

        assertEquals(12, outcome.parts.size)
        assertEquals(0, outcome.pairedNames)
        assertEquals(21, outcome.unusedNames)
        assertTrue(outcome.warnings.any { it.contains("数量不一致") })
        assertEquals(
            "26贺银成实践技能应试指南_0071-0086.pdf",
            outcome.parts.first().outputName,
        )
    }

    @Test
    fun `keeps default naming when only ranges are pasted`() {
        val outcome = PageRangeParser.parseBlock(pageColumn, 400, "book.pdf")
        assertEquals(12, outcome.parts.size)
        assertEquals(0, outcome.pairedNames)
        assertEquals("book_0071-0086.pdf", outcome.parts.first().outputName)
    }

    @Test
    fun `reads range and name on the same line in both orders`() {
        val leading = PageRangeParser.parseBlock("71-86 心肺听诊", 400, "book.pdf")
        assertEquals(1, leading.parts.size)
        assertEquals("心肺听诊.pdf", leading.parts.first().outputName)
        assertEquals(0, leading.pairedNames)

        val trailing = PageRangeParser.parseBlock("心肺听诊 71-86", 400, "book.pdf")
        assertEquals(1, trailing.parts.size)
        assertEquals("心肺听诊.pdf", trailing.parts.first().outputName)
        assertEquals(71, trailing.parts.first().startPage1)
        assertEquals(86, trailing.parts.first().endPage1)
    }

    @Test
    fun `treats a lone number as a single page`() {
        val outcome = PageRangeParser.parseBlock("5", 400, "book.pdf")
        assertEquals(1, outcome.parts.size)
        assertEquals(5, outcome.parts.first().startPage1)
        assertEquals(5, outcome.parts.first().endPage1)
    }

    @Test
    fun `reports an out-of-range line by number without discarding valid lines`() {
        val outcome = PageRangeParser.parseBlock("1-10\n11-500\n20-30", 420, "book.pdf")
        assertEquals(1, outcome.errors.size)
        assertEquals(2, outcome.errors.first().line)
        assertTrue(outcome.errors.first().message.contains("500"))
        assertTrue(outcome.hasBlockingError)
        // The valid lines stay usable so the preview can still show them.
        assertEquals(2, outcome.parts.size)
        assertEquals(20, outcome.parts[1].startPage1)
    }

    @Test
    fun `detects overlapping ranges and names both lines`() {
        val outcome = PageRangeParser.parseBlock("1-50\n40-80", 420, "book.pdf")
        assertEquals(1, outcome.overlaps.size)
        assertEquals(40, outcome.overlaps.first().startPage1)
        assertEquals(50, outcome.overlaps.first().endPage1)
        assertEquals(listOf(1, 2), outcome.overlaps.first().lines)
        assertTrue(outcome.warnings.any { it.contains("重叠") })
        // Overlap is a warning, never a blocker.
        assertTrue(!outcome.hasBlockingError)
    }

    @Test
    fun `reports uncovered pages without blocking the run`() {
        val outcome = PageRangeParser.parseBlock("1-100\n200-300", 420, "book.pdf")
        assertEquals(2, outcome.parts.size)
        assertTrue(!outcome.hasBlockingError)
        assertEquals(listOf(101..199, 301..420), outcome.uncoveredPages)
        assertEquals(219, outcome.uncoveredPageCount)
        assertTrue(outcome.warnings.any { it.contains("未被包含") })
    }

    @Test
    fun `no gaps when the ranges cover the whole document`() {
        val outcome = PageRangeParser.parseBlock("1-210\n211-420", 420, "book.pdf")
        assertTrue(outcome.uncoveredPages.isEmpty())
        assertTrue(outcome.overlaps.isEmpty())
    }

    @Test
    fun `counts how many names were renamed to avoid a clash`() {
        val outcome = PageRangeParser.parseBlock(
            "1-10 心肺听诊\n11-20 心肺听诊\n21-30 心肺听诊",
            420,
            "book.pdf",
        )
        assertEquals(3, outcome.parts.size)
        assertEquals(2, outcome.renamedDuplicates)
        assertEquals("心肺听诊.pdf", outcome.parts[0].outputName)
        assertEquals("心肺听诊_2.pdf", outcome.parts[1].outputName)
        assertEquals("心肺听诊_3.pdf", outcome.parts[2].outputName)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects names without any page range`() {
        PageRangeParser.parseBlock(nameColumn, 400, "book.pdf")
    }

    @Test
    fun `does not mistake digits inside names for page ranges`() {
        val outcome = PageRangeParser.parseBlock(
            text = "1-10\n26贺银成_ST01-CH01_心肺听诊",
            400,
            "book.pdf",
        )
        assertEquals(1, outcome.parts.size)
        assertEquals("26贺银成_ST01-CH01_心肺听诊.pdf", outcome.parts.first().outputName)
    }

    @Test
    fun `deduplicates identical names instead of overwriting files`() {
        val outcome = PageRangeParser.parseBlock(
            "1-10 心肺听诊\n11-20 心肺听诊",
            400,
            "book.pdf",
        )
        assertEquals(2, outcome.parts.size)
        assertEquals("心肺听诊.pdf", outcome.parts[0].outputName)
        assertEquals("心肺听诊_2.pdf", outcome.parts[1].outputName)
    }

    @Test
    fun `truncates long chinese names to a filesystem-safe byte length`() {
        val longName = "心肺听诊".repeat(30)
        val outcome = PageRangeParser.parseBlock("1-10 $longName", 400, "book.pdf")
        val name = outcome.parts.first().outputName
        assertTrue(
            "name is ${name.toByteArray(Charsets.UTF_8).size} bytes",
            name.toByteArray(Charsets.UTF_8).size <= 200,
        )
        assertTrue(name.endsWith(".pdf"))
    }
}
