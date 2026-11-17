package com.apvcode.devx.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.apvcode.devx.R
import com.apvcode.devx.data.McpServer
import com.apvcode.devx.data.McpStatus
import com.apvcode.devx.ui.chat.ChatViewModel
import com.apvcode.devx.ui.components.CircleIconButton
import com.apvcode.devx.ui.components.DevxIcons
import com.apvcode.devx.ui.components.DevxTextField
import com.apvcode.devx.ui.components.PillButton
import com.apvcode.devx.ui.components.pressable
import com.apvcode.devx.ui.remote.ConfirmCard
import com.apvcode.devx.ui.remote.SuccessMark
import com.apvcode.devx.ui.theme.DevxColors
import com.apvcode.devx.ui.theme.DevxType
import kotlinx.coroutines.delay
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.platform.LocalClipboardManager
import java.net.URI

/** A service that can be added with a tap: the address is known, only the token (if any) is missing. */
private class McpPreset(val key: String, val name: String, val url: String, val tokenLabel: Int?, val tokenRequired: Boolean, val about: Int, val help: String? = null, val keyHeader: String? = null)

private val PRESETS = listOf(
    McpPreset("github", "GitHub", "https://api.github.com", R.string.mcp_token_github, true, R.string.mcp_about_github, "https://github.com/settings/tokens/new?scopes=repo,read:org,read:user&description=Devx"),
    McpPreset("docs", "Context7", "https://mcp.context7.com/mcp", null, false, R.string.mcp_about_context7),
    McpPreset("wiki", "DeepWiki", "https://mcp.deepwiki.com/mcp", null, false, R.string.mcp_about_deepwiki),
    McpPreset("ms", "Microsoft Learn", "https://learn.microsoft.com/api/mcp", null, false, R.string.mcp_about_ms),
    McpPreset("cf", "Cloudflare Docs", "https://docs.mcp.cloudflare.com/mcp", null, false, R.string.mcp_about_cf),
    McpPreset("svelte", "Svelte", "https://mcp.svelte.dev/mcp", null, false, R.string.mcp_about_svelte),
    McpPreset("gmaps", "Google Maps", "https://mapstools.googleapis.com/mcp", R.string.mcp_token_gmaps, true, R.string.mcp_about_gmaps, "https://console.cloud.google.com/apis/credentials", keyHeader = "X-Goog-Api-Key"),
    McpPreset("astro", "Astro Docs", "https://mcp.docs.astro.build/mcp", null, false, R.string.mcp_about_astro),
)

/** The picture of a service, from its key or (for servers already added) its address. */
@Composable
private fun iconOf(keyOrUrl: String): ImageVector {
    val s = keyOrUrl.lowercase()
    return when {
        s == "github" || s.contains("github") -> DevxIcons.BrandGithub
        s == "docs" || s.contains("context7") -> DevxIcons.Book
        s == "wiki" || s.contains("deepwiki") -> DevxIcons.Sitemap
        s == "ms" || s.contains("microsoft") -> DevxIcons.Book
        s == "cf" || s.contains("cloudflare") -> DevxIcons.Cloud
        s == "svelte" || s.contains("svelte") -> DevxIcons.Cpu
        s == "astro" || s.contains("astro") -> DevxIcons.Sitemap
        s == "gmaps" || s.contains("mapstools") -> DevxIcons.Sitemap
        else -> DevxIcons.Mcp
    }
}

private sealed interface McpPage {
    data object Home : McpPage
    data object Pick : McpPage
    data object Form : McpPage
    class Connecting(val server: McpServer) : McpPage
}

private fun rank(p: McpPage) = when (p) { McpPage.Home -> 0; McpPage.Pick -> 1; McpPage.Form -> 2; is McpPage.Connecting -> 3 }

/** What was typed in the form: kept while the person goes back and forth. */
private class McpForm {
    var preset by mutableStateOf<McpPreset?>(null)
    var name by mutableStateOf("")
    var url by mutableStateOf("")
    var token by mutableStateOf("")
    var headers by mutableStateOf("")
    fun clear() { preset = null; name = ""; url = ""; token = ""; headers = "" }
}

/**
 * The MCP servers: a home with the ones added, a screen to choose a service, a form, and a connecting screen that
 * ends in a check mark (or in an error). The pages slide into one another.
 */
