package com.m1racle.reader.pdfsplit.split

/**
 * One output document: an inclusive 1-based page range of the source PDF.
 *
 * [sourceLine] is the 1-based line in whatever text produced this part, so an
 * error or overlap can be pointed back at the exact line the user typed.
 */
data class SplitPart(
    val index: Int,
    val startPage1: Int,
    val endPage1: Int,
    val outputName: String,
    val sourceLine: Int = 0,
) {
    val start0: Int get() = startPage1 - 1
    val end0: Int get() = endPage1 - 1
    val pageCount: Int get() = endPage1 - startPage1 + 1
}

/** A line the parser could not accept. Blocking: the run must not start. */
data class LineError(val line: Int, val message: String)

/**
 * Pages claimed by more than one range. Warning only: splitting the same page
 * into two files can be intentional, but it must never be silent.
 */
data class PageOverlap(
    val startPage1: Int,
    val endPage1: Int,
    val lines: List<Int>,
) {
    val pageCount: Int get() = endPage1 - startPage1 + 1
}

data class ParseOutcome(
    val parts: List<SplitPart>,
    val pairedNames: Int,
    val unusedNames: Int,
    val warnings: List<String>,
    val errors: List<LineError> = emptyList(),
    val overlaps: List<PageOverlap> = emptyList(),
    val uncoveredPages: List<IntRange> = emptyList(),
    val renamedDuplicates: Int = 0,
) {
    val hasBlockingError: Boolean get() = errors.isNotEmpty()
    val uncoveredPageCount: Int get() = uncoveredPages.sumOf { it.count() }
}

/**
 * Parses page ranges and optional names from a pasted block.
 *
 * Two layouts are supported, and they can be mixed in one paste:
 *
 *  1. One entry per line, range only (names typed later in the app):
 *       71-86
 *       87-91
 *
 *  2. Two columns pasted as two blocks — a page-range column, then a name
 *     column. Ranges and names are paired by order:
 *       71-86
 *       87-91
 *       26贺银成…CH01_心肺听诊
 *       26贺银成…CH02_普通X线影像诊断
 *
 *  A single line may also carry both: `71-86 心肺听诊` or `心肺听诊 71-86`.
 *
 * Invalid lines are reported individually instead of aborting the whole block,
 * so the user sees which line is wrong while the rest of the plan stays usable.
 * Overlaps and uncovered pages are reported but never block.
 */
object PageRangeParser {

    private const val SEPARATOR = "[-~－—–]"

    private val RANGE_ONLY = Regex("^\\s*(\\d+)\\s*$SEPARATOR\\s*(\\d+)\\s*\$")
    private val SINGLE_ONLY = Regex("^\\s*(\\d+)\\s*\$")
    private val RANGE_FIRST = Regex("^\\s*(\\d+)\\s*$SEPARATOR\\s*(\\d+)\\s+(\\S.*)\$")
    private val RANGE_LAST = Regex("^(.+?)\\s+(\\d+)\\s*$SEPARATOR\\s*(\\d+)\\s*\$")

    private const val MAX_LISTED_OVERLAPS = 3
    private const val MAX_LISTED_GAPS = 3

