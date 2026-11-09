package com.m1racle.reader.pdfsplit.ui

/**
 * Keeps everything derived from a source PDF bound to that one PDF.
 *
 * Every file pick bumps [SplitUiState.sourceGeneration]. Outline, selection,
 * plan, run and result are stamped with the generation they were produced for,
 * and every asynchronous result carries the generation it was started under.
 * A result whose generation is not the current one is dropped, so a slow read
 * of book A can never land on book B, and a plan made for A can never run on B.
 *
 * Pure state transitions only (no Android, no coroutines), so the ordering
 * cases — "old result arrives after a new pick" — are unit-testable.
 */
internal object SourceScope {

    /** A new pick: bump the generation and drop everything derived from the previous file. */
    fun beginPick(state: SplitUiState): SplitUiState {
        val previousPages = state.source?.pageCount
        val autoRange = previousPages != null && state.rules.rangeText.trim() == "1-$previousPages"
        val generation = state.sourceGeneration + 1
        return state.copy(
            sourceGeneration = generation,
            source = null,
            loadingSource = true,
            sourceError = null,
            rules = state.rules.copy(
                // Only the value this tool filled in for the old file is cleared;
                // text the user typed is kept and re-validated against the new file.
                rangeText = if (autoRange) "" else state.rules.rangeText,
                outline = OutlineUiState(generation = generation),
            ),
            preview = PreviewState(generation = generation),
            run = RunState(generation = generation),
            resultPreview = ResultPreviewState(),
        )
    }

    /** Applies [transform] only while [generation] is still the file on screen. */
    inline fun accept(
        state: SplitUiState,
        generation: Long,
        transform: (SplitUiState) -> SplitUiState,
    ): SplitUiState = if (generation == state.sourceGeneration) transform(state) else state

    /** The outline usable for planning: only one read from the current file. */
    fun currentOutline(state: SplitUiState): OutlineUiState =
        state.rules.outline.takeIf { it.generation == state.sourceGeneration }
            ?: OutlineUiState(generation = state.sourceGeneration)

    /**
     * Last check before writing anything: the plan on screen must have been made
     * for the current file and must equal a plan freshly rebuilt from the current
     * rules. Any disagreement means the screen is stale and nothing may run.
     */
    fun mayRun(state: SplitUiState, fresh: PreviewState): Boolean =
        mayRun(state.sourceGeneration, state.source?.pageCount, state.preview, fresh)

    fun mayRun(
        currentGeneration: Long,
        currentPages: Int?,
        shown: PreviewState,
        fresh: PreviewState,
    ): Boolean {
        val pages = currentPages ?: return false
        if (shown.generation != currentGeneration || fresh.generation != currentGeneration) return false
        if (shown.documentPages != pages || fresh.documentPages != pages) return false
        if (shown.blocked || fresh.blocked || shown.parts.isEmpty()) return false
        val shownRanges = shown.parts.map { it.startPage1 to it.endPage1 }
        val freshRanges = fresh.parts.map { it.startPage1 to it.endPage1 }
        if (shownRanges != freshRanges) return false
        return shown.parts.all { it.startPage1 >= 1 && it.endPage1 <= pages && it.endPage1 >= it.startPage1 }
    }
}
