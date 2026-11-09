package com.m1racle.reader.pdfsplit.split

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.zip.Deflater

/**
 * The scanner is the only part of the load path that does not go through
 * PDFBox, so these tests build real PDF bytes by hand and check what it reads.
 * Nothing here asserts on PDFBox behaviour: that is A1's job on a device.
 */
class PdfStructureScannerTest {

    // ── Classic xref table ──────────────────────────────────────────────────

    @Test
    fun `healthy table reports every in-use entry and no defect`() {
        val report = scan(classicTable())

        assertEquals(XrefReport.Kind.TABLE, report.kind)
        assertTrue(report.startXrefValid)
        assertEquals(3, report.inUseEntries)
        assertEquals(0, report.zeroOffsetEntries)
        assertEquals(0, report.badHeaderEntries)
        assertFalse(report.risky)
        assertFalse(report.inconclusive)
        assertEquals(4, report.declaredEntries) // includes the free entry 0
    }

    @Test
    fun `in-use entry with offset zero is counted and makes the file risky`() {
        // Object 3 forced to offset 0 — exactly the 21-entry defect found in the
        // 988 MB textbook that cost PDFBox a 1.04 GB byte-by-byte rescan.
        val report = scan(classicTable(forcedOffset = mapOf(3 to 0L)))

        assertEquals(XrefReport.Kind.TABLE, report.kind)
        assertEquals(1, report.zeroOffsetEntries)
        assertEquals(listOf(3), report.badObjectNumbers)
        assertTrue(report.risky)
    }

    @Test
    fun `offset pointing at the wrong bytes is reported but does not alone condemn the file`() {
        // A readable-but-wrong offset is a real defect, yet the header check is a
        // heuristic; it is surfaced in diagnostics without pushing a file that
        // PDFBox parses fine onto the slow path.
        val report = scan(classicTable(forcedOffset = mapOf(2 to 2L)))

        assertEquals(1, report.badHeaderEntries)
        assertEquals(0, report.zeroOffsetEntries)
        assertFalse(report.risky)
    }

    @Test
    fun `startxref beyond the end of the file is not trusted`() {
        val report = scan(classicTable(startXrefOverride = 99_999_999L))

        assertEquals(XrefReport.Kind.UNKNOWN, report.kind)
        assertFalse(report.startXrefValid)
        assertTrue(report.risky)
        assertTrue(report.inconclusive)
    }

    @Test
    fun `startxref followed by trailing junk is still found`() {
        val report = scan(classicTable(trailingJunk = "\n".repeat(400) + "garbage after eof"))
        assertEquals(XrefReport.Kind.TABLE, report.kind)
        assertTrue(report.startXrefValid)
    }

    @Test
    fun `encrypted trailer is flagged`() {
        val report = scan(classicTable(trailerExtra = " /Encrypt 9 0 R"))
        assertTrue(report.encrypted)
    }

    // ── Xref stream ────────────────────────────────────────────────────────

    @Test
    fun `uncompressed-predictor xref stream is decoded`() {
        val report = scan(xrefStream(predictor = 1))

        assertEquals(report.summary(), XrefReport.Kind.STREAM, report.kind)
        assertTrue(report.summary(), report.startXrefValid)
        assertEquals(report.summary(), 3, report.inUseEntries)
        assertEquals(0, report.zeroOffsetEntries)
        assertFalse(report.risky)
    }

    @Test
    fun `png-up xref stream is decoded`() {
        val report = scan(xrefStream(predictor = 12))

        assertEquals(report.summary(), XrefReport.Kind.STREAM, report.kind)
        assertEquals(report.summary(), 3, report.inUseEntries)
        assertEquals(report.summary(), 0, report.badHeaderEntries)
        assertFalse(report.risky)
    }

    @Test
    fun `zero offset inside an xref stream is counted`() {
        val report = scan(xrefStream(predictor = 12, zeroObject = 2))

        assertEquals(XrefReport.Kind.STREAM, report.kind)
        assertEquals(1, report.zeroOffsetEntries)
        assertTrue(report.risky)
    }

    @Test
    fun `object stream entries are counted separately from file offsets`() {
        val report = scan(xrefStream(predictor = 1, objectStreamEntry = true))

        assertEquals(XrefReport.Kind.STREAM, report.kind)
        // type 2 = compressed object: the second field is an object-stream
        // number, not a byte offset, so it must not be verified against the file.
        assertEquals(1, report.objectStreamEntries)
        assertEquals(0, report.badHeaderEntries)
        assertEquals(0, report.zeroOffsetEntries)
    }

    // ── Degenerate input ───────────────────────────────────────────────────

    @Test
    fun `non-pdf bytes yield no opinion, never a pass`() {
        val report = scan("this is not a pdf at all".toByteArray())

        assertEquals(XrefReport.Kind.UNKNOWN, report.kind)
        assertTrue(report.inconclusive)
        assertTrue(report.risky)
    }

    @Test
    fun `empty file yields no opinion`() {
        val report = scan(ByteArray(0))
        assertEquals(XrefReport.Kind.UNKNOWN, report.kind)
        assertTrue(report.inconclusive)
    }

    // ── Fixtures ───────────────────────────────────────────────────────────

