package com.m1racle.reader.pdfsplit.split

/**
 * Finalises file names against what already exists in the chosen output folder.
 *
 * `PageRangeParser` only guarantees uniqueness inside one batch. The filesystem,
 * however, may already hold a file with the same name, and the SAF provider
 * would silently create `name (1).pdf` instead — a name the user never saw.
 * Planning against the real directory makes the preview show what will actually
 * be written.
 */
object OutputPlanner {

    data class Plan(
        val parts: List<SplitPart>,
        val conflicts: Int,
    )

    fun avoidExisting(
        parts: List<SplitPart>,
        existingLowercaseNames: Set<String>,
    ): Plan {
        if (parts.isEmpty()) return Plan(emptyList(), 0)
        val taken = HashSet<String>(parts.size)
        var conflicts = 0

        val resolved = parts.map { part ->
            val key = part.outputName.lowercase()
            if (key !in existingLowercaseNames && taken.add(key)) {
                part
            } else {
                conflicts += 1
                val stem = if (part.outputName.endsWith(".pdf", ignoreCase = true)) {
                    part.outputName.dropLast(4)
                } else {
                    part.outputName
                }
                var suffix = 2
                var chosen: String
                while (true) {
                    val candidate = "${stem}_$suffix.pdf"
                    val candidateKey = candidate.lowercase()
                    if (candidateKey !in existingLowercaseNames && taken.add(candidateKey)) {
                        chosen = candidate
                        break
                    }
                    suffix += 1
                }
                part.copy(outputName = chosen)
            }
        }
        return Plan(resolved, conflicts)
    }
}
