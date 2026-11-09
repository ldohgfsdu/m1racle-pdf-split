package com.m1racle.reader.pdfsplit.split

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.util.zip.Inflater

/**
 * What the cross-reference table really looks like, measured without PDFBox.
 *
 * This exists for two reasons:
 *  - PDFBox's lenient parser reports "at least one entry couldn't be
 *    dereferenced" and then silently rescans 1 GB byte by byte. That message
 *    names one object and tells you nothing about how many entries are broken,
 *    so it cannot be used to decide anything. This scanner counts them.
 *  - It is an independent implementation (own trailer walk, own Flate/PNG
 *    predictor decoder, own `N G obj` header check). A conclusion that agrees
 *    with PDFBox while sharing no code with it is worth something; one that
 *    only repeats PDFBox's own log line is not.
 *
 * Everything here fails soft: an unparsable table yields [XrefReport.Kind.UNKNOWN]
 * and the caller must treat that as "no opinion", never as "healthy".
 */
internal object PdfStructureScanner {

    /** `%%EOF` may be followed by junk; real files carry ~1 KB, some carry far more. */
    private const val TAIL_BYTES = 512 * 1024

    /** A classic xref table is ~20 B per entry; 4 MB covers 200k entries. */
    private const val TABLE_WINDOW = 4 * 1024 * 1024

    /** Upper bound on random seeks used to verify object headers. */
    private const val MAX_VERIFIED_ENTRIES = 20_000

    /** Enough bytes to see `123 0 obj` after a stray newline or two. */
    private const val HEADER_PROBE_BYTES = 48

    private val ENTRY_PATTERN = Regex("^(\\d{1,10})\\s+(\\d{1,5})\\s+([nf])")
    private val SUBSECTION_PATTERN = Regex("^(\\d{1,7})\\s+(\\d{1,7})$")
    private val OBJECT_HEADER_PATTERN = Regex("^(\\d{1,10})\\s+(\\d{1,5})\\s+obj\\b")
    private val LENGTH_PATTERN = Regex("/Length\\s+(\\d{1,9})(?!\\s+\\d+\\s+R)")
    private val W_PATTERN = Regex("/W\\s*\\[\\s*(\\d+)\\s+(\\d+)\\s+(\\d+)\\s*]")
    private val INDEX_PATTERN = Regex("/Index\\s*\\[([^\\]]*)]")
    private val PREDICTOR_PATTERN = Regex("/Predictor\\s+(\\d+)")
    private val COLUMNS_PATTERN = Regex("/Columns\\s+(\\d+)")

    fun scan(file: File): XrefReport {
        val started = System.nanoTime()
        return try {
            RandomAccessFile(file, "r").use { raf -> scan(raf, started) }
        } catch (failure: Throwable) {
            XrefReport.unreadable(
                fileBytes = runCatching { file.length() }.getOrDefault(0L),
                reason = failure.javaClass.simpleName + ": " + (failure.message ?: ""),
                scanMs = millisSince(started),
            )
        }
    }

