package com.m1racle.reader.pdfsplit.split

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Runs the xref scanner and the AUTO gate against the fixture matrix in
 * `tools/make-compat-fixtures.py`.
 *
 * Scope note: this pins what the scanner *sees*. It cannot exercise
 * `probePageTree` because that needs PDFBox and an Android runtime; the
 * drawing-evidence rules are covered separately in [FastPathGuardTest], and the
 * end-to-end strict/lenient/AUTO verdicts for these files still have to be
 * taken on a device.
 */
class CompatFixturesTest {

    private fun fixture(name: String): File {
        val stream = checkNotNull(javaClass.classLoader?.getResourceAsStream("fixtures/$name")) {
            "missing test fixture $name"
        }
        val temp = File.createTempFile("fixture-", ".pdf")
        temp.deleteOnExit()
        stream.use { input -> temp.outputStream().use { input.copyTo(it) } }
        return temp
    }

    private fun report(name: String): XrefReport = PdfStructureScanner.scan(fixture(name))

    @Test
    fun `a healthy classic table is not vetoed`() {
        val report = report("01-healthy.pdf")

        assertEquals(XrefReport.Kind.TABLE, report.kind)
        assertTrue(report.startXrefValid)
        assertNull(FastPathGuard.xrefVeto(report))
    }

    @Test
    fun `a broken xref keyword is vetoed instead of read as an empty bad list`() {
        val report = report("06-xref-garbage.pdf")

        assertTrue(
            "unrecognised structure must not look healthy, got ${report.kind}",
            report.kind == XrefReport.Kind.UNKNOWN || report.kind == XrefReport.Kind.NONE,
        )
        assertNotNull(FastPathGuard.xrefVeto(report))
    }

    @Test
    fun `an incremental update still surfaces the defect in the previous generation`() {
        val report = report("07-incremental.pdf")

        assertTrue("scanner must walk /Prev", report.hasPrev)
        assertTrue(
            "object 5 is an in-use entry with offset 0 in the first generation, got " +
                report.badObjectNumbers,
            5 in report.badObjectNumbers,
        )
    }

    @Test
    fun `an object stream entry with offset 0 is still reported`() {
        val report = report("08-objstream.pdf")

        assertEquals(XrefReport.Kind.STREAM, report.kind)
        assertNull(FastPathGuard.xrefVeto(report))
        assertTrue("fixture must contain an object stream", report.objectStreamEntries >= 1 || report.inUseEntries >= 9)
        assertTrue("object 5 has offset 0, got ${report.badObjectNumbers}", 5 in report.badObjectNumbers)
    }

    @Test
    fun `an encrypted file is vetoed`() {
        val report = report("09-encrypted.pdf")

        assertTrue(report.encrypted)
        assertNotNull(FastPathGuard.xrefVeto(report))
    }
}
