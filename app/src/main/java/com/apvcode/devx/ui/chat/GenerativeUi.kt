package com.apvcode.devx.ui.chat

import com.apvcode.devx.ui.components.pressable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apvcode.devx.R
import com.apvcode.devx.ui.components.PillButton
import com.apvcode.devx.ui.theme.DevxColors
import com.apvcode.devx.ui.theme.DevxType
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/*
 * Generative UI: the assistant describes an interactive card as JSON and the app draws it natively.
 * The element set is a small vocabulary, not a template: the assistant picks and combines elements.
 * Unknown elements are skipped, depth and size are limited, and nothing here can run code.
 *
 * Elements: card, section, text, input, choice, button, svg, code, score, divider, map, image, chart.
 * Buttons: action = check | reset | submit. `when` = always | unchecked | checked | unsubmitted.
 */

sealed interface UiNode {
    data class Card(val title: String?, val subtitle: String?, val children: List<UiNode>) : UiNode
    data class Section(val title: String?, val children: List<UiNode>) : UiNode
    data class Text(val text: String, val style: String, val color: String) : UiNode
    data class Input(
        val id: String,
        val label: String?,
        val placeholder: String?,
        val multiline: Boolean,
        val answer: String?,
    ) : UiNode
    data class Choice(val id: String, val label: String?, val options: List<String>, val answer: String?) : UiNode
    data class Button(
        val label: String,
        val action: String,
        val outline: Boolean,
        val whenState: String,
        val header: String?,
        val sentLabel: String?,
    ) : UiNode
    data class Svg(val caption: String?, val svg: String) : UiNode
    data class Code(val language: String, val code: String) : UiNode

    /** A map, a picture or a chart inside a card: [json] is the element itself (see RichBlocks.kt). */
    data class Rich(val kind: String, val json: String) : UiNode
    data class Score(val label: String, val whenState: String) : UiNode
    data object Divider : UiNode
}

object UiParser {
    private const val MAX_DEPTH = 8
    private const val MAX_NODES = 150

    /** Returns null when the JSON is broken or the root element is unknown. */
    fun parse(json: String): UiNode? = try {
        val counter = intArrayOf(0)
        parseNode(JSONObject(json), 0, counter)
    } catch (_: JSONException) {
        null
    }

    private fun JSONObject.str(name: String): String? =
        if (has(name) && !isNull(name)) optString(name).takeIf { it.isNotEmpty() } else null

    private fun parseChildren(obj: JSONObject, depth: Int, counter: IntArray): List<UiNode> {
        val arr: JSONArray = obj.optJSONArray("children") ?: return emptyList()
        val out = ArrayList<UiNode>()
        for (i in 0 until arr.length()) {
            val child = arr.optJSONObject(i) ?: continue
            parseNode(child, depth + 1, counter)?.let(out::add)
        }
        return out
    }

    private fun parseNode(obj: JSONObject, depth: Int, counter: IntArray): UiNode? {
        if (depth > MAX_DEPTH || ++counter[0] > MAX_NODES) return null
        return when (obj.optString("type")) {
            "card" -> UiNode.Card(obj.str("title"), obj.str("subtitle"), parseChildren(obj, depth, counter))
            "section" -> UiNode.Section(obj.str("title"), parseChildren(obj, depth, counter))
            "text" -> UiNode.Text(
                text = obj.optString("text"),
                style = obj.optString("style", "body"),
                color = obj.optString("color", "default"),
            )
            "input" -> obj.str("id")?.let {
                UiNode.Input(
                    id = it,
                    label = obj.str("label"),
                    placeholder = obj.str("placeholder"),
                    multiline = obj.optBoolean("multiline", false),
                    answer = obj.str("answer"),
                )
            }
            "choice" -> obj.str("id")?.let { id ->
                val opts = obj.optJSONArray("options")
                val list = (0 until (opts?.length() ?: 0)).mapNotNull { opts?.optString(it) }.take(12)
                UiNode.Choice(id, obj.str("label"), list, obj.str("answer"))
            }
            "button" -> UiNode.Button(
                label = obj.optString("label"),
                action = obj.optString("action"),
                outline = obj.optString("style") == "outline",
                whenState = obj.optString("when", "always"),
                header = obj.str("header"),
                sentLabel = obj.str("sentLabel"),
            )
            "svg" -> obj.str("svg")?.let { UiNode.Svg(obj.str("caption"), it) }
            "code" -> UiNode.Code(obj.optString("language"), obj.optString("code"))
            "map", "image", "chart" -> UiNode.Rich(obj.optString("type"), obj.toString())
            "score" -> UiNode.Score(obj.optString("label"), obj.optString("when", "checked"))
            "divider" -> UiNode.Divider
            else -> null
        }
    }
}

/** What the user has typed or picked in one card, and whether it was checked or sent. */
@Stable
class UiCardState {
    val values = mutableStateMapOf<String, String>()
    var checked by mutableStateOf(false)
    var submitted by mutableStateOf(false)
}

