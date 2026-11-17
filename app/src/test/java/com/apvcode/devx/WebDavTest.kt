package com.apvcode.devx

import com.apvcode.devx.data.AutoBackup
import com.apvcode.devx.data.WebDav
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.Credentials
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WebDavTest {
    @get:Rule val tmp = TemporaryFolder()

    private val listing = """<?xml version="1.0"?>
<d:multistatus xmlns:d="DAV:">
 <d:response><d:href>/dav/backups/</d:href><d:propstat><d:prop><d:getlastmodified>Mon, 03 Nov 2026 10:00:00 GMT</d:getlastmodified></d:prop></d:propstat></d:response>
 <d:response><d:href>/dav/backups/devx-backup-2026-11-03.zip</d:href><d:propstat><d:prop><d:getlastmodified>Tue, 03 Nov 2026 10:00:00 GMT</d:getlastmodified></d:prop></d:propstat></d:response>
 <d:response><d:href>/dav/backups/devx-backup-2026-11-10.zip</d:href><d:propstat><d:prop><d:getlastmodified>Tue, 10 Nov 2026 10:00:00 GMT</d:getlastmodified></d:prop></d:propstat></d:response>
 <d:response><d:href>/dav/backups/my%20notes.txt</d:href></d:response>
</d:multistatus>"""

    @Test fun onlyHttpsAddressesAreAcceptedAndTheFolderEndsWithASlash() {
        assertEquals("https://cloud.example.com/dav/backups/", WebDav.folderUrl("https://cloud.example.com/dav/backups")?.toString())
        assertNull(WebDav.folderUrl("http://cloud.example.com/dav/"))
        assertNull(WebDav.folderUrl("not an address"))
        assertTrue(WebDav.folderUrl("http://127.0.0.1:8080/dav", allowInsecure = true) != null)
    }

    @Test fun theListingGivesTheFilesWithTheirTimesAndLeavesTheFoldersOut() {
        val files = WebDav.parseListing(listing)
        assertEquals(listOf("devx-backup-2026-11-03.zip", "devx-backup-2026-11-10.zip", "my notes.txt"), files.map { it.first })
        assertTrue(files[1].second > files[0].second)
        assertEquals(0L, files[2].second)
        // the same, with the other way servers write the names of the tags
        assertEquals(1, WebDav.parseListing("<multistatus><response><href>/a/b.zip</href></response></multistatus>").size)
    }

    @Test fun aCopyIsUploadedWithTheLoginAndTheOldOnesAreRemoved() {
        val server = MockWebServer()
        // put, then the listing, then two deletes (six copies in the folder, four stay)
        server.enqueue(MockResponse().setResponseCode(201))
        val many = (1..6).joinToString("") { "<d:response><d:href>/dav/devx-backup-2026-10-0$it.zip</d:href></d:response>" }
        server.enqueue(MockResponse().setResponseCode(207).setBody("<d:multistatus xmlns:d=\"DAV:\">$many</d:multistatus>"))
        server.enqueue(MockResponse().setResponseCode(204))
        server.enqueue(MockResponse().setResponseCode(204))
        server.start()
        try {
            val file = tmp.newFile("copy.zip").apply { writeText("zip bytes") }
            val folder = server.url("/dav/")
            val status = WebDav.send(folder, "me", "pa ss", "devx-backup-2026-11-17.zip", file)
            assertEquals(WebDav.Status.Ok, status)
            val put = server.takeRequest()
            assertEquals("PUT", put.method)
            assertEquals("/dav/devx-backup-2026-11-17.zip", put.path)
            assertEquals("zip bytes", put.body.readUtf8())
            assertEquals(Credentials.basic("me", "pa ss", Charsets.UTF_8), put.getHeader("Authorization"))
            assertEquals("PROPFIND", server.takeRequest().method)
            val d1 = server.takeRequest(); val d2 = server.takeRequest()
            assertEquals(listOf("DELETE", "DELETE"), listOf(d1.method, d2.method))
            assertEquals(setOf("/dav/devx-backup-2026-10-01.zip", "/dav/devx-backup-2026-10-02.zip"), setOf(d1.path, d2.path))
        } finally { server.shutdown() }
    }

    @Test fun theAnswersOfTheServerAreToldApart() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(207))
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(MockResponse().setResponseCode(500))
        server.start()
        try {
            val folder = server.url("/dav/")
            assertEquals(WebDav.Status.Ok, WebDav.check(folder, "u", "p"))
            assertEquals(WebDav.Status.BadLogin, WebDav.check(folder, "u", "p"))
            assertEquals(WebDav.Status.NotFound, WebDav.check(folder, "u", "p"))
            assertEquals(WebDav.Status.Failed, WebDav.check(folder, "u", "p"))
            assertEquals("Depth: 0", server.takeRequest().getHeader("Depth").let { "Depth: $it" })
        } finally { server.shutdown() }
        // a server that is not there is a failure, not a crash
        assertEquals(WebDav.Status.Failed, WebDav.check(okhttp3.HttpUrl.Builder().scheme("http").host("127.0.0.1").port(1).build(), "u", "p"))
    }

    @Test fun aFailedUploadLeavesTheOldCopiesAlone() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(507))
        server.start()
        try {
            val file = tmp.newFile("c.zip")
            assertEquals(WebDav.Status.Failed, WebDav.send(server.url("/dav/"), "u", "p", "devx-backup-2026-11-17.zip", file))
            assertEquals(1, server.requestCount)
        } finally { server.shutdown() }
    }

    @Test fun theOldestCopiesAreChosenByTimeThenByTheDateInTheName() {
        val files = listOf("devx-backup-2026-11-10.zip" to 0L, "devx-backup-2026-11-03.zip" to 0L, "devx-backup-2026-11-17.zip" to 0L, "other.txt" to 0L)
        assertEquals(listOf("devx-backup-2026-11-03.zip"), AutoBackup.toRemove(files, keep = 2))
    }
}