    fun parseBlock(
        text: String,
        pageCount: Int,
        sourceDisplayName: String,
    ): ParseOutcome {
        require(pageCount > 0) { "源 PDF 没有可拆分的页" }
        val rawLines = text.lines()
        val entries = ArrayList<Entry>(rawLines.size)
        val namePool = ArrayList<String>()
        val errors = ArrayList<LineError>()

        rawLines.forEachIndexed { position, raw ->
            val lineNo = position + 1
            val line = raw.trim()
            if (line.isEmpty()) return@forEachIndexed

            val rangeFirst = RANGE_FIRST.matchEntire(line)
            val rangeLast = RANGE_LAST.matchEntire(line)
            val rangeOnly = RANGE_ONLY.matchEntire(line)
            val singleOnly = SINGLE_ONLY.matchEntire(line)
            when {
                rangeOnly != null -> entries += Entry(
                    line = lineNo,
                    start = rangeOnly.groupValues[1].toIntOrNull() ?: 0,
                    end = rangeOnly.groupValues[2].toIntOrNull() ?: 0,
                    name = null,
                )
                singleOnly != null -> {
                    val page = singleOnly.groupValues[1].toIntOrNull() ?: 0
                    entries += Entry(line = lineNo, start = page, end = page, name = null)
                }
                rangeFirst != null -> entries += Entry(
                    line = lineNo,
                    start = rangeFirst.groupValues[1].toIntOrNull() ?: 0,
                    end = rangeFirst.groupValues[2].toIntOrNull() ?: 0,
                    name = rangeFirst.groupValues[3].trim().ifBlank { null },
                )
                rangeLast != null -> entries += Entry(
                    line = lineNo,
                    start = rangeLast.groupValues[2].toIntOrNull() ?: 0,
                    end = rangeLast.groupValues[3].toIntOrNull() ?: 0,
                    name = rangeLast.groupValues[1].trim().ifBlank { null },
                )
                else -> namePool += line
            }
        }

        require(entries.isNotEmpty() || errors.isNotEmpty()) {
            if (namePool.isEmpty()) "请先粘贴页码范围，例如 71-86" else "只看到名称，没有页码"
        }
        if (entries.isEmpty()) {
            return ParseOutcome(emptyList(), 0, 0, emptyList(), errors)
        }

        val base = sanitizeBase(sourceDisplayName)
        val used = mutableSetOf<String>()
        val validEntries = ArrayList<Entry>(entries.size)
        entries.forEach { entry ->
            when {
                entry.start < 1 -> errors += LineError(
                    entry.line,
                    "页码从 1 开始，这一行是 ${entry.start}",
                )
                entry.end < entry.start -> errors += LineError(
                    entry.line,
                    "起止颠倒：${entry.start}-${entry.end}",
                )
                entry.end > pageCount -> errors += LineError(
                    entry.line,
                    "${entry.start}-${entry.end} 超出总页数 $pageCount",
                )
                else -> validEntries += entry
            }
        }

        // Pairing is only safe when both columns have the same length; otherwise
        // the program cannot know which entries are missing.
        val unnamedCount = validEntries.count { it.name == null }
        val canPairWholeColumn = namePool.isNotEmpty() && namePool.size == unnamedCount
        var cursor = 0
        var paired = 0
        var renamed = 0

        val parts = validEntries.mapIndexed { position, entry ->
            val resolved = entry.name ?: if (canPairWholeColumn) {
                namePool.getOrNull(cursor)?.let {
                    cursor += 1
                    paired += 1
                    it
                }
            } else {
                null
            }
            val planned = planName(base, entry.start, entry.end, resolved, used)
            if (planned.renamed) renamed += 1
            SplitPart(
                index = position + 1,
                startPage1 = entry.start,
                endPage1 = entry.end,
                outputName = planned.name,
                sourceLine = entry.line,
            )
        }

        val overlaps = findOverlaps(parts, pageCount)
        val gaps = findGaps(parts, pageCount)
        val unused = (namePool.size - cursor).coerceAtLeast(0)

        val warnings = buildList {
            if (namePool.isNotEmpty() && !canPairWholeColumn) {
                add(
                    "页码 $unnamedCount 个、名称 ${namePool.size} 个，数量不一致，" +
                        "没有自动配对（错配比不配更糟）",
                )
                add("补齐其中一列，或改用「按目录」")
            }
            if (canPairWholeColumn && unused > 0) {
                add("有 $unused 个名称没配上页码，已忽略")
            }
            if (overlaps.isNotEmpty()) {
                add(
                    "有 ${overlaps.size} 处重叠：" + overlaps.take(MAX_LISTED_OVERLAPS)
                        .joinToString("；") { overlap ->
                            "第 ${overlap.lines.joinToString("、")} 行重复了 " +
                                "${overlap.startPage1}-${overlap.endPage1} 页"
                        },
                )
            }
            if (gaps.isNotEmpty()) {
                add(
                    "有 ${uncoveredPageCountOf(gaps)} 页未被包含：" +
                        gaps.take(MAX_LISTED_GAPS).joinToString("、") { range ->
                            if (range.first == range.last) {
                                "${range.first}"
                            } else {
                                "${range.first}-${range.last}"
                            }
                        } + if (gaps.size > MAX_LISTED_GAPS) " 等" else "",
                )
            }
            if (renamed > 0) {
                add("$renamed 个文件名重复，已自动加序号")
            }
        }

        return ParseOutcome(
            parts = parts,
            pairedNames = paired,
            unusedNames = unused,
            warnings = warnings,
            errors = errors,
            overlaps = overlaps,
            uncoveredPages = gaps,
            renamedDuplicates = renamed,
        )
    }

