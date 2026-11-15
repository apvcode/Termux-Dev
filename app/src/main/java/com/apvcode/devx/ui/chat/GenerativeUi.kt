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
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
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
 * Elements: card, section, text, input, choice, slider, toggle, button, svg, code, score, divider, map, image, chart.
 * Buttons: action = check | reset | submit. `when` = always | unchecked | checked | unsubmitted.
 */

sealed interface UiNode {
    data class Card(val title: String?, val subtitle: String?, val children: List<UiNode>) : UiNode
    data class Section(val title: String?, val children: List<UiNode>) : UiNode
    data class Text(val text: String, val style: String, val color: String) : UiNode
    /** A text field. [label] is what is asked, [hint] a line of help under it, [placeholder] only an example of an answer. */
    data class Input(
        val id: String,
        val label: String?,
        val hint: String?,
        val placeholder: String?,
        val multiline: Boolean,
        val keyboard: String,
        val required: Boolean,
        val value: String?,
        val answer: String?,
    ) : UiNode

    /** One option to pick, or several ([multiple]); [chips] lays them out as small pills; [other] adds a field for an answer of their own. */
    data class Choice(
        val id: String,
        val label: String?,
        val hint: String?,
        val options: List<String>,
        val answer: String?,
        val multiple: Boolean,
        val chips: Boolean,
        val other: Boolean,
        val required: Boolean,
    ) : UiNode
    data class Slider(val id: String, val label: String?, val hint: String?, val min: Float, val max: Float, val step: Float, val unit: String?, val value: Float) : UiNode
    data class Toggle(val id: String, val label: String?, val hint: String?, val value: Boolean) : UiNode
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

    /** What a field asks: models write it under several names. */
    private fun JSONObject.labelOf(): String? = str("label") ?: str("question") ?: str("title") ?: str("name")

    private fun JSONObject.hintOf(): String? = str("hint") ?: str("help") ?: str("description") ?: str("helper")

    private fun JSONObject.idOf(counter: IntArray): String = str("id") ?: ("f" + counter[0])

    private fun JSONObject.flag(name: String, default: Boolean = false): Boolean =
        if (!has(name) || isNull(name)) default else when (val v = opt(name)) {
            is Boolean -> v
            is String -> v.equals("true", true) || v.equals("yes", true)
            is Number -> v.toInt() != 0
            else -> default
        }