private fun UiNode.children(): List<UiNode> = when (this) {
    is UiNode.Card -> children
    is UiNode.Section -> children
    else -> emptyList()
}

private fun flatten(node: UiNode): List<UiNode> = listOf(node) + node.children().flatMap(::flatten)

private fun answerOf(node: UiNode): String? = when (node) {
    is UiNode.Input -> node.answer
    is UiNode.Choice -> node.answer
    else -> null
}

private fun isCorrect(value: String, answer: String): Boolean =
    answer.split("|").any { it.trim().equals(value.trim(), ignoreCase = true) }

private class UiContext(
    val root: UiNode,
    val state: UiCardState,
    val onSend: (String) -> Unit,
) {
    private val fields = flatten(root).filter { it is UiNode.Input || it is UiNode.Choice }
    private val graded = fields.filter { answerOf(it) != null }

    val gradedCount get() = graded.size

    fun correctCount(): Int = graded.count { isCorrect(state.values[idOf(it)].orEmpty(), answerOf(it).orEmpty()) }

    fun hasAnyInput(): Boolean = fields.any { !state.values[idOf(it)].isNullOrBlank() }

    fun submitText(header: String?): String {
        val lines = fields.mapIndexed { i, f ->
            val label = when (f) {
                is UiNode.Input -> f.label
                is UiNode.Choice -> f.label
                else -> null
            }
            "${i + 1}. ${label?.let { "$it: " }.orEmpty()}${state.values[idOf(f)].orEmpty()}"
        }
        return (listOfNotNull(header) + lines).joinToString("\n")
    }

    private fun idOf(node: UiNode): String = when (node) {
        is UiNode.Input -> node.id
        is UiNode.Choice -> node.id
        else -> ""
    }
}

@Composable
fun GenerativeCard(json: String, onSend: (String) -> Unit, modifier: Modifier = Modifier) {
    val root = remember(json) { UiParser.parse(json) }
    if (root == null) {
        Text("⚠ card", style = DevxType.Caption, color = DevxColors.TextSecondary, modifier = modifier)
        return
    }
    val state = remember(json) { UiCardState() }
    val ctx = remember(root, state, onSend) { UiContext(root, state, onSend) }
    RenderNode(root, ctx, modifier)
}

private fun visible(whenState: String, state: UiCardState): Boolean = when (whenState) {
    "unchecked" -> !state.checked
    "checked" -> state.checked
    "unsubmitted" -> !state.submitted
    else -> true
}

@Composable
private fun RenderNode(node: UiNode, ctx: UiContext, modifier: Modifier = Modifier) {
    when (node) {
        is UiNode.Card -> CardFrame(modifier) {
            if (node.title != null) {
                Text(node.title, style = DevxType.Large.copy(fontSize = 24.sp, lineHeight = 30.sp), color = DevxColors.TextPrimary)
            }
            if (node.subtitle != null) {
                Text(node.subtitle, style = DevxType.Caption, color = DevxColors.TextSecondary)
            }
            node.children.forEach { RenderNode(it, ctx) }
        }

        is UiNode.Section -> {
            val shape = RoundedCornerShape(20.dp)
            Column(
                modifier = modifier
                    .fillMaxWidth()
                    .border(1.dp, DevxColors.Outline, shape)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (node.title != null) Text(node.title, style = DevxType.BodyStrong, color = DevxColors.TextPrimary)
                node.children.forEach { RenderNode(it, ctx) }
            }
        }

        is UiNode.Text -> {
            val style = when (node.style) {
                "heading" -> DevxType.Title
                "strong" -> DevxType.BodyStrong
                "caption" -> DevxType.Caption
                else -> DevxType.Body
            }
            val color = when (node.color) {
                "success" -> DevxColors.Success
                "danger" -> DevxColors.Danger
                "warning" -> DevxColors.Warning
                "muted" -> DevxColors.TextSecondary
                else -> DevxColors.TextPrimary
            }
            Text(node.text, style = style, color = color, modifier = modifier)
        }

        is UiNode.Input -> InputView(node, ctx, modifier)
        is UiNode.Choice -> ChoiceView(node, ctx, modifier)
        is UiNode.Button -> ButtonView(node, ctx, modifier)
        is UiNode.Svg -> SvgView(node.caption.orEmpty(), node.svg, modifier)
        is UiNode.Code -> CodeView(node.language, node.code, modifier)
        is UiNode.Rich -> when (node.kind) {
            "map" -> MapBlock(node.json, modifier)
            "image" -> ImageBlock(node.json, modifier)
            else -> ChartBlock(node.json, modifier)
        }
        is UiNode.Score -> if (visible(node.whenState, ctx.state)) ScoreView(node, ctx, modifier)
        UiNode.Divider -> HorizontalDivider(modifier, color = DevxColors.Outline, thickness = 0.5.dp)
    }
}

