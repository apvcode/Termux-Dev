package com.apvcode.devx

import com.apvcode.devx.data.Archives
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ArchivesTest {
    private fun tree(): File {
        val root = Files.createTempDirectory("devx-arch").toFile()
        File(root, "src/sub").mkdirs()
        File(root, "src/a.txt").writeText("alpha")
        File(root, "src/sub/b.txt").writeText("bravo bravo")
        return root
    }

    @Test fun everyKindOfArchiveComesBackTheSame() {
        for (name in listOf("x.zip", "x.tar", "x.tar.gz", "x.tgz", "x.tar.bz2", "x.tar.xz", "x.7z")) {
            val root = tree()
            val archive = File(root, name)
            val stored = Archives.create(archive, listOf(File(root, "src")), root)
            assertEquals(name, 2, stored)
            val listed = Archives.list(archive).filter { !it.directory }.map { it.name }.sorted()
            assertEquals(name, listOf("src/a.txt", "src/sub/b.txt"), listed)
            val out = File(root, "out-$name")
            assertEquals(name, 2, Archives.extract(archive, out))
            assertEquals(name, "alpha", File(out, "src/a.txt").readText())
            assertEquals(name, "bravo bravo", File(out, "src/sub/b.txt").readText())
        }
    }

    @Test fun aSingleCompressedFileUnpacksUnderItsOwnName() {
        for (ext in listOf("gz", "bz2", "xz")) {
            val root = tree()
            val archive = File(root, "note.txt.$ext")
            Archives.create(archive, listOf(File(root, "src/a.txt")), root)
            val out = File(root, "o")
            Archives.extract(archive, out)
            assertEquals(ext, "alpha", File(out, "note.txt").readText())
        }
    }

    @Test fun anEntryThatLeavesTheFolderIsRefused() {
        val root = Files.createTempDirectory("devx-slip").toFile()
        val evil = File(root, "evil.zip")
        ZipOutputStream(evil.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("../escaped.txt"))
            z.write("x".toByteArray())
            z.closeEntry()
        }
        try {
            Archives.extract(evil, File(root, "out"))
            fail("should have been refused")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message, e.message!!.contains("outside"))
        }
        assertTrue(!File(root, "escaped.txt").exists())
    }

    @Test fun anUnknownTypeIsSaidSo() {
        try {
            Archives.list(File("what.rar"))
            fail()
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains(".7z"))
        }
    }
}
