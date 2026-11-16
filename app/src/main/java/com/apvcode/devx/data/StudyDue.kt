package com.apvcode.devx.data

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * How many cards of the study notebooks are due, read straight from the saved notebooks (the widget has no app behind it to ask).
 */
object StudyDue {
    /** Cards due now by notebook, for the study notebooks that have any (in the order they are saved). */
    fun counts(json: String, now: Long = System.currentTimeMillis()): Map<String, Int> {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return emptyMap()
        val out = LinkedHashMap<String, Int>()
        for (name in root.keys()) {
            val o = root.optJSONObject(name) ?: continue
            if (o.optString("kind") != ProjectInfo.KIND_STUDY) continue
            val cards = o.optJSONObject("plan")?.optJSONArray("cards") ?: continue
            var due = 0
            for (i in 0 until cards.length()) if ((cards.optJSONObject(i)?.optLong("due") ?: Long.MAX_VALUE) <= now) due++
            if (due > 0) out[name] = due
        }
        return out
    }

    fun read(context: Context, now: Long = System.currentTimeMillis()): Map<String, Int> =
        runCatching { counts(File(context.filesDir, "projects_meta.json").takeIf { it.exists() }?.readText().orEmpty(), now) }.getOrDefault(emptyMap())
}