@Composable
private fun Feedback(correct: Boolean, answer: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(
            if (correct) Icons.Outlined.CheckCircle else Icons.Outlined.Cancel,
            null,
            tint = if (correct) DevxColors.Success else DevxColors.Danger,
            modifier = Modifier.size(20.dp),
        )
        Text(
            text = if (correct) stringResource(R.string.quiz_correct) else stringResource(R.string.quiz_answer, answer.substringBefore("|")),
            style = DevxType.Body,
            color = if (correct) DevxColors.Success else DevxColors.Danger,
        )
    }
}

@Composable
private fun InputView(node: UiNode.Input, ctx: UiContext, modifier: Modifier) {
    val value = ctx.state.values[node.id].orEmpty()
    val locked = ctx.state.checked || ctx.state.submitted
    val verdict = if (ctx.state.checked && node.answer != null) isCorrect(value, node.answer) else null
    val shape = RoundedCornerShape(if (node.multiline) 20.dp else 26.dp)
    val borderColor = when (verdict) {
        true -> DevxColors.Success
        false -> DevxColors.Danger
        null -> DevxColors.Outline
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        BasicTextField(
            value = value,
            onValueChange = { ctx.state.values[node.id] = it },
            enabled = !locked,
            textStyle = DevxType.Body.copy(color = DevxColors.TextPrimary),
            cursorBrush = SolidColor(DevxColors.Accent),
            singleLine = !node.multiline,
            minLines = if (node.multiline) 3 else 1,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .border(1.dp, borderColor, shape),
            decorationBox = { inner ->
                Box(Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
                    if (value.isEmpty()) {
                        Text(
                            node.placeholder ?: stringResource(R.string.quiz_hint),
                            style = DevxType.Body,
                            color = DevxColors.TextDisabled,
                        )
                    }
                    inner()
                }
            },
        )
        if (verdict != null) Feedback(verdict, node.answer.orEmpty())
    }
}

@Composable
private fun ChoiceView(node: UiNode.Choice, ctx: UiContext, modifier: Modifier) {
    val selected = ctx.state.values[node.id]
    val locked = ctx.state.checked || ctx.state.submitted
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        node.options.forEach { option ->
            val isSelected = selected == option
            val verdict = if (ctx.state.checked && node.answer != null && isSelected) isCorrect(option, node.answer) else null
            val borderColor = when {
                verdict == true -> DevxColors.Success
                verdict == false -> DevxColors.Danger
                isSelected -> DevxColors.Accent
                else -> DevxColors.Outline
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .pressable(enabled = !locked, scaleTo = 0.97f) { ctx.state.values[node.id] = option }
                    .clip(RoundedCornerShape(26.dp))
                    .border(1.dp, borderColor, RoundedCornerShape(26.dp))
                    .padding(horizontal = 18.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    Modifier
                        .size(18.dp)
                        .border(2.dp, if (isSelected) DevxColors.Accent else DevxColors.TextDisabled, CircleShape)
                        .padding(4.dp)
                        .background(if (isSelected) DevxColors.Accent else Color.Transparent, CircleShape),
                )
                Text(option, style = DevxType.Body, color = DevxColors.TextPrimary)
            }
        }
        if (ctx.state.checked && node.answer != null) {
            Feedback(selected != null && isCorrect(selected, node.answer), node.answer)
        }
    }
}

@Composable
private fun ButtonView(node: UiNode.Button, ctx: UiContext, modifier: Modifier) {
    if (!visible(node.whenState, ctx.state)) return
    val state = ctx.state
    val isSubmit = node.action == "submit"
    val enabled = when {
        isSubmit -> !state.submitted && ctx.hasAnyInput()
        else -> true
    }
    PillButton(
        text = if (isSubmit && state.submitted) node.sentLabel ?: node.label else node.label,
        filled = !node.outline,
        enabled = enabled,
        modifier = modifier,
        onClick = {
            when (node.action) {
                "check" -> state.checked = true
                "reset" -> {
                    state.values.clear()
                    state.checked = false
                    state.submitted = false
                }
                "submit" -> {
                    state.submitted = true
                    ctx.onSend(ctx.submitText(node.header))
                }
            }
        },
    )
}

@Composable
private fun ScoreView(node: UiNode.Score, ctx: UiContext, modifier: Modifier) {
    val total = ctx.gradedCount
    val correct = ctx.correctCount()
    val color = when {
        total == 0 -> DevxColors.TextPrimary
        correct == total -> DevxColors.Success
        correct == 0 -> DevxColors.Danger
        else -> DevxColors.Warning
    }
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("$correct / $total", style = DevxType.Large, color = color, textAlign = TextAlign.Center)
        if (node.label.isNotEmpty()) {
            Text(node.label, style = DevxType.Body, color = DevxColors.TextSecondary)
        }
    }
}
