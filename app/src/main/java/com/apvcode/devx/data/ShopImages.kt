package com.apvcode.devx.data

import android.content.Context
import org.json.JSONObject

/**
 * The main picture of each product the shop tools have seen, by the product's address. A card is drawn long after the search that found
 * the product (and again each time the chat is opened), so the picture is kept: the assistant does not have to copy a long address, and
 * a card it wrote without one still gets its photo. The newest 300 are kept.
 */
object ShopImages {
    private const val FILE = "shop_images"
    private const val MAX = 300
    private val lock = Any()
    private var map: LinkedHashMap<String, String>? = null

    private fun load(context: Context): LinkedHashMap<String, String> = map ?: LinkedHashMap<String, String>().also { m ->
        runCatching {
            val o = JSONObject(context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString("images", "{}") ?: "{}")
            o.keys().forEach { k -> o.optString(k).takeIf { it.isNotBlank() }?.let { m[k] = it } }
        }.logged("shopimages")
        map = m
    }

    private fun keyOf(url: String): String = Shops.forUrl(url)?.let { Shops.canonical(it, url) } ?: url.substringBefore('?').trimEnd('/')

    fun put(context: Context, url: String, image: String) {
        if (image.isBlank() || Shops.forUrl(url) == null) return
        synchronized(lock) {
            val m = load(context)
            val k = keyOf(url)
            m.remove(k)
            m[k] = image
            while (m.size > MAX) m.remove(m.keys.first())
            context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString("images", JSONObject(m as Map<*, *>).toString()).apply()
        }
    }

    fun get(context: Context, url: String): String? = synchronized(lock) { load(context)[keyOf(url)] }
}