@OptIn(ExperimentalAnimationApi::class)
@Composable
fun McpScreen(vm: ChatViewModel, onBack: () -> Unit) {
    val store = vm.mcp
    val form = remember { McpForm() }
    var page by remember { mutableStateOf<McpPage>(McpPage.Home) }
    var opened by remember { mutableStateOf<McpServer?>(null) }
    var removing by remember { mutableStateOf<McpServer?>(null) }

    BackHandler(enabled = page != McpPage.Home) {
        page = when (page) { McpPage.Form -> McpPage.Pick; else -> McpPage.Home }
    }
    Box(Modifier.fillMaxSize().background(DevxColors.Bg)) {
        AnimatedContent(
            targetState = page,
            modifier = Modifier.fillMaxSize(),
            transitionSpec = {
                val forward = rank(targetState) > rank(initialState)
                if (forward) (slideInHorizontally(spring(dampingRatio = 0.9f, stiffness = 380f)) { it / 5 } + fadeIn(tween(240))) togetherWith (slideOutHorizontally(tween(240)) { -it / 8 } + fadeOut(tween(160)))
                else (slideInHorizontally(spring(dampingRatio = 0.9f, stiffness = 380f)) { -it / 5 } + fadeIn(tween(240))) togetherWith (slideOutHorizontally(tween(240)) { it / 8 } + fadeOut(tween(160)))
            },
            label = "mcp-page",
        ) { current ->
            when (current) {
                McpPage.Home -> HomePage(vm, onBack, onAdd = { form.clear(); page = McpPage.Pick }, onOpen = { opened = it })
                McpPage.Pick -> PickPage(
                    onBack = { page = McpPage.Home },
                    added = { url -> store.existing(url) != null },
                    // One that is already added is not added again: its card opens instead.
                    onPreset = { p ->
                        val there = store.existing(p.url)
                        if (there != null) { opened = there; page = McpPage.Home } else { form.preset = p; form.name = p.name; form.url = p.url; page = McpPage.Form }
                    },
                    onOther = { form.preset = null; form.name = ""; form.url = ""; page = McpPage.Form },
                )
                McpPage.Form -> FormPage(form, taken = { store.existing(it) != null }, onBack = { page = McpPage.Pick }, onConnect = {
                    // A key that the service wants in a header of its own (Google Maps): it goes there, not as a bearer token.
                    val keyHeader = form.preset?.keyHeader
                    val server = if (keyHeader != null) store.add(form.name, form.url, "", "$keyHeader: ${form.token.trim()}") else store.add(form.name, form.url, form.token, form.headers)
                    page = McpPage.Connecting(server)
                })
                is McpPage.Connecting -> ConnectingPage(
                    server = current.server,
                    onDone = { page = McpPage.Home },
                    onRetry = { store.reconnect(current.server) },
                    onClose = { store.remove(current.server); page = McpPage.Home },
                )
            }
        }

        // The details of a server come up from the bottom over the home page.
        val shown = opened
        Sheet(open = shown != null && shown in store.servers, onDismiss = { opened = null }) {
            shown?.let { s ->
                ServerSheet(s, onReconnect = { store.reconnect(s) }, onToggle = { store.setEnabled(s, !s.enabled) }, onRemove = { removing = s }, onReadOnly = { store.setReadOnly(s, !s.readOnly) }, onResetAllowed = { store.clearAlways(s) })
            }
        }
        ConfirmCard(
            open = removing != null, onDismiss = { removing = null },
            icon = DevxIcons.Unlink, title = stringResource(R.string.mcp_remove_title), body = stringResource(R.string.mcp_remove_text, removing?.name.orEmpty()),
            confirm = stringResource(R.string.mcp_remove), cancel = stringResource(R.string.settings_cancel),
            onConfirm = { removing?.let { store.remove(it) }; removing = null; opened = null },
        )
    }
}

// ───────────────────────── common pieces ─────────────────────────

