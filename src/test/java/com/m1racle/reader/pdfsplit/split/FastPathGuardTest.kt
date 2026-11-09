package com.m1racle.reader.pdfsplit.split

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the fail-closed rules of the `AUTO` gate.
 *
 * Each case here corresponds to a way the first implementation could have
 * concluded "nobody paints this resource, losing it is harmless" without having
 * earned that conclusion.
 */
class FastPathGuardTest {

    // ── #xx escapes ─────────────────────────────────────────────────────────

    @Test
    fun `escaped names decode before being compared with resource keys`() {
        assertEquals("KSPX1", FastPathGuard.decodePdfName("KSPX#31"))
        assertEquals("A B", FastPathGuard.decodePdfName("A#20B"))
        assertEquals("plain", FastPathGuard.decodePdfName("plain"))
    }

    @Test
    fun `a painted name written with escapes is not mistaken for an unpainted one`() {
        // `/KSPX#31 Do` paints resource KSPX1. Before decoding, the extracted
        // name was the literal "KSPX#31", never matched the key, and the dangling
        // resource was waved through as harmless.
        val evidence = FastPathGuard.scanDrawings("/KSPX#31 Do\n", truncated = false)

        assertEquals(setOf("KSPX1"), evidence.names)
        assertTrue(evidence.complete)
        assertEquals(
            MissSeverity.FATAL,
            FastPathGuard.severityForUnresolved(isXObject = true, name = "KSPX1", evidence = evidence),
        )
    }

    // ── truncation ──────────────────────────────────────────────────────────

    @Test
    fun `a truncated scan can never prove a resource unpainted`() {
        // The `Do` was beyond the byte budget. Nothing was found, but the scan
        // did not see the end of the stream, so absence means nothing.
        val evidence = FastPathGuard.scanDrawings("/Im1 Do", truncated = true)

        assertTrue(!evidence.complete)
        assertEquals(
            MissSeverity.FATAL,
            FastPathGuard.severityForUnresolved(isXObject = true, name = "Im9", evidence = evidence),
        )
    }

    @Test
    fun `an empty stream that was read to the end is legitimately unpainted`() {
        val evidence = FastPathGuard.scanDrawings("", truncated = false)

        assertTrue(evidence.complete)
        assertEquals(
            MissSeverity.SOFT,
            FastPathGuard.severityForUnresolved(isXObject = true, name = "Im1", evidence = evidence),
        )
    }

    @Test
    fun `a Do operator past the one mebibyte budget is not silently dropped`() {
        // The prototype false negative: a page whose artwork is painted after
        // 1 MiB of vector preamble. The old budget stopped reading, found no
        // `Do` for /Im9, and reported it as unpainted.
        val padding = buildString {
            repeat(60_000) { append("q 1 0 0 1 0 0 cm Q\n") }   // ≈ 1.2 MiB of vector preamble
            append("BT /F1 12 Tf 0 0 Td (x) Tj ET\n")
        }
        val full = padding + "/Im9 Do\n"
        assertTrue("fixture must exceed the budget", full.length > (1 shl 20))

        // What the reader sees when it stops at the budget.
        val truncatedView = full.substring(0, 1 shl 20)
        val evidence = FastPathGuard.scanDrawings(truncatedView, truncated = true)

        assertTrue("no name was found in the truncated head", evidence.names.isEmpty())
        assertTrue("but the scan may not claim completeness", !evidence.complete)
        assertEquals(
            MissSeverity.FATAL,
            FastPathGuard.severityForUnresolved(isXObject = true, name = "Im9", evidence = evidence),
        )
    }

    // ── operator cross-check ────────────────────────────────────────────────

    @Test
    fun `an unpaired Do operator invalidates the whole scan`() {
        // A Do whose name we could not read: the resource it paints is unknown,
        // so no resource on this page may be declared unpainted.
        val evidence = FastPathGuard.scanDrawings("/Im1 Do\nq 100 0 0 100 0 0 cm Do Q", truncated = false)

        assertEquals(setOf("Im1"), evidence.names)
        assertTrue("unpaired Do must invalidate the scan", !evidence.complete)
        assertEquals(
            MissSeverity.FATAL,
            FastPathGuard.severityForUnresolved(isXObject = true, name = "Im2", evidence = evidence),
        )
    }

