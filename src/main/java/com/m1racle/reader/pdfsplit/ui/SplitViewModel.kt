package com.m1racle.reader.pdfsplit.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.m1racle.reader.pdfsplit.split.ConcurrencyPolicy
import com.m1racle.reader.pdfsplit.split.LineError
import com.m1racle.reader.pdfsplit.split.OutlineNode
import com.m1racle.reader.pdfsplit.split.OutlinePlanner
import com.m1racle.reader.pdfsplit.split.OutputPlanner
import com.m1racle.reader.pdfsplit.split.OutputPreview
import com.m1racle.reader.pdfsplit.split.PageOverlap
import com.m1racle.reader.pdfsplit.split.PageRangeParser
import com.m1racle.reader.pdfsplit.split.PdfBoxSetup
import com.m1racle.reader.pdfsplit.split.PdfChapterDetector
import com.m1racle.reader.pdfsplit.split.PdfOutlineReader
import com.m1racle.reader.pdfsplit.split.PdfProbe
import com.m1racle.reader.pdfsplit.split.PdfRangeSplitter
import com.m1racle.reader.pdfsplit.split.SafOutputDirectory
import com.m1racle.reader.pdfsplit.split.SplitEvent
import com.m1racle.reader.pdfsplit.split.SplitOutput
import com.m1racle.reader.pdfsplit.split.SplitPart
import com.m1racle.reader.pdfsplit.split.TaskSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class SplitMode { PAGES, FIXED_SIZE, OUTLINE }

enum class OutlineSource { NONE, BOOKMARKS, TEXT_SCAN }

enum class RunPhase { IDLE, RUNNING, DONE, FAILED, CANCELLED }

data class SourceInfo(
    val uri: Uri,
    val name: String,
    val pageCount: Int,
    val sizeBytes: Long,
    val hasOutline: Boolean = false,
    val pageLabels: List<String>? = null,
    val probing: Boolean = false,
    val textLayerKnown: Boolean? = null,
)

data class OutlineUiState(
    val nodes: List<OutlineNode> = emptyList(),
    val selected: Set<Int> = emptySet(),
    val depth: Int = 1,
    val loading: Boolean = false,
    val scanning: Int = 0,
    val totalForScan: Int = 0,
    val message: String? = null,
    val source: OutlineSource = OutlineSource.NONE,
) {
    val maxDepth: Int get() = nodes.maxOfOrNull { it.depth } ?: 1
}

data class RulesState(
    val rangeText: String = "",
    val pagesPerPart: String = "50",
    val outline: OutlineUiState = OutlineUiState(),
)

data class PreviewState(
    val parts: List<SplitPart> = emptyList(),
    val errors: List<LineError> = emptyList(),
    val overlaps: List<PageOverlap> = emptyList(),
    val uncovered: List<IntRange> = emptyList(),
    val renamedInBatch: Int = 0,
    val renamedByFolder: Int = 0,
    val notes: List<String> = emptyList(),
    val blocked: Boolean = false,
    val blockedReason: String? = null,
) {
    val fileCount: Int get() = parts.size
    val uncoveredPages: Int get() = uncovered.sumOf { it.count() }
    val coveredPages: Int get() = parts.sumOf { it.pageCount }
}

data class RunState(
    val phase: RunPhase = RunPhase.IDLE,
    val fraction: Float = 0f,
    val headline: String = "",
    val detail: String = "",
    val tasks: List<TaskSnapshot> = emptyList(),
    val outputs: List<SplitOutput> = emptyList(),
    val finished: Int = 0,
    val total: Int = 0,
    val error: String? = null,
)

/** A written output opened for visual verification: first + last page. */
data class ResultPreviewState(
    val output: SplitOutput? = null,
    val firstPage: Bitmap? = null,
    val lastPage: Bitmap? = null,
    val sizeBytes: Long = 0L,
    val loading: Boolean = false,
    val error: String? = null,
)