/** A part of a page that comes in after the one above it. */
@Composable
private fun Appear(index: Int, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val a = remember { Animatable(0f) }
    LaunchedEffect(Unit) { delay(60L + index * 70L); a.animateTo(1f, spring(dampingRatio = 0.82f, stiffness = 300f)) }
    Box(modifier.graphicsLayer { alpha = a.value.coerceIn(0f, 1f); translationY = (1f - a.value) * 26.dp.toPx(); val k = 0.96f + 0.04f * a.value; scaleX = k; scaleY = k }) { content() }
}

@Composable
private fun Header(title: String, subtitle: String?, onBack: () -> Unit, trailing: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        CircleIconButton(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.cd_back), onBack)
        Column(Modifier.weight(1f)) {
            Text(title, style = DevxType.Title, color = DevxColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(subtitle, style = DevxType.Caption, color = DevxColors.TextSecondary, maxLines = 1)
        }
        trailing?.invoke()
    }
}

/** The picture of a service in a round badge. While it connects a short arc runs round it; connected, it wears a thin green ring; failed, a red one. */
@Composable
private fun Badge(icon: ImageVector, status: McpStatus?, size: Dp = 52.dp, iconSize: Dp = size * 0.46f) {
    val spin by rememberInfiniteTransition(label = "spin").animateFloat(0f, 360f, infiniteRepeatable(tween(1100, easing = LinearEasing)), label = "spin")
    val ring by animateFloatAsState(if (status == McpStatus.Connected || status == McpStatus.Failed) 1f else 0f, tween(500), label = "ring")
    val track = DevxColors.Outline
    val ink = DevxColors.TextPrimary
    val good = DevxColors.Success
    val bad = DevxColors.Danger
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Box(Modifier.size(size - 8.dp).clip(CircleShape).background(DevxColors.CardInner), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = if (status == McpStatus.Off) DevxColors.TextDisabled else DevxColors.TextPrimary, modifier = Modifier.size(iconSize))
        }
        Canvas(Modifier.matchParentSizeCompat()) {
            val w = 2.5.dp.toPx()
            val tl = Offset(w / 2, w / 2)
            val sz = Size(this.size.width - w, this.size.height - w)
            when (status) {
                McpStatus.Connecting -> {
                    drawArc(track, 0f, 360f, false, tl, sz, style = Stroke(w))
                    drawArc(ink, spin, 100f, false, tl, sz, style = Stroke(w, cap = StrokeCap.Round))
                }
                McpStatus.Connected -> drawArc(good.copy(alpha = 0.9f), -90f, 360f * ring, false, tl, sz, style = Stroke(w, cap = StrokeCap.Round))
                McpStatus.Failed -> drawArc(bad.copy(alpha = 0.9f), -90f, 360f * ring, false, tl, sz, style = Stroke(w, cap = StrokeCap.Round))
                else -> Unit
            }
        }
    }
}

private fun Modifier.matchParentSizeCompat(): Modifier = this.fillMaxSize()

// ───────────────────────── home ─────────────────────────

@Composable
private fun HomePage(vm: ChatViewModel, onBack: () -> Unit, onAdd: () -> Unit, onOpen: (McpServer) -> Unit) {
    val servers = vm.mcp.servers
    val connected = servers.count { it.status == McpStatus.Connected }
    Column(Modifier.fillMaxSize()) {
        Header(
            stringResource(R.string.mcp_title),
            if (servers.isEmpty()) null else stringResource(R.string.mcp_connected_count, connected, servers.size),
            onBack,
            trailing = { if (servers.isNotEmpty()) CircleIconButton(Icons.Outlined.Add, stringResource(R.string.mcp_add), onAdd) },
        )
        if (servers.isEmpty()) {
            EmptyHome(onAdd)
        } else {
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(servers.size, key = { servers[it].id }) { i ->
                    Appear(i) { ServerCard(servers[i]) { onOpen(servers[i]) } }
                }
                item { Appear(servers.size) { Text(stringResource(R.string.mcp_token_note), style = DevxType.Caption, color = DevxColors.TextDisabled, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) } }
            }
        }
    }
}