    private fun scan(raf: RandomAccessFile, started: Long): XrefReport {
        val size = raf.length()
        if (size <= 0L) return XrefReport.unreadable(0L, "空文件", millisSince(started))

        val tailLength = minOf(size, TAIL_BYTES.toLong()).toInt()
        val tail = readAt(raf, size - tailLength, tailLength)
            ?: return XrefReport.unreadable(size, "无法读取文件尾部", millisSince(started))

        val startXref = findLastStartXref(tail)
            ?: return XrefReport.unreadable(
                size,
                "尾部 ${tailLength / 1024} KB 内找不到 startxref",
                millisSince(started),
            )

        if (startXref <= 0L || startXref >= size) {
            return XrefReport(
                fileBytes = size,
                startXref = startXref,
                startXrefValid = false,
                kind = XrefReport.Kind.UNKNOWN,
                encrypted = false,
                hasPrev = false,
                declaredEntries = 0,
                inspectedEntries = 0,
                inUseEntries = 0,
                zeroOffsetEntries = 0,
                badHeaderEntries = 0,
                objectStreamEntries = 0,
                unverifiedEntries = 0,
                badObjectNumbers = emptyList(),
                reason = "startxref=$startXref 落在文件范围之外（0..$size）",
                scanMs = millisSince(started),
            )
        }

        val head = readAt(raf, startXref, 2048)
            ?: return XrefReport.unreadable(
                size,
                "startxref 指向的位置读不出来",
                millisSince(started),
            )
        val headText = String(head, StandardCharsets.ISO_8859_1)
        val trimmed = headText.trimStart()

        return when {
            trimmed.startsWith("xref") -> parseClassicTable(raf, startXref, size, started)
            OBJECT_HEADER_PATTERN.containsMatchIn(trimmed.take(64)) ->
                parseXrefStream(raf, startXref, size, headText, started)
            else -> XrefReport(
                fileBytes = size,
                startXref = startXref,
                startXrefValid = false,
                kind = XrefReport.Kind.UNKNOWN,
                encrypted = false,
                hasPrev = false,
                declaredEntries = 0,
                inspectedEntries = 0,
                inUseEntries = 0,
                zeroOffsetEntries = 0,
                badHeaderEntries = 0,
                objectStreamEntries = 0,
                unverifiedEntries = 0,
                badObjectNumbers = emptyList(),
                reason = "startxref 处既不是 xref 表也不是 xref 流对象",
                scanMs = millisSince(started),
            )
        }
    }

    // ── Classic table ───────────────────────────────────────────────────────

    private fun parseClassicTable(
        raf: RandomAccessFile,
        offset: Long,
        size: Long,
        started: Long,
    ): XrefReport {
        val windowLength = minOf(size - offset, TABLE_WINDOW.toLong()).toInt()
        val bytes = readAt(raf, offset, windowLength)
            ?: return XrefReport.unreadable(size, "xref 表读取失败", millisSince(started))
        val text = String(bytes, StandardCharsets.ISO_8859_1)

        val accumulator = Accumulator()
        val lines = text.split("\r\n", "\n", "\r")
        var index = 1 // skip the "xref" keyword line
        var declared = 0

        while (index < lines.size) {
            val line = lines[index].trim()
            if (line.isEmpty()) {
                index++
                continue
            }
            if (line.startsWith("trailer")) break

            val subsection = SUBSECTION_PATTERN.matchEntire(line)
            if (subsection != null) {
                val first = subsection.groupValues[1].toIntOrNull() ?: 0
                val count = subsection.groupValues[2].toIntOrNull() ?: 0
                declared += count
                index++
                for (step in 0 until count) {
                    if (index + step >= lines.size) break
                    accumulator.consume(lines[index + step], first + step, raf)
                }
                index += count
                continue
            }

            // Malformed files interleave entries without a clean subsection
            // header. Consume what looks like an entry rather than aborting.
            val entry = ENTRY_PATTERN.find(line)
            if (entry != null) {
                accumulator.consume(line, -1, raf)
                index++
                continue
            }
            // Anything else means we have lost the grammar; stop and report
            // what was measured so far instead of inventing numbers.
            break
        }

        val trailerText = text.substringAfter("trailer", "")
        val encrypted = trailerText.contains("/Encrypt")
        val hasPrev = Regex("/Prev\\s+\\d+").containsMatchIn(trailerText)

        return accumulator.build(
            size = size,
            startXref = offset,
            kind = XrefReport.Kind.TABLE,
            startXrefValid = true,
            encrypted = encrypted,
            hasPrev = hasPrev,
            declared = declared,
            reason = if (accumulator.inspected == 0) "xref 表存在但未解析出任何条目" else "",
            scanMs = millisSince(started),
        )
    }

