package com.apvcode.devx

import com.apvcode.devx.data.Attachment
import com.apvcode.devx.data.AttachmentKind
import com.apvcode.devx.data.NotebookTools
import com.apvcode.devx.data.ProjectInfo
import com.apvcode.devx.data.NotebookTools.Companion.SourceMatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** How the assistant points at a source of a notebook to remove or change it. */
class SourceToolsTest {
    private fun src(id: String, name: String, url: String = "") = Attachment(id, AttachmentKind.Text, name, "text/plain", "$id.txt", 10, url = url)

    private val all = listOf(src("1", "Go tour.txt"), src("2", "Go spec.pdf"), src("3", "Rust book.txt", "https://doc.rust-lang.org/book"))

    private fun one(ref: String): String? = (NotebookTools.findSource(all, ref) as? SourceMatch.One)?.source?.id

    @Test fun exactNameWins() = assertEquals("2", one("Go spec.pdf"))
    @Test fun caseAndQuotesDoNotMatter() = assertEquals("1", one("«go tour.txt»"))
    @Test fun numberPointsAtTheListedPlace() = assertEquals("3", one("3"))
    @Test fun addressFindsAPage() = assertEquals("3", one("https://doc.rust-lang.org/book"))
    @Test fun aUniquePartFits() = assertEquals("3", one("rust"))
    @Test fun anAmbiguousPartIsNotGuessed() = assertTrue(NotebookTools.findSource(all, "go") is SourceMatch.Many)
    @Test fun nothingFitsNothing() = assertTrue(NotebookTools.findSource(all, "python") is SourceMatch.None)

    private fun tools() = NotebookTools({ null }, { emptyList() }, { _, _ -> false }, {})
    private fun names(inStudy: Boolean, inNotebook: Boolean) = tools().definitions(inStudy, inSpace = true, inNotebook = inNotebook).map { it.name }

    @Test fun anIdeasNotebookGetsTheMapAndTheSourceTools() {
        val n = names(inStudy = false, inNotebook = true)
        assertTrue("study_graph" in n)
        assertTrue("source_add_text" in n && "source_remove" in n && "source_edit" in n)
        assertTrue("cards_add" !in n) // cards are for study notebooks only
    }

    @Test fun aProjectGetsNoneOfThem() {
        val n = names(inStudy = false, inNotebook = false)
        assertTrue("study_graph" !in n && "source_add_text" !in n && "source_remove" !in n)
    }
}
