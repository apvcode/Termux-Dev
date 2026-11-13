package com.apvcode.devx.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * GitHub through its ordinary web API (api.github.com), with the person's own token: no MCP server and no Copilot are
 * involved. The assistant is given one tool that sends a request; GitHub's API is documented and well known to models.
 */
class GithubRest(private val http: OkHttpClient) {

    /** Asks GitHub who the token belongs to; the login of the account, or an error that says what is wrong. */
    suspend fun check(base: String, token: String): String = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(base.trimEnd('/') + "/user").headers(token).build()
        http.newCall(request).execute().use { r ->
            val text = r.body?.string().orEmpty()
            when (r.code) {
                200 -> JSONObject(text).optString("login").ifEmpty { "GitHub" }
                401 -> throw McpException("GitHub does not accept this token (it is wrong, or it has expired).")
                403 -> throw McpException("GitHub refused the token: " + messageOf(text))
                else -> throw McpException("GitHub answered HTTP ${r.code}: " + messageOf(text))
            }
        }
    }

    /** One request to the API. [path] starts with "/" (for example /repos/owner/name/issues); [body] is the JSON to send, if any. */
    suspend fun call(base: String, token: String, method: String, path: String, body: JSONObject?): McpResult = withContext(Dispatchers.IO) {
        val verb = method.uppercase()
        if (verb !in setOf("GET", "POST", "PATCH", "PUT", "DELETE")) return@withContext McpResult("The method must be GET, POST, PATCH, PUT or DELETE.", true)
        if (!path.startsWith("/") || path.contains("://")) return@withContext McpResult("The path must start with / (for example /user/repos): it is a path on api.github.com.", true)
        val payload = if (verb == "GET" || verb == "DELETE") (if (body != null && body.length() > 0 && verb == "DELETE") body else null) else (body ?: JSONObject())
        val request = Request.Builder().url(base.trimEnd('/') + path).headers(token)
            .method(verb, payload?.toString()?.toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(request).execute().use { r ->
            val text = r.body?.string().orEmpty()
            val shown = if (text.length > MAX_TEXT) text.take(MAX_TEXT) + "\n[cut: the answer was longer; ask for a page (per_page, page) or a narrower path]" else text
            McpResult("HTTP ${r.code}\n" + shown.ifBlank { "(empty answer)" }, !r.isSuccessful)
        }
    }

    private fun Request.Builder.headers(token: String) = this
        .header("Authorization", "Bearer $token")
        .header("Accept", "application/vnd.github+json")
        .header("X-GitHub-Api-Version", "2022-11-28")
        .header("User-Agent", "devx-android")

    private fun messageOf(body: String): String = runCatching { JSONObject(body).optString("message") }.getOrNull().orEmpty().ifEmpty { body.take(160) }

    companion object {
        const val BASE = "https://api.github.com"
        const val MAX_TEXT = 20_000
        fun isRest(url: String) = url.trimEnd('/').equals(BASE, ignoreCase = true)
    }
}
