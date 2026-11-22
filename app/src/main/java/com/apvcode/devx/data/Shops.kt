package com.apvcode.devx.data

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLEncoder
import java.util.Locale

/**
 * The online shops the assistant can look at, and the plain (no phone needed) logic around them: what a shop's search address is,
 * which links on a page are products, how a "prove you are a person" page is told from a real one, how a price and a review are read
 * from the data a shop publishes. The reading itself (the hidden browser, the requests) is in [ShopTools].
 */
class Shop(
    val id: String,
    val label: String,
    /** Hosts of the shop (the shop's own domains). */
    val hosts: List<String>,
    val search: (String) -> String,
    /** A link that is a product (as seen in an address). */
    val item: Regex,
    /** The page with the reviews of a product, when the shop keeps them apart. */
    val reviews: ((String) -> String)? = null,
    val about: String,
)

object Shops {
    private fun q(s: String) = URLEncoder.encode(s, "UTF-8")

    val all: List<Shop> = listOf(
        Shop("wildberries", "Wildberries", listOf("wildberries.ru", "wb.ru"), { "https://www.wildberries.ru/catalog/0/search.aspx?search=${q(it)}" },
            Regex("/catalog/\\d+/detail\\.aspx"), { it.substringBefore('?').removeSuffix("/").replace(Regex("/detail\\.aspx$"), "/feedbacks") }, "Russian marketplace: goods of all kinds, prices in roubles."),
        Shop("ozon", "Ozon", listOf("ozon.ru"), { "https://www.ozon.ru/search/?text=${q(it)}&from_global=true" },
            Regex("/product/[^/?#]*\\d{5,}"), { it.substringBefore('?').trimEnd('/') + "/reviews/" }, "Russian marketplace: goods of all kinds, prices in roubles."),
        Shop("market", "Yandex Market", listOf("market.yandex.ru", "market.yandex.com"), { "https://market.yandex.ru/search?text=${q(it)}" },
            Regex("/(?:product(?:--[^/]+)?|card/[^/]+)/\\d{5,}"), { it.substringBefore('?').trimEnd('/') + "/reviews" }, "Russian price comparison: the same product in many shops."),
        Shop("avito", "Avito", listOf("avito.ru"), { "https://www.avito.ru/all?q=${q(it)}" },
            Regex("_\\d{8,}(?:[?#]|$)"), null, "Russian classifieds: mostly used goods from people, and new ones from small sellers."),
        Shop("aliexpress", "AliExpress", listOf("aliexpress.com", "aliexpress.ru"), { "https://aliexpress.com/wholesale?SearchText=${q(it)}" },
            Regex("/(?:item|i)/\\d{8,}\\.html"), null, "Cheap goods shipped from China."),
        Shop("amazon", "Amazon", listOf("amazon.com"), { "https://www.amazon.com/s?k=${q(it)}" },
            Regex("/(?:dp|gp/product)/[A-Z0-9]{10}"), null, "The big global shop; prices in dollars."),
        Shop("ebay", "eBay", listOf("ebay.com"), { "https://www.ebay.com/sch/i.html?_nkw=${q(it)}" },
            Regex("/itm/(?:[^/?#]+/)?\\d{9,}"), null, "Auctions and shops: new and used goods from all over the world."),
        Shop("facebook", "Facebook Marketplace", listOf("facebook.com"), { "https://www.facebook.com/marketplace/search/?query=${q(it)}" },
            Regex("/marketplace/item/\\d+"), null, "Used goods from people nearby. Facebook shows most of it only to people who are logged in, so this is often thin."),
    )

    fun byId(id: String): Shop? = all.firstOrNull { it.id == id }

