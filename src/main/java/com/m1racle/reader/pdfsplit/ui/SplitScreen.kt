package com.m1racle.reader.pdfsplit.ui

import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.m1racle.reader.pdfsplit.split.ConcurrencyPolicy
import com.m1racle.reader.pdfsplit.split.OutlineNode
import com.m1racle.reader.pdfsplit.split.SplitOutput
import com.m1racle.reader.pdfsplit.split.SplitPart
import com.m1racle.reader.pdfsplit.split.TaskSnapshot
import com.m1racle.reader.pdfsplit.split.TaskStage

@Composable
fun SplitScreen(
    state: SplitUiState,
    vm: SplitViewModel,
    onPickPdf: () -> Unit,
    onPickOutput: () -> Unit,
    onBack: () -> Unit,
) {
    var fullName by remember { mutableStateOf<String?>(null) }
    var planExpanded by remember { mutableStateOf(false) }
    var concurrencyHelp by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Palette.background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .imePadding(),
    ) {
        HeroHeader(state, onBack)

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Dimens.pagePadding),
            verticalArrangement = Arrangement.spacedBy(Dimens.cardGap),
        ) {
            Spacer(Modifier.height(2.dp))
            if (state.source == null) {
                EmptyState(
                    loading = state.loadingSource,
                    error = state.sourceError,
                    onPickPdf = onPickPdf,
                )
            } else {
                SourceCard(state, onPickPdf) { fullName = it }
                RuleCard(state, vm)

                if (state.run.error != null && state.run.phase != RunPhase.RUNNING) {
                    InfoCard(title = "上次运行") {
                        Text(
                            text = state.run.error.orEmpty(),
                            fontSize = 13.sp,
                            color = Palette.danger,
                        )
                    }
                }

                when {
                    state.run.phase == RunPhase.RUNNING || state.run.tasks.isNotEmpty() ->
                        TaskBoard(state.run)
                    state.run.phase == RunPhase.DONE -> ResultCard(state, vm)
                    else -> PlanCard(state, planExpanded) { planExpanded = !planExpanded }
                }

                OutputCard(state, vm, onPickOutput)
                Spacer(Modifier.height(6.dp))
            }
        }

        if (state.source != null) {
            BottomDock(state, vm) { concurrencyHelp = true }
        }
    }

    fullName?.let { name ->
        AlertDialog(
            onDismissRequest = { fullName = null },
            confirmButton = {
                TextButton(onClick = { fullName = null }) { Text("关闭") }
            },
            title = { Text("完整文件名", fontSize = 15.sp) },
            text = { Text(name, fontSize = 13.sp, fontFamily = FontFamily.Monospace) },
        )
    }

    if (concurrencyHelp) {
        AlertDialog(
            onDismissRequest = { concurrencyHelp = false },
            confirmButton = {
                TextButton(onClick = { concurrencyHelp = false }) { Text("知道了") }
            },
            title = { Text("同时处理几个文件", fontSize = 15.sp) },
            text = {
                Text(
                    text = "数字表示同一时间切分几个章节。数字越大通常越快，但每多一个就会多占一份 PDF 解析内存；" +
                        "大文件建议保持「自动」。本机上限 ${ConcurrencyPolicy.maxAllowed(state.cores)} 个。",
                    fontSize = 13.sp,
                    lineHeight = 20.sp,
                )
            },
        )
    }

    ResultPreviewDialog(state, vm)
}

// ── Hero ────────────────────────────────────────────────────────────────────

@Composable
private fun HeroHeader(state: SplitUiState, onBack: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(138.dp),
    ) {
        HeroDecoration(modifier = Modifier.matchParentSize())
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = Dimens.pagePadding, vertical = 6.dp),
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier.size(Dimens.touch).offset(x = (-10).dp),
            ) {
                Text(text = "‹", fontSize = 30.sp, color = Palette.textPrimary)
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "PDF 拆分",
                        fontSize = 27.sp,
                        fontWeight = FontWeight.Bold,
                        color = Palette.textPrimary,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "批量按页切分 · 多段同时进行",
                        fontSize = 12.sp,
                        color = Palette.textSecondary,
                    )
                }
                PageCountBadge(
                    text = state.source?.let { "${it.pageCount} 页" } ?: "— 页",
                )
            }
        }
    }
}