    // ── Xref stream ─────────────────────────────────────────────────────────

    private fun parseXrefStream(
        raf: RandomAccessFile,
        offset: Long,
        size: Long,
        headText: String,
        started: Long,
    ): XrefReport {
        val streamKeyword = headText.indexOf("stream")
        if (streamKeyword < 0) {
            return XrefReport.unreadable(size, "xref 流缺少 stream 关键字", millisSince(started))
        }
        var dataStart = streamKeyword + "stream".length
        // `stream` must be followed by CRLF or LF; tolerate CR alone.
        if (dataStart < headText.length && headText[dataStart] == '\r') dataStart++
        if (dataStart < headText.length && headText[dataStart] == '\n') dataStart++

        val declaredLength = LENGTH_PATTERN.find(headText.substring(0, streamKeyword))
            ?.groupValues?.get(1)?.toIntOrNull()
        val length = when {
            declaredLength != null && declaredLength > 0 -> declaredLength
            else -> {
                // Indirect /Length: locate `endstream` backwards from the next
                // 64 KB and treat the gap as the payload. Good enough to decode.
                val probe = readAt(raf, offset, minOf(size - offset, 64L * 1024).toInt())
                    ?: return XrefReport.unreadable(
                        size,
                        "xref 流读取失败",
                        millisSince(started),
                    )
                val end = String(probe, StandardCharsets.ISO_8859_1).lastIndexOf("endstream")
                if (end <= dataStart) {
                    return XrefReport.unreadable(
                        size,
                        "xref 流长度未知且找不到 endstream",
                        millisSince(started),
                    )
                }
                end - dataStart
            }
        }

        val compressed = readAt(raf, offset + dataStart, minOf(length, 8 * 1024 * 1024))
            ?: return XrefReport.unreadable(size, "xref 流数据读取失败", millisSince(started))

        val dict = headText.substring(0, streamKeyword)
        val filters = Regex("/Filter\\s*\\[?\\s*/(\\w+)").find(dict)?.groupValues?.get(1).orEmpty()
        val decoded = when (filters) {
            "", "FlateDecode", "Fl" -> inflate(compressed) ?: return XrefReport.unreadable(
                size,
                "xref 流 Flate 解压失败",
                millisSince(started),
            )
            else -> return XrefReport(
                fileBytes = size,
                startXref = offset,
                startXrefValid = true,
                kind = XrefReport.Kind.UNKNOWN,
                encrypted = dict.contains("/Encrypt"),
                hasPrev = Regex("/Prev\\s+\\d+").containsMatchIn(dict),
                declaredEntries = 0,
                inspectedEntries = 0,
                inUseEntries = 0,
                zeroOffsetEntries = 0,
                badHeaderEntries = 0,
                objectStreamEntries = 0,
                unverifiedEntries = 0,
                badObjectNumbers = emptyList(),
                reason = "xref 流使用未支持的过滤器 /$filters",
                scanMs = millisSince(started),
            )
        }

        val widths = W_PATTERN.find(dict)?.groupValues?.drop(1)?.mapNotNull { it.toIntOrNull() }
            ?: return XrefReport.unreadable(size, "xref 流缺少 /W", millisSince(started))
        if (widths.size < 3 || widths.sum() <= 0) {
            return XrefReport.unreadable(size, "xref 流 /W 非法", millisSince(started))
        }
        val columns = widths.sum()
        val predictor = PREDICTOR_PATTERN.find(dict)?.groupValues?.get(1)?.toIntOrNull() ?: 1
        val declaredColumns = COLUMNS_PATTERN.find(dict)?.groupValues?.get(1)?.toIntOrNull() ?: columns
        val rows = unpredict(decoded, predictor, maxOf(declaredColumns, columns))
            ?: return XrefReport.unreadable(size, "xref 流 PNG 预测还原失败", millisSince(started))

        val indexRanges = parseIndex(dict)
        val accumulator = Accumulator()
        var cursor = 0
        var declared = 0
        for ((first, count) in indexRanges) {
            declared += count
            for (step in 0 until count) {
                if (cursor + columns > rows.size) break
                val type = field(rows, cursor, widths[0]).let { if (widths[0] == 0) 1L else it }
                val second = field(rows, cursor + widths[0], widths[1])
                cursor += columns
                when (type) {
                    1L -> accumulator.consumeValue(second, first + step, raf)
                    2L -> {
                        // Compressed object: the "offset" is an object-stream
                        // number, not a file offset. Nothing to verify here.
                        accumulator.inspected++
                        accumulator.inUse++
                        accumulator.objectStream++
                    }
                    else -> accumulator.inspected++ // type 0 = free
                }
            }
        }

        return accumulator.build(
            size = size,
            startXref = offset,
            kind = XrefReport.Kind.STREAM,
            startXrefValid = true,
            encrypted = dict.contains("/Encrypt"),
            hasPrev = Regex("/Prev\\s+\\d+").containsMatchIn(dict),
            declared = declared,
            reason = if (accumulator.inspected == 0) "xref 流解析出 0 个条目" else "",
            scanMs = millisSince(started),
        )
    }