    /** "wb", "ozon.ru", "Yandex Market", "fb"... to a shop. */
    fun find(word: String): Shop? {
        val w = word.trim().lowercase().replace(Regex("[^a-z0-9а-яё.]"), "")
        if (w.isEmpty()) return null
        val alias = mapOf(
            "wb" to "wildberries", "вб" to "wildberries", "вайлдберриз" to "wildberries", "wildberries" to "wildberries",
            "ozon" to "ozon", "озон" to "ozon",
            "market" to "market", "yandexmarket" to "market", "яндексмаркет" to "market", "маркет" to "market", "ям" to "market",
            "avito" to "avito", "авито" to "avito",
            "ali" to "aliexpress", "aliexpress" to "aliexpress", "алиэкспресс" to "aliexpress", "али" to "aliexpress",
            "amazon" to "amazon", "амазон" to "amazon",
            "ebay" to "ebay", "ибей" to "ebay",
            "facebook" to "facebook", "fb" to "facebook", "marketplace" to "facebook", "facebookmarketplace" to "facebook", "фейсбук" to "facebook",
        )
        alias[w]?.let { return byId(it) }
        return all.firstOrNull { shop -> shop.hosts.any { w == it || w.endsWith(it) } }
    }

    /** The shop an address belongs to (its own domain or a subdomain), or null for any other site. */
    fun forUrl(url: String): Shop? {
        val host = runCatching { URI(url.trim()).host.orEmpty().lowercase() }.getOrDefault("")
        if (host.isEmpty()) return null
        return all.firstOrNull { s -> s.hosts.any { h -> host == h || host.endsWith(".$h") || (h == "amazon.com" && host.contains(".amazon.")) || (h == "ebay.com" && host.contains(".ebay.")) } }
    }

    fun isProduct(shop: Shop, url: String): Boolean = shop.item.containsMatchIn(url)

    /** One address for one product, without the tracking tail, so that the same product from two routes is listed once. */
    fun canonical(shop: Shop, url: String): String {
        if (shop.id == "amazon") {
            Regex("(https?://[^/]+)/(?:[^/]+/)?(?:dp|gp/product)/([A-Z0-9]{10})").find(url)?.let { return it.groupValues[1] + "/dp/" + it.groupValues[2] }
        }
        if (shop.id == "aliexpress") {
            // Its links come in several shapes (some even without a host): the number is the product.
            Regex("/(?:item|i)/(\\d{8,})\\.html").find(url)?.let { return "https://www.aliexpress.com/item/" + it.groupValues[1] + ".html" }
        }
        return url.substringBefore('#').substringBefore('?').trimEnd('/')
    }

    /** What to ask for when the person gave no shop: the shops that fit the language of the words. */
    fun defaultFor(query: String): List<Shop> =
        if (Regex("[\\u0400-\\u04FF]").containsMatchIn(query)) listOf("wildberries", "ozon", "market").mapNotNull(::byId)
        else listOf("amazon", "ebay", "aliexpress").mapNotNull(::byId)

    // ---------- blocked pages ----------

    private val BLOCK = Regex(
        "captcha|капч|not a robot|не робот|подозрительн|are you human|verify you are|checking your browser|just a moment|access denied|" +
            "доступ ограничен|проверка безопасности|automated access|unusual traffic|pardon our interruption|sorry, we just need to make sure|" +
            "подтвердите, что запросы|нам очень жаль|something went wrong|log into facebook|войдите в facebook|see more on facebook|attention required|" +
            "нет соединения|выключите vpn|проблема с ip|проблемами с ip|инцидент:|error 403|429 too many",
        RegexOption.IGNORE_CASE,
    )

    /** True when a page (its title and the start of its text) is a check that wants a person, or a wall, not the shop itself. */
    fun blocked(title: String, text: String): Boolean = BLOCK.containsMatchIn(title + "\n" + text.take(900))

    // ---------- search results read from a page ----------

    class Item(val url: String, val text: String, val alt: String = "", val image: String = "")

