package com.apvcode.devx.ui.chat

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.apvcode.devx.R
import com.apvcode.devx.data.Msg
import com.apvcode.devx.data.Source
import com.apvcode.devx.data.ToolKind
import com.apvcode.devx.ui.components.CircleIconButton
import com.apvcode.devx.ui.components.DevxIcons
import com.apvcode.devx.ui.components.pressable
import com.apvcode.devx.ui.theme.DevxColors
import com.apvcode.devx.ui.theme.DevxType
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * While the assistant uses a tool: what it is doing, in words, with a small animated picture of it, and
 * under it the sites it has touched so far (their icons) and how many there are.
 */
@Composable
fun ToolActivityRow(msg: Msg, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val activity = msg.activity
    val transition = rememberInfiniteTransition(label = "tool-pulse")
    val pulse by transition.animateFloat(
        0.55f, 1f, infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "pulse",
    )
    val tappable = if (onClick != null) Modifier.pressable(scaleTo = 0.98f, haptic = false, onClick = onClick) else Modifier
    Column(modifier.then(tappable).padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (activity != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StepIcon(activity.kind)
                val label = stepLabel(activity.kind)
                Text(
                    label + if (activity.detail.isNotBlank()) " · " + activity.detail else "",
                    style = DevxType.Body,
                    color = DevxColors.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false).graphicsLayer { alpha = pulse },
                )
                if (onClick != null) Icon(DevxIcons.ChevronRight, null, tint = DevxColors.TextDisabled, modifier = Modifier.size(18.dp))
            }
        }
        if (msg.sources.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val shown = msg.sources.takeLast(6)
                Box(Modifier.size(width = (22 + 14 * (shown.size - 1)).dp, height = 22.dp)) {
                    shown.forEachIndexed { i, s -> Favicon(s.host, Modifier.offset(x = (14 * i).dp), 22.dp) }
                }
                Text(
                    stringResource(R.string.tool_sites, msg.sources.size),
                    style = DevxType.Caption,
                    color = DevxColors.TextSecondary,
                )
            }
        }
    }
}

/** The picture that stands for a kind of step. */
@Composable
fun StepIcon(kind: ToolKind, size: Dp = 22.dp) {
    when (kind) {
        ToolKind.Search -> WorldSearchIcon(size = size)
        ToolKind.Read -> WorldWwwIcon(size = size)
        ToolKind.File -> Icon(DevxIcons.FileText, null, tint = DevxColors.TextPrimary, modifier = Modifier.size(size))
        ToolKind.Memory -> Icon(DevxIcons.Notebook, null, tint = DevxColors.TextPrimary, modifier = Modifier.size(size))
        ToolKind.History -> Icon(DevxIcons.ChatSearch, null, tint = DevxColors.TextPrimary, modifier = Modifier.size(size))
        ToolKind.Skill -> Icon(androidx.compose.ui.res.painterResource(R.drawable.ic_p_bolt), null, tint = DevxColors.TextPrimary, modifier = Modifier.size(size))
        ToolKind.Mcp -> Icon(DevxIcons.Mcp, null, tint = DevxColors.TextPrimary, modifier = Modifier.size(size))
        ToolKind.Phone -> Icon(androidx.compose.ui.res.painterResource(R.drawable.ic_p_device_mobile), null, tint = DevxColors.TextPrimary, modifier = Modifier.size(size))
    }
}

/** What a kind of step is called. */
@Composable
fun stepLabel(kind: ToolKind): String = stringResource(
    when (kind) {
        ToolKind.Search -> R.string.tool_searching
        ToolKind.Read -> R.string.tool_reading
        ToolKind.File -> R.string.tool_file
        ToolKind.Memory -> R.string.tool_memory
        ToolKind.History -> R.string.tool_history
        ToolKind.Skill -> R.string.tool_skill
        ToolKind.Mcp -> R.string.tool_mcp
        ToolKind.Phone -> R.string.tool_phone
    },
)

/** The icon of a site, from its address; a plain circle until it arrives. */
@Composable
fun Favicon(host: String, modifier: Modifier = Modifier, size: Dp = 24.dp) {
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(DevxColors.CardInner)
            .border(1.dp, DevxColors.Outline, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (host.isNotEmpty()) {
            AsyncImage(
                model = "https://www.google.com/s2/favicons?domain=$host&sz=64",
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().padding(2.dp).clip(CircleShape),
            )
        }
    }
}

/** The globe with a magnifier: a still picture (the movement looked wrong). */
@Composable
fun WorldSearchIcon(modifier: Modifier = Modifier, size: Dp = 24.dp) {
    val tint = DevxColors.TextPrimary
    Box(modifier.size(size)) {
        Icon(painterResource(R.drawable.ic_world_search_globe), null, tint = tint, modifier = Modifier.fillMaxSize())
        Icon(painterResource(R.drawable.ic_world_search_lens), null, tint = tint, modifier = Modifier.fillMaxSize())
    }
}

/** The globe with WWW on it: a still picture. */
@Composable
fun WorldWwwIcon(modifier: Modifier = Modifier, size: Dp = 24.dp) {
    val tint = DevxColors.TextPrimary
    Box(modifier.size(size)) {
        Icon(painterResource(R.drawable.ic_world_www_planet), null, tint = tint, modifier = Modifier.fillMaxSize())
        Icon(painterResource(R.drawable.ic_world_www_text), null, tint = tint, modifier = Modifier.fillMaxSize())
    }
}

/** The pages an answer was built from: the ones read first, then the ones only found. Pressing one opens it. */
@Composable
fun SourcesScreen(sources: List<Source>, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val read = sources.filter { it.opened }
    val found = sources.filter { !it.opened }
    Column(
        modifier
            .fillMaxSize()
            .background(DevxColors.Bg)
            .statusBarsPadding(),
    ) {
        Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
            CircleIconButton(
                icon = Icons.Outlined.Close,
                contentDescription = stringResource(R.string.cd_close),
                onClick = onClose,
                modifier = Modifier.align(Alignment.CenterStart),
            )
            Text(stringResource(R.string.sources_title), style = DevxType.Title, color = DevxColors.TextPrimary)
        }
        LazyColumn(Modifier.weight(1f), contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp)) {
            if (read.isNotEmpty()) {
                item { SectionTitle(stringResource(R.string.sources_opened)) }
                items(read, key = { "r" + it.url }) { SourceRow(it) { open(context, it.url) } }
            }
            if (found.isNotEmpty()) {
                item { SectionTitle(stringResource(R.string.sources_found)) }
                items(found, key = { "f" + it.url }) { SourceRow(it) { open(context, it.url) } }
            }
            if (sources.isEmpty()) item { Text(stringResource(R.string.sources_empty), style = DevxType.Body, color = DevxColors.TextSecondary) }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = DevxType.Section, color = DevxColors.TextSecondary, modifier = Modifier.padding(start = 6.dp, top = 16.dp, bottom = 6.dp))
}

@Composable
private fun SourceRow(source: Source, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .pressable(scaleTo = 0.98f, haptic = false, onClick = onClick)
            .clip(RoundedCornerShape(18.dp))
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Favicon(source.host, size = 32.dp)
        Column(Modifier.weight(1f)) {
            Text(source.title.ifBlank { source.host }, style = DevxType.Body, color = DevxColors.TextPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(source.host, style = DevxType.Caption, color = DevxColors.TextSecondary, maxLines = 1)
        }
    }
}

private fun open(context: android.content.Context, url: String) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
