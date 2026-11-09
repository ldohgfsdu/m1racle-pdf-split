package com.m1racle.reader.pdfsplit.split

/**
 * Decision rules for the `AUTO` fast path, kept free of PDFBox types so they can
 * be unit-tested on the JVM.
 *
 * The whole point of this file is one asymmetry: **concluding "this resource is
 * painted, so losing it is fatal" is always safe, while concluding "nobody
 * paints it, so losing it is harmless" is only safe if we are sure we read the
 * entire content stream.** Every rule below errs towards the first.
 */
internal enum class MissSeverity {
    /** Not a strict-specific risk: both parsers return null, so nothing is lost. */
    IGNORE,

    /** Unresolvable, and provably never painted. Counted, not fatal. */
    SOFT,

    /** Unresolvable, and either painted or impossible to prove unpainted. */
    FATAL,
}

/**
 * What a page (or a Form XObject) actually paints, and whether that reading can
 * be trusted.
 *
 * [complete] is the load-bearing field. It is false whenever the scan was cut
 * short or whenever the operator scan looks internally inconsistent, and a
 * resource may only be downgraded to [MissSeverity.SOFT] when it is true.
 */
internal data class DrawingEvidence(
    val names: Set<String>,
    val complete: Boolean,
) {
    companion object {
        /** Nothing could be read at all — the pessimistic default. */
        val UNREAD = DrawingEvidence(emptySet(), complete = false)
    }
}

internal object FastPathGuard {

    /**
     * `/Name Do`. A name is followed by a delimiter before the operator, and in
     * practice that delimiter is whitespace. Anything this pattern fails to
     * match is caught by [scanDrawings]'s `Do`-count cross-check rather than
     * being silently dropped.
     */
    private val DRAWN_NAME = Regex("/([A-Za-z0-9_.+#-]{1,64})\\s+Do\\b")

    /**
     * Every `Do` token, used as a cross-check on [DRAWN_NAME]: if the file has
     * more `Do` tokens than the pattern could pair with a name, the scan is not
     * trustworthy and absence proves nothing.
     *
     * The lookbehind excludes `/Do` (a name called Do) and name characters, so
     * ordinary name tokens cannot inflate the count.
     */
    private val DO_TOKEN = Regex("(?<![A-Za-z0-9_#/.+])\\bDo\\b")

    /**
     * Decodes `#xx` escapes in a PDF name.
     *
     * Without this, `/KSPX#31 Do` yields the literal string `KSPX#31`, which
     * never matches the resource key `KSPX1` — so a resource that *is* painted
     * gets classified as unpainted, which is exactly the false negative that
     * must not happen.
     */
    fun decodePdfName(raw: String): String {
        if (raw.indexOf('#') < 0) return raw
        val out = StringBuilder(raw.length)
        var i = 0
        while (i < raw.length) {
            val ch = raw[i]
            if (ch == '#' && i + 2 < raw.length) {
                val code = raw.substring(i + 1, i + 3).toIntOrNull(16)
                if (code != null) {
                    out.append(code.toChar())
                    i += 3
                    continue
                }
            }
            out.append(ch)
            i++
        }
        return out.toString()
    }

    /**
     * Extracts the painted XObject names from decoded content-stream text.
     *
     * [truncated] must be true when the caller stopped reading before the end of
     * the stream; that alone makes the evidence incomplete regardless of what
     * was found.
     */
    fun scanDrawings(text: CharSequence, truncated: Boolean): DrawingEvidence {
        val matches = DRAWN_NAME.findAll(text)
        var paired = 0
        val names = LinkedHashSet<String>()
        for (match in matches) {
            paired++
            names.add(decodePdfName(match.groupValues[1]))
        }
        val doTokens = DO_TOKEN.findAll(text).count()

        // Every Do must have been paired with a name we understood. A mismatch
        // means our reading of the stream is incomplete (an operator we did not
        // recognise, an inline image payload, a name spelling we cannot decode),
        // and "not in names" then means nothing.
        val consistent = paired == doTokens
        return DrawingEvidence(names, complete = !truncated && consistent)
    }

    /** Merges what a Form XObject paints into the evidence for its own resources. */
    fun combine(parent: DrawingEvidence, child: DrawingEvidence): DrawingEvidence =
        DrawingEvidence(parent.names + child.names, parent.complete && child.complete)

    /**
     * How to treat a resource entry that resolved to null.
     *
     * @param isXObject whether the entry lives under `/XObject`, the only
     *   category where being unpainted makes losing it harmless.
     * @param name the (decoded) resource key.
     */
    fun severityForUnresolved(
        isXObject: Boolean,
        name: String,
        evidence: DrawingEvidence,
    ): MissSeverity = when {
        !isXObject -> MissSeverity.FATAL
        !evidence.complete -> MissSeverity.FATAL
        name in evidence.names -> MissSeverity.FATAL
        else -> MissSeverity.SOFT
    }

    /**
     * Whether the xref scan gives us a trustworthy list of bad object numbers.
     *
     * Returns null when the answer is yes. A non-null string is the reason the
     * scan cannot be relied on, and the probe then treats *every* unresolved
     * reference as fatal instead of assuming it was never declared: with no bad
     * list, "unresolved" and "in the bad list" are indistinguishable, and
     * guessing the benign one is how a silent image loss ships.
     */
    fun xrefVeto(report: XrefReport?): String? {
        if (report == null) return "结构扫描失败"
        if (report.kind == XrefReport.Kind.NONE) return "未找到交叉引用表"
        if (report.kind == XrefReport.Kind.UNKNOWN) return "交叉引用表格式无法识别"
        if (report.inspectedEntries == 0) return "交叉引用表未产出任何记录"
        if (!report.startXrefValid) return "startxref 不可用（${report.startXref}）"
        if (report.encrypted) return "文件已加密，扫描不完整"
        return null
    }
}
