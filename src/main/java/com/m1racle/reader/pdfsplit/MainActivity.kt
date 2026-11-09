package com.m1racle.reader.pdfsplit

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import com.m1racle.reader.pdfsplit.ui.PdfSplitTheme
import com.m1racle.reader.pdfsplit.ui.SplitScreen
import com.m1racle.reader.pdfsplit.ui.SplitViewModel

/**
 * Thin shell: intent entry, SAF launchers and the screen. All splitting state
 * lives in [SplitViewModel] so it survives rotation and process recreation.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val incoming = intent?.let(::incomingPdfUri)

        setContent {
            PdfSplitTheme {
                val vm: SplitViewModel = viewModel()
                val state by vm.state.collectAsState()

                val pickPdf = rememberLauncherForActivityResult(
                    ActivityResultContracts.OpenDocument(),
                ) { uri ->
                    if (uri != null) {
                        runCatching {
                            contentResolver.takePersistableUriPermission(
                                uri,
                                Intent.FLAG_GRANT_READ_URI_PERMISSION,
                            )
                        }
                        vm.onSourcePicked(uri)
                    }
                }

                val pickOutput = rememberLauncherForActivityResult(
                    ActivityResultContracts.OpenDocumentTree(),
                ) { uri ->
                    if (uri != null) {
                        runCatching {
                            contentResolver.takePersistableUriPermission(
                                uri,
                                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                            )
                        }
                        vm.setOutputDirectory(uri, treeLabel(uri))
                    }
                }

                LaunchedEffect(incoming) {
                    if (incoming != null && state.source?.uri != incoming) {
                        vm.onSourcePicked(incoming)
                    }
                }

                SplitScreen(
                    state = state,
                    vm = vm,
                    onPickPdf = { pickPdf.launch(arrayOf("application/pdf")) },
                    onPickOutput = { pickOutput.launch(null) },
                    onBack = { finish() },
                )
            }
        }
    }

    private fun incomingPdfUri(intent: Intent): Uri? = when (intent.action) {
        Intent.ACTION_VIEW -> intent.data
        Intent.ACTION_SEND -> extractStream(intent)
        else -> null
    }

    @Suppress("DEPRECATION")
    private fun extractStream(intent: Intent): Uri? =
        intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri

    private fun treeLabel(uri: Uri): String {
        val documentId = runCatching {
            DocumentsContract.getTreeDocumentId(uri)
        }.getOrNull() ?: return uri.toString()
        return documentId.substringAfter(':', documentId)
    }
}
