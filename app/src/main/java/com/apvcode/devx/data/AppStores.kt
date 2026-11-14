package com.apvcode.devx.data

import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.TimeUnit

/** One app of a store, as its card shows it. */
class StoreApp(
    val store: String,
    val id: String,
    val name: String,
    val developer: String,
    val summary: String,
    val icon: String?,
    val rating: Double?,
    val ratings: Int?,
    val installs: String?,
    val price: String?,
    val url: String,
)

/**
 * Looks for apps in the stores' public pages, and reads one app's page: name, who made it, a line about it, the picture, the
 * stars, the number of installs. Google Play and F-Droid; more stores can be added the same way.
 */
object AppStores {
    const val PLAY = "google_play"
    const val FDROID = "f_droid"

    val stores = listOf(PLAY, FDROID)

    fun label(store: String) = when (store) { FDROID -> "F-Droid"; else -> "Google Play" }

    private val http = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).followRedirects(true).build()
    private const val UA = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
    private val cache = LruCache<String, StoreApp>(120)

    fun key(store: String, id: String) = "$store:$id"

    fun normalise(store: String) = when (store.lowercase().replace('-', '_').replace(' ', '_')) { "fdroid", "f_droid" -> FDROID; else -> PLAY }

    private fun hl() = Locale.getDefault().language.ifEmpty { "en" }
    private fun gl() = Locale.getDefault().country.ifEmpty { "US" }

    private fun get(url: String): String? = runCatching {
        http.newCall(Request.Builder().url(url).header("User-Agent", UA).header("Accept-Language", "${hl()},en;q=0.8").build()).execute().use { r ->
            if (!r.isSuccessful) null else r.body?.string()
        }
    }.getOrNull()

    /** The ids of the apps the store finds for these words, best match first. */
    suspend fun search(store: String, query: String, limit: Int = 4): List<String> = withContext(Dispatchers.IO) {
        val q = java.net.URLEncoder.encode(query, "UTF-8")
        when (normalise(store)) {
            FDROID -> {
                val body = get("https://search.f-droid.org/api/search_apps?q=$q") ?: return@withContext emptyList()
                runCatching {
                    val apps = JSONObject(body).optJSONArray("apps") ?: return@runCatching emptyList<String>()
                    (0 until minOf(apps.length(), limit)).map { apps.getJSONObject(it).optString("url").substringAfterLast('/') }.filter { it.isNotBlank() }
                }.getOrDefault(emptyList())
            }
            else -> {
                val html = get("https://play.google.com/store/search?q=$q&c=apps&hl=${hl()}&gl=${gl()}") ?: return@withContext emptyList()
                val ids = LinkedHashSet<String>()
                for (m in Regex("/store/apps/details\\?id=([a-zA-Z0-9._]+)").findAll(html)) { ids += m.groupValues[1]; if (ids.size >= limit) break }
                ids.toList()
            }
        }
    }

    /** One app, from its page (kept for a while). */
    suspend fun details(store: String, id: String): StoreApp? {
        val kind = normalise(store)
        cache.get(key(kind, id))?.let { return it }
        val app = withContext(Dispatchers.IO) { if (kind == FDROID) fdroid(id) else play(id) }
        if (app != null) cache.put(key(kind, id), app)
        return app
    }

    suspend fun detailsOf(store: String, ids: List<String>): List<StoreApp?> = coroutineScope { ids.map { async { details(store, it) } }.awaitAll() }

    private fun playUrl(id: String) = "https://play.google.com/store/apps/details?id=$id"

    private fun play(id: String): StoreApp? {
        val html = get("https://play.google.com/store/apps/details?id=$id&hl=${hl()}&gl=${gl()}") ?: return null
        val ld = Regex("<script type=\"application/ld\\+json\"[^>]*>(.*?)</script>", RegexOption.DOT_MATCHES_ALL).find(html)?.groupValues?.get(1) ?: return null
        val o = runCatching { JSONObject(ld) }.getOrNull() ?: return null
        val rating = o.optJSONObject("aggregateRating")
        val offer = o.optJSONArray("offers")?.optJSONObject(0)
        val price = offer?.optString("price")?.toDoubleOrNull()?.let { p -> if (p == 0.0) "free" else "${offer.optString("price")} ${offer.optString("priceCurrency")}" }
        // The number of installs is written under "Downloads" in the page itself, not in its data block.
        val installs = Regex(">(\\d[\\d.,\\s]*[KkMmBbКкМмТт]?\\+)</div>\\s*<div[^>]*>[^<]{3,20}</div>").findAll(html).map { it.groupValues[1].trim() }.firstOrNull()
        return StoreApp(
            store = PLAY, id = id, name = o.optString("name"), developer = o.optJSONObject("author")?.optString("name").orEmpty(),
            summary = o.optString("description").take(240), icon = o.optString("image").takeIf { it.startsWith("https://") },
            rating = rating?.optString("ratingValue")?.toDoubleOrNull(), ratings = rating?.optString("ratingCount")?.toIntOrNull(),
            installs = installs, price = price, url = playUrl(id),
        )
    }

    private fun fdroid(id: String): StoreApp? {
        val search = get("https://search.f-droid.org/api/search_apps?q=${java.net.URLEncoder.encode(id, "UTF-8")}") ?: return null
        val apps = runCatching { JSONObject(search).optJSONArray("apps") }.getOrNull() ?: return null
        val match = (0 until apps.length()).map { apps.getJSONObject(it) }.firstOrNull { it.optString("url").substringAfterLast('/') == id } ?: return null
        return StoreApp(
            store = FDROID, id = id, name = match.optString("name"), developer = "", summary = match.optString("summary"),
            icon = match.optString("icon").takeIf { it.startsWith("https://") && !it.contains("ic_repo_app_default") },
            rating = null, ratings = null, installs = null, price = "free", url = match.optString("url").ifBlank { "https://f-droid.org/packages/$id" },
        )
    }
}
