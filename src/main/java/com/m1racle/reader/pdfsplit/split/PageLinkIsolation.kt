package com.m1racle.reader.pdfsplit.split

import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionGoTo
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotation
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDNamedDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageDestination
import java.io.IOException
import java.util.IdentityHashMap

/**
 * Breaks annotation page-tree references inherited from [PDDocument.importPage].
 *
 * Without this, an extracted part keeps link annotations pointing at the source
 * document's page tree, so tapping an internal link either jumps to the wrong
 * page or makes the part unreadable. Every annotation dictionary and `/Annots`
 * array is cloned before its `/P` entry or link destination is rewritten; the
 * source document is never mutated.
 */
internal object PageLinkIsolation {

    @Throws(IOException::class)
    fun isolate(
        sourceDoc: PDDocument,
        destDoc: PDDocument,
        sourceStart0: Int,
        destinationStart0: Int = 0,
    ) {
        require(sourceStart0 >= 0) { "sourceStart0 must not be negative" }
        require(destinationStart0 >= 0) { "destinationStart0 must not be negative" }
        require(destinationStart0 <= destDoc.numberOfPages) {
            "destinationStart0 exceeds destination document"
        }
        val mappedPageCount = destDoc.numberOfPages - destinationStart0
        val sourceEndExclusive = sourceStart0.toLong() + mappedPageCount.toLong()
        require(sourceEndExclusive <= sourceDoc.numberOfPages.toLong()) {
            "selected source pages exceed source document"
        }

        val sourceToDestination = IdentityHashMap<COSDictionary, PDPage>()
        val importedPages = ArrayList<PDPage>(mappedPageCount)
        repeat(mappedPageCount) { offset ->
            val sourcePage = sourceDoc.getPage(sourceStart0 + offset)
            val destinationPage = destDoc.getPage(destinationStart0 + offset)
            sourceToDestination[sourcePage.cosObject] = destinationPage
            importedPages += destinationPage
        }

        importedPages.forEach { destinationPage ->
            val annotations = destinationPage.annotations
            if (annotations.isEmpty()) return@forEach

            val clonedAnnotations = COSArray()
            annotations.forEach { sourceAnnotation ->
                val cloned = PDAnnotation.createAnnotation(
                    COSDictionary(sourceAnnotation.cosObject),
                )
                cloned.page = destinationPage
                if (cloned is PDAnnotationLink) {
                    isolateLink(
                        link = cloned,
                        sourceDoc = sourceDoc,
                        sourceStart0 = sourceStart0,
                        importedPages = importedPages,
                        sourceToDestination = sourceToDestination,
                    )
                }
                clonedAnnotations.add(cloned.cosObject)
            }
            destinationPage.cosObject.setItem(COSName.ANNOTS, clonedAnnotations)
        }
    }

    private fun isolateLink(
        link: PDAnnotationLink,
        sourceDoc: PDDocument,
        sourceStart0: Int,
        importedPages: List<PDPage>,
        sourceToDestination: Map<COSDictionary, PDPage>,
    ) {
        val directDestination = link.destination
        if (directDestination != null) {
            val remapped = remapDestination(
                destination = directDestination,
                sourceDoc = sourceDoc,
                sourceStart0 = sourceStart0,
                importedPages = importedPages,
                sourceToDestination = sourceToDestination,
            )
            if (remapped == null) {
                link.cosObject.removeItem(COSName.DEST)
            } else {
                link.cosObject.setItem(COSName.DEST, remapped)
            }
        }

        val action = link.action
        if (action is PDActionGoTo) {
            val remapped = action.destination?.let { destination ->
                remapDestination(
                    destination = destination,
                    sourceDoc = sourceDoc,
                    sourceStart0 = sourceStart0,
                    importedPages = importedPages,
                    sourceToDestination = sourceToDestination,
                )
            }
            if (remapped == null) {
                link.cosObject.removeItem(COSName.A)
            } else {
                val clonedAction = COSDictionary(action.cosObject)
                clonedAction.setItem(COSName.D, remapped)
                link.cosObject.setItem(COSName.A, clonedAction)
            }
        }
    }

    private fun remapDestination(
        destination: PDDestination,
        sourceDoc: PDDocument,
        sourceStart0: Int,
        importedPages: List<PDPage>,
        sourceToDestination: Map<COSDictionary, PDPage>,
    ): COSArray? {
        val pageDestination = when (destination) {
            is PDPageDestination -> destination
            is PDNamedDestination -> sourceDoc.documentCatalog.findNamedDestinationPage(destination)
            else -> null
        } ?: return null

        val destinationPage = pageDestination.page?.let { sourcePage ->
            sourceToDestination[sourcePage.cosObject]
        } ?: pageDestination.pageNumber
            .takeIf { sourcePageIndex -> sourcePageIndex >= sourceStart0 }
            ?.let { sourcePageIndex -> importedPages.getOrNull(sourcePageIndex - sourceStart0) }
            ?: return null

        return COSArray().also { clone ->
            clone.addAll(pageDestination.cosObject)
            clone.set(0, destinationPage)
        }
    }
}