// ── 01 Source ───────────────────────────────────────────────────────────────

@Composable
private fun SourceCard(
    state: SplitUiState,
    onPickPdf: () -> Unit,
    onShowFullName: (String) -> Unit,
) {
    val source = state.source
    NumberedCard(
        number = "01",
        title = "源文件",
        trailing = {
            if (state.loadingSource) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = Palette.accent,
                )
            }
        },
    ) {
        InnerRow {
            PdfGlyph()
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = source?.name ?: "还没有选择 PDF",
                    modifier = Modifier.clickable { source?.let { onShowFullName(it.name) } },
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = Palette.textPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = buildString {
                        append("PDF")
                        if (source != null) {
                            append(" · ${source.pageCount} 页")
                            if (source.sizeBytes > 0) append(" · ${formatSize(source.sizeBytes)}")
                            when {
                                source.probing -> append(" · 读取中…")
                                source.hasOutline -> append(" · 有书签")
                                else -> append(" · 无书签")
                            }
                        }
                    },
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = Palette.textTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(10.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .border(1.dp, Palette.strokeStrong, RoundedCornerShape(8.dp))
                    .clickable(onClick = onPickPdf)
                    .padding(horizontal = 10.dp, vertical = 7.dp),
            ) {
                Text(
                    text = "⇄  更换",
                    fontSize = 12.sp,
                    color = Palette.textPrimary,
                )
            }
        }
        state.sourceError?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, fontSize = 12.sp, color = Palette.danger)
        }
    }
}

@Composable
private fun PdfGlyph() {
    Box(
        modifier = Modifier
            .size(width = 30.dp, height = 36.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(Color(0xFFD9534F)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "PDF",
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White,
        )
    }
}

// ── 02 Rules ────────────────────────────────────────────────────────────────

@Composable
private fun RuleCard(state: SplitUiState, vm: SplitViewModel) {
    val hint = when {
        state.source?.pageLabels != null -> "本书有印刷页码，请按 PDF 页序填写"
        state.source?.hasOutline == true -> "检测到书签，可一键按整本目录逐章拆"
        state.source?.probing == true -> "正在读取目录与文字层…"
        else -> "整理规则 · 预览命名"
    }
    NumberedCard(
        number = "02",
        title = "页码与名称",
        subtitle = hint,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedAction(
                text = "指定页码",
                selected = state.mode == SplitMode.PAGES,
                onClick = { vm.setMode(SplitMode.PAGES) },
                modifier = Modifier.weight(1f),
            )
            OutlinedAction(
                text = "每份页数",
                selected = state.mode == SplitMode.FIXED_SIZE,
                onClick = { vm.setMode(SplitMode.FIXED_SIZE) },
                modifier = Modifier.weight(1f),
            )
            OutlinedAction(
                text = "按目录",
                selected = state.mode == SplitMode.OUTLINE,
                onClick = { vm.setMode(SplitMode.OUTLINE) },
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedAction(
                text = "☰  按整本目录逐章拆",
                selected = state.mode == SplitMode.OUTLINE &&
                    state.rules.outline.source == OutlineSource.BOOKMARKS,
                onClick = vm::splitWholeBookByChapters,
                modifier = Modifier.weight(1.4f),
                enabled = !state.rules.outline.loading,
            )
            OutlinedAction(
                text = "读取书签",
                selected = false,
                onClick = vm::loadOutline,
                modifier = Modifier.weight(1f),
                enabled = !state.rules.outline.loading,
            )
        }

        if (state.rules.outline.loading) {
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                    color = Palette.accent,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (state.rules.outline.totalForScan > 0) {
                        "扫描 ${state.rules.outline.scanning}/${state.rules.outline.totalForScan} 页"
                    } else {
                        "正在读取整本目录…"
                    },
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    color = Palette.textSecondary,
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        when (state.mode) {
            SplitMode.PAGES -> PagesInput(state, vm)
            SplitMode.FIXED_SIZE -> FixedSizeInput(state, vm)
            SplitMode.OUTLINE -> OutlinePanel(state, vm)
        }
    }
}

@Composable
private fun PagesInput(state: SplitUiState, vm: SplitViewModel) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Dimens.innerRadius))
            .background(Palette.inner)
            .border(1.5.dp, Palette.accent.copy(alpha = 0.75f), RoundedCornerShape(Dimens.innerRadius))
            .padding(14.dp),
    ) {
        Column {
            Row(verticalAlignment = Alignment.Top) {
                BasicTextField(
                    value = state.rules.rangeText,
                    onValueChange = vm::setRangeText,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 112.dp),
                    textStyle = TextStyle(
                        fontSize = 14.sp,
                        fontFamily = FontFamily.Monospace,
                        lineHeight = 22.sp,
                        color = Palette.textPrimary,
                    ),
                    cursorBrush = SolidColor(Palette.accent),
                )
                Text(text = "⤢", fontSize = 15.sp, color = Palette.textTertiary)
            }
        }
    }
    Spacer(Modifier.height(8.dp))
    Text(
        text = "每行一段，只用 PDF 物理页序号；可写成「71-86 心肺听诊」，也支持先贴页码列、再贴名称列。",
        fontSize = 11.sp,
        lineHeight = 17.sp,
        color = Palette.textTertiary,
    )
    state.source?.pageLabels?.let {
        Spacer(Modifier.height(4.dp))
        Text(
            text = "本书有印刷页码，输入以「PDF 第 N 页」为准。",
            fontSize = 11.sp,
            color = Palette.warning,
        )
    }
    val errors = state.preview.errors
    if (errors.isNotEmpty()) {
        Spacer(Modifier.height(8.dp))
        errors.take(3).forEach { error ->
            Text(
                text = "第 ${error.line} 行：${error.message}",
                fontSize = 11.sp,
                color = Palette.danger,
            )
        }
        if (errors.size > 3) {
            Text("还有 ${errors.size - 3} 行有错误", fontSize = 11.sp, color = Palette.danger)
        }
    }
}

