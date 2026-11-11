package com.apvcode.devx.ui.chat

import androidx.compose.foundation.layout.height
import com.apvcode.devx.ui.components.rememberCopied
import com.apvcode.devx.ui.components.CopyIcon
import com.apvcode.devx.ui.components.pressable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.svg.SvgDecoder
import com.apvcode.devx.R
import com.apvcode.devx.ui.components.PillButton
import com.apvcode.devx.ui.theme.DevxColors
import com.apvcode.devx.ui.theme.DevxType

/** Outlined frame used by cards inside a message. */
@Composable
fun CardFrame(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(24.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .border(1.dp, DevxColors.Outline, shape)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

/**
 * A picture the model drew as SVG, alive: it plays in a small isolated browser, so animations (SMIL, CSS),
 * scripts and things to press or drag all work. The browser has no network, no files and no way back into
 * the app: all it can do is draw what is in the picture.
 */
@Composable
fun SvgView(caption: String, svg: String, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(20.dp)
    val aspect = remember(svg) { svgAspect(svg) }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        androidx.compose.foundation.layout.BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(androidx.compose.ui.graphics.Color(0xFF101010))
                .border(1.dp, DevxColors.Outline, shape),
        ) {
            val height = (maxWidth / aspect).coerceIn(90.dp, 680.dp)
            androidx.compose.ui.viewinterop.AndroidView(
                factory = { context -> sandboxedWebView(context) },
                update = { web ->
                    if (web.tag != svg) {
                        web.tag = svg
                        web.loadDataWithBaseURL("about:blank", svgPage(svg), "text/html", "utf-8", null)
                    }
                },
                onRelease = { web ->
                    web.stopLoading()
                    web.destroy()
                },
                modifier = Modifier.fillMaxWidth().height(height),
            )
        }
        if (caption.isNotEmpty()) {
            Text(caption, style = DevxType.Caption, color = DevxColors.TextSecondary)
        }
    }
}

/** Width over height from the viewBox (or the width and height), the shape the picture asks for. */
internal fun svgAspect(svg: String): Float {
    val head = svg.substringBefore('>', "").take(2000)
    Regex("viewBox\\s*=\\s*[\"']\\s*[-\\d.]+[ ,]+[-\\d.]+[ ,]+([\\d.]+)[ ,]+([\\d.]+)").find(head)?.let { m ->
        val w = m.groupValues[1].toFloatOrNull()
        val h = m.groupValues[2].toFloatOrNull()
        if (w != null && h != null && w > 0f && h > 0f) return w / h
    }
    val w = Regex("\\swidth\\s*=\\s*[\"']([\\d.]+)(?:px)?[\"']").find(head)?.groupValues?.get(1)?.toFloatOrNull()
    val h = Regex("\\sheight\\s*=\\s*[\"']([\\d.]+)(?:px)?[\"']").find(head)?.groupValues?.get(1)?.toFloatOrNull()
    if (w != null && h != null && w > 0f && h > 0f) return w / h
    return 4f / 3f
}

private fun svgPage(svg: String): String =
    "<!doctype html><html><head><meta charset=\"utf-8\">" +
        "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no\">" +
        // Nothing may be fetched: only what is written in the picture.
        "<meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; img-src data:; font-src data:\">" +
        "<style>html,body{margin:0;padding:0;background:transparent;color:#fff;-webkit-tap-highlight-color:transparent;" +
        "-webkit-user-select:none;user-select:none;touch-action:manipulation}" +
        "body>svg{display:block;width:100%;height:auto}</style></head><body>" + svg + "</body></html>"

@android.annotation.SuppressLint("SetJavaScriptEnabled")
private fun sandboxedWebView(context: android.content.Context): android.webkit.WebView =
    android.webkit.WebView(context).apply {
        setBackgroundColor(android.graphics.Color.TRANSPARENT)
        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false
        overScrollMode = android.view.View.OVER_SCROLL_NEVER
        isLongClickable = false
        setOnLongClickListener { true }
        isHapticFeedbackEnabled = false
        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = false
            allowFileAccess = false
            allowContentAccess = false
            blockNetworkLoads = true
            setSupportZoom(false)
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
        }
        webViewClient = object : android.webkit.WebViewClient() {
            override fun shouldOverrideUrlLoading(view: android.webkit.WebView?, request: android.webkit.WebResourceRequest?) = true

            override fun shouldInterceptRequest(view: android.webkit.WebView?, request: android.webkit.WebResourceRequest?): android.webkit.WebResourceResponse? {
                val url = request?.url?.toString().orEmpty()
                return if (url.startsWith("about:") || url.startsWith("data:")) null
                else android.webkit.WebResourceResponse("text/plain", "utf-8", java.io.ByteArrayInputStream(ByteArray(0)))
            }
        }
    }

@Composable
fun CodeView(language: String, code: String, modifier: Modifier = Modifier) {
    val clipboard = LocalClipboardManager.current
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(DevxColors.CardInner)
            .border(1.dp, DevxColors.Outline, shape),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(DevxColors.Card)
                .padding(start = 14.dp, end = 6.dp)
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(language, style = DevxType.Caption, color = DevxColors.TextSecondary, modifier = Modifier.weight(1f))
            var copied by rememberCopied()
            Row(
                Modifier
                    .pressable(scaleTo = 0.92f) {
                        clipboard.setText(AnnotatedString(code))
                        copied = true
                    }
                    .clip(CircleShape)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                CopyIcon(copied, DevxColors.TextSecondary, null, Modifier.size(16.dp))
                Text(stringResource(R.string.code_copy), style = DevxType.Caption, color = DevxColors.TextSecondary)
            }
        }
        Box(Modifier.horizontalScroll(rememberScrollState()).padding(14.dp)) {
            Text(code, style = DevxType.Mono, color = DevxColors.TextPrimary)
        }
    }
}

/** The agent on the computer wants permission to run something risky. */
@Composable
fun ApprovalCard(command: String, modifier: Modifier = Modifier) {
    var decision by remember { mutableStateOf<Boolean?>(null) }
    val shape = RoundedCornerShape(24.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .border(1.dp, DevxColors.Warning.copy(alpha = 0.6f), shape)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(10.dp).background(DevxColors.Warning, CircleShape))
            Text(stringResource(R.string.approval_title), style = DevxType.BodyStrong, color = DevxColors.TextPrimary)
        }
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(DevxColors.CardInner)
                .horizontalScroll(rememberScrollState())
                .padding(14.dp),
        ) {
            Text(command, style = DevxType.Mono, color = DevxColors.TextPrimary)
        }
        when (decision) {
            null -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PillButton(
                    text = stringResource(R.string.approval_deny),
                    onClick = { decision = false },
                    filled = false,
                    modifier = Modifier.weight(1f),
                )
                PillButton(
                    text = stringResource(R.string.approval_allow),
                    onClick = { decision = true },
                    modifier = Modifier.weight(1f),
                )
            }
            true -> Text(
                stringResource(R.string.approval_allowed),
                style = DevxType.Body,
                color = DevxColors.Success,
            )
            false -> Text(
                stringResource(R.string.approval_denied),
                style = DevxType.Body,
                color = DevxColors.Danger,
            )
        }
    }
}