    private fun scan(bytes: ByteArray): XrefReport {
        val file = File.createTempFile("xref-test", ".pdf")
        return try {
            file.writeBytes(bytes)
            PdfStructureScanner.scan(file)
        } finally {
            file.delete()
        }
    }

    private fun classicTable(
        forcedOffset: Map<Int, Long> = emptyMap(),
        startXrefOverride: Long? = null,
        trailingJunk: String = "",
        trailerExtra: String = "",
    ): ByteArray {
        val text = StringBuilder("%PDF-1.7\n")
        val offsets = IntArray(3)
        val bodies = listOf(
            "<< /Type /Catalog /Pages 2 0 R >>",
            "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 200] >>",
        )
        bodies.forEachIndexed { index, body ->
            offsets[index] = text.length
            text.append("${index + 1} 0 obj\n").append(body).append("\nendobj\n")
        }

        val xrefOffset = text.length
        text.append("xref\n0 4\n")
        text.append("0000000000 65535 f \n")
        for (index in 0..2) {
            val objectNumber = index + 1
            val offset = forcedOffset[objectNumber] ?: offsets[index].toLong()
            text.append(String.format("%010d 00000 n \n", offset))
        }
        text.append("trailer\n<< /Size 4 /Root 1 0 R").append(trailerExtra).append(" >>\n")
        text.append("startxref\n").append(startXrefOverride ?: xrefOffset).append('\n')
        text.append("%%EOF\n").append(trailingJunk)
        return text.toString().toByteArray(StandardCharsets.ISO_8859_1)
    }

    /**
     * Objects 1..3 are plain; the xref stream is object 4, so its own offset is
     * known before the stream is written and no fixed point is needed.
     */
    private fun xrefStream(
        predictor: Int,
        zeroObject: Int? = null,
        objectStreamEntry: Boolean = false,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        fun write(text: String) = out.write(text.toByteArray(StandardCharsets.ISO_8859_1))

        write("%PDF-1.7\n")
        val offsets = IntArray(3)
        val bodies = listOf(
            "<< /Type /Catalog /Pages 2 0 R >>",
            "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 200] >>",
        )
        bodies.forEachIndexed { index, body ->
            offsets[index] = out.size()
            write("${index + 1} 0 obj\n$body\nendobj\n")
        }

        val xrefStreamOffset = out.size()
        val rowWidth = 7 // /W [1 4 2]
        val rows = ArrayList<ByteArray>()
        rows += row(type = 0, second = 0, third = 65535)
        for (index in 0..2) {
            val objectNumber = index + 1
            if (objectStreamEntry && objectNumber == 3) {
                rows += row(type = 2, second = 7, third = 0)
                continue
            }
            val offset = if (zeroObject == objectNumber) 0L else offsets[index].toLong()
            rows += row(type = 1, second = offset.toInt(), third = 0, width = rowWidth)
        }
        val plain = ByteArray(rows.size * rowWidth)
        rows.forEachIndexed { index, bytes -> bytes.copyInto(plain, index * rowWidth) }
        val encoded = if (predictor >= 10) encodePngUp(plain, rowWidth) else plain
        val compressed = deflate(encoded)

        write(
            "4 0 obj\n<< /Type /XRef /Size 5 /W [1 4 2] /Index [0 4] " +
                "/Filter /FlateDecode",
        )
        if (predictor >= 10) write(" /DecodeParms << /Predictor 12 /Columns $rowWidth >>")
        write(" /Length ${compressed.size} >>\nstream\n")
        out.write(compressed)
        write("\nendstream\nendobj\n")
        write("startxref\n$xrefStreamOffset\n%%EOF\n")
        return out.toByteArray()
    }

    private fun row(type: Int, second: Int, third: Int, width: Int = 7): ByteArray =
        byteArrayOf(
            type.toByte(),
            ((second shr 24) and 0xFF).toByte(),
            ((second shr 16) and 0xFF).toByte(),
            ((second shr 8) and 0xFF).toByte(),
            (second and 0xFF).toByte(),
            ((third shr 8) and 0xFF).toByte(),
            (third and 0xFF).toByte(),
        )

    /** PNG "Up" filter: every row is prefixed with 0x02 and delta-coded per column. */
    private fun encodePngUp(data: ByteArray, rowWidth: Int): ByteArray {
        val rowCount = data.size / rowWidth
        val out = ByteArray(rowCount * (rowWidth + 1))
        for (rowIndex in 0 until rowCount) {
            val target = rowIndex * (rowWidth + 1)
            out[target] = 2
            for (column in 0 until rowWidth) {
                val current = data[rowIndex * rowWidth + column].toInt() and 0xFF
                val above = if (rowIndex == 0) {
                    0
                } else {
                    data[(rowIndex - 1) * rowWidth + column].toInt() and 0xFF
                }
                out[target + 1 + column] = ((current - above) and 0xFF).toByte()
            }
        }
        return out
    }

    private fun deflate(data: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.BEST_COMPRESSION)
        deflater.setInput(data)
        deflater.finish()
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (!deflater.finished()) {
            val produced = deflater.deflate(buffer)
            out.write(buffer, 0, produced)
        }
        deflater.end()
        return out.toByteArray()
    }
}
