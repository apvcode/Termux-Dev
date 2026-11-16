package com.apvcode.devx

import com.apvcode.devx.data.Backup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BackupTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun theDataGoesInAndComesBackAndNothingBoundToThePhoneTravels() {
        val files = tmp.newFolder("files")
        val prefs = tmp.newFolder("prefs")
        java.io.File(files, "chats").mkdirs()
        java.io.File(files, "chats/a.json").writeText("{\"title\":\"hello\"}")
        java.io.File(files, "memory.json").writeText("[]")
        java.io.File(files, "remote_computers.json").writeText("secret pairing")
        java.io.File(files, "apk-signing-key.p8").writeText("secret key")
        java.io.File(prefs, "devx_prefs.xml").writeText("<map/>")
        java.io.File(prefs, "secrets.xml").writeText("encrypted keys")

        val zip = ByteArrayOutputStream()
        val saved = Backup.export(files, prefs, zip, "2.1.0")
        assertEquals(3, saved) // the chat, the memory, the preferences

        val files2 = tmp.newFolder("files2")
        val prefs2 = tmp.newFolder("prefs2")
        val back = Backup.restore(files2, prefs2, ByteArrayInputStream(zip.toByteArray()))
        assertEquals(3, back)
        assertEquals("{\"title\":\"hello\"}", java.io.File(files2, "chats/a.json").readText())
        assertTrue(java.io.File(prefs2, "devx_prefs.xml").exists())
        assertFalse(java.io.File(files2, "remote_computers.json").exists())
        assertFalse(java.io.File(files2, "apk-signing-key.p8").exists())
        assertFalse(java.io.File(prefs2, "secrets.xml").exists())
    }

    @Test
    fun aFileThatIsNotABackupOrClimbsOutOfTheFolderIsRefused() {
        val files = tmp.newFolder("f")
        val prefs = tmp.newFolder("p")
        val notBackup = ByteArrayOutputStream().also { out -> ZipOutputStream(out).use { z -> z.putNextEntry(ZipEntry("x.txt")); z.write(1); z.closeEntry() } }
        val e = runCatching { Backup.restore(files, prefs, ByteArrayInputStream(notBackup.toByteArray())) }.exceptionOrNull()
        assertTrue(e is IllegalArgumentException)

        val evil = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { z ->
                z.putNextEntry(ZipEntry("manifest.json")); z.write("{\"app\":\"devx-android\"}".toByteArray()); z.closeEntry()
                z.putNextEntry(ZipEntry("files/../outside.txt")); z.write(1); z.closeEntry()
            }
        }
        val n = Backup.restore(files, prefs, ByteArrayInputStream(evil.toByteArray()))
        assertEquals(0, n)
        assertFalse(java.io.File(files.parentFile, "outside.txt").exists())
    }

    @Test
    fun notebooksTheirCardsTasksAndTheStateOfFormsTravelToo() {
        val files = tmp.newFolder("nb-files")
        val prefs = tmp.newFolder("nb-prefs")
        java.io.File(files, "projects.json").writeText("{\"Physics\":{\"plan\":{\"cards\":[{\"q\":\"F\",\"a\":\"ma\"}]}}}")
        java.io.File(files, "library_meta.json").writeText("{}")
        java.io.File(files, "tasks.json").writeText("[]")
        java.io.File(files, "project_ab12cd34.json").writeText("[]")
        java.io.File(files, "attachments").mkdirs()
        java.io.File(files, "attachments/notes.pdf").writeText("pdf")
        java.io.File(prefs, "cards.xml").writeText("<map/>")
        java.io.File(prefs, "rich_state.xml").writeText("<map/>")
        java.io.File(prefs, "earcon.xml").writeText("<map/>")
        val zip = ByteArrayOutputStream()
        assertEquals(7, Backup.export(files, prefs, zip, "2.3.0")) // 5 files + the two preference files; the sound state of this phone stays
        val files2 = tmp.newFolder("nb-files2")
        val prefs2 = tmp.newFolder("nb-prefs2")
        Backup.restore(files2, prefs2, ByteArrayInputStream(zip.toByteArray()))
        assertTrue(java.io.File(files2, "projects.json").readText().contains("\"ma\""))
        assertTrue(java.io.File(files2, "attachments/notes.pdf").exists())
        assertTrue(java.io.File(prefs2, "cards.xml").exists())
        assertFalse(java.io.File(prefs2, "earcon.xml").exists())
    }
}
