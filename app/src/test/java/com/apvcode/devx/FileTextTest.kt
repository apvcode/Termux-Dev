package com.apvcode.devx

import com.apvcode.devx.data.FileText
import com.apvcode.devx.ui.components.fileIconRes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class FileTextTest {
    private fun zip(vararg entries: Pair<String, String>): File {
        val file = File.createTempFile("office", ".zip")
        ZipOutputStream(file.outputStream()).use { out ->
            for ((name, body) in entries) {
                out.putNextEntry(ZipEntry(name))
                out.write(body.toByteArray())
                out.closeEntry()
            }
        }
        return file
    }

    @Test fun wordParagraphsComeOutAsLines() {
        val f = zip("word/document.xml" to "<w:document><w:p><w:r><w:t>Hello</w:t></w:r></w:p><w:p><w:r><w:t>A &amp; B</w:t></w:r></w:p></w:document>")
        assertEquals("Hello\nA & B", FileText.office(f, "docx", 10_000))
    }

    @Test fun excelCellsUseTheSharedStrings() {
        val f = zip(
            "xl/sharedStrings.xml" to "<sst><si><t>Name</t></si><si><t>Apples</t></si></sst>",
            "xl/worksheets/sheet1.xml" to "<worksheet><sheetData><row r=\"1\"><c r=\"A1\" t=\"s\"><v>0</v></c><c r=\"B1\" t=\"s\"><v>1</v></c></row><row r=\"2\"><c r=\"A2\"><v>42</v></c></row></sheetData></worksheet>",
        )
        val text = FileText.office(f, "xlsx", 10_000)!!
        assertTrue(text, text.contains("Name\tApples"))
        assertTrue(text, text.contains("42"))
    }

    @Test fun powerPointSlidesAreNumbered() {
        val f = zip("ppt/slides/slide2.xml" to "<a:p><a:t>Second</a:t></a:p>", "ppt/slides/slide1.xml" to "<a:p><a:t>First</a:t></a:p>")
        val text = FileText.office(f, "pptx", 10_000)!!
        assertTrue(text, text.indexOf("First") < text.indexOf("Second"))
    }

    @Test fun anEmptyOrBrokenFileGivesNothing() {
        assertNull(FileText.office(File.createTempFile("empty", ".zip"), "docx", 100))
    }
}

class FileIconTest {
    @Test fun knownFormatsHaveTheirOwnIconAndUnknownOnesGetTheGenericOne() {
        val unknown = fileIconRes("whatever.zzzz")
        for (name in listOf("a.pdf", "a.DOCX", "a.7z", "a.json", "a.mp4", "a.db", "a.csv", "a.html", "a.py", "a.xlsx", "a.png")) {
            assertNotEquals(name, unknown, fileIconRes(name))
        }
        assertEquals(fileIconRes("a.jpg"), fileIconRes("b.JPEG"))
    }
}