data class SplitUiState(
    val mode: SplitMode = SplitMode.PAGES,
    val source: SourceInfo? = null,
    val loadingSource: Boolean = false,
    val sourceError: String? = null,
    val rules: RulesState = RulesState(),
    val preview: PreviewState = PreviewState(),
    val outputTreeUri: Uri? = null,
    val outputLabel: String? = null,
    val deepVerify: Boolean = false,
    val concurrencyAuto: Boolean = true,
    val concurrencyManual: Int = 2,
    val advancedExpanded: Boolean = false,
    val run: RunState = RunState(),
    val resultPreview: ResultPreviewState = ResultPreviewState(),
    /** True between a cancellation and the end of its filesystem cleanup. */
    val cleaningUp: Boolean = false,
) {
    val cores: Int get() = Runtime.getRuntime().availableProcessors()
    val effectiveConcurrency: Int
        get() = if (concurrencyAuto) {
            ConcurrencyPolicy.auto(cores)
        } else {
            ConcurrencyPolicy.sanitize(concurrencyManual, cores)
        }

    /** Non-null when the primary action is unavailable, with the reason. */
    val blockedReason: String?
        get() = when {
            run.phase == RunPhase.RUNNING -> null
            source == null -> "请先选择 PDF"
            preview.parts.isEmpty() -> when (mode) {
                SplitMode.PAGES -> "请填写页码，例如 1-50"
                SplitMode.FIXED_SIZE -> "请填写每份页数"
                SplitMode.OUTLINE -> "请先读取目录并勾选章节"
            }
            preview.errors.isNotEmpty() -> "第 ${preview.errors.first().line} 行有错误"
            outputTreeUri == null -> "请先选择输出目录"
            else -> null
        }

    val canStart: Boolean
        get() = blockedReason == null &&
            preview.parts.isNotEmpty() &&
            run.phase != RunPhase.RUNNING &&
            !cleaningUp
}