    private fun parseIndex(dict: String): List<Pair<Int, Int>> {
        val raw = INDEX_PATTERN.find(dict)?.groupValues?.get(1) ?: return emptyList()
        val numbers = raw.split(Regex("[\\s,]+")).mapNotNull { it.trim().toIntOrNull() }
        if (numbers.size < 2) return emptyList()
        return numbers.chunked(2).mapNotNull { pair ->
            if (pair.size == 2) pair[0] to pair[1] else null
        }
    }

    /** Big-endian unsigned read of [width] bytes (0..8). */
    private fun field(data: ByteArray, offset: Int, width: Int): Long {
        var value = 0L
        for (index in 0 until width) {
            val position = offset + index
            if (position >= data.size) return value
            value = (value shl 8) or (data[position].toLong() and 0xFF)
        }
        return value
    }

    private fun inflate(data: ByteArray): ByteArray? = try {
        val inflater = Inflater()
        inflater.setInput(data)
        val sink = ByteArrayOutputStream(maxOf(data.size * 4, 4096))
        val buffer = ByteArray(16 * 1024)
        var guard = 0
        while (!inflater.finished() && guard < 100_000) {
            val produced = inflater.inflate(buffer)
            if (produced == 0) {
                if (inflater.needsInput() || inflater.needsDictionary()) break
            } else {
                sink.write(buffer, 0, produced)
            }
            guard++
        }
        inflater.end()
        sink.toByteArray().takeIf { it.isNotEmpty() }
    } catch (failure: Throwable) {
        null
    }

    /**
     * Undo the PNG predictor used by xref streams. Predictors below 10 are a
     * no-op; 10..15 prefix every row with a filter byte.
     */
    private fun unpredict(data: ByteArray, predictor: Int, columns: Int): ByteArray? {
        if (predictor < 10) return data
        if (columns <= 0) return null
        val rowLength = columns + 1
        val rowCount = data.size / rowLength
        if (rowCount <= 0) return null
        val out = ByteArray(rowCount * columns)
        val previous = IntArray(columns)

        for (row in 0 until rowCount) {
            val sourceStart = row * rowLength
            val filter = data[sourceStart].toInt() and 0xFF
            val targetStart = row * columns
            for (column in 0 until columns) {
                val raw = data[sourceStart + 1 + column].toInt() and 0xFF
                val left = if (column > 0) out[targetStart + column - 1].toInt() and 0xFF else 0
                val up = previous[column]
                val upLeft = if (column > 0) previous[column - 1] else 0
                val value = when (filter) {
                    0 -> raw
                    1 -> raw + left
                    2 -> raw + up
                    3 -> raw + (left + up) / 2
                    4 -> raw + paeth(left, up, upLeft)
                    else -> return null
                }
                out[targetStart + column] = value.toByte()
            }
            for (column in 0 until columns) {
                previous[column] = out[targetStart + column].toInt() and 0xFF
            }
        }
        return out
    }

