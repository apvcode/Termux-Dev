package com.apvcode.devx.ui.remote

import com.apvcode.devx.data.logged
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import com.apvcode.devx.data.Level
import com.apvcode.devx.ui.chat.levelLabelRes
import com.apvcode.devx.ui.chat.levelMeaningRes
import kotlinx.coroutines.delay
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apvcode.devx.R
import com.apvcode.devx.remote.RemoteConnection
import com.apvcode.devx.remote.RemoteSession
import com.apvcode.devx.ui.components.DevxIcons
import com.apvcode.devx.ui.components.DevxTextField
import com.apvcode.devx.ui.components.PillButton
import com.apvcode.devx.ui.components.pressable
import com.apvcode.devx.ui.theme.DevxColors
import com.apvcode.devx.ui.theme.DevxType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset

private fun fmt(n: Int): String = if (n >= 1_000_000) "%.1fM".format(n / 1_000_000f) else if (n >= 1000) "${n / 1000}k" else "$n"

/** A section that comes in a little after the one above it: the sheet fills in one after another. */
@Composable
private fun Stagger(index: Int, content: @Composable () -> Unit) {
    val a = remember { Animatable(0f) }
    LaunchedEffect(Unit) { delay(140L + index * 60L); a.animateTo(1f, tween(420, easing = FastOutSlowInEasing)) }
    Box(Modifier.graphicsLayer { alpha = a.value; translationY = (1f - a.value) * 22.dp.toPx() }) { content() }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, style = DevxType.Section, color = DevxColors.TextSecondary, modifier = Modifier.padding(start = 6.dp))
        content()
    }
}

/** Two big cards side by side: the chosen one fills in white. */
@Composable
private fun ModeCard(icon: ImageVector, title: String, hint: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val fill by animateColorAsState(if (selected) DevxColors.Accent else DevxColors.CardInner, tween(260), label = "fill")
    val ink by animateColorAsState(if (selected) DevxColors.OnAccent else DevxColors.TextPrimary, tween(260), label = "ink")
    val sub by animateColorAsState(if (selected) DevxColors.OnAccent.copy(alpha = 0.65f) else DevxColors.TextSecondary, tween(260), label = "sub")
    Column(modifier.clip(RoundedCornerShape(24.dp)).background(fill).pressable(scaleTo = 0.97f, haptic = false, onClick = onClick).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(icon, null, tint = ink, modifier = Modifier.size(26.dp))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = DevxType.BodyStrong, color = ink)
            Text(hint, style = DevxType.Caption, color = sub, minLines = 2, maxLines = 3)
        }
    }
}