@Composable
private fun FixedSizeInput(state: SplitUiState, vm: SplitViewModel) {
    InnerRow {
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Palette.accent.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            Text("≡", fontSize = 14.sp, color = Palette.accent)
        }
        Spacer(Modifier.width(12.dp))
        Text("每份页数", fontSize = 13.sp, color = Palette.textPrimary)
        Spacer(Modifier.width(10.dp))
        CompactNumberField(
            value = state.rules.pagesPerPart,
            onValueChange = vm::setPagesPerPart,
            modifier = Modifier.width(72.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text("页一份", fontSize = 13.sp, color = Palette.textSecondary)
    }
    Spacer(Modifier.height(10.dp))
    val total = state.source?.pageCount ?: 0
    val per = state.rules.pagesPerPart.toIntOrNull() ?: 0
    if (total > 0 && per > 0) {
        val count = (total + per - 1) / per
        val last = total - (count - 1) * per
        Text(
            text = "整本 $total 页 → $count 个文件，最后一个 $last 页",
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            color = Palette.accent,
        )
    }
    Spacer(Modifier.height(6.dp))
    Text(
        text = "按固定页数切，不提供「均分成 N 份」——那是另一种规则。",
        fontSize = 11.sp,
        color = Palette.textTertiary,
    )
}

@Composable
private fun OutlinePanel(state: SplitUiState, vm: SplitViewModel) {
    val outline = state.rules.outline
    outline.message?.let { message ->
        Text(
            text = message,
            fontSize = 12.sp,
            lineHeight = 18.sp,
            color = if (outline.nodes.isEmpty()) Palette.warning else Palette.accent,
        )
        Spacer(Modifier.height(10.dp))
    }

    if (outline.nodes.isEmpty()) {
        OutlinedAction(
            text = "▣  扫描正文标题",
            selected = false,
            onClick = vm::scanTextHeadings,
            enabled = !outline.loading,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "纯图片扫描件没有文字层，扫描标题需要 OCR，本版本未包含。",
            fontSize = 11.sp,
            color = Palette.textTertiary,
        )
        return
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("显示到", fontSize = 12.sp, color = Palette.textTertiary)
        Spacer(Modifier.width(8.dp))
        (1..outline.maxDepth).forEach { depth ->
            val active = depth == outline.depth
            Box(
                modifier = Modifier
                    .padding(end = 6.dp)
                    .size(width = 34.dp, height = 30.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (active) Palette.accent else Palette.inner)
                    .border(1.dp, if (active) Palette.accent else Palette.cardStroke, RoundedCornerShape(8.dp))
                    .clickable { vm.setDepth(depth) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "$depth 级",
                    fontSize = 10.sp,
                    color = if (active) Palette.onAccent else Palette.textSecondary,
                )
            }
        }
        Spacer(Modifier.weight(1f))
        TextButton(onClick = vm::selectAllAtDepth) {
            Text("全选本级", fontSize = 12.sp, color = Palette.accent)
        }
    }

    Spacer(Modifier.height(8.dp))
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 260.dp)
            .verticalScroll(rememberScrollState())
            .clip(RoundedCornerShape(Dimens.innerRadius))
            .background(Palette.inner)
            .padding(horizontal = 6.dp, vertical = 4.dp),
    ) {
        outline.nodes.filter { it.depth <= outline.depth }.forEach { node ->
            OutlineRow(
                node = node,
                checked = node.index in outline.selected,
                onToggle = { vm.toggleChapter(node.index) },
            )
        }
    }
    Spacer(Modifier.height(6.dp))
    Text(
        text = "已选 ${outline.selected.size} 个章节；父子章节互斥，避免同一页被写进两个文件。",
        fontSize = 11.sp,
        color = Palette.textTertiary,
    )
}

