package com.apvcode.devx.data

import com.apvcode.devx.model.Block

/**
 * Splits a model's answer into what the screen draws: text, code, pictures and cards. Cards and
 * pictures are fenced blocks (` ```devx-card ` and ` ```svg `). While the answer is still arriving a
 * card whose closing fence has not come yet is shown as "being built".
 */
private val RICH_TAGS = mapOf(
    "devx-weather" to "weather", "devx-timer" to "timer", "devx-checklist" to "checklist", "devx-table" to "table",
    "devx-contact" to "contact", "devx-apps" to "apps", "devx-shop" to "shop", "devx-link" to "link", "mermaid" to "mermaid", "devx-mermaid" to "mermaid",
)

fun parseBlocks(text: String): List<Block> {
    val blocks = ArrayList<Block>()
    val prose = StringBuilder()

    fun flushProse() {
        val t = prose.toString().trim('\n')
        if (t.isNotBlank()) blocks += Block.Text(t)
        prose.clear()
    }

    val lines = text.split("\n")
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        val trimmed = line.trimStart()
        if (trimmed.startsWith("```")) {
            val opening = trimmed.removePrefix("```").trim()
            // Models sometimes put the content on the fence's own line (```devx-reply ["Yes","No"]```): for the blocks
            // the app knows, what follows the name is the content, and a closing fence at the end of a line closes it.
            val name = opening.substringBefore(' ').lowercase()
            val known = name.startsWith("devx-") || name == "svg" || name == "mermaid"
            val inline = if (known) opening.substringAfter(' ', "").trim() else ""
            val info = if (known) name else opening.lowercase()
            val body = StringBuilder()
            var j = i + 1
            var closed = false
            if (inline.isNotEmpty()) {
                if (inline.endsWith("```")) {
                    body.append(inline.removeSuffix("```").trimEnd())
                    closed = true
                    j = i
                } else body.append(inline)
            }
            while (!closed && j < lines.size) {
                val l = lines[j].trim()
                if (l == "```") {
                    closed = true
                    break
                }
                if (known && l.endsWith("```") && !l.startsWith("```")) {
                    if (body.isNotEmpty()) body.append('\n')
                    body.append(l.removeSuffix("```").trimEnd())
                    closed = true
                    break
                }
                if (body.isNotEmpty()) body.append('\n')
                body.append(lines[j])
                j++
            }
            flushProse()
            val content = body.toString()
            blocks += when {
                info == "devx-card" -> if (closed) Block.Ui(content) else Block.Building
                info == "svg" && closed && "<svg" in content -> Block.Svg("", content)
                info == "devx-map" -> if (closed) Block.Map(content) else Block.Building
                info == "devx-image" -> if (closed) Block.Image(content) else Block.Building
                info == "devx-chart" -> if (closed) Block.Chart(content) else Block.Building
                info in RICH_TAGS -> if (closed) Block.Rich(RICH_TAGS.getValue(info), content) else Block.Building
                info == "devx-reply" -> if (closed) Block.Replies(content) else Block.Building
                info == "devx-file" && closed -> Block.File(content.trim())
                info == "devx-file" -> Block.Building
                info == "svg" && !closed -> Block.Building
                else -> Block.Code(info, content)
            }
            i = if (closed) j + 1 else lines.size
        } else {
            prose.append(line).append('\n')
            i++
        }
    }
    flushProse()
    return blocks
}
