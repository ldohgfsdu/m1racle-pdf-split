package com.m1racle.reader.pdfsplit.split

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PageRangeSyntaxRegressionTest {
    private fun parse(text: String) = PageRangeParser.parseBlock(text, 20, "book.pdf")

    @Test fun commaSeparatedRangesAreThreeParts() {
        val outcome = parse("1-5,8,12-20")
        assertFalse(outcome.hasBlockingError)
        assertEquals(listOf(1..5, 8..8, 12..20),
            outcome.parts.map { it.startPage1..it.endPage1 })
        assertEquals(listOf(1, 1, 1), outcome.parts.map { it.sourceLine })
    }

    @Test fun chinesePunctuationAndSemicolonAlsoSeparateRanges() {
        val outcome = parse("1-3，4-5、8；12-20")
        assertFalse(outcome.hasBlockingError)
        assertEquals(4, outcome.parts.size)
        assertEquals(8, outcome.parts[2].startPage1)
    }

    @Test fun openEndStartsAtRequestedPage() {
        val outcome = parse("3-")
        assertFalse(outcome.hasBlockingError)
        assertEquals(3..20, outcome.parts.single().let { it.startPage1..it.endPage1 })
    }

    @Test fun openStartEndsAtRequestedPage() {
        val outcome = parse("-5")
        assertFalse(outcome.hasBlockingError)
        assertEquals(1..5, outcome.parts.single().let { it.startPage1..it.endPage1 })
    }

    @Test fun openRangeAndRegularRangeCanShareALine() {
        val outcome = parse("1-5,10-")
        assertFalse(outcome.hasBlockingError)
        assertEquals(listOf(1..5, 10..20), outcome.parts.map { it.startPage1..it.endPage1 })
    }

    @Test fun malformedNumericRangeIsBlocking() {
        val outcome = parse("1-5\n3-xyz")
        assertTrue(outcome.hasBlockingError)
        assertEquals(2, outcome.errors.single().line)
        assertEquals(1, outcome.parts.size)
    }

    @Test fun malformedLetterRangeIsNotSilentlyAFileName() {
        val outcome = parse("1-5\nx-y")
        assertTrue(outcome.hasBlockingError)
        assertEquals(2, outcome.errors.single().line)
    }

    @Test fun trailingCommaIsBlocking() {
        val outcome = parse("1-5,")
        assertTrue(outcome.hasBlockingError)
        assertEquals(1, outcome.parts.size)
        assertEquals(1, outcome.errors.single().line)
    }

    @Test fun outOfBoundsOpenRangeIsBlocking() {
        val outcome = parse("30-")
        assertTrue(outcome.hasBlockingError)
        assertEquals(0, outcome.parts.size)
    }

    @Test fun ambiguousOneNameDoesNotSilentlyRenameOnePart() {
        val outcome = parse("1-5\nabc")
        assertFalse(outcome.hasBlockingError)
        assertEquals("book_0001-0005.pdf", outcome.parts.single().outputName)
        assertTrue(outcome.warnings.any { it.contains("名称") })
    }

    @Test fun twoColumnChineseTextbookNamesStillPair() {
        val outcome = parse(
            "1-5\n6-10\n26贺银成_ST01-CH01_心肺听诊\n26贺银成_ST01-CH02_CT诊断"
        )
        assertFalse(outcome.hasBlockingError)
        assertEquals(2, outcome.pairedNames)
        assertEquals("26贺银成_ST01-CH01_心肺听诊.pdf", outcome.parts[0].outputName)
    }

    @Test fun inlineNamesStillWork() {
        val outcome = parse("1-5 心肺听诊\nCT影像 6-10")
        assertFalse(outcome.hasBlockingError)
        assertEquals("心肺听诊.pdf", outcome.parts[0].outputName)
        assertEquals("CT影像.pdf", outcome.parts[1].outputName)
    }
}
