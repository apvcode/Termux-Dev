package com.apvcode.devx.ui.chat

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.apvcode.devx.R
import com.apvcode.devx.data.ShopImages
import com.apvcode.devx.data.Shops
import com.apvcode.devx.ui.components.pressable
import com.apvcode.devx.ui.theme.DevxColors
import com.apvcode.devx.ui.theme.DevxType
import org.json.JSONObject

/** One product as the assistant wrote it into a devx-shop block. Everything on it comes from the block; nothing is looked up when it is drawn. */
class ShopProduct(
    val shop: String,
    val title: String,
    val price: String,
    val note: String,
    val rating: String,
    val reviews: String,
    val image: String,
    val url: String,
) {
    /** The shop the address belongs to; a card whose address is not a page of one of the known shops cannot be opened (a link written by a page's text is never trusted). */
    val openable: Boolean get() = url.startsWith("https://") && Shops.forUrl(url) != null
}

/** The products of a devx-shop block: {"products":[{...}]} or one product written directly. At most six. */
fun parseShopProducts(json: String): List<ShopProduct> {
    val root = runCatching { JSONObject(json) }.getOrNull() ?: return emptyList()
    val array = root.optJSONArray("products")
    val objects = if (array != null) (0 until minOf(array.length(), 6)).mapNotNull { array.optJSONObject(it) } else listOf(root)
    fun JSONObject.text(name: String): String = if (has(name) && !isNull(name)) opt(name).toString().replace(Regex("\\s+"), " ").trim() else ""
    return objects.mapNotNull { o ->
        val url = o.text("url")
        val title = o.text("title").ifBlank { o.text("name") }
        if (title.isBlank()) return@mapNotNull null
        val shop = Shops.forUrl(url)?.id ?: Shops.find(o.text("store"))?.id.orEmpty()
        val image = o.text("image").takeIf { it.startsWith("https://") && it.length <= 400 }.orEmpty()
        ShopProduct(shop, title.take(160), o.text("price").take(40), o.text("note").ifBlank { o.text("description") }.take(260), o.text("rating").take(6), o.text("reviews").take(12), image, url)
    }
}

private class Brand(val label: String, val color: Color, val glyph: String, val ink: Color = Color.White)

private val BRANDS = mapOf(
    "wildberries" to Brand("Wildberries", Color(0xFFCB11AB), "WB"),
    "ozon" to Brand("Ozon", Color(0xFF005BFF), "O"),
    "market" to Brand("Яндекс Маркет", Color(0xFFFFDD00), "М", Color(0xFF1B1B1B)),
    "avito" to Brand("Avito", Color(0xFF0099F7), "A"),
    "aliexpress" to Brand("AliExpress", Color(0xFFE62E04), "Ali"),
    "amazon" to Brand("Amazon", Color(0xFFFF9900), "a", Color(0xFF111111)),
    "ebay" to Brand("eBay", Color(0xFF0064D2), "e"),
    "facebook" to Brand("Facebook Marketplace", Color(0xFF1877F2), "f"),
)

/** The shop's mark: its colour and first letters on a rounded square (a small badge, in place of the shop's own logo). */
@Composable
private fun ShopMark(shop: String, size: Dp) {
    val brand = BRANDS[shop]
    Box(Modifier.size(size).clip(RoundedCornerShape(size * 0.28f)).background(brand?.color ?: DevxColors.CardInner), contentAlignment = Alignment.Center) {
        Text(brand?.glyph ?: "?", color = brand?.ink ?: DevxColors.TextPrimary, fontWeight = FontWeight.Bold, fontSize = (size.value * (if ((brand?.glyph?.length ?: 1) > 1) 0.36f else 0.5f)).sp, maxLines = 1)
    }
}

/**
 * Products of online shops as cards: the picture, the shop's mark and name, the name of the product, the price, the stars and a few lines
 * about it, and a button that opens it in the shop. {"products":[{"url":"https://...","title":"...","price":"2 499 ₽","note":"...","rating":4.8,"reviews":1200,"image":"https://..."}]}
 */
@Composable
fun ShopBlock(json: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val products = remember(json) { parseShopProducts(json) }
    if (products.isEmpty()) return
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        products.forEach { p -> ShopCard(p) { openProduct(context, p) } }
    }
}

@Composable
private fun ShopCard(p: ShopProduct, onOpen: () -> Unit) {
    val context = LocalContext.current
    // The photo the assistant wrote, or the one kept when the product was found.
    val image = remember(p.url, p.image) { p.image.ifEmpty { if (p.openable) ShopImages.get(context, p.url).orEmpty() else "" } }
    val shape = RoundedCornerShape(22.dp)
    val brand = BRANDS[p.shop]
    Row(
        Modifier.fillMaxWidth().clip(shape).background(DevxColors.Card).then(if (p.openable) Modifier.pressable(scaleTo = 0.985f, haptic = false, onClick = onOpen) else Modifier).padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.size(96.dp).clip(RoundedCornerShape(16.dp)).background(DevxColors.CardInner), contentAlignment = Alignment.Center) {
            // The shop's mark is under the photo: it shows while the photo loads and stays if the photo cannot be loaded.
            ShopMark(p.shop, 44.dp)
            if (image.isNotEmpty()) AsyncImage(model = image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(96.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                ShopMark(p.shop, 18.dp)
                Text(brand?.label ?: stringResource(R.string.shop_store), style = DevxType.Caption, color = DevxColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(p.title, style = DevxType.BodyStrong, color = DevxColors.TextPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (p.price.isNotEmpty() || p.rating.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (p.price.isNotEmpty()) Text(p.price, style = DevxType.Title, color = DevxColors.TextPrimary, maxLines = 1)
                if (p.rating.isNotEmpty()) Text(
                    "★ " + p.rating + if (p.reviews.isNotEmpty()) " · " + p.reviews else "",
                    style = DevxType.Caption, color = DevxColors.TextPrimary, maxLines = 1,
                    modifier = Modifier.clip(RoundedCornerShape(50)).background(DevxColors.CardInner).padding(horizontal = 9.dp, vertical = 4.dp),
                )
            }
            if (p.note.isNotEmpty()) Text(p.note, style = DevxType.Caption, color = DevxColors.TextSecondary, maxLines = 3, overflow = TextOverflow.Ellipsis)
            if (p.openable) Text(
                stringResource(R.string.apps_open), style = DevxType.Caption.copy(fontWeight = FontWeight.Medium), color = DevxColors.OnAccent,
                modifier = Modifier.padding(top = 6.dp).clip(RoundedCornerShape(50)).background(DevxColors.Accent).pressable(scaleTo = 0.94f, haptic = false, onClick = onOpen).padding(horizontal = 18.dp, vertical = 8.dp),
            )
        }
    }
}

/** The product's page: in the shop's own app if the phone has it, else in the browser. */
private fun openProduct(context: Context, p: ShopProduct) {
    if (!p.openable) return
    val ok = runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(p.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
    if (!ok) Toast.makeText(context, R.string.rich_cannot_open, Toast.LENGTH_SHORT).show()
}
