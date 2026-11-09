package com.m1racle.reader.pdfsplit.split

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.provider.OpenableColumns
import kotlin.math.roundToInt

/**
 * Reads back a file this tool has just written, so the user can verify the cut
 * instead of trusting a "done" label. Rendering uses the platform PdfRenderer;
 * no PDFBox object escapes the split engine.
 */
object OutputPreview {

    fun fileSize(context: Context, uri: Uri): Long = runCatching {
        context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.SIZE),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else 0L
        } ?: 0L
    }.getOrDefault(0L)

    /** Renders one physical page into a white-backed bitmap, or null on any failure. */
    fun renderPage(
        context: Context,
        uri: Uri,
        pageIndex: Int,
        targetWidth: Int = 640,
    ): Bitmap? {
        val descriptor = runCatching {
            context.contentResolver.openFileDescriptor(uri, "r")
        }.getOrNull() ?: return null

        return runCatching {
            descriptor.use { fd ->
                PdfRenderer(fd).use { renderer ->
                    if (pageIndex < 0 || pageIndex >= renderer.pageCount) return null
                    val page = renderer.openPage(pageIndex)
                    try {
                        val width = targetWidth.coerceIn(64, 1280)
                        val scale = width.toFloat() / page.width.coerceAtLeast(1)
                        val height = (page.height * scale).roundToInt().coerceIn(64, 1800)
                        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                        bitmap.eraseColor(Color.WHITE)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        bitmap
                    } finally {
                        page.close()
                    }
                }
            }
        }.getOrNull()
    }
}