class SplitViewModel(
    application: Application,
    private val saved: SavedStateHandle,
) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(restoreState())
    val state: StateFlow<SplitUiState> = _state.asStateFlow()

    private var previewJob: Job? = null
    private var runJob: Job? = null

    private fun restoreState(): SplitUiState {
        val mode = saved.get<String>(KEY_MODE)?.let { runCatching { SplitMode.valueOf(it) }.getOrNull() }
        val rangeText = saved.get<String>(KEY_RANGE).orEmpty()
        val pagesPerPart = saved.get<String>(KEY_PER_PART) ?: "50"
        val outputUri = saved.get<String>(KEY_OUTPUT)?.let(Uri::parse)
        return SplitUiState(
            mode = mode ?: SplitMode.PAGES,
            rules = RulesState(rangeText = rangeText, pagesPerPart = pagesPerPart),
            outputTreeUri = outputUri,
            outputLabel = outputUri?.let { runCatching { it.lastPathSegment }.getOrNull() },
            deepVerify = saved.get<Boolean>(KEY_DEEP) ?: false,
        )
    }

    private fun persist() {
        val s = _state.value
        saved[KEY_MODE] = s.mode.name
        saved[KEY_RANGE] = s.rules.rangeText
        saved[KEY_PER_PART] = s.rules.pagesPerPart
        saved[KEY_OUTPUT] = s.outputTreeUri?.toString()
        saved[KEY_DEEP] = s.deepVerify
    }

    // ── Source ──────────────────────────────────────────────────────────────

    fun onSourcePicked(uri: Uri) {
        viewModelScope.launch {
            _state.update {
                it.copy(loadingSource = true, sourceError = null, run = RunState())
            }
            val basic = withContext(Dispatchers.IO) { readBasic(uri) }
            if (basic == null) {
                _state.update {
                    it.copy(
                        loadingSource = false,
                        sourceError = "读不出页数，可能已加密或文件损坏",
                    )
                }
                return@launch
            }
            _state.update {
                it.copy(
                    loadingSource = false,
                    source = basic.copy(probing = true),
                    rules = it.rules.copy(
                        rangeText = if (it.rules.rangeText.isBlank()) {
                            "1-${basic.pageCount}"
                        } else {
                            it.rules.rangeText
                        },
                    ),
                )
            }
            schedulePreview()

            val extras = PdfProbe.probe(getApplication(), uri)
            _state.update { state ->
                val current = state.source ?: return@update state
                if (current.uri != uri) return@update state
                state.copy(
                    source = current.copy(
                        probing = false,
                        hasOutline = extras?.hasOutline ?: false,
                        pageLabels = extras?.pageLabels,
                    ),
                )
            }
        }
    }

    private fun readBasic(uri: Uri): SourceInfo? {
        val context = getApplication<Application>()
        val resolver = context.contentResolver
        var name = uri.lastPathSegment ?: "document.pdf"
        var size = 0L
        runCatching {
            resolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getString(0)?.let { name = it }
                    if (!cursor.isNull(1)) size = cursor.getLong(1)
                }
            }
        }
        val pageCount = runCatching {
            resolver.openFileDescriptor(uri, "r")?.use { descriptor ->
                PdfRenderer(descriptor).use { it.pageCount }
            }
        }.getOrNull() ?: return null
        if (pageCount <= 0) return null
        return SourceInfo(uri = uri, name = name, pageCount = pageCount, sizeBytes = size)
    }

    // ── Rules ───────────────────────────────────────────────────────────────

    fun setMode(mode: SplitMode) {
        // Switching mode starts a new plan; a previous "done" card must not
        // linger above the new (empty) preview.
        _state.update {
            it.copy(mode = mode, run = RunState(), resultPreview = ResultPreviewState())
        }
        persist()
        schedulePreview()
    }

    fun setRangeText(text: String) {
        _state.update { it.copy(rules = it.rules.copy(rangeText = text)) }
        persist()
        schedulePreview()
    }

    fun setPagesPerPart(text: String) {
        _state.update { it.copy(rules = it.rules.copy(pagesPerPart = text)) }
        persist()
        schedulePreview()
    }

    fun loadOutline() {
        val source = _state.value.source ?: return
        viewModelScope.launch {
            _state.update {
                it.copy(
                    rules = it.rules.copy(
                        outline = it.rules.outline.copy(loading = true, message = null),
                    ),
                )
            }
            val nodes = runCatching {
                PdfOutlineReader.readTree(getApplication(), source.uri)
            }.getOrNull().orEmpty()
            _state.update { state ->
                val outline = state.rules.outline.copy(
                    nodes = nodes,
                    loading = false,
                    selected = emptySet(),
                    depth = 1,
                    source = if (nodes.isEmpty()) OutlineSource.NONE else OutlineSource.BOOKMARKS,
                    message = if (nodes.isEmpty()) {
                        "这个 PDF 没有书签（与有没有文字层无关）"
                    } else {
                        "读取到 ${nodes.size} 个书签，最深 ${nodes.maxOfOrNull { n -> n.depth } ?: 1} 级"
                    },
                )
                state.copy(rules = state.rules.copy(outline = outline))
            }
            schedulePreview()
        }
    }

    fun scanTextHeadings() {
        val source = _state.value.source ?: return
        viewModelScope.launch {
            _state.update {
                it.copy(
                    rules = it.rules.copy(
                        outline = it.rules.outline.copy(
                            loading = true,
                            message = null,
                            scanning = 0,
                            totalForScan = source.pageCount,
                        ),
                    ),
                )
            }
            val detection = runCatching {
                PdfChapterDetector.detect(getApplication(), source.uri) { scanned, total ->
                    _state.update { state ->
                        state.copy(
                            rules = state.rules.copy(
                                outline = state.rules.outline.copy(
                                    scanning = scanned,
                                    totalForScan = total,
                                ),
                            ),
                        )
                    }
                }
            }.getOrNull()

            _state.update { state ->
                val outline = when {
                    detection == null -> state.rules.outline.copy(
                        loading = false,
                        message = "扫描正文失败，文件可能已损坏",
                    )
                    !detection.hasTextLayer -> state.rules.outline.copy(
                        loading = false,
                        message = "这本书没有文字层（扫描件），读不出标题；" +
                            "但书签可能仍然存在，试试「读取书签」",
                    )
                    detection.chapters.isEmpty() -> state.rules.outline.copy(
                        loading = false,
                        message = "有文字层，但没找到明显的章节标题",
                    )
                    else -> {
                        val nodes = detection.chapters.mapIndexed { index, chapter ->
                            OutlineNode(
                                index = index,
                                title = chapter.title,
                                depth = 1,
                                parentIndex = -1,
                                startPage1 = chapter.startPage1,
                            )
                        }
                        state.rules.outline.copy(
                            nodes = nodes,
                            selected = emptySet(),
                            depth = 1,
                            loading = false,
                            source = OutlineSource.TEXT_SCAN,
                            message = "从正文识别到 ${nodes.size} 个章节" +
                                "（${detection.textPages}/${detection.totalPages} 页有文字）",
                        )
                    }
                }
                state.copy(rules = state.rules.copy(outline = outline))
            }
            schedulePreview()
        }
    }

    fun toggleChapter(index: Int) {
        _state.update { state ->
            val outline = state.rules.outline
            state.copy(
                rules = state.rules.copy(
                    outline = outline.copy(
                        selected = OutlinePlanner.toggle(outline.selected, index, outline.nodes),
                    ),
                ),
            )
        }
        schedulePreview()
    }

    fun setDepth(depth: Int) {
        _state.update { state ->
            val outline = state.rules.outline
            val allowed = depth.coerceIn(1, outline.maxDepth.coerceAtLeast(1))
            state.copy(rules = state.rules.copy(outline = outline.copy(depth = allowed)))
        }
        schedulePreview()
    }

    fun selectAllAtDepth() {
        _state.update { state ->
            val outline = state.rules.outline
            val wanted = outline.nodes.filter { it.depth == outline.depth }
            state.copy(
                rules = state.rules.copy(
                    outline = outline.copy(selected = wanted.map { it.index }.toSet()),
                ),
            )
        }
        schedulePreview()
    }

    fun clearSelection() {
        _state.update { state ->
            state.copy(
                rules = state.rules.copy(
                    outline = state.rules.outline.copy(selected = emptySet()),
                ),
            )
        }
        schedulePreview()
    }

    // ── Output & advanced ───────────────────────────────────────────────────

    fun setOutputDirectory(uri: Uri, label: String) {
        _state.update { it.copy(outputTreeUri = uri, outputLabel = label) }
        persist()
        schedulePreview()
    }

    fun setDeepVerify(enabled: Boolean) {
        _state.update { it.copy(deepVerify = enabled) }
        persist()
    }

    fun setConcurrencyAuto(auto: Boolean) {
        _state.update { it.copy(concurrencyAuto = auto) }
    }

    fun setConcurrencyManual(value: Int) {
        _state.update {
            it.copy(
                concurrencyAuto = false,
                concurrencyManual = ConcurrencyPolicy.sanitize(value, it.cores),
            )
        }
    }

    fun toggleAdvanced() {
        _state.update { it.copy(advancedExpanded = !it.advancedExpanded) }
    }

    // ── Whole book, one tap per chapter ─────────────────────────────────────

    private var resultPreviewJob: Job? = null

    /**
     * Reads the entire book's outline and selects every leaf chapter, so a whole
     * book becomes one file per chapter without hand-picking bookmarks. Falls
     * back to the text layer, and states plainly when OCR would be required.
     */
    fun splitWholeBookByChapters() {
        val source = _state.value.source ?: return
        viewModelScope.launch {
            _state.update {
                it.copy(
                    mode = SplitMode.OUTLINE,
                    run = RunState(),
                    resultPreview = ResultPreviewState(),
                    rules = it.rules.copy(
                        outline = it.rules.outline.copy(
                            loading = true,
                            message = "正在读取整本目录…",
                        ),
                    ),
                )
            }
            val nodes = withContext(Dispatchers.IO) {
                runCatching {
                    PdfOutlineReader.readTree(getApplication(), source.uri)
                }.getOrNull().orEmpty()
            }
            if (nodes.isNotEmpty()) {
                applyWholeBookOutline(nodes)
                return@launch
            }

            val detection = withContext(Dispatchers.IO) {
                runCatching {
                    PdfChapterDetector.detect(getApplication(), source.uri) { _, _ -> }
                }.getOrNull()
            }
            if (detection != null && detection.chapters.isNotEmpty()) {
                val generated = detection.chapters.mapIndexed { index, chapter ->
                    OutlineNode(
                        index = index,
                        title = chapter.title,
                        depth = 1,
                        parentIndex = -1,
                        startPage1 = chapter.startPage1,
                    )
                }
                _state.update { state ->
                    val outline = state.rules.outline.copy(
                        nodes = generated,
                        selected = generated.map { it.index }.toSet(),
                        depth = 1,
                        loading = false,
                        source = OutlineSource.TEXT_SCAN,
                        message = "整本没有书签，已从正文文字识别出 ${generated.size} 个章节",
                    )
                    state.copy(rules = state.rules.copy(outline = outline))
                }
            } else {
                _state.update { state ->
                    val noText = detection == null || !detection.hasTextLayer
                    val outline = state.rules.outline.copy(
                        nodes = emptyList(),
                        selected = emptySet(),
                        loading = false,
                        source = OutlineSource.NONE,
                        message = if (noText) {
                            "整本既没有书签，也没有文字层（纯图片扫描件）。识别章节需要 OCR，本版本未包含；请改用「指定页码」或「固定页数」。"
                        } else {
                            "整本没有书签，正文里也没找到明显的章节标题；请改用「指定页码」。"
                        },
                    )
                    state.copy(rules = state.rules.copy(outline = outline))
                }
            }
            schedulePreview()
        }
    }

    private fun applyWholeBookOutline(nodes: List<OutlineNode>) {
        // "逐章" means the chapter level, not every last sub-section: a textbook
        // with 600 bookmarks would otherwise produce hundreds of tiny files.
        // Prefer level 2 (e.g. ST01-CH03), fall back to level 1, and keep the
        // level switch in the UI so the user can pick a different granularity.
        val level = if (nodes.any { it.depth == 2 }) 2 else 1
        val parents = nodes.map { it.parentIndex }.toSet()
        val atLevel = nodes.filter { it.depth == level }
        val orphanTops = nodes.filter { it.depth < level && it.index !in parents }
        val chosen = (atLevel + orphanTops)
            .distinctBy { it.startPage1 }
            .ifEmpty { nodes.filter { it.depth == 1 } }
        _state.update { state ->
            val outline = state.rules.outline.copy(
                nodes = nodes,
                selected = chosen.map { it.index }.toSet(),
                depth = level,
                loading = false,
                source = OutlineSource.BOOKMARKS,
                message = "整本目录：${nodes.size} 个书签，已按第 $level 级选中 " +
                    "${chosen.size} 个章节；可在下方切换层级后点「全选本级」",
            )
            state.copy(rules = state.rules.copy(outline = outline))
        }
        schedulePreview()
    }

    // ── Post-split preview ──────────────────────────────────────────────────

    fun openResultPreview(output: SplitOutput) {
        resultPreviewJob?.cancel()
        _state.update {
            it.copy(resultPreview = ResultPreviewState(output = output, loading = true))
        }
        resultPreviewJob = viewModelScope.launch {
            val context = getApplication<Application>()
            val result = withContext(Dispatchers.IO) {
                val size = OutputPreview.fileSize(context, output.uri)
                val first = OutputPreview.renderPage(context, output.uri, 0)
                val last = if (output.pageCount > 1) {
                    OutputPreview.renderPage(context, output.uri, output.pageCount - 1)
                } else {
                    null
                }
                Triple(size, first, last)
            }
            _state.update { current ->
                if (current.resultPreview.output?.uri != output.uri) return@update current
                current.copy(
                    resultPreview = current.resultPreview.copy(
                        sizeBytes = result.first,
                        firstPage = result.second,
                        lastPage = result.third,
                        loading = false,
                        error = if (result.second == null) "无法读取这个 PDF 的页面" else null,
                    ),
                )
            }
        }
    }

    fun closeResultPreview() {
        resultPreviewJob?.cancel()
        _state.update { it.copy(resultPreview = ResultPreviewState()) }
    }

    // ── Preview ─────────────────────────────────────────────────────────────

    private fun schedulePreview() {
        previewJob?.cancel()
        previewJob = viewModelScope.launch {
            val quick = buildPreview(existing = emptySet(), knownFolder = false)
            _state.update { it.copy(preview = quick) }
            val tree = _state.value.outputTreeUri ?: return@launch
            delay(250)
            val existing = SafOutputDirectory.listExistingNames(getApplication(), tree)
            val full = buildPreview(existing = existing, knownFolder = true)
            _state.update { it.copy(preview = full) }
        }
    }

    private fun buildPreview(existing: Set<String>, knownFolder: Boolean): PreviewState {
        val state = _state.value
        val source = state.source
            ?: return PreviewState(blocked = true, blockedReason = "请先选择 PDF")
        val pageCount = source.pageCount

        val raw = when (state.mode) {
            SplitMode.PAGES -> {
                if (state.rules.rangeText.isBlank()) {
                    return PreviewState(blocked = true, blockedReason = "请填写页码")
                }
                val outcome = runCatching {
                    PageRangeParser.parseBlock(
                        state.rules.rangeText,
                        pageCount,
                        source.name,
                    )
                }.getOrElse { error ->
                    return PreviewState(
                        blocked = true,
                        blockedReason = error.message ?: "页码无法解析",
                    )
                }
                RawPlan(
                    parts = outcome.parts,
                    errors = outcome.errors,
                    overlaps = outcome.overlaps,
                    uncovered = outcome.uncoveredPages,
                    renamedInBatch = outcome.renamedDuplicates,
                    notes = outcome.warnings,
                )
            }
            SplitMode.FIXED_SIZE -> {
                val size = state.rules.pagesPerPart.toIntOrNull() ?: 0
                if (size <= 0) {
                    return PreviewState(blocked = true, blockedReason = "每份页数必须大于 0")
                }
                val parts = PageRangeParser.chunks(pageCount, size, source.name)
                RawPlan(
                    parts = parts,
                    errors = emptyList(),
                    overlaps = PageRangeParser.findOverlaps(parts, pageCount),
                    uncovered = PageRangeParser.findGaps(parts, pageCount),
                    renamedInBatch = 0,
                    notes = emptyList(),
                )
            }
            SplitMode.OUTLINE -> {
                val outline = state.rules.outline
                if (outline.selected.isEmpty()) {
                    return PreviewState(
                        blocked = true,
                        blockedReason = if (outline.nodes.isEmpty()) {
                            "还没有读取目录"
                        } else {
                            "请勾选要拆分的章节"
                        },
                    )
                }
                val parts = OutlinePlanner.plan(
                    nodes = outline.nodes,
                    selected = outline.selected,
                    totalPages = pageCount,
                    sourceDisplayName = source.name,
                )
                RawPlan(
                    parts = parts,
                    errors = emptyList(),
                    overlaps = PageRangeParser.findOverlaps(parts, pageCount),
                    uncovered = PageRangeParser.findGaps(parts, pageCount),
                    renamedInBatch = 0,
                    notes = emptyList(),
                )
            }
        }

        val planned = if (knownFolder) {
            OutputPlanner.avoidExisting(raw.parts, existing)
        } else {
            OutputPlanner.Plan(raw.parts, 0)
        }

        val blocked = raw.errors.isNotEmpty() || planned.parts.isEmpty()
        val reason = when {
            raw.errors.isNotEmpty() -> "第 ${raw.errors.first().line} 行有错误"
            planned.parts.isEmpty() -> "没有可拆分的页码"
            else -> null
        }

        return PreviewState(
            parts = planned.parts,
            errors = raw.errors,
            overlaps = raw.overlaps,
            uncovered = raw.uncovered,
            renamedInBatch = raw.renamedInBatch,
            renamedByFolder = planned.conflicts,
            notes = raw.notes,
            blocked = blocked,
            blockedReason = reason,
        )
    }

    private data class RawPlan(
        val parts: List<SplitPart>,
        val errors: List<LineError>,
        val overlaps: List<PageOverlap>,
        val uncovered: List<IntRange>,
        val renamedInBatch: Int,
        val notes: List<String>,
    )

    // ── Run ─────────────────────────────────────────────────────────────────

    fun start() {
        val state = _state.value
        if (state.run.phase == RunPhase.RUNNING) return
        val source = state.source ?: return
        val tree = state.outputTreeUri ?: return
        val parts = state.preview.parts
        if (parts.isEmpty() || state.preview.blocked) return

        _state.update {
            it.copy(
                run = RunState(
                    phase = RunPhase.RUNNING,
                    headline = "准备中…",
                    total = parts.size,
                ),
                resultPreview = ResultPreviewState(),
            )
        }
        runJob = viewModelScope.launch {
            try {
                // Re-read the folder right before writing: the preview snapshot
                // may be stale, and a stale name makes the SAF provider append
                // " (1)" behind the user's back.
                val existing = SafOutputDirectory.listExistingNames(getApplication(), tree)
                val fresh = OutputPlanner.avoidExisting(parts, existing)
                if (fresh.conflicts > 0) {
                    _state.update { current ->
                        current.copy(
                            preview = current.preview.copy(
                                parts = fresh.parts,
                                renamedByFolder = fresh.conflicts,
                            ),
                        )
                    }
                }
                val finalParts = fresh.parts
                val outputs = PdfRangeSplitter(getApplication()).split(
                    sourceUri = source.uri,
                    outputTreeUri = tree,
                    expectedPageCount = source.pageCount,
                    parts = finalParts,
                    deepVerify = state.deepVerify,
                    concurrency = state.effectiveConcurrency,
                ) { event ->
                    applyEngineEvent(event)
                }
                _state.update {
                    it.copy(
                        run = RunState(
                            phase = RunPhase.DONE,
                            fraction = 1f,
                            headline = "完成",
                            detail = "${outputs.size} 个文件已写入",
                            outputs = outputs,
                            finished = outputs.size,
                            total = outputs.size,
                        ),
                    )
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                _state.update {
                    it.copy(
                        run = it.run.copy(
                            phase = RunPhase.CANCELLED,
                            headline = "已取消",
                            detail = "写入中的文件已清理",
                        ),
                    )
                }
                throw cancelled
            } catch (error: Exception) {
                _state.update {
                    it.copy(
                        run = RunState(
                            phase = RunPhase.FAILED,
                            headline = "失败",
                            error = error.message ?: "拆分失败",
                        ),
                    )
                }
            } finally {
                _state.update { it.copy(cleaningUp = false) }
                runJob = null
            }
        }
    }

    private suspend fun applyEngineEvent(event: SplitEvent) {
        withContext(Dispatchers.Main) {
            _state.update { state ->
                // A late progress tick must never resurrect a cancelled run.
                if (state.run.phase == RunPhase.CANCELLED) return@update state
                state.copy(
                    run = when (event) {
                        is SplitEvent.Staging -> state.run.copy(
                            headline = "读取源文件",
                            detail = if (event.totalBytes > 0) {
                                "${event.copiedBytes / 1048576} / " +
                                    "${event.totalBytes / 1048576} MB"
                            } else {
                                ""
                            },
                            fraction = if (event.totalBytes > 0) {
                                (event.copiedBytes.toFloat() / event.totalBytes) * 0.05f
                            } else {
                                0.02f
                            },
                        )
                        is SplitEvent.Opening -> state.run.copy(
                            headline = "解析 PDF",
                            fraction = 0.05f,
                        )
                        is SplitEvent.Tasks -> state.run.copy(
                            headline = "拆分中",
                            detail = activeSummary(event.tasks),
                            fraction = 0.05f + 0.95f * event.overallFraction,
                            tasks = event.tasks,
                            finished = event.finishedParts,
                            total = event.totalParts,
                        )
                    },
                )
            }
        }
    }

    private fun activeSummary(tasks: List<TaskSnapshot>): String {
        val active = tasks.filter {
            it.stage == com.m1racle.reader.pdfsplit.split.TaskStage.WRITING ||
                it.stage == com.m1racle.reader.pdfsplit.split.TaskStage.CHECKING ||
                it.stage == com.m1racle.reader.pdfsplit.split.TaskStage.SAVING
        }
        return if (active.isEmpty()) {
            "共 ${tasks.size} 个文件"
        } else {
            active.take(2).joinToString(" · ") { "${it.startPage1}-${it.endPage1}" }
        }
    }

    fun cancel() {
        val job = runJob ?: return
        // Feedback must land on the same frame as the tap. Waiting for the engine
        // to unwind kept the button in "拆分中" for minutes while a single large
        // save() call continued to burn CPU.
        _state.update { current ->
            if (current.run.phase != RunPhase.RUNNING) return@update current
            current.copy(
                cleaningUp = true,
                run = current.run.copy(
                    phase = RunPhase.CANCELLED,
                    headline = "已取消",
                    detail = "正在停止写入并清理未完成的片段…",
                    tasks = emptyList(),
                ),
            )
        }
        job.cancel()
    }

    /** Page label for a physical page, when the document defines them. */
    fun labelFor(page1: Int): String? {
        val labels = _state.value.source?.pageLabels ?: return null
        return labels.getOrNull(page1 - 1)?.takeIf { it.isNotBlank() }
    }

    private companion object {
        const val KEY_MODE = "mode"
        const val KEY_RANGE = "rangeText"
        const val KEY_PER_PART = "pagesPerPart"
        const val KEY_OUTPUT = "outputDir"
        const val KEY_DEEP = "deepVerify"
    }
}
