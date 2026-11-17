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
        assertEquals(7, Backup.export(files, prefs, zip, "2.3.0")) // 5 files + the two preference files; the volume state of this phone stays
        val files2 = tmp.newFolder("nb-files2")
        val prefs2 = tmp.newFolder("nb-prefs2")
        Backup.restore(files2, prefs2, ByteArrayInputStream(zip.toByteArray()))
        assertTrue(java.io.File(files2, "projects.json").readText().contains("\"ma\""))
        assertTrue(java.io.File(files2, "attachments/notes.pdf").exists())
        assertTrue(java.io.File(prefs2, "cards.xml").exists())
        assertFalse(java.io.File(prefs2, "earcon.xml").exists())
    }

    @Test
    fun theKeysTravelOnlyUnderAPasswordAndComeBackIntoTheKeystore() {
        val files = tmp.newFolder("k-files")
        val prefs = tmp.newFolder("k-prefs")
        java.io.File(files, "providers.json").writeText("[]")
        java.io.File(files, "apk-signing-key.p8").writeText("the signing key")
        java.io.File(prefs, "applock.xml").writeText("<map/>")
        val secrets = mapOf("provider-1" to "sk-abc", "mcp:7" to "{\"token\":\"t\"}")

        val plain = ByteArrayOutputStream()
        Backup.export(files, prefs, plain, "2.3.0", secrets = secrets)
        assertFalse(Backup.hasSecrets(ByteArrayInputStream(plain.toByteArray())))
        assertFalse(String(plain.toByteArray(), Charsets.ISO_8859_1).contains("sk-abc"))

        val zip = ByteArrayOutputStream()
        Backup.export(files, prefs, zip, "2.3.0", secrets = secrets, password = "hunter2".toCharArray())
        val bytes = zip.toByteArray()
        assertTrue(Backup.hasSecrets(ByteArrayInputStream(bytes)))
        assertFalse(String(bytes, Charsets.ISO_8859_1).contains("sk-abc"))
        assertFalse(String(bytes, Charsets.ISO_8859_1).contains("the signing key"))

        // A wrong password changes nothing at all.
        val files2 = tmp.newFolder("k-files2")
        val prefs2 = tmp.newFolder("k-prefs2")
        val bad = runCatching { Backup.restore(files2, prefs2, ByteArrayInputStream(bytes), "wrong".toCharArray()) }.exceptionOrNull()
        assertTrue(bad is Backup.WrongPassword)
        assertFalse(java.io.File(files2, "providers.json").exists())
        assertFalse(java.io.File(files2.parentFile, "restore_stage").exists())

        var got: Backup.Vault? = null
        Backup.restore(files2, prefs2, ByteArrayInputStream(bytes), "hunter2".toCharArray()) { got = it }
        assertEquals(secrets, got?.secrets)
        assertEquals("the signing key", java.io.File(files2, "apk-signing-key.p8").readText())
        assertTrue(java.io.File(prefs2, "applock.xml").exists())

        // Without a password the rest comes back and the keys are left alone.
        val files3 = tmp.newFolder("k-files3")
        val prefs3 = tmp.newFolder("k-prefs3")
        var touched = false
        Backup.restore(files3, prefs3, ByteArrayInputStream(bytes)) { touched = true }
        assertFalse(touched)
        assertTrue(java.io.File(files3, "providers.json").exists())
        assertFalse(java.io.File(files3, "apk-signing-key.p8").exists())
    }

    @Test
    fun aBackupCutShortChangesNothing() {
        val files = tmp.newFolder("c-files")
        val prefs = tmp.newFolder("c-prefs")
        for (i in 0 until 40) java.io.File(files, "f$i.json").writeText("x".repeat(4000) + i)
        val zip = ByteArrayOutputStream()
        Backup.export(files, prefs, zip, "2.3.0")
        val cut = zip.toByteArray().copyOf(zip.size() / 2)
        val files2 = tmp.newFolder("c-files2")
        java.io.File(files2, "f0.json").writeText("mine")
        val e = runCatching { Backup.restore(files2, tmp.newFolder("c-prefs2"), ByteArrayInputStream(cut)) }.exceptionOrNull()
        assertTrue(e != null)
        assertEquals("mine", java.io.File(files2, "f0.json").readText())
        assertFalse(java.io.File(files2.parentFile, "restore_stage").exists())
    }
}

class BackupPreviewTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun aBackupIsLookedAtBeforeItIsRestoredAndNothingChanges() {
        val files = tmp.newFolder("files")
        val prefs = tmp.newFolder("prefs")
        java.io.File(files, "chats").mkdirs()
        java.io.File(files, "chats/a.json").writeText("{}")
        java.io.File(files, "chats/b.json").writeText("{}")
        java.io.File(files, "attachments").mkdirs()
        java.io.File(files, "attachments/x.png").writeText("png")
        java.io.File(files, "projects.json").writeText("[\"Physics\",\"Maths\"]")
        java.io.File(files, "memory.json").writeText("[]")
        java.io.File(files, "project_p1.json").writeText("[]")
        java.io.File(files, "remote_computers.json").writeText("pairing")
        java.io.File(prefs, "devx_prefs.xml").writeText("<map/>")
        val zip = ByteArrayOutputStream()
        Backup.export(files, prefs, zip, "2.3.0", now = 1234L, secrets = mapOf("k" to "v"), password = "pw".toCharArray())

        // on a phone that has one of the chats already
        val files2 = tmp.newFolder("files2")
        val prefs2 = tmp.newFolder("prefs2")
        java.io.File(files2, "chats").mkdirs()
        java.io.File(files2, "chats/a.json").writeText("old")
        val p = Backup.inspect(files2, prefs2, ByteArrayInputStream(zip.toByteArray()))
        assertEquals("2.3.0", p.version)
        assertEquals(1234L, p.at)
        assertTrue(p.hasSecrets)
        assertEquals(2, p.chats)
        assertEquals(2, p.projects)
        assertEquals(2, p.memoryFiles)
        assertEquals(1, p.attachments)
        assertEquals(1, p.settings)
        assertEquals(1, p.replaced)
        assertEquals(7, p.files) // 2 chats, 1 attachment, projects, 2 memory files, 1 preference file (the pairing is not in it)
        assertEquals("old", java.io.File(files2, "chats/a.json").readText())
        assertFalse(java.io.File(files2, "chats/b.json").exists())
    }

    @Test
    fun aFileThatIsNotABackupHasNoPreview() {
        val zip = ByteArrayOutputStream()
        ZipOutputStream(zip).use { it.putNextEntry(ZipEntry("manifest.json")); it.write("{\"app\":\"other\"}".toByteArray()); it.closeEntry() }
        val dir = tmp.newFolder("x")
        var refused = false
        try { Backup.inspect(dir, dir, ByteArrayInputStream(zip.toByteArray())) } catch (e: IllegalArgumentException) { refused = true }
        assertTrue(refused)
    }
}
