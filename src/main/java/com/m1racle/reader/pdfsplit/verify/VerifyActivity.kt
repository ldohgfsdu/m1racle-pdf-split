package com.m1racle.reader.pdfsplit.verify

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.m1racle.reader.pdfsplit.ui.PdfSplitTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * A1 正确性验证台。
 *
 * 独立入口，不进入正常拆分流程：对同一份 PDF 分别用 lenient（生产）和
 * strict（候选快速路径）各拆一遍，然后逐页比对落盘产物的几何、内容流、
 * 资源、注释、链接、文本层，并列出 strict 侧不可解析的对象。
 */
class VerifyActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PdfSplitTheme { VerifyScreen() }
        }
    }
}

private data class VerifyUiState(
    val sourceUri: Uri? = null,
    val outputTreeUri: Uri? = null,
    val renderCompare: Boolean = false,
    val renderDpi: Int = 72,
    val partPages: Int = 20,
    val running: Boolean = false,
    val log: List<String> = emptyList(),
    val error: String? = null,
    val report: VerifyReport? = null,
)

@Composable
private fun VerifyScreen() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf(VerifyUiState()) }
    var job by remember { mutableStateOf<Job?>(null) }

    val pickPdf = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            state = state.copy(sourceUri = uri, report = null, error = null)
        }
    }
    val pickOutput = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            state = state.copy(outputTreeUri = uri, report = null, error = null)
        }
    }

    fun append(line: String) {
        state = state.copy(log = (state.log + line).takeLast(400))
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "A1 正确性验证",
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "同一份 PDF，lenient（生产）与 strict（候选快速加载）各拆一遍，" +
                    "逐页比对落盘产物。只做验证，不改生产加载策略。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp,
                lineHeight = 19.sp,
            )

            PickerRow(
                title = "源 PDF",
                value = state.sourceUri?.lastPathSegment ?: "未选择",
                action = "选择",
                enabled = !state.running,
                onClick = { pickPdf.launch(arrayOf("application/pdf")) },
            )
            PickerRow(
                title = "输出目录",
                value = state.outputTreeUri?.lastPathSegment ?: "未选择（L-/S- 两套产物写这里）",
                action = "选择",
                enabled = !state.running,
                onClick = { pickOutput.launch(null) },
            )

            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "渲染像素比对（PDFBox 自渲染，较慢）",
                        color = MaterialTheme.colorScheme.onBackground,
                        fontSize = 14.sp,
                    )
                    Text(
                        text = "默认关闭；独立渲染器的全量像素比对在电脑上做",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                    )
                }
                Switch(
                    checked = state.renderCompare,
                    enabled = !state.running,
                    onCheckedChange = { state = state.copy(renderCompare = it) },
                )
            }
            Text(
                text = "分块：每 ${state.partPages} 页一段；DPI：${state.renderDpi}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    enabled = !state.running && state.sourceUri != null && state.outputTreeUri != null,
                    onClick = {
                        val source = state.sourceUri
                        val tree = state.outputTreeUri
                        if (source == null || tree == null) return@Button
                        state = state.copy(running = true, log = emptyList(), error = null, report = null)
                        job = scope.launch {
                            try {
                                val result = SplitVerifier(context).verify(
                                    sourceUri = source,
                                    outputTreeUri = tree,
                                    partPages = state.partPages,
                                    renderCompare = state.renderCompare,
                                    renderDpi = state.renderDpi,
                                    onLog = { line -> append(line) },
                                )
                                state = state.copy(report = result)
                            } catch (cancellation: CancellationException) {
                                append("已取消")
                            } catch (failure: Throwable) {
                                state = state.copy(
                                    error = "${failure.javaClass.simpleName}: ${failure.message}",
                                )
                            } finally {
                                state = state.copy(running = false)
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                ) {
                    Text(if (state.running) "验证中…" else "开始验证")
                }
                if (state.running) {
                    Button(
                        onClick = { job?.cancel() },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                    ) { Text("取消") }
                }
            }

            state.error?.let { message ->
                Text(
                    text = "失败：$message",
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 13.sp,
                )
            }

            state.report?.let { report ->
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = "结果",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = "比对 ${report.comparedPages} 页 / 一致 ${report.identicalPages} 页 / " +
                                "差异 ${report.diffPages} 页 / 证据不足 ${report.weakPages} 页",
                            color = MaterialTheme.colorScheme.onBackground,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = "strict 拆分失败=${report.strictSplitFailed}；" +
                                "strict 侧不可解析资源=${report.findings} 条",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 13.sp,
                        )
                        Text(
                            text = "报告：${report.reportUri ?: "仅本地"}\n本地副本：${report.localReportPath}",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                        )
                    }
                }
            }

            if (state.log.isNotEmpty()) {
                Text(
                    text = "运行日志",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = state.log.joinToString("\n"),
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier
                            .background(MaterialTheme.colorScheme.surface)
                            .padding(12.dp),
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun PickerRow(
    title: String,
    value: String,
    action: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
            )
            Text(
                text = value,
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 14.sp,
            )
        }
        Button(
            onClick = onClick,
            enabled = enabled,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
        ) { Text(action, fontSize = 13.sp) }
    }
}