    private fun paeth(left: Int, up: Int, upLeft: Int): Int {
        val estimate = left + up - upLeft
        val dLeft = kotlin.math.abs(estimate - left)
        val dUp = kotlin.math.abs(estimate - up)
        val dUpLeft = kotlin.math.abs(estimate - upLeft)
        return when {
            dLeft <= dUp && dLeft <= dUpLeft -> left
            dUp <= dUpLeft -> up
            else -> upLeft
        }
    }

    // ── Shared plumbing ─────────────────────────────────────────────────────

    private class Accumulator {
        var inspected = 0
        var inUse = 0
        var zeroOffset = 0
        var badHeader = 0
        var objectStream = 0
        var unverified = 0
        val badObjects = ArrayList<Int>(32)

        fun consume(line: String, objectNumber: Int, raf: RandomAccessFile) {
            val match = ENTRY_PATTERN.find(line.trim()) ?: return
            val type = match.groupValues[3]
            if (type == "f") {
                inspected++
                return
            }
            val offset = match.groupValues[1].toLongOrNull() ?: 0L
            consumeValue(offset, objectNumber, raf)
        }

        fun consumeValue(offset: Long, objectNumber: Int, raf: RandomAccessFile) {
            inspected++
            inUse++
            if (offset <= 0L) {
                zeroOffset++
                recordSample(objectNumber)
                return
            }
            if (verified >= MAX_VERIFIED_ENTRIES) {
                unverified++
                return
            }
            verified++
            if (!hasObjectHeader(raf, offset)) {
                badHeader++
                recordSample(objectNumber)
            }
        }

        private var verified = 0

        private fun recordSample(objectNumber: Int) {
            if (objectNumber >= 0) badObjects.add(objectNumber)
        }

        fun build(
            size: Long,
            startXref: Long,
            kind: XrefReport.Kind,
            startXrefValid: Boolean,
            encrypted: Boolean,
            hasPrev: Boolean,
            declared: Int,
            reason: String,
            scanMs: Long,
        ) = XrefReport(
            fileBytes = size,
            startXref = startXref,
            startXrefValid = startXrefValid,
            kind = kind,
            encrypted = encrypted,
            hasPrev = hasPrev,
            declaredEntries = declared,
            inspectedEntries = inspected,
            inUseEntries = inUse,
            zeroOffsetEntries = zeroOffset,
            badHeaderEntries = badHeader,
            objectStreamEntries = objectStream,
            unverifiedEntries = unverified,
            badObjectNumbers = badObjects.toList(),
            reason = reason,
            scanMs = scanMs,
        )
    }

    private fun hasObjectHeader(raf: RandomAccessFile, offset: Long): Boolean {
        val probe = readAt(raf, offset, HEADER_PROBE_BYTES) ?: return false
        val text = String(probe, StandardCharsets.ISO_8859_1).trimStart()
        return OBJECT_HEADER_PATTERN.containsMatchIn(text)
    }

    private fun findLastStartXref(tail: ByteArray): Long? {
        val marker = "startxref".toByteArray(StandardCharsets.US_ASCII)
        var index = tail.size - marker.size
        while (index >= 0) {
            var matched = true
            for (step in marker.indices) {
                if (tail[index + step] != marker[step]) {
                    matched = false
                    break
                }
            }
            if (matched) {
                var cursor = index + marker.size
                while (cursor < tail.size && !isDigit(tail[cursor])) cursor++
                if (cursor >= tail.size) {
                    index--
                    continue
                }
                var value = 0L
                while (cursor < tail.size && isDigit(tail[cursor])) {
                    value = value * 10 + (tail[cursor] - '0'.code)
                    cursor++
                }
                return value
            }
            index--
        }
        return null
    }