@Composable
private fun OutlineRow(node: OutlineNode, checked: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 42.dp)
            .clickable(onClick = onToggle)
            .padding(start = ((node.depth - 1) * 12).dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = { onToggle() },
            colors = CheckboxDefaults.colors(
                checkedColor = Palette.accent,
                uncheckedColor = Palette.strokeStrong,
                checkmarkColor = Palette.onAccent,
            ),
        )
        Text(
            text = node.title,
            modifier = Modifier.weight(1f),
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = if (checked) Palette.textPrimary else Palette.textSecondary,
        )
        Text(
            text = "p${node.startPage1}",
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            color = Palette.textTertiary,
        )
        Spacer(Modifier.width(8.dp))
    }
}

// ── 03 Output ───────────────────────────────────────────────────────────────

@Composable
private fun OutputCard(
    state: SplitUiState,
    vm: SplitViewModel,
    onPickOutput: () -> Unit,
) {
    NumberedCard(number = "03", title = "输出", subtitle = "保存位置 · 校验强度") {
        InnerRow(onClick = onPickOutput) {
            FolderGlyph()
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("保存目录", fontSize = 13.sp, color = Palette.textPrimary)
                Spacer(Modifier.height(3.dp))
                Text(
                    text = state.outputLabel ?: "未选择",
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (state.outputTreeUri == null) {
                        Palette.textTertiary
                    } else {
                        Palette.textSecondary
                    },
                )
            }
            if (state.preview.parts.isNotEmpty()) {
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "示例：${state.preview.parts.first().outputName}",
                    modifier = Modifier.width(90.dp),
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = Palette.textTertiary,
                )
            }
            Text("›", fontSize = 22.sp, color = Palette.textTertiary)
        }

        Spacer(Modifier.height(10.dp))
        InnerRow {
            ShieldGlyph(enabled = state.deepVerify)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("深度校验", fontSize = 13.sp, color = Palette.textPrimary)
                Spacer(Modifier.height(3.dp))
                Text(
                    text = if (state.deepVerify) {
                        "落盘后回读并逐页比对内容，慢一些但更保险"
                    } else {
                        "只校验页数与页面尺寸（默认，快）"
                    },
                    fontSize = 11.sp,
                    color = Palette.textTertiary,
                )
            }
            Switch(
                checked = state.deepVerify,
                onCheckedChange = vm::setDeepVerify,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Palette.onAccent,
                    checkedTrackColor = Palette.accent,
                ),
            )
        }

        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp)
                .clickable(onClick = vm::toggleAdvanced),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("高级设置", fontSize = 12.sp, color = Palette.textSecondary)
            Spacer(Modifier.weight(1f))
            Text(
                text = if (state.advancedExpanded) "收起" else "展开",
                fontSize = 12.sp,
                color = Palette.textTertiary,
            )
        }
        if (state.advancedExpanded) {
            Text(
                text = "同时处理 ${state.effectiveConcurrency} 个文件" +
                    (if (state.concurrencyAuto) "（自动，可在底部数字调整）" else "（手动设定）"),
                fontSize = 12.sp,
                color = Palette.textSecondary,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "每个同时处理的任务都会额外加载一份 PDF 解析结构，" +
                    "本机上限 ${ConcurrencyPolicy.maxAllowed(state.cores)} 个。",
                fontSize = 11.sp,
                lineHeight = 17.sp,
                color = Palette.textTertiary,
            )
            if (!state.concurrencyAuto) {
                Spacer(Modifier.height(6.dp))
                TextButton(onClick = { vm.setConcurrencyAuto(true) }) {
                    Text("恢复自动", fontSize = 12.sp, color = Palette.accent)
                }
            }
        }
    }
}

