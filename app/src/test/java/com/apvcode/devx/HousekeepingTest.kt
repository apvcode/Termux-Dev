package com.apvcode.devx

import com.apvcode.devx.data.AtomicFile
import com.apvcode.devx.data.ErrorLog
import com.apvcode.devx.data.logged
import com.apvcode.devx.data.PrefFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class HousekeepingTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun names(file: File): Set<String> =
        Regex("<string\\s+name=\"([^\"]+)\"(?![^>]*translatable=\"false\")").findAll(file.readText()).map { it.groupValues[1] }.toSet() +
            Regex("<plurals\\s+name=\"([^\"]+)\"").findAll(file.readText()).map { it.groupValues[1] }.toSet()

    @Test fun everyLanguageHasEveryString() {
        val res = File("src/main/res")
        // The names of the languages are each written in that language, once, in the base file.
        val base = names(File(res, "values/strings.xml")).filter { !it.startsWith("lang_") }.toSet()
        assertTrue(base.size > 500)
        val missing = res.listFiles { f -> f.isDirectory && f.name.startsWith("values-") && File(f, "strings.xml").exists() }!!
            .associate { it.name to (base - names(File(it, "strings.xml"))) }
            .filterValues { it.isNotEmpty() }
        assertTrue("Missing translations: " + missing.mapValues { it.value.sorted().take(8) }, missing.isEmpty())
    }

    @Test fun aPreferenceFileIsEitherBackedUpOrLeftOutOnPurpose() {
        assertTrue(PrefFiles.backedUp.intersect(PrefFiles.leftOut.toSet()).isEmpty())
        // Every name that the code opens is in the list: a new file has to be put there and decided on.
        val known = (PrefFiles.backedUp + PrefFiles.leftOut).toSet()
        val used = File("src/main/java").walkTopDown().filter { it.extension == "kt" }
            .flatMap { f -> Regex("getSharedPreferences\\(\\s*\"([^\"]+)\"").findAll(f.readText()).map { it.groupValues[1] } }.toSet()
        assertTrue("Preference files not in PrefFiles: $used", used.all { it in known })
    }

    @Test fun writesAreWholeEvenWhenManyThreadsWriteTheSameFile() {
        val file = File(tmp.newFolder("w"), "data.json")
        val threads = (1..8).map { n -> Thread { repeat(40) { AtomicFile.write(file, "{\"n\":$n,\"pad\":\"" + "x".repeat(2000) + "\"}") } } }
        threads.forEach { it.start() }; threads.forEach { it.join() }
        val text = file.readText()
        assertTrue(text.startsWith("{\"n\":") && text.endsWith("\"}"))
        assertTrue(!File(file.parentFile, "data.json.tmp").exists())
    }

    @Test fun theErrorLogKeepsTheLastLinesAndCounts() {
        ErrorLog.clear()
        repeat(70) { ErrorLog.warn("t", "problem $it") }
        assertEquals(70, ErrorLog.count)
        assertEquals(60, ErrorLog.recent().size)
        assertTrue(ErrorLog.recent().last().contains("problem 69"))
        ErrorLog.clear()
        assertEquals(0, ErrorLog.count)
    }

    @Test fun theSameFailureAgainAndAgainIsOneLineWithANumber() {
        ErrorLog.clear()
        ErrorLog.warn("a", "one")
        repeat(4) { ErrorLog.warn("b", "again", IllegalStateException("x")) }
        ErrorLog.warn("a", "two")
        val lines = ErrorLog.recent()
        assertEquals(3, lines.size)
        assertTrue(lines[1].endsWith("(×4)"))
        assertEquals(6, ErrorLog.count)
        ErrorLog.clear()
    }

    @Test fun loggedNotesAFailureAndGivesTheResultOnButNotACancellation() {
        ErrorLog.clear()
        assertEquals(7, runCatching { 7 }.logged("t").getOrNull())
        assertEquals(0, ErrorLog.count)
        assertEquals(-1, runCatching<Int> { error("boom") }.logged("t").getOrDefault(-1))
        assertEquals(1, ErrorLog.count)
        assertTrue(ErrorLog.recent().single().contains("boom"))
        runCatching<Unit> { throw kotlinx.coroutines.CancellationException("stop") }.logged("t")
        assertEquals(1, ErrorLog.count)
        ErrorLog.clear()
    }

    private fun texts(file: File): Map<String, String> =
        Regex("<string\\s+name=\"([^\"]+)\"[^>]*>(.*?)</string>", RegexOption.DOT_MATCHES_ALL).findAll(file.readText()).associate { it.groupValues[1] to it.groupValues[2] }

    private val languages get() = File("src/main/res").listFiles { f -> f.isDirectory && f.name.startsWith("values-") && File(f, "strings.xml").exists() }!!

    /** A translation that loses or adds a "%1$s" or "%d" crashes (or shows garbage) when the text is formatted. */
    @Test fun everyTranslationKeepsThePlaceholders() {
        val base = texts(File("src/main/res/values/strings.xml"))
        val holder = Regex("%(?:\\d+\\$)?[sdf]")
        val bad = ArrayList<String>()
        for (dir in languages) for ((key, text) in texts(File(dir, "strings.xml"))) {
            val original = base[key] ?: continue
            if (holder.findAll(original).map { it.value.replace(Regex("^%\\d+\\$"), "%") }.toList().sorted() != holder.findAll(text).map { it.value.replace(Regex("^%\\d+\\$"), "%") }.toList().sorted()) bad += "${dir.name}/$key"
        }
        assertTrue("Placeholders differ: $bad", bad.isEmpty())
    }

    /** A new string that was pasted into every language unchanged was never translated: it must be a name or a term that stays as it is. */
    @Test fun aStringIdenticalInAllLanguagesIsOnPurpose() {
        val base = texts(File("src/main/res/values/strings.xml"))
        val langs = languages.map { texts(File(it, "strings.xml")) }
        val same = base.filter { (k, v) -> !k.startsWith("lang_") && v.count { it.isLetter() } > 3 && langs.count { it[k] == v } >= langs.size - 1 }.keys
        val allowed = setOf(
            "app_name", "kind_anthropic", "about_github", "export_markdown", "compress_devx", "remote_title", "persona_name_ph",
        )
        assertTrue("Untranslated (same text in every language): ${same - allowed}", (same - allowed).isEmpty())
    }
}