    /** Monotonic elapsed milliseconds. `System.nanoTime` keeps this testable off-device. */
    private fun millisSince(startedNanos: Long): Long = (System.nanoTime() - startedNanos) / 1_000_000L

    private fun isDigit(byte: Byte): Boolean = byte >= '0'.code.toByte() && byte <= '9'.code.toByte()

    private fun readAt(raf: RandomAccessFile, offset: Long, length: Int): ByteArray? {
        if (offset < 0L || length <= 0) return null
        return try {
            raf.seek(offset)
            val buffer = ByteArray(length)
            var read = 0
            while (read < length) {
                val produced = raf.read(buffer, read, length - read)
                if (produced <= 0) break
                read += produced
            }
            if (read == length) buffer else buffer.copyOf(read)
        } catch (failure: Throwable) {
            null
        }
    }
}

/**
 * Result of [PdfStructureScanner.scan]. Every field is measured, none inferred.
 */
internal data class XrefReport(
    val fileBytes: Long,
    val startXref: Long,
    val startXrefValid: Boolean,
    val kind: Kind,
    val encrypted: Boolean,
    val hasPrev: Boolean,
    val declaredEntries: Int,
    val inspectedEntries: Int,
    val inUseEntries: Int,
    val zeroOffsetEntries: Int,
    val badHeaderEntries: Int,
    val objectStreamEntries: Int,
    val unverifiedEntries: Int,
    /**
     * Every in-use entry whose offset is unusable. Lenient parsing repairs these
     * by rescanning; strict parsing does not, so a page that *does* reference one
     * of them is the one case where the fast path can silently lose artwork.
     */
    val badObjectNumbers: List<Int>,
    val reason: String,
    val scanMs: Long,
) {
    enum class Kind { NONE, TABLE, STREAM, UNKNOWN }

    /**
     * True when the table itself is provably defective, so a strict parser is
     * *known* to reject it. Only counts defects that were read out of the file:
     * `badHeaderEntries` is reported but does not make the file risky, because a
     * legal-but-unusual object header must not push a healthy file onto the slow
     * path.
     */
    val risky: Boolean
        get() = !startXrefValid || zeroOffsetEntries > 0

    /** The scanner had no opinion; callers must not read this as "healthy". */
    val inconclusive: Boolean
        get() = kind == Kind.UNKNOWN || inspectedEntries == 0

    /** One line for logcat and the UI, in the same shape as other diagnostics. */
    fun summary(): String = buildString {
        append("xref kind=").append(kind)
        append(" startxref=").append(startXref)
        append(" valid=").append(startXrefValid)
        append(" entries=").append(inspectedEntries).append('/').append(declaredEntries)
        append(" inUse=").append(inUseEntries)
        append(" zeroOffset=").append(zeroOffsetEntries)
        append(" badHeader=").append(badHeaderEntries)
        append(" objStream=").append(objectStreamEntries)
        append(" unverified=").append(unverifiedEntries)
        append(" encrypted=").append(encrypted)
        append(" prev=").append(hasPrev)
        append(" scanMs=").append(scanMs)
        if (reason.isNotEmpty()) append(" note=").append(reason)
    }

    companion object {
        fun unreadable(fileBytes: Long, reason: String, scanMs: Long) = XrefReport(
            fileBytes = fileBytes,
            startXref = -1L,
            startXrefValid = false,
            kind = Kind.UNKNOWN,
            encrypted = false,
            hasPrev = false,
            declaredEntries = 0,
            inspectedEntries = 0,
            inUseEntries = 0,
            zeroOffsetEntries = 0,
            badHeaderEntries = 0,
            objectStreamEntries = 0,
            unverifiedEntries = 0,
            badObjectNumbers = emptyList(),
            reason = reason,
            scanMs = scanMs,
        )
    }
}