@Composable
private fun FolderGlyph() {
    Box(modifier = Modifier.size(24.dp), contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .size(width = 19.dp, height = 15.dp)
                .offset(y = 2.dp)
                .clip(RoundedCornerShape(3.dp))
                .border(1.4.dp, Palette.textSecondary, RoundedCornerShape(3.dp)),
        )
        Box(
            modifier = Modifier
                .size(width = 8.dp, height = 4.dp)
                .offset(x = (-5).dp, y = (-6).dp)
                .clip(RoundedCornerShape(2.dp))
                .background(Palette.textSecondary),
        )
    }
}

@Composable
private fun ShieldGlyph(enabled: Boolean) {
    val color = if (enabled) Palette.accent else Palette.textSecondary
    Box(
        modifier = Modifier
            .size(24.dp)
            .clip(RoundedCornerShape(7.dp))
            .border(1.4.dp, color, RoundedCornerShape(7.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text("✓", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = color)
    }
}

// ── Dynamic information cards ───────────────────────────────────────────────

@Composable
private fun InfoCard(
    title: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Dimens.cardRadius))
            .background(Palette.card)
            .border(1.dp, Palette.cardStroke, RoundedCornerShape(Dimens.cardRadius))
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = title,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = Palette.textPrimary,
            )
            Spacer(Modifier.weight(1f))
            trailing?.invoke()
        }
        Spacer(Modifier.height(12.dp))
        content()
    }
}

@Composable
private fun PlanCard(state: SplitUiState, expanded: Boolean, onToggleExpand: () -> Unit) {
    val preview = state.preview
    InfoCard(
        title = "拆分计划",
        trailing = {
            if (preview.parts.size > 3) {
                TextButton(onClick = onToggleExpand) {
                    Text(
                        text = if (expanded) "收起" else "展开全部 ${preview.parts.size} 项",
                        fontSize = 12.sp,
                        color = Palette.accent,
                    )
                }
            }
        },
    ) {
        if (preview.parts.isEmpty()) {
            Text(
                text = preview.blockedReason ?: "还没有可拆分的区间",
                fontSize = 13.sp,
                color = Palette.textTertiary,
            )
            return@InfoCard
        }
        Text(
            text = buildString {
                append("${preview.fileCount} 个文件")
                append(" · 覆盖 ${preview.coveredPages} 页")
                if (preview.uncoveredPages > 0) append(" · 未包含 ${preview.uncoveredPages} 页")
            },
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = Palette.accent,
        )

        val notes = ArrayList<String>()
        if (preview.overlaps.isNotEmpty()) {
            val first = preview.overlaps.first()
            notes += "第 ${first.lines.joinToString("、")} 行重叠了 ${first.startPage1}-${first.endPage1} 页"
        }
        if (preview.uncovered.isNotEmpty()) {
            val first = preview.uncovered.first()
            notes += "未包含 " + if (first.first == first.last) {
                "${first.first} 页"
            } else {
                "${first.first}-${first.last} 页"
            }
        }
        if (preview.renamedInBatch > 0) notes += "${preview.renamedInBatch} 个名称重复，已自动加序号"
        if (preview.renamedByFolder > 0) notes += "${preview.renamedByFolder} 个与目录已有文件重名，已改名"
        notes += preview.notes
        notes.forEach { note ->
            Spacer(Modifier.height(6.dp))
            Text(note, fontSize = 11.sp, color = Palette.warning)
        }

        Spacer(Modifier.height(10.dp))
        val shown = if (expanded) preview.parts else preview.parts.take(3)
        shown.forEach { part -> PreviewRow(part) }
        if (!expanded && preview.parts.size > 3) {
            Text(
                text = "还有 ${preview.parts.size - 3} 个…",
                fontSize = 11.sp,
                color = Palette.textTertiary,
            )
        }
        if (preview.uncovered.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = "未包含的页不会写进任何文件（你可能只想拆部分章节）。",
                fontSize = 11.sp,
                color = Palette.textTertiary,
            )
        }
    }
}

