package com.m1racle.reader.pdfsplit.split

import org.junit.Assert.assertEquals
import org.junit.Test

class PdfPageLabelsTest {

    @Test
    fun `formats decimal roman and letter styles`() {
        assertEquals("7", PdfPageLabels.formatNumber("D", 7))
        assertEquals("IV", PdfPageLabels.formatNumber("R", 4))
        assertEquals("iv", PdfPageLabels.formatNumber("r", 4))
        assertEquals("C", PdfPageLabels.formatNumber("A", 3))
        assertEquals("c", PdfPageLabels.formatNumber("a", 3))
        // A range with no style contributes its prefix only.
        assertEquals("", PdfPageLabels.formatNumber(null, 9))
    }

    @Test
    fun `roman numerals cover the table and the subtractive cases`() {
        assertEquals("I", PdfPageLabels.toRoman(1))
        assertEquals("IV", PdfPageLabels.toRoman(4))
        assertEquals("IX", PdfPageLabels.toRoman(9))
        assertEquals("XL", PdfPageLabels.toRoman(40))
        assertEquals("XC", PdfPageLabels.toRoman(90))
        assertEquals("CD", PdfPageLabels.toRoman(400))
        assertEquals("CM", PdfPageLabels.toRoman(900))
        assertEquals("MCMXCIV", PdfPageLabels.toRoman(1994))
    }

    @Test
    fun `letters roll over like a spreadsheet column`() {
        assertEquals("A", PdfPageLabels.toLetters(1))
        assertEquals("Z", PdfPageLabels.toLetters(26))
        assertEquals("AA", PdfPageLabels.toLetters(27))
        assertEquals("AZ", PdfPageLabels.toLetters(52))
        assertEquals("BA", PdfPageLabels.toLetters(53))
    }
}
