package com.apvcode.devx.data

import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * A folder on a WebDAV server (Nextcloud, ownCloud, a NAS, a hosting that has one) as a place for the weekly copy: the file is put there,
 * and the old copies are removed so that a few stay. The login goes in the Basic header, so only https addresses are accepted: the
 * password must not travel in the clear.
 */
object WebDav {
    enum class Status { Ok, BadLogin, NotFound, NotSecure, Failed }

    private val client by lazy {
        OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).writeTimeout(120, TimeUnit.SECONDS).callTimeout(15, TimeUnit.MINUTES).build()
    }

    /** The address of the folder as one that ends with "/", or null when it is not an https address. [allowInsecure] is for tests. */
    fun folderUrl(raw: String, allowInsecure: Boolean = false): HttpUrl? {
        val text = raw.trim().let { if (it.endsWith("/")) it else "$it/" }
        val url = text.toHttpUrlOrNull() ?: return null
        return if (url.isHttps || allowInsecure) url else null
    }

    private fun request(url: HttpUrl, user: String, password: String) =
        Request.Builder().url(url).header("Authorization", Credentials.basic(user, password, Charsets.UTF_8))

    private fun statusOf(code: Int): Status = when {
        code in 200..299 -> Status.Ok
        code == 401 || code == 403 -> Status.BadLogin
        code == 404 || code == 409 -> Status.NotFound
        else -> Status.Failed
    }

    /** Asks the server about the folder: is it there, and does the login work. */
    fun check(folder: HttpUrl, user: String, password: String, http: OkHttpClient = client): Status = try {
        http.newCall(request(folder, user, password).header("Depth", "0").method("PROPFIND", "".toRequestBody("application/xml".toMediaType())).build()).execute().use { statusOf(it.code) }
    } catch (e: Exception) {
        ErrorLog.warn("webdav", "the folder could not be reached", e); Status.Failed
    }

    /** Puts [file] in the folder under [name] (one of that name is replaced). */
    fun upload(folder: HttpUrl, user: String, password: String, name: String, file: File, http: OkHttpClient = client): Status = try {
        val target = folder.newBuilder().addPathSegment(name).build()
        http.newCall(request(target, user, password).put(file.asRequestBody("application/zip".toMediaType())).build()).execute().use { statusOf(it.code) }
    } catch (e: Exception) {
        ErrorLog.warn("webdav", "the copy could not be uploaded", e); Status.Failed
    }

    /** The files of the folder with the time each was changed (0 when the server does not say). */
    fun list(folder: HttpUrl, user: String, password: String, http: OkHttpClient = client): List<Pair<String, Long>> = try {
        http.newCall(request(folder, user, password).header("Depth", "1").method("PROPFIND", "".toRequestBody("application/xml".toMediaType())).build()).execute().use {
            if (it.isSuccessful) parseListing(it.body?.string().orEmpty()) else emptyList()
        }
    } catch (e: Exception) {
        ErrorLog.warn("webdav", "the folder could not be listed", e); emptyList()
    }

    fun delete(folder: HttpUrl, user: String, password: String, name: String, http: OkHttpClient = client): Boolean = try {
        http.newCall(request(folder.newBuilder().addPathSegment(name).build(), user, password).delete().build()).execute().use { it.isSuccessful }
    } catch (e: Exception) {
        ErrorLog.warn("webdav", "an old copy could not be removed", e); false
    }

    private val RESPONSE = Regex("<(?:\\w+:)?response[ >].*?</(?:\\w+:)?response>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private val HREF = Regex("<(?:\\w+:)?href>\\s*(.*?)\\s*</(?:\\w+:)?href>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private val MODIFIED = Regex("<(?:\\w+:)?getlastmodified>\\s*(.*?)\\s*</(?:\\w+:)?getlastmodified>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))

    /** The files in a server's answer to a listing (folders, which end with "/", are left out). Names are the last part of the address, decoded. */
    internal fun parseListing(xml: String): List<Pair<String, Long>> {
        val format = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US)
        return RESPONSE.findAll(xml).mapNotNull { r ->
            val href = HREF.find(r.value)?.groupValues?.get(1) ?: return@mapNotNull null
            if (href.endsWith("/")) return@mapNotNull null
            val name = runCatching { URLDecoder.decode(href.substringAfterLast('/').replace("+", "%2B"), "UTF-8") }.getOrNull()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val at = MODIFIED.find(r.value)?.groupValues?.get(1)?.let { runCatching { format.parse(it)?.time }.getOrNull() } ?: 0L
            name to at
        }.toList()
    }

    /** Puts the copy in the folder and removes the old ones, keeping [keep]. */
    fun send(folder: HttpUrl, user: String, password: String, name: String, file: File, keep: Int = AutoBackup.KEEP, http: OkHttpClient = client): Status {
        val status = upload(folder, user, password, name, file, http)
        if (status != Status.Ok) return status
        val files = list(folder, user, password, http)
        AutoBackup.toRemove(files, keep).forEach { delete(folder, user, password, it, http) }
        return Status.Ok
    }
}