    private fun optionsOf(obj: JSONObject): List<String> {
        val arr = obj.optJSONArray("options") ?: obj.optJSONArray("choices") ?: obj.optJSONArray("items") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            when (val o = arr.opt(i)) {
                is JSONObject -> o.str("label") ?: o.str("text") ?: o.str("title") ?: o.str("value")
                null -> null
                else -> o.toString().takeIf { it.isNotBlank() }
            }
        }.take(16)
    }

    private fun parseNode(obj: JSONObject, depth: Int, counter: IntArray): UiNode? {
        if (depth > MAX_DEPTH || ++counter[0] > MAX_NODES) return null
        val type = obj.optString("type").lowercase().trim()
        return when (type) {
            "card" -> UiNode.Card(obj.str("title"), obj.str("subtitle"), parseChildren(obj, depth, counter))
            "section" -> UiNode.Section(obj.str("title"), parseChildren(obj, depth, counter))
            "text", "heading", "paragraph", "title", "caption" -> UiNode.Text(
                text = obj.str("text") ?: obj.str("label") ?: obj.str("title").orEmpty(),
                style = obj.str("style") ?: if (type == "heading" || type == "title") "heading" else if (type == "caption") "caption" else "body",
                color = obj.optString("color", "default"),
            )
            "input", "textarea", "field", "text_input", "textfield", "text_field" -> UiNode.Input(
                id = obj.idOf(counter),
                label = obj.labelOf(),
                hint = obj.hintOf(),
                placeholder = obj.str("placeholder") ?: obj.str("example"),
                multiline = type == "textarea" || obj.flag("multiline") || obj.optInt("lines", 1) > 1,
                keyboard = obj.optString("keyboard", obj.optString("kind", "text")).lowercase(),
                required = obj.flag("required"),
                value = obj.str("value") ?: obj.str("default"),
                answer = obj.str("answer"),
            )
            "choice", "radio", "select", "single", "checkboxes", "multiselect", "multi", "checkbox_group", "chips" -> {
                val multiple = obj.flag("multiple") || type in setOf("checkboxes", "multiselect", "multi", "checkbox_group")
                UiNode.Choice(
                    id = obj.idOf(counter),
                    label = obj.labelOf(),
                    hint = obj.hintOf(),
                    options = optionsOf(obj),
                    answer = obj.str("answer"),
                    multiple = multiple,
                    chips = obj.flag("chips") || type == "chips" || obj.optString("layout") == "chips",
                    other = obj.flag("other") || obj.flag("allowOther"),
                    required = obj.flag("required"),
                )
            }
            "slider", "range", "scale" -> {
                val min = obj.optDouble("min", 0.0).toFloat()
                val max = obj.optDouble("max", 10.0).toFloat().let { if (it <= min) min + 1f else it }
                UiNode.Slider(
                    id = obj.idOf(counter), label = obj.labelOf(), hint = obj.hintOf(), min = min, max = max,
                    step = obj.optDouble("step", 1.0).toFloat().coerceAtLeast(0.01f), unit = obj.str("unit"),
                    value = obj.optDouble("value", min.toDouble()).toFloat().coerceIn(min, max),
                )
            }
            "toggle", "switch", "yesno", "yes_no" -> UiNode.Toggle(obj.idOf(counter), obj.labelOf(), obj.hintOf(), obj.flag("value"))
            "button", "submit" -> UiNode.Button(
                label = obj.optString("label"),
                action = obj.str("action") ?: if (type == "submit") "submit" else "",
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

/**
 * The cards keep what was written in them: while the app runs (a card that scrolls out of the list and back, or whose message is redrawn,
 * is not emptied) and also after it is closed and opened again (a sent form stays sent). Cards that are word for word the same share it,
 * which is rare and harmless.
 */
private object CardStates {
    private val cache = object : LinkedHashMap<Int, UiCardState>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, UiCardState>?) = size > 80
    }

    private fun prefs(context: android.content.Context) = context.applicationContext.getSharedPreferences("cards", android.content.Context.MODE_PRIVATE)

    @Synchronized
    fun of(context: android.content.Context, json: String): UiCardState = cache.getOrPut(json.hashCode()) { UiCardState().also { load(context, json.hashCode(), it) } }

    private fun load(context: android.content.Context, key: Int, state: UiCardState) {
        val raw = prefs(context).getString(key.toString(), null) ?: return
        runCatching {
            val o = JSONObject(raw)
            val v = o.optJSONObject("v")
            v?.keys()?.forEach { state.values[it] = v.optString(it) }
            state.checked = o.optBoolean("c")
            state.submitted = o.optBoolean("s")
        }
    }

    fun save(context: android.content.Context, key: Int, state: UiCardState) {
        val p = prefs(context)
        val values = JSONObject().also { o -> state.values.toMap().forEach { (k, v) -> o.put(k, v) } }
        val entry = JSONObject().put("v", values).put("c", state.checked).put("s", state.submitted).put("t", System.currentTimeMillis())
        val edit = p.edit().putString(key.toString(), entry.toString())
        // Only the newest hundred cards are kept.
        val all = p.all
        if (all.size > 100) {
            all.entries.sortedBy { runCatching { JSONObject(it.value as String).optLong("t") }.getOrDefault(0L) }.take(all.size - 90).forEach { edit.remove(it.key) }
        }
        edit.apply()
    }
}

private fun UiNode.children(): List<UiNode> = when (this) {
    is UiNode.Card -> children
    is UiNode.Section -> children
    else -> emptyList()
}

private const val MANY = "\u001F"

private fun isField(n: UiNode) = n is UiNode.Input || n is UiNode.Choice || n is UiNode.Slider || n is UiNode.Toggle

private fun idOf(node: UiNode): String = when (node) {
    is UiNode.Input -> node.id
    is UiNode.Choice -> node.id
    is UiNode.Slider -> node.id
    is UiNode.Toggle -> node.id
    else -> ""
}

private fun answerOf(node: UiNode): String? = when (node) {
    is UiNode.Input -> node.answer
    is UiNode.Choice -> node.answer
    else -> null
}

private fun isCorrect(value: String, answer: String): Boolean =
    answer.split("|").any { it.trim().equals(value.trim(), ignoreCase = true) }

private fun isRequired(n: UiNode) = (n is UiNode.Input && n.required) || (n is UiNode.Choice && n.required)

private fun pickedOf(value: String?): List<String> = value.orEmpty().split(MANY).filter { it.isNotEmpty() }

private fun numberText(v: Float, step: Float): String =
    if (step >= 1f && v == v.toInt().toFloat()) v.toInt().toString() else "%.2f".format(java.util.Locale.US, v).trimEnd('0').trimEnd('.')

private class UiContext(
    val root: UiNode,
    val state: UiCardState,
    val onSend: (String) -> Unit,
) {
    /** Every field with the title of the section it stands in: that title says what a bare field is about. */
    private val fields: List<Pair<UiNode, String?>> = buildList {
        fun walk(n: UiNode, section: String?) {
            if (isField(n)) add(n to section)
            val into = (n as? UiNode.Section)?.title ?: section
            n.children().forEach { walk(it, into) }
        }
        walk(root, null)
    }
    private val graded = fields.map { it.first }.filter { answerOf(it) != null }

    val gradedCount get() = graded.size

    fun correctCount(): Int = graded.count { isCorrect(state.values[idOf(it)].orEmpty(), answerOf(it).orEmpty()) }

    /** Something was filled in, or the card only has things that always have a value (a slider, a switch). */
    fun hasAnyInput(): Boolean = fields.any { (f, _) ->
        when (f) {
            is UiNode.Slider, is UiNode.Toggle -> true
            else -> !state.values[idOf(f)].isNullOrBlank()
        }
    }

    fun missingRequired(): Boolean = fields.any { (f, _) -> isRequired(f) && state.values[idOf(f)].isNullOrBlank() }

    /**
     * What goes back to the model: the section titles, and under each one a line per answered field, "what was asked: the answer", so
     * that the answers can be read without seeing the card. Fields left empty are not listed at all; the model is told they were skipped.
     */
    fun submitText(header: String?): String {
        val groups = LinkedHashMap<String?, MutableList<String>>()
        var skipped = 0
        fields.forEachIndexed { i, (f, section) ->
            val (label, value) = when (f) {
                is UiNode.Input -> (f.label ?: f.placeholder) to state.values[f.id].orEmpty().trim()
                is UiNode.Choice -> {
                    val picked = pickedOf(state.values[f.id]) + listOfNotNull(state.values[f.id + "#other"]?.trim()?.takeIf { it.isNotEmpty() })
                    f.label to picked.joinToString(", ")
                }
                is UiNode.Slider -> f.label to (state.values[f.id] ?: numberText(f.value, f.step)) + (f.unit?.let { " $it" } ?: "")
                is UiNode.Toggle -> f.label to (if ((state.values[f.id] ?: f.value.toString()).toBoolean()) "yes" else "no")
                else -> null to ""
            }
            if (value.isBlank()) { skipped++; return@forEachIndexed }
            groups.getOrPut(section) { ArrayList() } += "- " + (label?.trim()?.let { "$it \u2014 " } ?: "Field ${i + 1} \u2014 ") + value.replace("\n", " ")
        }
        val out = ArrayList<String>()
        header?.let { out += it }
        groups.forEach { (section, lines) ->
            out += ""
            if (section != null) out += "**$section**\n"
            out += lines.joinToString("\n")
        }
        if (skipped > 0) out += "\n(The other fields were left empty.)"
        return out.joinToString("\n").trim()
    }
}

@Composable
fun GenerativeCard(json: String, onSend: (String) -> Unit, modifier: Modifier = Modifier) {
    val root = remember(json) { UiParser.parse(json) }
    if (root == null) {
        Text("⚠ card", style = DevxType.Caption, color = DevxColors.TextSecondary, modifier = modifier)
        return
    }
    val context = androidx.compose.ui.platform.LocalContext.current
    val state = remember(json) { CardStates.of(context, json) }
    val ctx = remember(root, state, onSend) { UiContext(root, state, onSend) }
    // What is written or sent is kept, a moment after it stops changing.
    @OptIn(kotlinx.coroutines.FlowPreview::class)
    LaunchedEffect(state) {
        androidx.compose.runtime.snapshotFlow { Triple(state.values.toMap(), state.checked, state.submitted) }
            .drop(1)
            .debounce(500)
            .collect { CardStates.save(context, json.hashCode(), state) }
    }
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
        is UiNode.Slider -> SliderView(node, ctx, modifier)
        is UiNode.Toggle -> ToggleView(node, ctx, modifier)
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

/** What a field asks, in full, above it; and under it the help line, when there is one. A field is never only a grey hint. */
@Composable
private fun FieldHead(label: String?, hint: String?, required: Boolean) {
    if (label == null && hint == null) return
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (label != null) {
            Text(
                if (required) "$label *" else label,
                style = DevxType.BodyStrong.copy(fontSize = 15.sp, lineHeight = 21.sp), color = DevxColors.TextPrimary,
            )
        }
        if (hint != null) Text(hint, style = DevxType.Caption, color = DevxColors.TextSecondary)
    }
}

@Composable
private fun InputView(node: UiNode.Input, ctx: UiContext, modifier: Modifier) {
    LaunchedEffect(node.id) {
        // A value that was given as a start is there from the first moment (and is not put back after it was cleared).
        if (node.value != null && ctx.state.values[node.id] == null) ctx.state.values[node.id] = node.value
    }
    val value = ctx.state.values[node.id].orEmpty()
    val locked = ctx.state.checked || ctx.state.submitted
    val verdict = if (ctx.state.checked && node.answer != null) isCorrect(value, node.answer) else null
    val shape = RoundedCornerShape(if (node.multiline) 20.dp else 26.dp)
    val borderColor = when (verdict) {
        true -> DevxColors.Success
        false -> DevxColors.Danger
        null -> DevxColors.Outline
    }
    val keyboard = when (node.keyboard) {
        "number", "numeric", "integer", "decimal" -> androidx.compose.ui.text.input.KeyboardType.Number
        "email" -> androidx.compose.ui.text.input.KeyboardType.Email
        "phone", "tel" -> androidx.compose.ui.text.input.KeyboardType.Phone
        "url" -> androidx.compose.ui.text.input.KeyboardType.Uri
        else -> androidx.compose.ui.text.input.KeyboardType.Text
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FieldHead(node.label, node.hint, node.required)
        BasicTextField(
            value = value,
            onValueChange = { ctx.state.values[node.id] = it },
            enabled = !locked,
            textStyle = DevxType.Body.copy(color = DevxColors.TextPrimary),
            cursorBrush = SolidColor(DevxColors.Accent),
            singleLine = !node.multiline,
            minLines = if (node.multiline) 3 else 1,
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                keyboardType = keyboard,
                capitalization = if (keyboard == androidx.compose.ui.text.input.KeyboardType.Text) androidx.compose.ui.text.input.KeyboardCapitalization.Sentences else androidx.compose.ui.text.input.KeyboardCapitalization.None,
            ),
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

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ChoiceView(node: UiNode.Choice, ctx: UiContext, modifier: Modifier) {
    val raw = ctx.state.values[node.id]
    val picked = if (node.multiple) pickedOf(raw) else listOfNotNull(raw?.takeIf { it.isNotEmpty() })
    val locked = ctx.state.checked || ctx.state.submitted
    fun toggle(option: String) {
        if (!node.multiple) { ctx.state.values[node.id] = option; return }
        val next = if (option in picked) picked - option else picked + option
        ctx.state.values[node.id] = next.joinToString(MANY)
    }
    @Composable
    fun One(option: String) {
        val isSelected = option in picked
        val verdict = if (ctx.state.checked && node.answer != null && isSelected) isCorrect(option, node.answer) else null
        val borderColor = when {
            verdict == true -> DevxColors.Success
            verdict == false -> DevxColors.Danger
            isSelected -> DevxColors.Accent
            else -> DevxColors.Outline
        }
        val shape = RoundedCornerShape(26.dp)
        Row(
            modifier = (if (node.chips) Modifier else Modifier.fillMaxWidth())
                .clip(shape)
                .pressable(enabled = !locked, scaleTo = 0.97f) { toggle(option) }
                .border(1.dp, borderColor, shape)
                .background(if (isSelected && node.chips) DevxColors.Accent.copy(alpha = 0.14f) else Color.Transparent, shape)
                .padding(horizontal = if (node.chips) 16.dp else 18.dp, vertical = if (node.chips) 10.dp else 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!node.chips) {
                // A round mark for one answer, a square one for several.
                val markShape = if (node.multiple) RoundedCornerShape(5.dp) else CircleShape
                Box(
                    Modifier
                        .size(18.dp)
                        .border(2.dp, if (isSelected) DevxColors.Accent else DevxColors.TextDisabled, markShape)
                        .padding(3.dp)
                        .background(if (isSelected) DevxColors.Accent else Color.Transparent, markShape),
                )
            }
            Text(option, style = DevxType.Body, color = DevxColors.TextPrimary, modifier = if (node.chips) Modifier else Modifier.weight(1f))
        }
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FieldHead(node.label, node.hint ?: if (node.multiple) stringResource(R.string.card_pick_several) else null, node.required)
        if (node.chips) {
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                node.options.forEach { One(it) }
            }
        } else node.options.forEach { One(it) }
        if (node.other) {
            val own = ctx.state.values[node.id + "#other"].orEmpty()
            BasicTextField(
                value = own,
                onValueChange = { ctx.state.values[node.id + "#other"] = it },
                enabled = !locked,
                textStyle = DevxType.Body.copy(color = DevxColors.TextPrimary),
                cursorBrush = SolidColor(DevxColors.Accent),
                singleLine = true,
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).border(1.dp, DevxColors.Outline, RoundedCornerShape(26.dp)),
                decorationBox = { inner ->
                    Box(Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
                        if (own.isEmpty()) Text(stringResource(R.string.card_other), style = DevxType.Body, color = DevxColors.TextDisabled)
                        inner()
                    }
                },
            )
        }
        if (ctx.state.checked && node.answer != null) {
            Feedback(picked.isNotEmpty() && picked.all { isCorrect(it, node.answer) }, node.answer)
        }
    }
}

@Composable
private fun SliderView(node: UiNode.Slider, ctx: UiContext, modifier: Modifier) {
    val locked = ctx.state.checked || ctx.state.submitted
    val current = ctx.state.values[node.id]?.toFloatOrNull() ?: node.value
    val steps = (((node.max - node.min) / node.step).toInt() - 1).coerceIn(0, 100)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { FieldHead(node.label, node.hint, false) }
            Text(numberText(current, node.step) + (node.unit?.let { " $it" } ?: ""), style = DevxType.BodyStrong, color = DevxColors.TextPrimary)
        }
        androidx.compose.material3.Slider(
            value = current,
            onValueChange = { v ->
                val snapped = (Math.round((v - node.min) / node.step) * node.step + node.min).coerceIn(node.min, node.max)
                ctx.state.values[node.id] = numberText(snapped, node.step)
            },
            valueRange = node.min..node.max,
            steps = steps,
            enabled = !locked,
            colors = androidx.compose.material3.SliderDefaults.colors(
                thumbColor = DevxColors.Accent, activeTrackColor = DevxColors.Accent, inactiveTrackColor = DevxColors.Outline,
            ),
        )
    }
}

@Composable
private fun ToggleView(node: UiNode.Toggle, ctx: UiContext, modifier: Modifier) {
    val locked = ctx.state.checked || ctx.state.submitted
    val on = (ctx.state.values[node.id] ?: node.value.toString()).toBoolean()
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.weight(1f)) { FieldHead(node.label, node.hint, false) }
        androidx.compose.material3.Switch(
            checked = on,
            onCheckedChange = { ctx.state.values[node.id] = it.toString() },
            enabled = !locked,
            colors = androidx.compose.material3.SwitchDefaults.colors(
                checkedThumbColor = DevxColors.OnAccent, checkedTrackColor = DevxColors.Accent,
                uncheckedThumbColor = DevxColors.TextSecondary, uncheckedTrackColor = DevxColors.Card, uncheckedBorderColor = DevxColors.Outline,
            ),
        )
    }
}

@Composable
private fun ButtonView(node: UiNode.Button, ctx: UiContext, modifier: Modifier) {
    if (!visible(node.whenState, ctx.state)) return
    val state = ctx.state
    val isSubmit = node.action == "submit"
    val enabled = when {
        isSubmit -> !state.submitted && ctx.hasAnyInput() && !ctx.missingRequired()
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