@Composable
private fun PreviewRow(part: SplitPart) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (part.startPage1 == part.endPage1) {
                "P${part.startPage1}"
            } else {
                "P${part.startPage1}-${part.endPage1}"
            },
            modifier = Modifier.width(84.dp),
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            color = Palette.accent,
        )
        Text(
            text = part.outputName,
            modifier = Modifier.weight(1f),
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = Palette.textSecondary,
        )
        Text(
            text = "${part.pageCount}p",
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            color = Palette.textTertiary,
        )
    }
}

@Composable
private fun TaskBoard(run: RunState) {
    InfoCard(
        title = "进度",
        trailing = {
            Text(
                text = "${(run.fraction * 100).toInt()}%",
                fontSize = 14.sp,
                fontFamily = FontFamily.Monospace,
                color = Palette.accent,
            )
        },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = buildString {
                    append(run.headline.ifBlank { "拆分中" })
                    if (run.total > 0) append("  ${run.finished}/${run.total}")
                },
                modifier = Modifier.weight(1f),
                fontSize = 13.sp,
                color = Palette.textPrimary,
            )
        }
        Spacer(Modifier.height(8.dp))
        ProgressBar(run.fraction)
        if (run.detail.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = run.detail,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = Palette.textTertiary,
            )
        }
        if (run.tasks.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 220.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                run.tasks.forEach { task -> TaskRow(task) }
            }
        }
        run.error?.let {
            Spacer(Modifier.height(10.dp))
            Text(it, fontSize = 12.sp, color = Palette.danger)
        }
    }
}

@Composable
private fun TaskRow(task: TaskSnapshot) {
    val done = task.stage == TaskStage.DONE
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "${task.startPage1}-${task.endPage1}",
                modifier = Modifier.width(70.dp),
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = if (done) Palette.accent else Palette.textSecondary,
            )
            Text(
                text = task.name,
                modifier = Modifier.weight(1f),
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = Palette.textTertiary,
            )
            Text(
                text = when (task.stage) {
                    TaskStage.QUEUED -> "等待"
                    TaskStage.WRITING -> "${task.pageInPart}/${task.pageCount}"
                    TaskStage.CHECKING -> "校验"
                    TaskStage.SAVING -> "写入"
                    TaskStage.DONE -> "完成"
                },
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                color = when (task.stage) {
                    TaskStage.DONE -> Palette.accent
                    TaskStage.QUEUED -> Palette.textTertiary
                    else -> Palette.textSecondary
                },
            )
        }
        Spacer(Modifier.height(4.dp))
        ProgressBar(
            fraction = task.fraction,
            height = 3.dp,
            dimmed = done || task.stage == TaskStage.QUEUED,
        )
    }
}

@Composable
private fun ProgressBar(fraction: Float, height: Dp = 6.dp, dimmed: Boolean = false) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(999.dp))
            .background(Palette.track),
    ) {
        val safe = fraction.coerceIn(0f, 1f)
        if (safe > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(safe)
                    .height(height)
                    .clip(RoundedCornerShape(999.dp))
                    .background(
                        Brush.horizontalGradient(
                            if (dimmed) {
                                listOf(
                                    Palette.accentDim.copy(alpha = 0.4f),
                                    Palette.accentDim.copy(alpha = 0.4f),
                                )
                            } else {
                                listOf(Palette.accentDim, Palette.accent)
                            },
                        ),
                    ),
            )
        }
    }
}

// ── Post-split result + visual verification ─────────────────────────────────

