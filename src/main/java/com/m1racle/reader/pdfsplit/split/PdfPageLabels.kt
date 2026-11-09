package com.m1racle.reader.pdfsplit.split

import android.content.Context
import android.net.Uri
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSNumber
import com.tom_roush.pdfbox.pdmodel.PDDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Printed page labels (`/PageLabels`), which often differ from the physical page
 * order — a book whose page 1 is physical page 9 still prints "1" on it.
 *
 * Splitting always addresses physical pages; labels exist only so the UI can
 * show both numbers without ever mixing them up.
 */
object PdfPageLabels {

    /**
     * @return one label per physical page (index 0 = physical page 1), or null
     * when the document defines no page labels at all.
     */
    suspend fun read(context: Context, uri: Uri): List<String>? =
        withContext(Dispatchers.IO) {
            val staged = PdfBoxSetup.stageSource(context, uri) { _, _ -> }
            try {
                PdfBoxSetup.load(staged.file).use { document -> extract(document) }
            } catch (error: Exception) {
                null
            } finally {
                if (staged.temporary) staged.file.delete()
            }
        }

    internal fun extract(document: PDDocument): List<String>? {
        val catalog = document.documentCatalog.cosObject
        val labelsDictionary = catalog.getDictionaryObject(COSName.PAGE_LABELS) as? COSDictionary
            ?: return null
        val numbers = labelsDictionary.getDictionaryObject(COSName.NUMS) as? COSArray
            ?: return null
        if (numbers.size() < 2) return null

        val ranges = ArrayList<LabelRange>()
        var index = 0
        while (index + 1 < numbers.size()) {
            val startPage = (numbers.getObject(index) as? COSNumber)?.intValue() ?: break
            val dictionary = numbers.getObject(index + 1) as? COSDictionary ?: break
            ranges += LabelRange(
                fromPage0 = startPage,
                style = dictionary.getNameAsString(COSName.S),
                prefix = dictionary.getString(COSName.P).orEmpty(),
                startNumber = if (dictionary.containsKey(COSName.ST)) {
                    dictionary.getInt(COSName.ST, 1)
                } else {
                    1
                },
            )
            index += 2
        }
        if (ranges.isEmpty()) return null
        ranges.sortBy { it.fromPage0 }

        val totalPages = document.numberOfPages
        if (totalPages <= 0) return null
        return (0 until totalPages).map { page0 ->
            val range = ranges.lastOrNull { it.fromPage0 <= page0 } ?: return@map ""
            val ordinal = range.startNumber + (page0 - range.fromPage0)
            range.prefix + formatNumber(range.style, ordinal)
        }
    }

    internal fun formatNumber(style: String?, value: Int): String = when {
        style == null -> ""
        style == "D" -> value.toString()
        style == "R" -> toRoman(value)
        style == "r" -> toRoman(value).lowercase()
        style == "A" -> toLetters(value)
        style == "a" -> toLetters(value).lowercase()
        else -> value.toString()
    }

    internal fun toRoman(value: Int): String {
        if (value <= 0 || value >= 4000) return value.toString()
        val table = listOf(
            1000 to "M", 900 to "CM", 500 to "D", 400 to "CD",
            100 to "C", 90 to "XC", 50 to "L", 40 to "XL",
            10 to "X", 9 to "IX", 5 to "V", 4 to "IV", 1 to "I",
        )
        var remaining = value
        val builder = StringBuilder()
        table.forEach { (unit, symbol) ->
            while (remaining >= unit) {
                builder.append(symbol)
                remaining -= unit
            }
        }
        return builder.toString()
    }

    /** 1 → A, 26 → Z, 27 → AA. */
    internal fun toLetters(value: Int): String {
        if (value <= 0) return value.toString()
        var remaining = value
        val builder = StringBuilder()
        while (remaining > 0) {
            val offset = (remaining - 1) % 26
            builder.insert(0, ('A' + offset))
            remaining = (remaining - 1) / 26
        }
        return builder.toString()
    }

    private data class LabelRange(
        val fromPage0: Int,
        val style: String?,
        val prefix: String,
        val startNumber: Int,
    )
}