    /** Splits the whole document into fixed-size chunks; the tail keeps its size. */
    fun chunks(pageCount: Int, pagesPerPart: Int, sourceDisplayName: String): List<SplitPart> {
        require(pageCount > 0) { "源 PDF 没有可拆分的页" }
        require(pagesPerPart > 0) { "每份页数必须大于 0" }
        val used = mutableSetOf<String>()
        val base = sanitizeBase(sourceDisplayName)
        val parts = ArrayList<SplitPart>()
        var start = 1
        while (start <= pageCount) {
            val end = minOf(start + pagesPerPart - 1, pageCount)
            parts += SplitPart(
                index = parts.size + 1,
                startPage1 = start,
                endPage1 = end,
                outputName = planName(base, start, end, null, used).name,
            )
            start = end + 1
        }
        return parts
    }

    fun formatRanges(parts: List<SplitPart>): String =
        parts.joinToString("\n") { "${it.startPage1}-${it.endPage1}" }

    fun sanitizeBase(displayName: String): String {
        val withoutExtension = displayName
            .substringBeforeLast('.', displayName)
            .ifBlank { "PDF" }
        val cleaned = withoutExtension
            .replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]"), "_")
            .replace(Regex("\\s+"), " ")
            .trim()
            .trimEnd('.')
        return truncateToBytes(cleaned, MAX_BASE_BYTES).ifBlank { "PDF" }
    }

    /** Chinese names cost 3 bytes per character; the filesystem cap is 255 bytes. */
    private fun truncateToBytes(text: String, maxBytes: Int): String {
        if (text.toByteArray(Charsets.UTF_8).size <= maxBytes) return text
        var end = text.length
        while (end > 0) {
            val slice = text.substring(0, end)
            if (slice.toByteArray(Charsets.UTF_8).size <= maxBytes) return slice.trimEnd()
            end -= 1
        }
        return ""
    }

    internal data class PlannedName(val name: String, val renamed: Boolean)

    internal fun planName(
        base: String,
        start: Int,
        end: Int,
        explicitName: String?,
        used: MutableSet<String>,
    ): PlannedName {
        val cleanedName = explicitName
            ?.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]"), "_")
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            ?.trimEnd('.')
            ?.let { truncateToBytes(it, MAX_NAME_BYTES) }
            ?.takeIf { it.isNotBlank() }

        val stem = cleanedName
            ?: "${base}_${start.toString().padStart(4, '0')}-" +
            end.toString().padStart(4, '0')

        var candidate = "$stem.pdf"
        var suffix = 2
        var renamed = false
        while (!used.add(candidate.lowercase())) {
            renamed = true
            candidate = "${stem}_$suffix.pdf"
            suffix += 1
        }
        return PlannedName(candidate, renamed)
    }

    internal fun findOverlaps(parts: List<SplitPart>, pageCount: Int): List<PageOverlap> {
        if (parts.isEmpty() || pageCount <= 0) return emptyList()
        val counts = IntArray(pageCount + 2)
        parts.forEach { part ->
            val from = part.startPage1.coerceAtLeast(1)
            val to = part.endPage1.coerceAtMost(pageCount)
            for (page in from..to) counts[page] += 1
        }
        val result = ArrayList<PageOverlap>()
        var page = 1
        while (page <= pageCount) {
            if (counts[page] >= 2) {
                val start = page
                while (page <= pageCount && counts[page] >= 2) page += 1
                val end = page - 1
                val lines = parts
                    .filter { it.startPage1 <= end && it.endPage1 >= start }
                    .map { if (it.sourceLine > 0) it.sourceLine else it.index }
                    .distinct()
                    .sorted()
                result += PageOverlap(start, end, lines)
            } else {
                page += 1
            }
        }
        return result
    }

    internal fun findGaps(parts: List<SplitPart>, pageCount: Int): List<IntRange> {
        if (pageCount <= 0) return emptyList()
        if (parts.isEmpty()) return listOf(1..pageCount)
        val counts = IntArray(pageCount + 2)
        parts.forEach { part ->
            val from = part.startPage1.coerceAtLeast(1)
            val to = part.endPage1.coerceAtMost(pageCount)
            for (page in from..to) counts[page] += 1
        }
        val result = ArrayList<IntRange>()
        var page = 1
        while (page <= pageCount) {
            if (counts[page] == 0) {
                val start = page
                while (page <= pageCount && counts[page] == 0) page += 1
                result += start..(page - 1)
            } else {
                page += 1
            }
        }
        return result
    }

    private fun uncoveredPageCountOf(gaps: List<IntRange>): Int = gaps.sumOf { it.count() }

    private data class Entry(
        val line: Int,
        val start: Int,
        val end: Int,
        val name: String?,
    )

    private const val MAX_NAME_BYTES = 170
    private const val MAX_BASE_BYTES = 120
}
