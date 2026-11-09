package com.m1racle.reader.pdfsplit.split

import android.content.Context
import android.content.ContextWrapper
import com.tom_roush.pdfbox.pdmodel.PDDocument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.ClassRule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AutoContentArrayTest {
    companion object {
        @ClassRule
        @JvmField
        val temp = TemporaryFolder()
    }

    @Before
    fun initializePdfBox() {
        PdfBoxSetup.ensureInit(object : ContextWrapper(null) {
            override fun getApplicationContext(): Context = this
            override fun getCacheDir(): File = temp.root
        })
    }

    @Test
    fun `AUTO recovers the second content stream when its xref offset is zero`() {
        val file = fixture("11-content-array-broken.pdf")
        val compatible = importAndRead(file, LoadMode.LENIENT)
        assertTrue("The control must recover the second rectangle", "180 180 80 80 re" in compatible.second)

        val auto = importAndRead(file, LoadMode.AUTO)
        assertEquals(compatible.second, auto.second)
        assertEquals(LoadMode.LENIENT, auto.first)
    }

    @Test
    fun `AUTO keeps strict parsing when both content streams are readable`() {
        val auto = importAndRead(fixture("12-content-array-healthy.pdf"), LoadMode.AUTO)
        assertEquals(LoadMode.STRICT, auto.first)
        assertTrue("20 20 40 40 re" in auto.second)
        assertTrue("180 180 80 80 re" in auto.second)
    }

    private fun importAndRead(file: File, mode: LoadMode): Pair<LoadMode, String> {
        val output = temp.newFile()
        val loaded = PdfBoxSetup.open(file, mode)
        loaded.document.use { source ->
            PDDocument(PdfBoxSetup.memorySetting()).use { destination ->
                destination.importPage(source.getPage(0))
                destination.save(output)
            }
        }
        val contents = PdfBoxSetup.load(output).use { document ->
            document.getPage(0).contents.bufferedReader(Charsets.ISO_8859_1).use { it.readText() }
        }
        return loaded.mode to contents
    }

    private fun fixture(name: String): File {
        val file = temp.newFile(name)
        checkNotNull(javaClass.getResourceAsStream("/fixtures/$name")).use { input ->
            file.outputStream().use { input.copyTo(it) }
        }
        return file
    }
}
