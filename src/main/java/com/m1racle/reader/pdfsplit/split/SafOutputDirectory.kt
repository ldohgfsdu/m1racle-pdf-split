package com.m1racle.reader.pdfsplit.split

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/** Fails closed: an unknown directory content list cannot authorize a new write. */
object SafOutputDirectory {

    suspend fun listExistingNames(context: Context, treeUri: Uri): Set<String> =
        withContext(Dispatchers.IO) {
            val resolver = context.applicationContext.contentResolver
            val childrenUri = try {
                DocumentsContract.buildChildDocumentsUriUsingTree(
                    treeUri,
                    DocumentsContract.getTreeDocumentId(treeUri),
                )
            } catch (error: Exception) {
                throw IOException("保存目录已失效，请重新选择目录", error)
            }
            try {
                val names = HashSet<String>()
                val cursor = resolver.query(
                    childrenUri,
                    arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                    null,
                    null,
                    null,
                ) ?: throw IOException("无法读取保存目录，请重新选择目录")
                cursor.use {
                    while (it.moveToNext()) {
                        it.getString(0)?.let { name -> names += name.lowercase() }
                    }
                }
                names
            } catch (error: IOException) {
                throw error
            } catch (error: Exception) {
                throw IOException("无法读取保存目录，请检查权限或重新选择", error)
            }
        }
