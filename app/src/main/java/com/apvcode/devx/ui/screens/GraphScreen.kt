package com.apvcode.devx.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apvcode.devx.R
import com.apvcode.devx.data.GraphLayout
import com.apvcode.devx.data.StudyPlan
import com.apvcode.devx.ui.chat.ChatViewModel
import com.apvcode.devx.ui.components.CircleIconButton
import com.apvcode.devx.ui.components.PillButton
import com.apvcode.devx.ui.theme.DevxColors
import com.apvcode.devx.ui.theme.DevxType
import kotlin.math.hypot

/** How well a concept is known, from the cards that carry its name as their topic. */
enum class Mastery { Unknown, Learning, Known, Weak }

/** The concepts of a plan with their state, and the weak ones first-class: a topic in the weak spots makes the concept weak. */
internal fun masteryOf(plan: StudyPlan, name: String): Mastery {
    val n = name.trim().lowercase()
    fun related(topic: String): Boolean {
        val t = topic.trim().lowercase()
        return t.isNotEmpty() && (t == n || (t.length >= 4 && n.contains(t)) || (n.length >= 4 && t.contains(n)))
    }
    if (plan.weakSpots(20).any { related(it.topic) }) return Mastery.Weak
    val cards = plan.cards.filter { related(it.topic) }
    return when {
        cards.isEmpty() -> Mastery.Unknown
        cards.all { it.reps >= 2 } -> Mastery.Known
        else -> Mastery.Learning
    }
}

@Composable
private fun Mastery.color(): Color = when (this) {
    Mastery.Weak -> DevxColors.Danger
    Mastery.Learning -> DevxColors.Warning
    Mastery.Known -> DevxColors.Success
    Mastery.Unknown -> DevxColors.TextSecondary
}

/**
 * The concept map of a study notebook as a picture: the concepts as dots coloured by how well they are known, the links between them,
 * placed so that related concepts are close. Pinch and drag to move around; tap a concept to see its line and to ask about it.
 */
