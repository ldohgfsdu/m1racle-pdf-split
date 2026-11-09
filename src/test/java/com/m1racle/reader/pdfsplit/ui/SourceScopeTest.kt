package com.m1racle.reader.pdfsplit.ui

import com.m1racle.reader.pdfsplit.split.OutlineNode
import com.m1racle.reader.pdfsplit.split.SplitPart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * File state isolation: whatever was read, planned or run for one PDF must not
 * survive a pick of another PDF, even when it arrives late.
 */
class SourceScopeTest {

    private val oldBookNodes = listOf(
        OutlineNode(0, "Old Chapter 1", 1, -1, 1),
        OutlineNode(1, "Old Chapter 2", 1, -1, 50),
    )

    private fun part(start: Int, end: Int, index: Int = 1) =
        SplitPart(index = index, startPage1 = start, endPage1 = end, outputName = "p$index.pdf")

    /** A screen that has read book A's outline and planned it. */
    private fun withOldBookPlanned(): SplitUiState {
        val picked = SourceScope.beginPick(SplitUiState())
        val g = picked.sourceGeneration
        return picked.copy(
            mode = SplitMode.OUTLINE,
            loadingSource = false,
            rules = picked.rules.copy(
                outline = OutlineUiState(nodes = oldBookNodes, selected = setOf(0, 1), generation = g),
            ),
            preview = PreviewState(parts = listOf(part(1, 49), part(50, 120, 2)), generation = g, documentPages = 120),
            run = RunState(phase = RunPhase.DONE, generation = g),
        )
    }

    @Test
    fun `every pick bumps the generation`() {
        val first = SourceScope.beginPick(SplitUiState())
        val second = SourceScope.beginPick(first)
        assertEquals(1L, first.sourceGeneration)
        assertEquals(2L, second.sourceGeneration)
    }

    @Test
    fun `picking a new file drops outline, selection, plan, run and result of the old one`() {
        val old = withOldBookPlanned()
        val next = SourceScope.beginPick(old)
        assertTrue(next.rules.outline.nodes.isEmpty())
        assertTrue(next.rules.outline.selected.isEmpty())
        assertTrue(next.preview.parts.isEmpty())
        assertEquals(RunPhase.IDLE, next.run.phase)
        assertEquals(null, next.resultPreview.output)
        assertEquals(next.sourceGeneration, next.rules.outline.generation)
        assertEquals(next.sourceGeneration, next.preview.generation)
        assertTrue(next.loadingSource)
        assertEquals(null, next.source)
    }

    @Test
    fun `an outline read for the old file arriving late is discarded`() {
        val old = withOldBookPlanned()
        val oldGeneration = old.sourceGeneration
        val next = SourceScope.beginPick(old)
        val afterLate = SourceScope.accept(next, oldGeneration) {
            it.copy(rules = it.rules.copy(outline = OutlineUiState(nodes = oldBookNodes, selected = setOf(0, 1), generation = oldGeneration)))
        }
        assertSame(next, afterLate)
    }

    @Test
    fun `a late plan, run result or engine tick of the old file is discarded`() {
        val old = withOldBookPlanned()
        val g = old.sourceGeneration
        val next = SourceScope.beginPick(old)
        val latePreview = SourceScope.accept(next, g) { it.copy(preview = old.preview) }
        val lateRun = SourceScope.accept(next, g) { it.copy(run = RunState(phase = RunPhase.DONE, generation = g)) }
        val lateFail = SourceScope.accept(next, g) { it.copy(run = RunState(phase = RunPhase.FAILED, error = "x")) }
        assertSame(next, latePreview)
        assertSame(next, lateRun)
        assertSame(next, lateFail)
    }

    @Test
    fun `results for the current file are applied`() {
        val next = SourceScope.beginPick(withOldBookPlanned())
        val g = next.sourceGeneration
        val applied = SourceScope.accept(next, g) { it.copy(loadingSource = false) }
        assertFalse(applied.loadingSource)
    }

    @Test
    fun `an outline stamped with another generation is never used for planning`() {
        val old = withOldBookPlanned()
        // Simulate a state where the outline was not reset (e.g. a future code path).
        val next = SourceScope.beginPick(old).let { it.copy(rules = it.rules.copy(outline = old.rules.outline)) }
        val usable = SourceScope.currentOutline(next)
        assertTrue(usable.nodes.isEmpty())
        assertTrue(usable.selected.isEmpty())
        assertEquals(oldBookNodes, SourceScope.currentOutline(old).nodes)
    }

    @Test
    fun `old book plan cannot run on the new book`() {
        val old = withOldBookPlanned()
        val oldPlan = old.preview
        val g2 = old.sourceGeneration + 1
        // New book has 30 pages; the screen still shows the old 120-page plan.
        val fresh = PreviewState(parts = listOf(part(1, 30)), generation = g2, documentPages = 30)
        assertFalse(SourceScope.mayRun(g2, 30, oldPlan, fresh))
        // Even if someone stamped the old plan with the new generation, its pages do not fit.
        assertFalse(SourceScope.mayRun(g2, 30, oldPlan.copy(generation = g2, documentPages = 30), fresh))
    }

    @Test
    fun `a plan that no longer matches a fresh re-plan cannot run`() {
        val shown = PreviewState(parts = listOf(part(1, 10)), generation = 3, documentPages = 20)
        val fresh = PreviewState(parts = listOf(part(1, 20)), generation = 3, documentPages = 20)
        assertFalse(SourceScope.mayRun(3, 20, shown, fresh))
    }

    @Test
    fun `a current plan that matches a fresh re-plan may run`() {
        val shown = PreviewState(parts = listOf(part(1, 10), part(11, 20, 2)), generation = 3, documentPages = 20)
        assertTrue(SourceScope.mayRun(3, 20, shown, shown.copy()))
        assertFalse(SourceScope.mayRun(3, null, shown, shown.copy()))
        assertFalse(SourceScope.mayRun(4, 20, shown, shown.copy()))
    }

    @Test
    fun `auto filled range of the old file is cleared but typed text is kept`() {
        val auto = SplitUiState(rules = RulesState(rangeText = "1-120"))
        // No source → nothing known about an auto fill; text is kept.
        assertEquals("1-120", SourceScope.beginPick(auto).rules.rangeText)
        val typed = SplitUiState(rules = RulesState(rangeText = "1-10\n11-20"))
        assertEquals("1-10\n11-20", SourceScope.beginPick(typed).rules.rangeText)
    }

    @Test
    fun `interleaved picks only ever accept the newest generation`() {
        var state = SplitUiState()
        val generations = ArrayList<Long>()
        repeat(5) {
            state = SourceScope.beginPick(state)
            generations += state.sourceGeneration
        }
        generations.dropLast(1).forEach { g ->
            state = SourceScope.accept(state, g) { it.copy(sourceError = "stale $g") }
        }
        assertEquals(null, state.sourceError)
        state = SourceScope.accept(state, generations.last()) { it.copy(sourceError = "current") }
        assertEquals("current", state.sourceError)
    }
}
