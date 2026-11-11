package com.apvcode.devx.data

import java.io.File
import java.util.zip.ZipFile

/** Reads the words out of office documents, so a model can be given what is written in them. */
object FileText {
    /** The words of a Word, Excel, PowerPoint or OpenDocument file: they are zip archives of XML. */
    fun office(file: File, extension: String, limit: Int): String? = runCatching {
        ZipFile(file).use { zip ->
            fun read(name: String): String? = zip.getEntry(name)?.let { zip.getInputStream(it).bufferedReader().readText() }
            fun names(pattern: Regex): List<String> =
                zip.entries().asSequence().map { it.name }.filter { pattern.matches(it) }.sortedBy { it.filter(Char::isDigit).toIntOrNull() ?: 0 }.toList()

            val text = when (extension) {
                "docx", "docm" -> read("word/document.xml")?.let { xmlText(it, Regex("</w:p>|<w:br/>|<w:tab/>")) }
                "pptx" -> names(Regex("ppt/slides/slide\\d+\\.xml")).joinToString("\n\n") { n ->
                    "-- Slide " + n.filter(Char::isDigit) + "\n" + xmlText(read(n).orEmpty(), Regex("</a:p>"))
                }
                "odt", "ods", "odp" -> read("content.xml")?.let { xmlText(it, Regex("</text:p>|</text:h>|<text:line-break/>|</table:table-row>")) }
                "xlsx", "xlsm" -> {
                    val shared = read("xl/sharedStrings.xml")?.let { xml ->
                        Regex("<si>(.*?)</si>", RegexOption.DOT_MATCHES_ALL).findAll(xml).map { si ->
                            unescape(Regex("<t[^>]*>(.*?)</t>", RegexOption.DOT_MATCHES_ALL).findAll(si.groupValues[1]).joinToString("") { it.groupValues[1] })
                        }.toList()
                    }.orEmpty()
                    names(Regex("xl/worksheets/sheet\\d+\\.xml")).joinToString("\n\n") { n ->
                        val rows = Regex("<row[^>]*>(.*?)</row>", RegexOption.DOT_MATCHES_ALL).findAll(read(n).orEmpty()).take(2000).map { row ->
                            Regex("<c ([^>]*?)(?:/>|>(.*?)</c>)", RegexOption.DOT_MATCHES_ALL).findAll(row.groupValues[1]).joinToString("\t") { cell ->
                                val attrs = cell.groupValues[1]
                                val body = cell.groupValues[2]
                                val value = Regex("<v>(.*?)</v>", RegexOption.DOT_MATCHES_ALL).find(body)?.groupValues?.get(1)
                                when {
                                    attrs.contains("t=\"s\"") -> shared.getOrNull(value?.toIntOrNull() ?: -1).orEmpty()
                                    value != null -> unescape(value)
                                    else -> unescape(Regex("<t[^>]*>(.*?)</t>", RegexOption.DOT_MATCHES_ALL).find(body)?.groupValues?.get(1).orEmpty())
                                }
                            }
                        }
                        "-- Sheet " + n.filter(Char::isDigit) + "\n" + rows.joinToString("\n")
                    }
                }
                else -> null
            }
            text?.trim()?.takeIf { it.isNotEmpty() }?.take(limit)
        }
    }.getOrNull()

    private fun xmlText(xml: String, breaks: Regex): String =
        unescape(xml.replace(breaks, "\n").replace(Regex("<[^>]+>"), "")).replace(Regex("\n{3,}"), "\n\n")

    private fun unescape(s: String): String =
        s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")
}