@Composable
fun GraphScreen(vm: ChatViewModel, name: String, onClose: () -> Unit, onAsk: (String) -> Unit) {
    val info = vm.projectInfo[name]
    if (info == null) { LaunchedEffect(Unit) { onClose() }; return }
    val plan = info.plan
    val concepts = plan.concepts.toList()
    val links = plan.links.toList()
    val tint = Color(info.color).let { if (info.color == 0xFFE6E6E6L) Color(0xFF8FA3D9) else it }
    val index = remember(concepts.map { it.id }) { concepts.mapIndexed { i, c -> c.id to i }.toMap() }
    val points = remember(concepts.map { it.id }, links.map { it.from + it.to }) {
        GraphLayout.layout(concepts.size, links.mapNotNull { l -> index[l.from]?.let { a -> index[l.to]?.let { b -> a to b } } })
    }
    val states = remember(concepts.map { it.name }, plan.cards.size, plan.cards.sumOf { it.reps + it.lapses }) { concepts.map { masteryOf(plan, it.name) } }
    var selected by remember { mutableStateOf<String?>(null) }
    var scale by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(fontSize = 11.sp, color = DevxColors.TextPrimary, textAlign = TextAlign.Center)
    val edgeStyle = TextStyle(fontSize = 9.sp, color = DevxColors.TextDisabled, textAlign = TextAlign.Center)
    val outline = DevxColors.Outline
    val strong = DevxColors.TextSecondary
    val ring = DevxColors.TextPrimary
    val colors = states.map { when (it) { Mastery.Weak -> DevxColors.Danger; Mastery.Learning -> DevxColors.Warning; Mastery.Known -> DevxColors.Success; Mastery.Unknown -> DevxColors.TextSecondary } }

    Column(Modifier.fillMaxSize().background(DevxColors.Bg).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            CircleIconButton(Icons.Outlined.Close, stringResource(R.string.cd_close), onClose)
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.graph_title), style = DevxType.BodyStrong, color = DevxColors.TextPrimary, maxLines = 1)
                Text(name, style = DevxType.Caption, color = DevxColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (concepts.isEmpty()) {
            Column(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.graph_empty), style = DevxType.Body, color = DevxColors.TextSecondary, textAlign = TextAlign.Center)
                PillButton(stringResource(R.string.graph_build), onClick = { onAsk(vm.graphBuildPrompt()) }, container = tint, modifier = Modifier.padding(top = 20.dp).fillMaxWidth())
            }
        } else {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                Canvas(
                    Modifier.fillMaxSize()
                        .pointerInput(Unit) { detectTransformGestures { _, drag, zoom, _ -> scale = (scale * zoom).coerceIn(0.6f, 4f); pan += drag } }
                        .pointerInput(points, scale, pan) {
                            detectTapGestures { tap ->
                                val side = minOf(size.width, size.height).toFloat()
                                val origin = Offset((size.width - side) / 2f, (size.height - side) / 2f)
                                var best = -1; var bestD = 48.dp.toPx()
                                points.forEachIndexed { i, p ->
                                    val at = (origin + Offset(p.x * side, p.y * side) - Offset(size.width / 2f, size.height / 2f)) * scale + Offset(size.width / 2f, size.height / 2f) + pan
                                    val d = hypot(tap.x - at.x, tap.y - at.y)
                                    if (d < bestD) { bestD = d; best = i }
                                }
                                selected = if (best >= 0) concepts[best].id else null
                            }
                        },
                ) {
                    val side = minOf(size.width, size.height)
                    val origin = Offset((size.width - side) / 2f, (size.height - side) / 2f)
                    val center = Offset(size.width / 2f, size.height / 2f)
                    fun at(i: Int) = (origin + Offset(points[i].x * side, points[i].y * side) - center) * scale + center + pan
                    val sel = selected?.let { index[it] }
                    links.forEach { l ->
                        val a = index[l.from] ?: return@forEach; val b = index[l.to] ?: return@forEach
                        val hot = sel != null && (a == sel || b == sel)
                        drawLine(if (hot) ring.copy(alpha = 0.8f) else outline, at(a), at(b), strokeWidth = (if (hot) 2.2.dp else 1.2.dp).toPx())
                        if (l.label.isNotEmpty() && (scale >= 0.9f || hot)) {
                            val mid = (at(a) + at(b)) / 2f
                            val t = measurer.measure(l.label, edgeStyle, maxLines = 1, constraints = Constraints(maxWidth = 110.dp.roundToPx()))
                            drawText(t, topLeft = Offset(mid.x - t.size.width / 2f, mid.y - t.size.height / 2f))
                        }
                    }
                    concepts.forEachIndexed { i, c ->
                        val p = at(i)
                        val r = (if (c.id == selected) 11.dp else 8.dp).toPx() * scale.coerceIn(0.8f, 1.6f)
                        drawCircle(colors[i].copy(alpha = 0.22f), r * 1.9f, p)
                        drawCircle(colors[i], r, p)
                        if (c.id == selected) drawCircle(ring, r * 1.45f, p, style = Stroke(2.dp.toPx()))
                        val t = measurer.measure(c.name, labelStyle, maxLines = 2, overflow = TextOverflow.Ellipsis, constraints = Constraints(maxWidth = (96.dp.toPx() * scale.coerceIn(0.8f, 1.4f)).toInt()))
                        drawText(t, topLeft = Offset(p.x - t.size.width / 2f, p.y + r * 1.5f))
                    }
                }
                Row(Modifier.align(Alignment.TopStart).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Legend(Mastery.Weak, R.string.graph_weak); Legend(Mastery.Learning, R.string.graph_learning); Legend(Mastery.Known, R.string.graph_known); Legend(Mastery.Unknown, R.string.graph_new)
                }
            }
            val chosen = concepts.firstOrNull { it.id == selected }
            if (chosen != null) {
                val state = states[index[chosen.id] ?: 0]
                val related = links.mapNotNull { l -> if (l.from == chosen.id) concepts.firstOrNull { it.id == l.to }?.name else if (l.to == chosen.id) concepts.firstOrNull { it.id == l.from }?.name else null }.distinct()
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp).clip(RoundedCornerShape(26.dp)).background(DevxColors.Card)
                        .border(1.dp, DevxColors.Outline, RoundedCornerShape(26.dp)).padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(Modifier.size(12.dp).clip(CircleShape).background(state.color()))
                        Text(chosen.name, style = DevxType.Title, color = DevxColors.TextPrimary, modifier = Modifier.weight(1f), maxLines = 2)
                    }
                    if (chosen.note.isNotBlank()) Text(chosen.note, style = DevxType.Body, color = DevxColors.TextSecondary)
                    if (related.isNotEmpty()) Text(stringResource(R.string.graph_related, related.joinToString(", ")), style = DevxType.Caption, color = DevxColors.TextSecondary)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(Modifier.weight(1f)) { PillButton(stringResource(R.string.graph_explain), onClick = { onAsk(stringResource0(vm, R.string.graph_explain_prompt, chosen.name, related.joinToString(", ").ifEmpty { "…" })) }, container = tint) }
                        Box(Modifier.weight(1f)) { PillButton(stringResource(R.string.graph_quiz), filled = false, onClick = { onAsk(stringResource0(vm, R.string.graph_quiz_prompt, chosen.name)) }) }
                    }
                }
            }
        }
    }
}

private fun stringResource0(vm: ChatViewModel, id: Int, vararg args: Any): String = vm.text(id, *args)

@Composable
private fun Legend(state: Mastery, label: Int) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(state.color()))
        Text(stringResource(label), style = DevxType.Caption.copy(fontSize = 11.sp), color = DevxColors.TextSecondary)
    }
}
