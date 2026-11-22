package com.apvcode.devx

import com.apvcode.devx.data.Shops
import com.apvcode.devx.ui.chat.parseShopProducts
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShopsTest {
    @Test fun storesAreFoundByNameAndByAddress() {
        assertEquals("wildberries", Shops.find("WB")?.id)
        assertEquals("market", Shops.find("Yandex Market")?.id)
        assertEquals("facebook", Shops.find("fb")?.id)
        assertEquals("ozon", Shops.find("озон")?.id)
        assertNull(Shops.find("nonsense"))
        assertEquals("ozon", Shops.forUrl("https://www.ozon.ru/product/naushniki-1234567/")?.id)
        assertEquals("amazon", Shops.forUrl("https://www.amazon.de/dp/B0DB52X2ZW")?.id)
        assertEquals("ebay", Shops.forUrl("https://www.ebay.co.uk/itm/860163356101")?.id)
        assertNull(Shops.forUrl("https://evil.example/ozon.ru/product/1234567"))
        assertNull(Shops.forUrl("https://notozon.ru/"))
    }

    @Test fun languageChoosesTheShops() {
        assertEquals(listOf("wildberries", "ozon", "market"), Shops.defaultFor("наушники").map { it.id })
        assertEquals(listOf("amazon", "ebay", "aliexpress"), Shops.defaultFor("earbuds").map { it.id })
    }

    @Test fun productLinksAreTold() {
        assertTrue(Shops.isProduct(Shops.byId("wildberries")!!, "https://www.wildberries.ru/catalog/123456/detail.aspx?targetUrl=GP"))
        assertFalse(Shops.isProduct(Shops.byId("wildberries")!!, "https://www.wildberries.ru/catalog/elektronika"))
        assertTrue(Shops.isProduct(Shops.byId("avito")!!, "https://www.avito.ru/moskva/telefony/iphone_12_1234567890"))
        assertFalse(Shops.isProduct(Shops.byId("avito")!!, "https://www.avito.ru/moskva/telefony"))
        assertTrue(Shops.isProduct(Shops.byId("ebay")!!, "https://www.ebay.com/itm/237127080371"))
        assertTrue(Shops.isProduct(Shops.byId("market")!!, "https://market.yandex.ru/product--naushniki/1234567890?sku=1"))
    }

    @Test fun addressesAreMadeOne() {
        val amazon = Shops.byId("amazon")!!
        assertEquals("https://www.amazon.com/dp/B0DB52X2ZW", Shops.canonical(amazon, "https://www.amazon.com/Some-Name/dp/B0DB52X2ZW/ref=sr_1_3?keywords=x"))
        val ali = Shops.byId("aliexpress")!!
        assertEquals("https://www.aliexpress.com/item/1005009371965599.html", Shops.canonical(ali, "https://item/1005009371965599.html"))
        assertEquals("https://www.ebay.com/itm/1234567890", Shops.canonical(Shops.byId("ebay")!!, "https://www.ebay.com/itm/1234567890?hash=1#x"))
    }

    @Test fun checkPagesAreNotMistakenForShops() {
        assertTrue(Shops.blocked("Доступ ограничен: проблема с IP", ""))
        assertTrue(Shops.blocked("Ozon", "Похоже, нет соединения\nВыключите VPN"))
        assertTrue(Shops.blocked("Are you not a robot?", ""))
        assertTrue(Shops.blocked("Amazon.com", "Sorry, we just need to make sure you're not a robot"))
        assertFalse(Shops.blocked("Xiaomi Earbuds for sale | eBay", "Skip to main content Results Filters"))
    }

    @Test fun cardsFromAPageAreKeptOnceInOrder() {
        val shop = Shops.byId("ebay")!!
        val page = JSONObject().put("items", JSONArray()
            .put(JSONObject().put("u", "https://www.ebay.com/itm/237127080371?x=1").put("t", "Xiaomi Buds Opens in a new window or tab Brand New $79.50 derosnopS"))
            .put(JSONObject().put("u", "https://www.ebay.com/itm/237127080371").put("t", "same product again, with a long enough text"))
            .put(JSONObject().put("u", "https://www.ebay.com/b/Brand-Outlet/bn_7115532402").put("t", "not a product at all, but long text"))
            .put(JSONObject().put("u", "https://www.ebay.com/itm/860161583336").put("t", "Second product $12.00 Buy It Now")))
        val items = Shops.items(shop, page.toString(), 10)
        assertEquals(2, items.size)
        assertEquals("https://www.ebay.com/itm/237127080371", items[0].url)
        assertEquals("Xiaomi Buds Brand New $79.50", items[0].text)
    }

    @Test fun wildberriesSearchAnswerIsRead() {
        val json = """{"data":{"products":[{"id":123456,"brand":"Xiaomi","name":"Наушники Buds","reviewRating":4.8,"feedbacks":1200,"sizes":[{"price":{"product":249900}}]},{"id":0,"name":"skipped"}]}}"""
        val items = Shops.wbProducts(json, 5)
        assertEquals(1, items.size)
        assertEquals("https://www.wildberries.ru/catalog/123456/detail.aspx", items[0].url)
        assertEquals("Xiaomi · Наушники Buds · 2 499 ₽ · ★ 4.8 · 1200 отзывов", items[0].text)
        assertTrue(Shops.wbProducts("not json", 5).isEmpty())
    }

    @Test fun wildberriesReviewsAndCard() {
        val card = Shops.wbCard("""{"data":{"products":[{"id":1,"root":777,"name":"Buds","brand":"X","supplier":"Shop LLC"}]}}""")
        assertNotNull(card)
        assertEquals(777L, card!!.reviewsKey)
        assertTrue(card.text.contains("Seller: Shop LLC"))
        val reviews = Shops.wbReviews("""{"feedbacks":[{"text":"Good sound","pros":"bass","cons":"","productValuation":5},{"text":"","pros":"","cons":""}]}""", 10)
        assertEquals(listOf("★5 Good sound Pros: bass"), reviews)
    }

    @Test fun productFactsComeFromTheShopsOwnMarkup() {
        val ld = """{"@context":"https://schema.org","@graph":[{"@type":"WebPage"},{"@type":"Product","name":"Buds 6","description":"Wireless  earbuds","brand":{"@type":"Brand","name":"Xiaomi"},
            "offers":{"@type":"Offer","price":"51.01","priceCurrency":"USD"},"aggregateRating":{"ratingValue":"4.6","reviewCount":"2352"},
            "review":[{"reviewBody":"Great little earbuds for the price","reviewRating":{"ratingValue":"5"}}]}]}"""
        val f = Shops.facts(listOf("not json", ld))
        assertNotNull(f)
        assertEquals("Buds 6", f!!.name)
        assertEquals("Xiaomi", f.brand)
        assertEquals("51.01 USD", f.price)
        assertEquals("★ 4.6 (2352)", f.rating)
        assertEquals("Wireless earbuds", f.description)
        assertEquals(listOf("★5 Great little earbuds for the price"), f.reviews)
        assertNull(Shops.facts(listOf("""{"@type":"WebPage"}""")))
    }

    @Test fun imagesAreKeptOnlyWhenTheyCanBeCopied() {
        assertEquals("https://img.example/a.jpg", Shops.imageUrl("//img.example/a.jpg"))
        assertEquals("", Shops.imageUrl("data:image/png;base64,AAAA"))
        assertEquals("", Shops.imageUrl("http://insecure.example/a.jpg"))
        assertEquals("", Shops.imageUrl("https://img.example/" + "a".repeat(400)))
        val page = JSONObject().put("items", JSONArray().put(JSONObject().put("u", "https://www.ebay.com/itm/860161583336").put("t", "Second product $12.00 Buy It Now").put("i", "https://i.ebayimg.com/x.jpg")))
        assertEquals("https://i.ebayimg.com/x.jpg", Shops.items(Shops.byId("ebay")!!, page.toString(), 5)[0].image)
    }

    @Test fun shopCardsAreReadFromTheBlock() {
        val block = """{"products":[
            {"url":"https://www.ozon.ru/product/naushniki-1234567/","title":"Buds 6","price":"2 499 ₽","rating":4.8,"reviews":1200,"note":"Light earbuds","image":"https://cdn.example/a.jpg"},
            {"url":"https://evil.example/pay","store":"ozon","title":"Fake","image":"http://insecure.example/a.jpg"},
            {"title":""}]}"""
        val cards = parseShopProducts(block)
        assertEquals(2, cards.size)
        assertEquals("ozon", cards[0].shop)
        assertEquals("4.8", cards[0].rating)
        assertEquals("1200", cards[0].reviews)
        assertTrue(cards[0].openable)
        assertEquals("https://cdn.example/a.jpg", cards[0].image)
        // A link that is not a page of one of the shops is never opened, whatever the card says; an insecure picture is dropped.
        assertFalse(cards[1].openable)
        assertEquals("", cards[1].image)
        assertEquals(1, parseShopProducts("""{"title":"Single","url":"https://www.amazon.com/dp/B0DB52X2ZW"}""").size)
        assertTrue(parseShopProducts("not json").isEmpty())
    }
}
