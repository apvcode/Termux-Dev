package com.apvcode.devx.data

import com.apvcode.devx.model.Block

/**
 * Splits a model's answer into what the screen draws: text, code, pictures and cards. Cards and
 * pictures are fenced blocks (` ```devx-card ` and ` ```svg `). While the answer is still arriving a
 * card whose closing fence has not come yet is shown as "being built".
 */
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
            val info = trimmed.removePrefix("```").trim().lowercase()
            val body = StringBuilder()
            var j = i + 1
            var closed = false
            while (j < lines.size) {
                if (lines[j].trim() == "```") {
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