@Composable
private fun ResultCard(state: SplitUiState, vm: SplitViewModel) {
    val run = state.run
    InfoCard(
        title = "拆分完成",
        trailing = {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(Palette.accent.copy(alpha = 0.12f))
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            ) {
                Text(
                    text = "${run.outputs.size} 个文件",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = Palette.accent,
                )
            }
        },
    ) {
        if (state.outputLabel != null) {
            Text(
                text = "保存在 ${state.outputLabel}",
                fontSize = 11.sp,
                color = Palette.textTertiary,
            )
            Spacer(Modifier.height(8.dp))
        }
        Text(
            text = "点「预览」看每个文件的第一页和最后一页，确认章节切得对不对。",
            fontSize = 11.sp,
            lineHeight = 17.sp,
            color = Palette.textSecondary,
        )
        Spacer(Modifier.height(10.dp))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 300.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            run.outputs.forEach { output -> ResultRow(output, vm) }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = "目录里已有同名文件时，会显示为 _2、_3 后缀。",
            fontSize = 11.sp,
            color = Palette.textTertiary,
        )
    }
}

@Composable
private fun ResultRow(output: SplitOutput, vm: SplitViewModel) {
    InnerRow {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = output.displayName,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = Palette.textPrimary,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "PDF 第 ${output.startPage1}-${output.endPage1} 页 · 共 ${output.pageCount} 页",
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = Palette.accent,
            )
        }
        Spacer(Modifier.width(8.dp))
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .border(1.dp, Palette.accent.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                .clickable { vm.openResultPreview(output) }
                .padding(horizontal = 12.dp, vertical = 7.dp),
        ) {
            Text("预览", fontSize = 12.sp, color = Palette.accent)
        }
    }
}

@Composable
private fun ResultPreviewDialog(state: SplitUiState, vm: SplitViewModel) {
    val preview = state.resultPreview
    val output = preview.output ?: return
    val context = LocalContext.current

    AlertDialog(
        onDismissRequest = vm::closeResultPreview,
        confirmButton = {
            TextButton(onClick = vm::closeResultPreview) { Text("关闭") }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(output.uri, "application/pdf")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    runCatching { context.startActivity(intent) }
                },
            ) { Text("用其他应用打开") }
        },
        title = {
            Text(
                text = output.displayName,
                fontSize = 14.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 470.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = buildString {
                        append("PDF 第 ${output.startPage1}-${output.endPage1} 页")
                        append(" · 共 ${output.pageCount} 页")
                        if (preview.sizeBytes > 0) append(" · ${formatSize(preview.sizeBytes)}")
                    },
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = Palette.textSecondary,
                )
                Spacer(Modifier.height(12.dp))
                when {
                    preview.loading -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = Palette.accent,
                            )
                            Spacer(Modifier.width(10.dp))
                            Text("正在读取页画面…", fontSize = 12.sp, color = Palette.textSecondary)
                        }
                    }
                    preview.error != null -> {
                        Text(preview.error, fontSize = 12.sp, color = Palette.danger)
                    }
                    else -> {
                        PageThumb("第一页 · 章节开头", preview.firstPage)
                        if (preview.lastPage != null) {
                            PageThumb("最后一页 · 第 ${output.pageCount} 页", preview.lastPage)
                        }
                        Text(
                            text = "对照首尾两页即可确认：起始页是不是章节首页、末尾有没有多切到下一章。",
                            fontSize = 11.sp,
                            lineHeight = 17.sp,
                            color = Palette.textTertiary,
                        )
                    }
                }
            }
        },
    )
}

@Composable
private fun PageThumb(label: String, bitmap: Bitmap?) {
    Text(label, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = Palette.textPrimary)
    Spacer(Modifier.height(6.dp))
    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = label,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(bitmap.width.toFloat() / bitmap.height.toFloat())
                .clip(RoundedCornerShape(8.dp))
                .background(Color.White)
                .border(1.dp, Palette.cardStroke, RoundedCornerShape(8.dp)),
            contentScale = ContentScale.FillBounds,
        )
    } else {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(90.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Palette.inner),
            contentAlignment = Alignment.Center,
        ) {
            Text("这一页无法渲染", fontSize = 11.sp, color = Palette.textTertiary)
        }
    }
    Spacer(Modifier.height(14.dp))
}

// ── Bottom dock ─────────────────────────────────────────────────────────────