    /**
     * The cards found on a search page by the script in [ShopTools] (a JSON list of address and card text), kept to real product links,
     * each product once, in the order of the page.
     */
    fun items(shop: Shop, json: String, limit: Int): List<Item> {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return emptyList()
        val array = root.optJSONArray("items") ?: return emptyList()
        val seen = HashSet<String>()
        val out = ArrayList<Item>()
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val url = o.optString("u")
            if (!isProduct(shop, url)) continue
            if (!seen.add(canonical(shop, url))) continue
            val text = tidyCard(o.optString("t"))
            val alt = o.optString("a").replace(Regex("\\s+"), " ").trim()
            if (text.length < 8 && alt.length < 8) continue
            out += Item(canonical(shop, url), text, alt, imageUrl(o.optString("i")))
            if (out.size >= limit) break
        }
        return out
    }

    /** A picture address worth passing on: https, not an inline picture, not too long (the assistant has to copy it). */
    fun imageUrl(raw: String): String {
        val u = raw.trim().let { if (it.startsWith("//")) "https:$it" else it }
        return if (u.startsWith("https://") && u.length <= 300 && !u.contains(' ')) u else ""
    }

    /** The card's text on one line: spaces squeezed, the usual shop noise ("add to cart") dropped, not too long. */
    fun tidyCard(raw: String): String {
        var t = raw.replace(Regex("\\s+"), " ").trim()
        t = t.replace(Regex("(?i)\\b(add to cart|add to basket|add to watchlist|в корзину|купить|быстрый просмотр|sponsored|реклама|promoted|save for later)\\b"), " ")
        t = t.replace(Regex("(?i)opens in a new window or tab|derosnopS|derotomorP"), " ")
        return t.replace(Regex("\\s+"), " ").trim().take(260)
    }

    // ---------- Wildberries: its own public data ----------

    fun wbSearchUrl(query: String): String =
        "https://search.wb.ru/exactmatch/ru/common/v13/search?appType=1&curr=rub&dest=-1257786&lang=ru&page=1&query=${q(query)}&resultset=catalog&sort=popular&spp=30"

    fun wbCardUrl(id: String): String = "https://card.wb.ru/cards/v2/detail?appType=1&curr=rub&dest=-1257786&nm=$id"

    /** Products from WB's search answer (the shape has changed over the years; both the old and new price fields are understood). */
    fun wbProducts(json: String, limit: Int): List<Item> {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return emptyList()
        val products = root.optJSONObject("data")?.optJSONArray("products") ?: root.optJSONArray("products") ?: return emptyList()
        val out = ArrayList<Item>()
        for (i in 0 until products.length()) {
            val p = products.optJSONObject(i) ?: continue
            val id = p.optLong("id")
            if (id <= 0) continue
            out += Item("https://www.wildberries.ru/catalog/$id/detail.aspx", wbLine(p))
            if (out.size >= limit) break
        }
        return out
    }

    private fun wbPrice(p: JSONObject): String? {
        val sizes = p.optJSONArray("sizes")
        val price = sizes?.optJSONObject(0)?.optJSONObject("price")
        val kopecks = price?.optLong("product", 0)?.takeIf { it > 0 } ?: p.optLong("salePriceU", 0).takeIf { it > 0 } ?: p.optLong("priceU", 0).takeIf { it > 0 }
        return kopecks?.let { "%,d ₽".format(Locale.US, it / 100).replace(',', ' ') }
    }

    private fun wbLine(p: JSONObject): String = listOfNotNull(
        p.optString("brand").takeIf { it.isNotBlank() }, p.optString("name").takeIf { it.isNotBlank() }, wbPrice(p),
        p.optDouble("reviewRating", 0.0).takeIf { it > 0 }?.let { "★ %.1f".format(Locale.US, it) },
        p.optInt("feedbacks", 0).takeIf { it > 0 }?.let { "$it отзывов" },
    ).joinToString(" · ")

    /** A product's facts from WB's card answer: one text block plus the id that its reviews are kept under. */
    class WbCard(val text: String, val reviewsKey: Long)

    fun wbCard(json: String): WbCard? {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return null
        val p = (root.optJSONObject("data")?.optJSONArray("products") ?: root.optJSONArray("products"))?.optJSONObject(0) ?: return null
        val lines = listOfNotNull(wbLine(p), p.optString("supplier").takeIf { it.isNotBlank() }?.let { "Seller: $it" })
        return WbCard(lines.joinToString("\n"), p.optLong("root", 0))
    }

    /** Reviews from WB's feedback answer: stars and the words, the most recent first. */
    fun wbReviews(json: String, limit: Int): List<String> {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return emptyList()
        val list = root.optJSONArray("feedbacks") ?: return emptyList()
        val out = ArrayList<String>()
        for (i in 0 until list.length()) {
            val f = list.optJSONObject(i) ?: continue
            val body = listOf(f.optString("text"), f.optString("pros").takeIf { it.isNotBlank() }?.let { "Pros: $it" }, f.optString("cons").takeIf { it.isNotBlank() }?.let { "Cons: $it" })
                .filterNotNull().filter { it.isNotBlank() }.joinToString(" ").replace(Regex("\\s+"), " ").trim()
            if (body.isEmpty()) continue
            val stars = f.optInt("productValuation", 0)
            out += (if (stars > 0) "★$stars " else "") + body.take(600)
            if (out.size >= limit) break
        }
        return out
    }

    // ---------- a product page ----------

    /** What the page publishes about the product for search engines (JSON-LD "Product"): the most reliable source, when it is there. */
    class Facts(val name: String, val description: String, val brand: String, val price: String, val rating: String, val reviews: List<String>)

    fun facts(blocks: List<String>): Facts? {
        for (raw in blocks) {
            val node = runCatching { org.json.JSONTokener(raw).nextValue() }.getOrNull() ?: continue
            val product = findProduct(node) ?: continue
            val name = product.optString("name")
            if (name.isBlank()) continue
            val offers = product.opt("offers").let { if (it is JSONArray) it.optJSONObject(0) else it as? JSONObject }
            val price = offers?.let { o ->
                val v = o.optString("price").ifBlank { o.optString("lowPrice") }
                if (v.isBlank()) "" else (v + " " + o.optString("priceCurrency")).trim()
            }.orEmpty()
            val rating = product.optJSONObject("aggregateRating")?.let { r ->
                val v = r.optString("ratingValue")
                val n = r.optString("reviewCount").ifBlank { r.optString("ratingCount") }
                if (v.isBlank()) "" else "★ $v" + if (n.isNotBlank()) " ($n)" else ""
            }.orEmpty()
            val brand = product.opt("brand").let { if (it is JSONObject) it.optString("name") else (it as? String).orEmpty() }
            val reviews = ArrayList<String>()
            val rv = product.opt("review")
            val list = when (rv) { is JSONArray -> (0 until rv.length()).mapNotNull { rv.optJSONObject(it) }; is JSONObject -> listOf(rv); else -> emptyList() }
            for (r in list) {
                val body = r.optString("reviewBody").ifBlank { r.optString("description") }.replace(Regex("\\s+"), " ").trim()
                if (body.isEmpty()) continue
                val stars = r.optJSONObject("reviewRating")?.optString("ratingValue").orEmpty()
                reviews += (if (stars.isNotBlank()) "★$stars " else "") + body.take(600)
            }
            return Facts(name, product.optString("description").replace(Regex("\\s+"), " ").trim().take(1500), brand, price, rating, reviews)
        }
        return null
    }

    private fun findProduct(node: Any?): JSONObject? = when (node) {
        is JSONObject -> {
            val type = node.opt("@type")?.toString().orEmpty()
            if (type.contains("Product")) node
            else (node.optJSONArray("@graph")?.let { g -> (0 until g.length()).firstNotNullOfOrNull { findProduct(g.opt(it)) } })
        }
        is JSONArray -> (0 until node.length()).firstNotNullOfOrNull { findProduct(node.opt(it)) }
        else -> null
    }
}