/** A row of choices with one thumb that slides to the chosen one. */
@Composable
private fun SlidingChoice(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth().clip(CircleShape).background(DevxColors.CardInner).padding(4.dp)) {
        val w = maxWidth / options.size
        val x by androidx.compose.animation.core.animateDpAsState(w * selected, spring(dampingRatio = 0.78f, stiffness = 420f), label = "thumb")
        Box(Modifier.offset(x = x).size(width = w, height = 42.dp).clip(CircleShape).background(DevxColors.Accent))
        Row {
            options.forEachIndexed { i, label ->
                val ink by animateColorAsState(if (i == selected) DevxColors.OnAccent else DevxColors.TextSecondary, tween(200), label = "ink")
                Box(Modifier.width(w).height(42.dp).clip(CircleShape).pressable(scaleTo = 0.96f, haptic = false) { onSelect(i) }, contentAlignment = Alignment.Center) {
                    Text(label, style = DevxType.Body.copy(fontSize = 15.sp), color = ink, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun LevelChip(text: String, selected: Boolean, onClick: () -> Unit) {
    val fill by animateColorAsState(if (selected) DevxColors.Accent else DevxColors.CardInner, tween(220), label = "fill")
    val ink by animateColorAsState(if (selected) DevxColors.OnAccent else DevxColors.TextPrimary, tween(220), label = "ink")
    Text(text, style = DevxType.Body.copy(fontSize = 15.sp), color = ink, modifier = Modifier.clip(CircleShape).background(fill).pressable(scaleTo = 0.92f, haptic = false, onClick = onClick).padding(horizontal = 16.dp, vertical = 9.dp))
}

@Composable
private fun levelLabel(key: String): String = Level.fromKey(key)?.let { stringResource(levelLabelRes(it)) } ?: key.replaceFirstChar { it.uppercase() }

/** A ring that fills to how much of the context is used. */
@Composable
private fun ContextGauge(used: Int, limit: Int) {
    val frac = (used.toFloat() / limit.coerceAtLeast(1)).coerceIn(0f, 1f)
    val shown by animateFloatAsState(frac, tween(1000, delayMillis = 300, easing = FastOutSlowInEasing), label = "gauge")
    val color = if (frac > 0.85f) DevxColors.Danger else if (frac > 0.65f) DevxColors.Warning else DevxColors.TextPrimary
    val track = DevxColors.Outline
    Box(Modifier.size(92.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.matchParentSize()) {
            val w = 7.dp.toPx()
            val tl = Offset(w / 2, w / 2)
            val sz = Size(size.width - w, size.height - w)
            drawArc(track, 0f, 360f, false, tl, sz, style = Stroke(w))
            drawArc(color, -90f, 360f * shown, false, tl, sz, style = Stroke(w, cap = StrokeCap.Round))
        }
        Text("${(shown * 100).toInt()}%", style = DevxType.Title, color = DevxColors.TextPrimary)
    }
}

/** Everything the person can do with this session, in one sheet: mode, approvals, model, reasoning, context, actions. */
@Composable
fun RemoteControls(session: RemoteSession, conn: RemoteConnection, onDismiss: () -> Unit, onCommand: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val s = session.state
    if (s == null) { LaunchedEffect(Unit) { onDismiss() }; return }
    var info by remember { mutableStateOf<JSONObject?>(null) }
    var tasks by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var picking by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(session, s.ctxUsed, s.cost) {
        info = runCatching { withContext(Dispatchers.IO) { session.info() } }.logged("remotesheets").getOrNull()
        tasks = runCatching { withContext(Dispatchers.IO) { session.tasks() } }.logged("remotesheets").getOrNull()?.let { a -> List(a.length()) { a.getJSONObject(it) } } ?: emptyList()
    }
    fun act(block: suspend () -> Unit) { scope.launch { runCatching { block() }.logged("remotesheets") } }

    // It opens by rising from the bottom while the screen dims, and closes the same way backwards (or follows the finger down).
    var shown by remember { mutableStateOf(false) }
    var closing by remember { mutableStateOf(false) }
    val drag = remember { Animatable(0f) }
    fun close() { if (closing) return; closing = true; shown = false; scope.launch { delay(300); onDismiss() } }
    LaunchedEffect(Unit) { shown = true }
    BackHandler(onBack = ::close)
    val scrim by animateFloatAsState(if (shown) 0.6f else 0f, tween(300), label = "scrim")
    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.9f).dp
    val dismissAt = with(density) { 140.dp.toPx() }

    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = (scrim * (1f - (drag.value / (dismissAt * 4f)).coerceIn(0f, 1f))))).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = ::close))
        AnimatedVisibility(
            visible = shown,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically(spring(dampingRatio = 0.86f, stiffness = 280f)) { it } + fadeIn(tween(220)),
            exit = slideOutVertically(tween(300, easing = FastOutLinearInEasing)) { it } + fadeOut(tween(260)),
        ) {
            val top = RoundedCornerShape(topStart = 34.dp, topEnd = 34.dp)
            Column(
                Modifier.fillMaxWidth().heightIn(max = maxHeight).offset { IntOffset(0, drag.value.toInt()) }
                    .clip(top).background(DevxColors.Drawer).border(1.dp, DevxColors.Outline.copy(alpha = 0.6f), top)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { },
            ) {
                // The grip and the title: pulling them down closes the sheet.
                Column(
                    Modifier.fillMaxWidth().draggable(
                        rememberDraggableState { d -> scope.launch { drag.snapTo((drag.value + d).coerceAtLeast(0f)) } },
                        Orientation.Vertical,
                        onDragStopped = { v -> if (drag.value > dismissAt || v > 1800f) close() else drag.animateTo(0f, spring(dampingRatio = 0.8f, stiffness = 400f)) },
                    ),
                ) {
                    Box(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 6.dp), contentAlignment = Alignment.Center) { Box(Modifier.size(width = 42.dp, height = 4.dp).clip(CircleShape).background(DevxColors.Outline)) }
                    Row(Modifier.fillMaxWidth().padding(start = 22.dp, end = 12.dp, top = 4.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.remote_controls), style = DevxType.Title, color = DevxColors.TextPrimary)
                            Text(listOfNotNull(s.model.takeIf { it.isNotEmpty() }, s.provider.takeIf { it.isNotEmpty() }).joinToString(" · "), style = DevxType.Caption, color = DevxColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Box(Modifier.size(40.dp).clip(CircleShape).background(DevxColors.CardInner).pressable(onClick = ::close), contentAlignment = Alignment.Center) {
                            Icon(Icons.Outlined.Close, null, tint = DevxColors.TextPrimary, modifier = Modifier.size(20.dp))
                        }
                    }
                }
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).navigationBarsPadding().padding(start = 16.dp, end = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
                    // A chat that runs in the terminal is steered there: what is shown here is read-only.
                    if (s.terminal) Text(stringResource(R.string.remote_terminal_note), style = DevxType.Caption, color = DevxColors.TextSecondary, modifier = Modifier.padding(horizontal = 6.dp))
                    Stagger(0) {
                        Section(stringResource(R.string.remote_mode)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                ModeCard(DevxIcons.Terminal, stringResource(R.string.remote_mode_agent), stringResource(R.string.remote_mode_agent_hint), !s.plan, Modifier.weight(1f)) { act { session.setMode(plan = false) } }
                                ModeCard(DevxIcons.ListCheck, stringResource(R.string.remote_mode_plan_label), stringResource(R.string.remote_mode_plan_hint), s.plan, Modifier.weight(1f)) { act { session.setMode(plan = true) } }
                            }
                        }
                    }
                    Stagger(1) {
                        Section(stringResource(R.string.remote_approvals)) {
                            SlidingChoice(listOf(stringResource(R.string.remote_ask), stringResource(R.string.remote_auto), stringResource(R.string.remote_everything)), if (s.extra) 2 else if (s.yolo) 1 else 0) {
                                act { when (it) { 0 -> session.setMode(yolo = false, extra = false); 1 -> session.setMode(yolo = true, extra = false); else -> session.setMode(yolo = true, extra = true) } }
                            }
                            Text(when { s.extra -> stringResource(R.string.remote_everything_hint); s.yolo -> stringResource(R.string.remote_auto_hint); else -> stringResource(R.string.remote_ask_hint) }, style = DevxType.Caption, color = if (s.extra) DevxColors.Danger else DevxColors.TextSecondary, modifier = Modifier.padding(horizontal = 6.dp).animateContentSize())
                        }
                    }
                    Stagger(2) {
                        Section(stringResource(R.string.remote_model)) {
                            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(DevxColors.CardInner).pressable(scaleTo = 0.98f, haptic = false) { picking = true }.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                Box(Modifier.size(42.dp).clip(CircleShape).background(DevxColors.Card), contentAlignment = Alignment.Center) { Icon(DevxIcons.Cpu, null, tint = DevxColors.TextPrimary, modifier = Modifier.size(22.dp)) }
                                Column(Modifier.weight(1f)) {
                                    Text(s.model, style = DevxType.Body, color = DevxColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(s.provider, style = DevxType.Caption, color = DevxColors.TextSecondary, maxLines = 1)
                                }
                                Icon(DevxIcons.ChevronRight, null, tint = DevxColors.TextSecondary, modifier = Modifier.size(22.dp))
                            }
                        }
                    }
                    if (s.effort.supported || s.effort.alwaysOn) Stagger(3) {
                        Section(stringResource(R.string.remote_effort)) {
                            if (!s.effort.supported) {
                                Text(stringResource(R.string.reasoning_always_on), style = DevxType.Caption, color = DevxColors.TextSecondary, modifier = Modifier.padding(horizontal = 6.dp))
                            } else {
                                // Every level the model has, none cut off: the chips wrap onto the next line.
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    LevelChip(stringResource(R.string.reasoning_auto), s.effort.level == null) { act { session.setEffort(null) } }
                                    s.effort.levels.forEach { l -> LevelChip(levelLabel(l), s.effort.level == l) { act { session.setEffort(l) } } }
                                }
                                val meaning = Level.fromKey(s.effort.level)?.let { stringResource(levelMeaningRes(it)) }
                                if (meaning != null) Text(meaning, style = DevxType.Caption, color = DevxColors.TextSecondary, modifier = Modifier.padding(horizontal = 6.dp).animateContentSize())
                            }
                        }
                    }
                    Stagger(4) {
                        Section(stringResource(R.string.remote_context)) {
                            val used = s.ctxUsed
                            val limit = s.ctxLimit.coerceAtLeast(1)
                            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(DevxColors.CardInner).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                                    ContextGauge(used, limit)
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text("${fmt(used)} / ${fmt(limit)}", style = DevxType.Title, color = DevxColors.TextPrimary)
                                        Text((if (s.costEstimated) "~$" else "$") + (if (s.cost >= 1) "%.2f".format(s.cost) else "%.4f".format(s.cost)), style = DevxType.Body, color = DevxColors.TextSecondary)
                                        info?.optJSONObject("usage")?.let { u -> Text(stringResource(R.string.remote_usage_line, u.optInt("requests"), fmt(u.optInt("promptTokens")), fmt(u.optInt("completionTokens"))), style = DevxType.Caption, color = DevxColors.TextDisabled) }
                                    }
                                }
                                info?.optJSONObject("context")?.let { c ->
                                    val parts = listOf(c.optInt("system") + c.optInt("tools"), c.optInt("user"), c.optInt("assistant"), c.optInt("toolResults"))
                                    val total = parts.sum().coerceAtLeast(1)
                                    val shades = listOf(0.95f, 0.7f, 0.45f, 0.25f)
                                    Row(Modifier.fillMaxWidth().height(8.dp).clip(CircleShape).background(DevxColors.Outline), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                        parts.forEachIndexed { i, v -> if (v > 0) Box(Modifier.weight(v.toFloat() / total).height(8.dp).background(DevxColors.TextPrimary.copy(alpha = shades[i]))) }
                                    }
                                    Text(stringResource(R.string.remote_context_parts, fmt(parts[0]), fmt(parts[1]), fmt(parts[2]), fmt(parts[3])), style = DevxType.Caption, color = DevxColors.TextDisabled)
                                }
                            }
                            PillButton(stringResource(R.string.remote_compact), onClick = { close(); session.startCompact() }, filled = false, enabled = !session.compacting && !s.running)
                        }
                    }
                    if (tasks.isNotEmpty()) Stagger(5) {
                        Section(stringResource(R.string.remote_tasks)) {
                            tasks.forEach { t ->
                                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(DevxColors.CardInner).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    StatusDot(if (t.optString("status") == "running") LinkVisual.Connecting else LinkVisual.Offline)
                                    Column(Modifier.weight(1f)) {
                                        Text(t.optString("label").ifBlank { t.optString("command") }, style = DevxType.Caption.copy(fontSize = 14.sp), color = DevxColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(t.optString("status"), style = DevxType.Caption, color = DevxColors.TextDisabled)
                                    }
                                    if (t.optString("status") == "running") Text(stringResource(R.string.remote_stop), style = DevxType.Caption, color = DevxColors.Danger, modifier = Modifier.clip(CircleShape).pressable { act { session.stopTask(t.optString("id")) } }.padding(8.dp))
                                }
                            }
                        }
                    }
                    Stagger(6) {
                        Section(stringResource(R.string.remote_chat)) {
                            ActionRow(DevxIcons.Pencil, stringResource(R.string.menu_rename)) { renaming = true }
                            ActionRow(DevxIcons.Undo, stringResource(R.string.remote_undo)) { close(); onCommand("/undo") }
                            ActionRow(DevxIcons.ChatPlus, stringResource(R.string.remote_new_here)) { close(); onCommand("/clear") }
                        }
                    }
                }
            }
        }
    }
    if (picking) RemoteModelPicker(session, conn, onDismiss = { picking = false })
    if (renaming) {
        var title by remember { mutableStateOf(s.title) }
        RemoteDialog(stringResource(R.string.dialog_rename_chat), { renaming = false }) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                DevxTextField(stringResource(R.string.dialog_rename_chat), title, { title = it })
                PillButton(stringResource(R.string.dialog_save), enabled = title.isNotBlank(), onClick = { renaming = false; act { session.rename(title.trim()) } })
            }
        }
    }
}

