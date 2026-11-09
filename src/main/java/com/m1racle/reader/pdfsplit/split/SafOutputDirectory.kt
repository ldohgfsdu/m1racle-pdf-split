package com.m1racle.reader.pdfsplit.split

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Reads what is already in the chosen output folder, so names can be planned. */
object SafOutputDirectory {

    suspend fun listExistingNames(context: Context, treeUri: Uri): Set<String> =
        withContext(Dispatchers.IO) {
            val resolver = context.applicationContext.contentResolver
            val names = HashSet<String>()
            val childrenUri = runCatching {
                DocumentsContract.buildChildDocumentsUriUsingTree(
                    treeUri,
                    DocumentsContract.getTreeDocumentId(treeUri),
                )
            }.getOrNull() ?: return@withContext names

            runCatching {
                resolver.query(
                    childrenUri,
                    arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                    null,
                    null,
                    null,
                )?.use { cursor ->
                    while (cursor.moveToNext()) {
                        cursor.getString(0)?.let { names += it.lowercase() }
                    }
                }
            }
            names
        }
}