/** Nothing added yet: the services float in as a little family, one line, one button. */
@Composable
private fun EmptyHome(onAdd: () -> Unit) {
    val bob by rememberInfiniteTransition(label = "bob").animateFloat(0f, 1f, infiniteRepeatable(tween(2600, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "bob")
    Column(Modifier.fillMaxSize().navigationBarsPadding().padding(horizontal = 32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            listOf(DevxIcons.Book to 0, DevxIcons.BrandGithub to 1, DevxIcons.Sitemap to 2).forEachIndexed { i, (icon, order) ->
                Appear(order) {
                    val lift = if (i == 1) -10 else 0
                    Box(
                        Modifier.graphicsLayer { translationY = (lift + (if (i % 2 == 0) bob else 1f - bob) * 8f).dp.toPx() }
                            .size(if (i == 1) 84.dp else 64.dp).clip(CircleShape).background(DevxColors.Card).border(1.dp, DevxColors.Outline, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) { Icon(icon, null, tint = DevxColors.TextPrimary, modifier = Modifier.size(if (i == 1) 38.dp else 28.dp)) }
                }
            }
        }
        Spacer(Modifier.height(32.dp))
        Appear(3) { Text(stringResource(R.string.mcp_hero_title), style = DevxType.Title, color = DevxColors.TextPrimary, textAlign = TextAlign.Center) }
        Spacer(Modifier.height(8.dp))
        Appear(4) { Text(stringResource(R.string.mcp_hero_body), style = DevxType.Body, color = DevxColors.TextSecondary, textAlign = TextAlign.Center) }
        Spacer(Modifier.height(28.dp))
        Appear(5) { PillButton(stringResource(R.string.mcp_add), onClick = onAdd, modifier = Modifier.fillMaxWidth()) }
    }
}

@Composable
private fun ServerCard(s: McpServer, onClick: () -> Unit) {
    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(0.5f, 1f, infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "pulse")
    // A failed one gives a short shake once, so that the eye finds it.
    val shake = remember { Animatable(0f) }
    LaunchedEffect(s.status) {
        if (s.status == McpStatus.Failed) {
            for (d in listOf(10f, -10f, 7f, -7f, 3f, 0f)) shake.animateTo(d, tween(55))
        }
    }
    Row(
        Modifier.fillMaxWidth().graphicsLayer { translationX = shake.value.dp.toPx() }
            .clip(RoundedCornerShape(28.dp)).background(DevxColors.Card).pressable(scaleTo = 0.98f, haptic = false, onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Badge(iconOf(s.url), s.status)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(s.name, style = DevxType.Body, color = DevxColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            when (s.status) {
                McpStatus.Connected -> Text(stringResource(R.string.mcp_tools_count, s.tools.size), style = DevxType.Caption, color = DevxColors.TextSecondary)
                McpStatus.Connecting -> Text(stringResource(R.string.mcp_connecting), style = DevxType.Caption, color = DevxColors.TextSecondary, modifier = Modifier.graphicsLayer { alpha = pulse })
                McpStatus.Failed -> Text(stringResource(R.string.mcp_failed), style = DevxType.Caption, color = DevxColors.Danger)
                McpStatus.Off -> Text(stringResource(R.string.mcp_inactive), style = DevxType.Caption, color = DevxColors.TextDisabled)
            }
        }
        Icon(Icons.Outlined.ChevronRight, null, tint = DevxColors.TextDisabled, modifier = Modifier.size(22.dp))
    }
}

// ───────────────────────── choosing a service ─────────────────────────

@Composable
private fun PickPage(onBack: () -> Unit, added: (String) -> Boolean, onPreset: (McpPreset) -> Unit, onOther: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Header(stringResource(R.string.mcp_pick_title), null, onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // Two tiles to a row.
            val tiles = PRESETS.map { Triple(it.name, iconOf(it.key) to (if (added(it.url)) R.string.mcp_already_added else it.about), { onPreset(it) }) } + listOf(Triple(stringResource(R.string.mcp_other), DevxIcons.Mcp to R.string.mcp_other_about, onOther))
            tiles.chunked(2).forEachIndexed { row, pair ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    pair.forEachIndexed { col, (name, iconAbout, click) ->
                        Appear(row * 2 + col, Modifier.weight(1f)) { Tile(name, iconAbout.first, stringResource(iconAbout.second), click, done = iconAbout.second == R.string.mcp_already_added) }
                    }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun Tile(name: String, icon: ImageVector, about: String, onClick: () -> Unit, done: Boolean = false) {
    Column(
        Modifier.fillMaxWidth().heightIn(min = 168.dp).clip(RoundedCornerShape(28.dp)).background(DevxColors.Card).pressable(scaleTo = 0.96f, haptic = false, onClick = onClick).padding(18.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
            Box(Modifier.size(52.dp).clip(CircleShape).background(DevxColors.CardInner), contentAlignment = Alignment.Center) { Icon(icon, null, tint = DevxColors.TextPrimary, modifier = Modifier.size(26.dp)) }
            if (done) Box(Modifier.size(26.dp).clip(CircleShape).background(DevxColors.Success.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) { Icon(DevxIcons.Check, null, tint = DevxColors.Success, modifier = Modifier.size(16.dp)) }
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(name, style = DevxType.BodyStrong, color = DevxColors.TextPrimary, maxLines = 1)
            Text(about, style = DevxType.Caption, color = DevxColors.TextSecondary, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
    }
}

// ───────────────────────── the form ─────────────────────────

@Composable
private fun FormPage(form: McpForm, taken: (String) -> Boolean, onBack: () -> Unit, onConnect: () -> Unit) {
    val preset = form.preset
    val custom = preset == null
    val uri = LocalUriHandler.current
    val secure = form.url.trim().startsWith("https://")
    val clipboard = LocalClipboardManager.current
    val duplicate = form.url.isNotBlank() && taken(form.url)
    val ready = !duplicate && form.name.isNotBlank() && secure && (preset?.tokenRequired != true || form.token.isNotBlank())
    Column(Modifier.fillMaxSize()) {
        Header(preset?.name ?: stringResource(R.string.mcp_other), null, onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).imePadding().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            val pop = remember { Animatable(0.5f) }
            LaunchedEffect(Unit) { pop.animateTo(1f, spring(dampingRatio = 0.5f, stiffness = 300f)) }
            Box(Modifier.graphicsLayer { scaleX = pop.value; scaleY = pop.value; alpha = pop.value.coerceIn(0f, 1f) }) {
                Box(Modifier.size(96.dp).clip(CircleShape).background(DevxColors.Card).border(1.dp, DevxColors.Outline, CircleShape), contentAlignment = Alignment.Center) {
                    Icon(iconOf(preset?.key ?: form.url), null, tint = DevxColors.TextPrimary, modifier = Modifier.size(44.dp))
                }
            }
            if (!custom) Appear(0) { Text(stringResource(preset!!.about), style = DevxType.Body, color = DevxColors.TextSecondary, textAlign = TextAlign.Center) }
            if (custom) {
                Appear(0) { DevxTextField(stringResource(R.string.mcp_name), form.name, { form.name = it }) }
                Appear(1) { DevxTextField(stringResource(R.string.mcp_url), form.url, { form.url = it }, placeholder = "https://example.com/mcp", keyboardType = KeyboardType.Uri) }
            }
            if (custom || preset?.tokenLabel != null) {
                val label = preset?.tokenLabel?.let { stringResource(it) } ?: stringResource(R.string.mcp_token)
                Appear(2) { DevxTextField(label + if (preset?.tokenRequired == true) "" else " · " + stringResource(R.string.mcp_optional), form.token, { form.token = it }, password = true) }
            }
            if (custom) Appear(3) { DevxTextField(stringResource(R.string.mcp_headers), form.headers, { form.headers = it }, placeholder = "X-Api-Key: …", singleLine = false, minLines = 2) }
            if (preset?.help != null) {
                Appear(3) {
                    Text(
                        stringResource(R.string.mcp_how_token), style = DevxType.Body, color = DevxColors.TextPrimary,
                        modifier = Modifier.clip(CircleShape).border(1.dp, DevxColors.Outline, CircleShape).pressable(scaleTo = 0.96f, haptic = false) { uri.openUri(preset.help) }.padding(horizontal = 18.dp, vertical = 10.dp),
                    )
                }
                Appear(3) {
                    Text(
                        stringResource(R.string.mcp_paste), style = DevxType.Body, color = DevxColors.TextPrimary,
                        modifier = Modifier.clip(CircleShape).border(1.dp, DevxColors.Outline, CircleShape).pressable(scaleTo = 0.96f, haptic = false) { clipboard.getText()?.text?.trim()?.let { form.token = it } }.padding(horizontal = 18.dp, vertical = 10.dp),
                    )
                }
            }
            if (custom && form.url.isNotBlank() && !secure) Text(stringResource(R.string.mcp_https_only), style = DevxType.Caption, color = DevxColors.Danger)
            else if (duplicate) Text(stringResource(R.string.mcp_already_added), style = DevxType.Caption, color = DevxColors.Danger)
            Spacer(Modifier.height(4.dp))
            Appear(4) { PillButton(stringResource(R.string.mcp_connect), enabled = ready, onClick = onConnect, modifier = Modifier.fillMaxWidth()) }
        }
    }
}

// ───────────────────────── connecting ─────────────────────────

/** The big badge with the arc running round it; when the server answers the arc closes into a check mark and the tools are counted. */
@Composable
private fun ConnectingPage(server: McpServer, onDone: () -> Unit, onRetry: () -> Unit, onClose: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    val status = server.status
    val shake = remember { Animatable(0f) }
    LaunchedEffect(status) {
        when (status) {
            McpStatus.Connected -> { haptic.performHapticFeedback(HapticFeedbackType.Confirm); delay(1900); onDone() }
            McpStatus.Failed -> { haptic.performHapticFeedback(HapticFeedbackType.LongPress); for (d in listOf(14f, -14f, 10f, -10f, 5f, 0f)) shake.animateTo(d, tween(60)) }
            else -> Unit
        }
    }
    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(0.45f, 1f, infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "pulse")
    Column(Modifier.fillMaxSize().navigationBarsPadding().padding(horizontal = 32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Crossfade(targetState = status == McpStatus.Connected, animationSpec = tween(350), label = "badge") { done ->
            if (done) SuccessMark(size = 150.dp)
            else Box(Modifier.graphicsLayer { translationX = shake.value.dp.toPx() }) { Badge(iconOf(server.url), if (status == McpStatus.Failed) McpStatus.Failed else McpStatus.Connecting, size = 132.dp, iconSize = 54.dp) }
        }
        Spacer(Modifier.height(28.dp))
        when (status) {
            McpStatus.Connected -> {
                Text(stringResource(R.string.mcp_ready), style = DevxType.Title, color = DevxColors.TextPrimary)
                Spacer(Modifier.height(6.dp))
                // The tools are counted up, so that the number is felt.
                val shown by animateFloatAsState(server.tools.size.toFloat(), tween(900, delayMillis = 250, easing = FastOutSlowInEasing), label = "count")
                Text(stringResource(R.string.mcp_tools_count, shown.toInt()), style = DevxType.Body, color = DevxColors.TextSecondary)
            }
            McpStatus.Failed -> {
                Text(stringResource(R.string.mcp_failed), style = DevxType.Title, color = DevxColors.TextPrimary)
                Spacer(Modifier.height(8.dp))
                Text(server.error ?: "", style = DevxType.Caption, color = DevxColors.TextSecondary, textAlign = TextAlign.Center, maxLines = 4, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(28.dp))
                PillButton(stringResource(R.string.mcp_retry), onClick = onRetry, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(10.dp))
                PillButton(stringResource(R.string.mcp_close), onClick = onClose, filled = false, modifier = Modifier.fillMaxWidth())
            }
            else -> Text(stringResource(R.string.mcp_connecting_to, server.name), style = DevxType.Title, color = DevxColors.TextPrimary, textAlign = TextAlign.Center, modifier = Modifier.graphicsLayer { alpha = pulse })
        }
    }
}

// ───────────────────────── the details of one server ─────────────────────────

/** A card that comes up from the bottom while the screen dims; a tap outside sends it back down. */
@Composable
private fun Sheet(open: Boolean, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    if (open) BackHandler(onBack = onDismiss)
    val scrim by animateFloatAsState(if (open) 0.6f else 0f, tween(280), label = "scrim")
    Box(Modifier.fillMaxSize()) {
        if (scrim > 0.001f) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = scrim)).then(if (open) Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss) else Modifier))
        AnimatedVisibility(
            visible = open,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically(spring(dampingRatio = 0.86f, stiffness = 300f)) { it } + fadeIn(tween(200)),
            exit = slideOutVertically(tween(280)) { it } + fadeOut(tween(220)),
        ) {
            val top = RoundedCornerShape(topStart = 34.dp, topEnd = 34.dp)
            Column(
                Modifier.fillMaxWidth().shadow(20.dp, top).clip(top).background(DevxColors.Drawer).border(1.dp, DevxColors.Outline.copy(alpha = 0.6f), top)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { }.navigationBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(Modifier.size(width = 42.dp, height = 4.dp).clip(CircleShape).background(DevxColors.Outline))
                Spacer(Modifier.height(16.dp))
                content()
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ServerSheet(s: McpServer, onReconnect: () -> Unit, onToggle: () -> Unit, onRemove: () -> Unit, onReadOnly: () -> Unit, onResetAllowed: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Badge(iconOf(s.url), s.status, size = 84.dp)
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(s.name, style = DevxType.Title, color = DevxColors.TextPrimary)
            Text(runCatching { URI(s.url).host }.getOrNull() ?: s.url, style = DevxType.Caption, color = DevxColors.TextSecondary)
        }
        when (s.status) {
            McpStatus.Failed -> Text(s.error ?: stringResource(R.string.mcp_failed), style = DevxType.Caption, color = DevxColors.Danger, textAlign = TextAlign.Center, maxLines = 4, overflow = TextOverflow.Ellipsis)
            McpStatus.Connected -> Text(stringResource(R.string.mcp_tools_count, s.tools.size), style = DevxType.Body, color = DevxColors.TextSecondary)
            McpStatus.Connecting -> Text(stringResource(R.string.mcp_connecting), style = DevxType.Body, color = DevxColors.TextSecondary)
            McpStatus.Off -> Text(stringResource(R.string.mcp_inactive), style = DevxType.Body, color = DevxColors.TextDisabled)
        }
        if (s.tools.isNotEmpty()) {
            // The tools as small chips: a glance at what the server can do.
            FlowRow(Modifier.heightIn(max = 150.dp).verticalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                s.tools.take(40).forEach { t ->
                    Text(t.name, style = DevxType.Caption, color = DevxColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.clip(CircleShape).background(DevxColors.CardInner).padding(horizontal = 12.dp, vertical = 6.dp))
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ActionTile(DevxIcons.Refresh, stringResource(R.string.mcp_reconnect), Modifier.weight(1f), enabled = s.enabled, onClick = onReconnect)
            ActionTile(DevxIcons.Plug, stringResource(if (s.enabled) R.string.mcp_turn_off else R.string.mcp_turn_on), Modifier.weight(1f), onClick = onToggle)
            ActionTile(DevxIcons.Unlink, stringResource(R.string.mcp_remove), Modifier.weight(1f), danger = true, onClick = onRemove)
        }
        // Only reading, and what was allowed for good.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ActionTile(DevxIcons.ShieldLock, stringResource(if (s.readOnly) R.string.mcp_readonly_on else R.string.mcp_readonly_off), Modifier.weight(1f), highlight = s.readOnly, onClick = onReadOnly)
            ActionTile(DevxIcons.Refresh, stringResource(R.string.mcp_reset_allowed), Modifier.weight(1f), enabled = s.always.isNotEmpty(), onClick = onResetAllowed)
        }
        if (s.always.isNotEmpty()) Text(stringResource(R.string.mcp_allowed_list, s.always.joinToString(", ")), style = DevxType.Caption, color = DevxColors.TextDisabled, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun ActionTile(icon: ImageVector, label: String, modifier: Modifier, danger: Boolean = false, enabled: Boolean = true, highlight: Boolean = false, onClick: () -> Unit) {
    val ink = if (!enabled) DevxColors.TextDisabled else if (danger) DevxColors.Danger else DevxColors.TextPrimary
    Column(
        modifier.clip(RoundedCornerShape(24.dp)).background(if (danger) DevxColors.Danger.copy(alpha = 0.12f) else if (highlight) DevxColors.Success.copy(alpha = 0.16f) else DevxColors.CardInner)
            .pressable(scaleTo = 0.95f, haptic = false, enabled = enabled, onClick = onClick).padding(vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(icon, null, tint = ink, modifier = Modifier.size(24.dp))
        Text(label, style = DevxType.Caption, color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
    }
}