    @Test
    fun `a clean stream keeps the soft classification`() {
        val evidence = FastPathGuard.scanDrawings("/Im1 Do\n/Im2 Do\n", truncated = false)

        assertTrue(evidence.complete)
        assertEquals(setOf("Im1", "Im2"), evidence.names)
        assertEquals(
            MissSeverity.SOFT,
            FastPathGuard.severityForUnresolved(isXObject = true, name = "Im3", evidence = evidence),
        )
        assertEquals(
            MissSeverity.FATAL,
            FastPathGuard.severityForUnresolved(isXObject = true, name = "Im2", evidence = evidence),
        )
    }

    @Test
    fun `non XObject categories are always fatal`() {
        val evidence = FastPathGuard.scanDrawings("", truncated = false)

        assertEquals(
            MissSeverity.FATAL,
            FastPathGuard.severityForUnresolved(isXObject = false, name = "F1", evidence = evidence),
        )
    }

    // ── nested Form XObjects ────────────────────────────────────────────────

    @Test
    fun `a form's own drawings count for the form's own resources`() {
        val page = FastPathGuard.scanDrawings("/Fm1 Do", truncated = false)
        val form = FastPathGuard.scanDrawings("/Im7 Do", truncated = false)

        val merged = FastPathGuard.combine(page, form)
        assertEquals(setOf("Fm1", "Im7"), merged.names)
        assertEquals(
            MissSeverity.FATAL,
            FastPathGuard.severityForUnresolved(isXObject = true, name = "Im7", evidence = merged),
        )
    }

    @Test
    fun `incomplete nested evidence taints the merge`() {
        val page = FastPathGuard.scanDrawings("/Fm1 Do", truncated = false)
        val form = FastPathGuard.scanDrawings("/Im7 Do", truncated = true)

        val merged = FastPathGuard.combine(page, form)
        assertTrue(!merged.complete)
        assertEquals(
            MissSeverity.FATAL,
            FastPathGuard.severityForUnresolved(isXObject = true, name = "Im8", evidence = merged),
        )
    }

    // ── xref veto ───────────────────────────────────────────────────────────

    @Test
    fun `a healthy stream xref is not vetoed`() {
        assertNull(FastPathGuard.xrefVeto(report(kind = XrefReport.Kind.STREAM, inspected = 3163)))
    }

    @Test
    fun `a scan failure is a veto rather than an empty bad list`() {
        // null used to become `emptySet()` of bad objects, which made every
        // dangling reference look like "never declared" — the benign branch.
        assertNotNull(FastPathGuard.xrefVeto(null))
    }

    @Test
    fun `an unrecognised table is a veto`() {
        assertNotNull(FastPathGuard.xrefVeto(report(kind = XrefReport.Kind.UNKNOWN, inspected = 0)))
    }

    @Test
    fun `no xref keyword at all is a veto`() {
        assertNotNull(FastPathGuard.xrefVeto(report(kind = XrefReport.Kind.NONE, inspected = 0)))
    }

    @Test
    fun `a table with zero inspected entries is a veto`() {
        assertNotNull(FastPathGuard.xrefVeto(report(kind = XrefReport.Kind.TABLE, inspected = 0)))
    }

    @Test
    fun `an unusable startxref is a veto`() {
        assertNotNull(
            FastPathGuard.xrefVeto(report(kind = XrefReport.Kind.TABLE, inspected = 10, startValid = false)),
        )
    }

    @Test
    fun `an encrypted file is a veto`() {
        assertNotNull(
            FastPathGuard.xrefVeto(report(kind = XrefReport.Kind.STREAM, inspected = 10, encrypted = true)),
        )
    }

    private fun report(
        kind: XrefReport.Kind,
        inspected: Int,
        startValid: Boolean = true,
        encrypted: Boolean = false,
    ) = XrefReport(
        fileBytes = 1024,
        startXref = if (startValid) 512 else 99_999_999,
        startXrefValid = startValid,
        kind = kind,
        encrypted = encrypted,
        hasPrev = false,
        declaredEntries = inspected,
        inspectedEntries = inspected,
        inUseEntries = inspected,
        zeroOffsetEntries = 0,
        badHeaderEntries = 0,
        objectStreamEntries = 0,
        unverifiedEntries = 0,
        badObjectNumbers = emptyList(),
        reason = "test",
        scanMs = 1,
    )

    @Test
    fun `partial and unverified xref scans veto strict parsing`() {
        val complete = report(kind = XrefReport.Kind.TABLE, inspected = 10)
        assertNotNull(FastPathGuard.xrefVeto(complete.copy(declaredEntries = 11)))
        assertNotNull(FastPathGuard.xrefVeto(complete.copy(unverifiedEntries = 1)))
    }
}
