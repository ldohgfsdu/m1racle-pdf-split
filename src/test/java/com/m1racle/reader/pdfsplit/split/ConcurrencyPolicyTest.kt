package com.m1racle.reader.pdfsplit.split

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The cap is 1 on purpose. Every worker loads its own PDDocument and PDFBox
 * spills the whole document into the scratch file, so N workers write N x the
 * document size before the first part starts. Measured 2026-11-08 on a 926 MB
 * scan: one load = 3.0-4.1 s and 804-932 MB of scratch; six workers on the real
 * 420 page book produced 93-137 s per load with no gain in total time
 * (138,710 ms at 4 workers vs 138,523 ms at 6 workers).
 */
class ConcurrencyPolicyTest {

    @Test
    fun `auto never asks for more than one worker`() {
        assertEquals(1, ConcurrencyPolicy.auto(1))
        assertEquals(1, ConcurrencyPolicy.auto(2))
        assertEquals(1, ConcurrencyPolicy.auto(3))
        assertEquals(1, ConcurrencyPolicy.auto(4))
        assertEquals(1, ConcurrencyPolicy.auto(8))
        assertEquals(1, ConcurrencyPolicy.auto(16))
    }

    @Test
    fun `manual choice is clamped to the single worker cap`() {
        assertEquals(1, ConcurrencyPolicy.maxAllowed(2))
        assertEquals(1, ConcurrencyPolicy.maxAllowed(12))
        assertEquals(1, ConcurrencyPolicy.sanitize(9, 12))
        assertEquals(1, ConcurrencyPolicy.sanitize(0, 8))
        assertEquals(1, ConcurrencyPolicy.sanitize(3, 8))
    }
}