@Composable
private fun BottomDock(state: SplitUiState, vm: SplitViewModel, onHelp: () -> Unit) {
    val running = state.run.phase == RunPhase.RUNNING
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Palette.surface)
            .padding(horizontal = Dimens.pagePadding, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(width = 7.dp, height = 14.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Palette.accent),
            )
            Spacer(Modifier.width(8.dp))
            Text("同时处理", fontSize = 12.sp, color = Palette.textSecondary)
            Spacer(Modifier.width(4.dp))
            Text(
                text = "(?)",
                fontSize = 11.sp,
                color = Palette.accent,
                modifier = Modifier.clickable(onClick = onHelp),
            )
            Spacer(Modifier.weight(1f))
            val maxAllowed = ConcurrencyPolicy.maxAllowed(state.cores)
            listOf(1, 2, 3, 4, 6).filter { it <= maxAllowed }.forEach { value ->
                DockNumberChip(
                    value = value,
                    selected = !state.concurrencyAuto && state.concurrencyManual == value,
                    onClick = { vm.setConcurrencyManual(value) },
                )
                Spacer(Modifier.width(4.dp))
            }
            Text(
                text = if (state.concurrencyAuto) {
                    "自动 ${state.effectiveConcurrency}"
                } else {
                    "${state.effectiveConcurrency} 个"
                },
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = Palette.accent,
            )
        }

        Spacer(Modifier.height(10.dp))
        if (!running) {
            Text(
                text = when {
                    state.cleaningUp -> "已取消，正在清理未完成的文件…"
                    state.run.phase == RunPhase.DONE -> "已生成 ${state.run.outputs.size} 个文件，可逐项预览"
                    else -> state.blockedReason
                        ?: "预计生成 ${state.preview.fileCount} 个 PDF"
                },
                fontSize = 11.sp,
                color = if (state.blockedReason != null) Palette.warning else Palette.textSecondary,
            )
            Spacer(Modifier.height(8.dp))
        }
        PrimaryActionButton(
            text = when {
                running -> "取消"
                state.run.phase == RunPhase.DONE -> "再拆一次"
                else -> "开始拆分"
            },
            onClick = { if (running) vm.cancel() else vm.start() },
            enabled = running || state.canStart,
            running = running,
        )
    }
}

// ── Small helpers ───────────────────────────────────────────────────────────

@Composable
private fun EmptyState(loading: Boolean, error: String?, onPickPdf: () -> Unit) {
    NumberedCard(number = "01", title = "先选一本 PDF") {
        Text(
            text = "支持按页码、每份页数或整本目录逐章拆，多个章节可以同时处理。",
            fontSize = 13.sp,
            lineHeight = 20.sp,
            color = Palette.textSecondary,
        )
        error?.let {
            Spacer(Modifier.height(10.dp))
            Text(it, fontSize = 12.sp, color = Palette.danger)
        }
        Spacer(Modifier.height(18.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Palette.accent)
                .clickable(enabled = !loading, onClick = onPickPdf),
            contentAlignment = Alignment.Center,
        ) {
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = Palette.onAccent,
                )
            } else {
                Text(
                    text = "▶  选择 PDF",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Palette.onAccent,
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        Text(
            text = "也可以从文件管理器、微信里用「打开方式」直接把 PDF 送到这里。",
            fontSize = 11.sp,
            lineHeight = 17.sp,
            color = Palette.textTertiary,
        )
    }
}

@Composable
private fun CompactNumberField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(Dimens.controlRadius))
            .background(Palette.inner)
            .border(1.dp, Palette.strokeStrong, RoundedCornerShape(Dimens.controlRadius))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicTextField(
            value = value,
            onValueChange = { onValueChange(it.filter(Char::isDigit).take(5)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            textStyle = TextStyle(
                fontSize = 14.sp,
                fontFamily = FontFamily.Monospace,
                color = Palette.textPrimary,
            ),
            cursorBrush = SolidColor(Palette.accent),
        )
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1024L * 1024L * 1024L -> String.format("%.1f GB", bytes / 1073741824.0)
    bytes >= 1024L * 1024L -> String.format("%.0f MB", bytes / 1048576.0)
    bytes >= 1024L -> String.format("%.0f KB", bytes / 1024.0)
    else -> "$bytes B"
}
