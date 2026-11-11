package com.apvcode.devx.data

import org.json.JSONObject

/**
 * Some models (Gemini behind some gateways) do not make a tool call but write it out in their text, as
 * `call:default_api:search_in_file{name:report.pdf,query:price}`. Nothing runs then, and the model answers
 * from a guess. This reads such a line as the call it was meant to be, but only for a tool that exists.
 */
object TextToolCalls {
    private val CALL = Regex("call:(?:default_api:)?([A-Za-z_][A-Za-z0-9_]*)\\{(.*?)\\}", RegexOption.DOT_MATCHES_ALL)
    private val KEY = Regex("(?:^|,\\s*)([A-Za-z_][A-Za-z0-9_]*):")

    fun find(text: String, tools: List<WireTool>): List<Piece.ToolCall> {
        val byName = tools.associateBy { it.name }
        return CALL.findAll(text).mapNotNull { m ->
            val tool = byName[m.groupValues[1]] ?: return@mapNotNull null
            val properties = tool.parameters.optJSONObject("properties") ?: JSONObject()
            val body = m.groupValues[2]
            val keys = KEY.findAll(body).toList()
            val args = JSONObject()
            keys.forEachIndexed { i, key ->
                val name = key.groupValues[1]
                val from = key.range.last + 1
                val to = keys.getOrNull(i + 1)?.range?.first ?: body.length
                val value = body.substring(from, to).trim().trim('"', '\'')
                val integer = properties.optJSONObject(name)?.optString("type") == "integer"
                args.put(name, if (integer) value.toIntOrNull() ?: value else value)
            }
            Piece.ToolCall("text_" + java.util.UUID.randomUUID().toString().take(12), tool.name, args.toString())
        }.toList()
    }

    fun has(text: String): Boolean = CALL.containsMatchIn(text)

    /** The text without those lines (they are not for the reader). */
    fun strip(text: String): String = CALL.replace(text, "").trim()
}
