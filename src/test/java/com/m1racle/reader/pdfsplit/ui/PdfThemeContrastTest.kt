package com.m1racle.reader.pdfsplit.ui

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

/** Visual contrast is part of release correctness, not only a subjective theme choice. */
class PdfThemeContrastTest {
    private fun luminance(color: Color): Double {
        fun linear(value: Float): Double {
            val x = value.toDouble()
            return if (x <= 0.04045) x / 12.92 else ((x + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * linear(color.red) +
            0.7152 * linear(color.green) +
            0.0722 * linear(color.blue)
    }

    private fun ratio(first: Color, second: Color): Double {
        val a = luminance(first)
        val b = luminance(second)
        return (maxOf(a, b) + 0.05) / (minOf(a, b) + 0.05)
    }

    private fun readable(foreground: Color, background: Color, label: String) {
        val contrast = ratio(foreground, background)
        assertTrue("$label contrast $contrast below WCAG 2.2 AA 4.5:1", contrast >= 4.5)
    }

    @Test fun lightThemeContentIsReadable() {
        readable(lightPdfPalette.textPrimary, lightPdfPalette.background, "light primary")
        readable(lightPdfPalette.textSecondary, lightPdfPalette.card, "light secondary")
        readable(lightPdfPalette.textTertiary, lightPdfPalette.inner, "light muted")
        readable(lightPdfPalette.onAccent, lightPdfPalette.accent, "light CTA")
        readable(lightPdfPalette.warning, lightPdfPalette.surface, "light status")
    }

    @Test fun darkThemeContentIsReadable() {
        readable(darkPdfPalette.textPrimary, darkPdfPalette.background, "dark primary")
        readable(darkPdfPalette.textSecondary, darkPdfPalette.card, "dark secondary")
        readable(darkPdfPalette.textTertiary, darkPdfPalette.inner, "dark muted")
        readable(darkPdfPalette.onAccent, darkPdfPalette.accent, "dark CTA")
        readable(darkPdfPalette.warning, darkPdfPalette.surface, "dark status")
    }
}