@Composable
private fun ActionRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(DevxColors.CardInner).pressable(scaleTo = 0.98f, haptic = false, onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Icon(icon, null, tint = DevxColors.TextSecondary, modifier = Modifier.size(22.dp))
        Text(label, style = DevxType.Body, color = DevxColors.TextPrimary, modifier = Modifier.weight(1f))
        Icon(DevxIcons.ChevronRight, null, tint = DevxColors.TextDisabled, modifier = Modifier.size(20.dp))
    }
}

private class ProviderInfo(val id: String, val name: String, val hasKey: Boolean, val current: Boolean)

/** The providers and models of the computer: choose another model for this chat, or add a provider. */
@Composable
fun RemoteModelPicker(session: RemoteSession, conn: RemoteConnection, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var providers by remember { mutableStateOf<List<ProviderInfo>>(emptyList()) }
    var chosen by remember { mutableStateOf<String?>(null) }
    var models by remember { mutableStateOf<List<Pair<String, Boolean>>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var adding by remember { mutableStateOf(false) }
    var reload by remember { mutableStateOf(0) }

    LaunchedEffect(reload) {
        try {
            val r = withContext(Dispatchers.IO) { conn.call("providers.list") }
            val a = r.optJSONArray("providers")
            providers = List(a?.length() ?: 0) { a!!.getJSONObject(it).let { p -> ProviderInfo(p.getString("id"), p.optString("name"), p.optBoolean("hasKey"), p.optBoolean("current")) } }
            if (chosen == null) chosen = session.state?.provider?.takeIf { id -> providers.any { it.id == id } } ?: providers.firstOrNull { it.current }?.id ?: providers.firstOrNull()?.id
        } catch (e: Exception) { error = e.message }
    }
    LaunchedEffect(chosen) {
        val id = chosen ?: return@LaunchedEffect
        loading = true; error = null; models = emptyList()
        try {
            val r = withContext(Dispatchers.IO) { conn.call("models.list", JSONObject().put("provider", id)) }
            val a = r.optJSONArray("models")
            models = List(a?.length() ?: 0) { a!!.getJSONObject(it).let { m -> m.getString("id") to m.optBoolean("viaAnthropic") } }.sortedBy { it.first.lowercase() }
        } catch (e: Exception) { error = e.message }
        loading = false
    }

    RemoteDialog(stringResource(R.string.remote_model), onDismiss) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                providers.forEach { p ->
                    val on = p.id == chosen
                    Text(p.name, style = DevxType.Caption.copy(fontSize = 14.sp), color = if (on) DevxColors.OnAccent else DevxColors.TextPrimary, modifier = Modifier.clip(CircleShape).background(if (on) DevxColors.Accent else Color.Transparent).border(1.dp, if (on) DevxColors.Accent else DevxColors.Outline, CircleShape).pressable(scaleTo = 0.96f) { chosen = p.id }.padding(horizontal = 14.dp, vertical = 8.dp))
                }
                Text("+ " + stringResource(R.string.remote_add_provider), style = DevxType.Caption.copy(fontSize = 14.sp), color = DevxColors.TextSecondary, modifier = Modifier.clip(CircleShape).border(1.dp, DevxColors.Outline, CircleShape).pressable(scaleTo = 0.96f) { adding = true }.padding(horizontal = 14.dp, vertical = 8.dp))
            }
            Row(Modifier.fillMaxWidth().clip(CircleShape).background(DevxColors.CardInner).padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    BasicTextField(query, { query = it }, singleLine = true, textStyle = DevxType.Body.copy(color = DevxColors.TextPrimary), cursorBrush = SolidColor(DevxColors.Accent), modifier = Modifier.fillMaxWidth())
                    if (query.isEmpty()) Text(stringResource(R.string.search_placeholder), style = DevxType.Body, color = DevxColors.TextDisabled)
                }
            }
            Column(Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState())) {
                if (loading) Text(stringResource(R.string.remote_loading), style = DevxType.Caption, color = DevxColors.TextSecondary, modifier = Modifier.padding(8.dp))
                error?.let { Text(friendly(it), style = DevxType.Caption, color = DevxColors.Danger, modifier = Modifier.padding(8.dp)) }
                models.filter { query.isBlank() || it.first.contains(query.trim(), ignoreCase = true) }.forEach { (m, viaAnthropic) ->
                    val on = m == session.state?.model && chosen == session.state?.provider
                    Row(Modifier.fillMaxWidth().pressable(scaleTo = 0.98f, haptic = false) { scope.launch { runCatching { session.setModel(chosen, m) }.logged("remotesheets"); onDismiss() } }.padding(horizontal = 8.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(m, style = DevxType.Body, color = DevxColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        if (viaAnthropic) Text(stringResource(R.string.model_format_anthropic), style = DevxType.Caption, color = DevxColors.TextDisabled, modifier = Modifier.padding(horizontal = 8.dp))
                        if (on) Icon(DevxIcons.Check, null, tint = DevxColors.TextPrimary, modifier = Modifier.size(20.dp))
                    }
                }
            }
        }
    }
    if (adding) {
        var name by remember { mutableStateOf("") }
        var url by remember { mutableStateOf("") }
        var key by remember { mutableStateOf("") }
        var failure by remember { mutableStateOf<String?>(null) }
        RemoteDialog(stringResource(R.string.remote_add_provider), { adding = false }) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                DevxTextField(stringResource(R.string.provider_name), name, { name = it })
                DevxTextField(stringResource(R.string.provider_url), url, { url = it }, placeholder = "https://api.example.com/v1")
                DevxTextField(stringResource(R.string.provider_key), key, { key = it }, password = true)
                failure?.let { Text(friendly(it), style = DevxType.Caption, color = DevxColors.Danger) }
                PillButton(stringResource(R.string.dialog_save), enabled = name.isNotBlank() && url.isNotBlank(), onClick = {
                    scope.launch {
                        try {
                            val r = withContext(Dispatchers.IO) { conn.call("provider.add", JSONObject().put("name", name.trim()).put("baseUrl", url.trim()).put("apiKey", key.trim())) }
                            chosen = r.optString("id"); adding = false; reload++
                        } catch (e: Exception) { failure = e.message }
                    }
                })
            }
        }
    }
}
