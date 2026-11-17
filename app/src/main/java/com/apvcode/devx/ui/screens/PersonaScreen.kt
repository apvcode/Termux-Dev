package com.apvcode.devx.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apvcode.devx.R
import com.apvcode.devx.data.Persona
import com.apvcode.devx.ui.components.CircleIconButton
import com.apvcode.devx.ui.components.DevxDialog
import com.apvcode.devx.ui.components.DevxTextField
import com.apvcode.devx.ui.components.PillButton
import com.apvcode.devx.ui.components.SettingsDivider
import com.apvcode.devx.ui.components.pressable
import com.apvcode.devx.ui.theme.DevxColors
import com.apvcode.devx.ui.theme.DevxType
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.text.style.TextAlign

private val Soft = spring<Float>(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow)

/** How the assistant speaks: a live preview, ready styles, thirteen settings, a name and the person's own words. */
@Composable
fun PersonaScreen(onBack: () -> Unit) {
    val current = Persona.current()
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    var fineTop by remember { mutableIntStateOf(0) }
    var askReset by remember { mutableStateOf(false) }
    if (askReset) {
        DevxDialog(stringResource(R.string.persona_reset), onDismiss = { askReset = false }) {
            Column(Modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(stringResource(R.string.persona_reset_ask), style = DevxType.Body, color = DevxColors.TextSecondary)
                PillButton(stringResource(R.string.persona_reset_do), onClick = { Persona.reset(); askReset = false })
                PillButton(stringResource(R.string.settings_cancel), filled = false, onClick = { askReset = false })
            }
        }
    }
    Column(Modifier.fillMaxSize().background(DevxColors.Bg).statusBarsPadding()) {
        // The top bar: back, the title with the style under it, and reset when there is something to reset.
        Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
            CircleIconButton(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.cd_back), onBack, Modifier.align(Alignment.CenterStart))
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.persona_title), style = DevxType.Title, color = DevxColors.TextPrimary)
                AnimatedContent(
                    targetState = current?.title ?: R.string.persona_style_custom,
                    transitionSpec = { (fadeIn(tween(220)) + slideInVertically { it / 2 }) togetherWith (fadeOut(tween(120)) + slideOutVertically { -it / 2 }) },
                    label = "style name",
                ) { Text(stringResource(it), style = DevxType.Caption, color = DevxColors.TextSecondary) }
            }
            ResetButton(!Persona.isDefault, Modifier.align(Alignment.CenterEnd)) { askReset = true }
        }
        Column(
            Modifier.fillMaxSize().imePadding().verticalScroll(scroll).navigationBarsPadding().padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Preview(Modifier.appear(0))

            Label(stringResource(R.string.persona_style), Modifier.appear(1).padding(top = 8.dp))
            Styles(current, Modifier.appear(2)) { scope.launch { scroll.animateScrollTo(fineTop, spring(stiffness = Spring.StiffnessLow)) } }

            Label(stringResource(R.string.persona_fine), Modifier.onGloballyPositioned { fineTop = it.positionInParent().y.toInt() }.appear(3).padding(top = 8.dp))
            Persona.Group.values().forEachIndexed { i, group -> GroupCard(group, Modifier.appear(4 + i)) }

            Label(stringResource(R.string.persona_intro), Modifier.appear(7).padding(top = 8.dp))
            Column(Modifier.appear(8), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Counted(Persona.name, Persona.MAX_NAME) {
                    DevxTextField(stringResource(R.string.persona_name), Persona.name, { Persona.saveName(it.replace("\n", "")) }, placeholder = stringResource(R.string.persona_name_ph))
                }
                Counted(Persona.instructions, Persona.MAX_INSTRUCTIONS) {
                    DevxTextField(stringResource(R.string.persona_own), Persona.instructions, { Persona.saveInstructions(it) }, singleLine = false, minLines = 4, placeholder = stringResource(R.string.persona_own_placeholder))
                }
                Tips()
            }
        }
    }
}

@Composable
private fun ResetButton(visible: Boolean, modifier: Modifier, onClick: () -> Unit) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn(tween(200)) + scaleIn(spring(dampingRatio = 0.6f, stiffness = 400f), initialScale = 0.6f),
        exit = fadeOut(tween(150)) + scaleOut(targetScale = 0.6f),
    ) {
        CircleIconButton(ImageVector.vectorResource(R.drawable.ic_c_restore), stringResource(R.string.persona_reset), onClick)
    }
}

/** Comes in once, when the screen opens: a soft rise and fade, one part a moment after another. */
@Composable
private fun Modifier.appear(index: Int): Modifier {
    val shown = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(35L * index)
        shown.animateTo(1f, spring(dampingRatio = 0.9f, stiffness = 260f))
    }
    return graphicsLayer {
        alpha = shown.value
        translationY = (1f - shown.value) * 18.dp.toPx()
    }
}

@Composable
private fun Label(text: String, modifier: Modifier = Modifier) {
    Text(text, style = DevxType.Section, color = DevxColors.TextSecondary, modifier = modifier.padding(start = 8.dp))
}

/** A field with a quiet count of letters under it once it gets close to the limit. */
@Composable
private fun Counted(text: String, max: Int, field: @Composable () -> Unit) {
    Column {
        field()
        AnimatedVisibility(text.length > max * 0.7f, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
            Text(
                "${text.length} / $max",
                style = DevxType.Caption,
                color = if (text.length >= max) DevxColors.Warning else DevxColors.TextDisabled,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp, end = 8.dp),
                textAlign = TextAlign.End,
            )
        }
    }
}

// ---------------------------------------------------------------- preview

/** A made-up exchange that is put together from the settings, so that every change shows at once. */
@Composable
private fun Preview(modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(28.dp)
    Column(
        modifier.fillMaxWidth().clip(shape).background(DevxColors.Card).border(1.dp, DevxColors.Outline, shape).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(ImageVector.vectorResource(R.drawable.ic_c_sparkles), null, tint = DevxColors.TextSecondary, modifier = Modifier.size(16.dp))
            Text(stringResource(R.string.persona_preview), style = DevxType.Caption, color = DevxColors.TextSecondary, modifier = Modifier.weight(1f))
            AnimatedContent(Persona.name.trim().ifEmpty { "Devx" }, transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(120)) }, label = "name") {
                Text(it, style = DevxType.Caption, color = DevxColors.TextDisabled, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            Text(
                stringResource(R.string.pv_q),
                style = DevxType.Body,
                color = DevxColors.TextPrimary,
                modifier = Modifier.clip(RoundedCornerShape(20.dp)).background(DevxColors.CardInner).padding(horizontal = 14.dp, vertical = 9.dp),
            )
        }
        val answer = previewAnswer()
        AnimatedContent(
            targetState = answer,
            transitionSpec = {
                (fadeIn(tween(260, delayMillis = 60)) togetherWith fadeOut(tween(120))) using
                    SizeTransform(clip = false) { _, _ -> spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessMediumLow) }
            },
            label = "answer",
        ) { blocks ->
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                blocks.forEach { Text(it, style = DevxType.Reply.copy(fontSize = 15.sp, lineHeight = 23.sp), color = DevxColors.TextPrimary) }
            }
        }
    }
}

@Composable
private fun previewAnswer(): List<AnnotatedString> {
    fun l(key: String) = Persona.level(key)
    val casual = l("formality") == 0
    val formal = l("formality") == 2
    val manyEmoji = l("emoji") == 2
    val short = l("length") == 0
    val detailed = l("length") == 2
    val paras = mutableListOf<String>()
    var heading: String? = null
    var bullets: List<String> = emptyList()

    val open = stringResource(if (formal) R.string.pv_open_formal else if (casual) R.string.pv_open_casual else R.string.pv_open)
    val shortText = stringResource(R.string.pv_short)
    val normalText = stringResource(R.string.pv_normal)
    val head = stringResource(R.string.pv_heading)
    val b = listOf(stringResource(R.string.pv_b1), stringResource(R.string.pv_b2), stringResource(R.string.pv_b3))
    val blunt = stringResource(R.string.pv_blunt)
    val gentle = stringResource(R.string.pv_gentle)
    val why = stringResource(when (l("expertise")) { 0 -> R.string.pv_why_simple; 2 -> R.string.pv_why_expert; else -> R.string.pv_why })
    val more = stringResource(R.string.pv_more)
    val example = stringResource(R.string.pv_example)
    val opinion = stringResource(R.string.pv_opinion)
    val neutral = stringResource(R.string.pv_neutral)
    val joke = stringResource(R.string.pv_joke)
    val ask = stringResource(if (casual) R.string.pv_ask_casual else R.string.pv_ask)
    val bye = stringResource(R.string.pv_bye)

    if (l("warmth") == 2 || l("enthusiasm") == 2) paras += open + if (manyEmoji) " 👋" else ""
    val structured = l("structure") == 2
    if (structured) {
        if (!short) heading = head + if (manyEmoji) " 🍵" else ""
        bullets = b
    } else {
        paras += (if (short) shortText else normalText) + if (manyEmoji) " 🍵" else ""
    }
    val after = mutableListOf<String>()
    if (l("directness") == 2) after += blunt else if (l("directness") == 0 && !short) after += gentle
    if (l("approach") == 2 || (detailed && l("approach") != 0)) after += why
    if (detailed && l("approach") != 0) after += more
    if (l("examples") == 2) after += example
    if (l("opinions") == 2) after += opinion else if (l("opinions") == 0 && !short) after += neutral
    if (l("humor") == 2) after += joke + if (manyEmoji) " 😄" else ""
    when {
        l("questions") == 2 -> after += ask
        l("warmth") == 2 -> after += bye + (if (l("enthusiasm") == 0) "." else "!") + if (manyEmoji) " ☕" else ""
    }

    val out = mutableListOf<AnnotatedString>()
    paras.forEach { out += AnnotatedString(it) }
    if (heading != null || bullets.isNotEmpty()) out += buildAnnotatedString {
        heading?.let { withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(it) }; append("\n") }
        bullets.forEachIndexed { i, line ->
            if (i > 0) append("\n")
            append("•  ")
            val colon = line.indexOf(':')
            if (colon > 0) {
                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(line.substring(0, colon + 1)) }
                append(line.substring(colon + 1))
            } else append(line)
        }
    }
    after.forEach { out += AnnotatedString(it) }
    return out
}

// ---------------------------------------------------------------- styles

@Composable
private fun Styles(current: Persona.Style?, modifier: Modifier = Modifier, onCustom: () -> Unit) {
    val row = rememberLazyListState()
    val selected = current?.let { Persona.styles.indexOf(it) } ?: Persona.styles.size
    LaunchedEffect(selected) { row.animateScrollToItem((selected - 1).coerceAtLeast(0)) }
    LazyRow(
        // Edge to edge: the cards slide out from under the sides of the screen, not from the margin.
        modifier.layout { measurable, constraints ->
            val bleed = 16.dp.roundToPx()
            val placeable = measurable.measure(constraints.copy(minWidth = constraints.maxWidth + bleed * 2, maxWidth = constraints.maxWidth + bleed * 2))
            layout(constraints.maxWidth, placeable.height) { placeable.place(-bleed, 0) }
        },
        state = row,
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        itemsIndexed(Persona.styles, key = { _, s -> s.id }) { _, style ->
            StyleCard(style.icon, stringResource(style.title), stringResource(style.hint), current?.id == style.id) { Persona.apply(style) }
        }
        item(key = "custom") {
            StyleCard(R.drawable.ic_c_adjustments_horizontal, stringResource(R.string.persona_style_custom), stringResource(R.string.persona_style_custom_hint), current == null, onCustom)
        }
    }
}

@Composable
private fun StyleCard(icon: Int, title: String, hint: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(24.dp)
    val bg by animateColorAsState(if (selected) DevxColors.Accent else DevxColors.Card, tween(280), label = "bg")
    val fg by animateColorAsState(if (selected) DevxColors.OnAccent else DevxColors.TextPrimary, tween(280), label = "fg")
    val sub by animateColorAsState(if (selected) DevxColors.OnAccent.copy(alpha = 0.66f) else DevxColors.TextSecondary, tween(280), label = "sub")
    val edge by animateColorAsState(if (selected) DevxColors.Accent else DevxColors.Outline, tween(280), label = "edge")
    val lift by animateFloatAsState(if (selected) 1f else 0.97f, spring(dampingRatio = 0.55f, stiffness = 380f), label = "lift")
    Column(
        Modifier
            .width(136.dp)
            .height(164.dp)
            .pressable(scaleTo = 0.94f, onClick = onClick)
            .graphicsLayer { scaleX = lift; scaleY = lift }
            .clip(shape)
            .background(bg)
            .border(1.dp, edge, shape)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(38.dp).clip(CircleShape).background(fg.copy(alpha = 0.08f)), contentAlignment = Alignment.Center) {
            Icon(ImageVector.vectorResource(icon), null, tint = fg, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.height(2.dp))
        Text(title, style = DevxType.BodyStrong.copy(fontSize = 15.sp, lineHeight = 20.sp), color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(hint, style = DevxType.Caption.copy(fontSize = 12.sp, lineHeight = 16.sp), color = sub, maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
}

// ---------------------------------------------------------------- fine settings

@Composable
private fun GroupCard(group: Persona.Group, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(28.dp)
    var open by rememberSaveable(group.name) { mutableStateOf(true) }
    val members = Persona.traits.withIndex().filter { it.value.group == group }
    val changed = members.count { Persona.level(it.index) != 1 }
    val turn by animateFloatAsState(if (open) 180f else 0f, spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow), label = "chevron")
    Column(modifier.fillMaxWidth().clip(shape).background(DevxColors.Card).border(1.dp, DevxColors.Outline, shape)) {
        Row(
            Modifier.fillMaxWidth().pressable(scaleTo = 0.98f, haptic = false) { open = !open }.padding(horizontal = 18.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(ImageVector.vectorResource(group.icon), null, tint = DevxColors.TextPrimary, modifier = Modifier.size(22.dp))
            Text(stringResource(group.title), style = DevxType.BodyStrong, color = DevxColors.TextPrimary, modifier = Modifier.weight(1f))
            AnimatedVisibility(changed > 0, enter = fadeIn() + scaleIn(spring(dampingRatio = 0.6f, stiffness = 500f)), exit = fadeOut() + scaleOut()) {
                AnimatedContent(changed, transitionSpec = { (fadeIn() + slideInVertically { if (targetState > initialState) it else -it }) togetherWith (fadeOut() + slideOutVertically { if (targetState > initialState) -it else it }) }, label = "count") { n ->
                    Text(
                        n.toString(),
                        style = DevxType.Caption.copy(fontWeight = FontWeight.Medium),
                        color = DevxColors.OnAccent,
                        modifier = Modifier.clip(CircleShape).background(DevxColors.Accent).padding(horizontal = 8.dp, vertical = 1.dp),
                    )
                }
            }
            Icon(ImageVector.vectorResource(R.drawable.ic_chevron_down), null, tint = DevxColors.TextSecondary, modifier = Modifier.size(20.dp).graphicsLayer { rotationZ = turn })
        }
        AnimatedVisibility(
            open,
            enter = expandVertically(spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessMediumLow)) + fadeIn(tween(220, delayMillis = 60)),
            exit = shrinkVertically(spring(dampingRatio = 1f, stiffness = Spring.StiffnessMedium)) + fadeOut(tween(120)),
        ) {
            Column {
                members.forEach { (index, trait) ->
                    SettingsDivider()
                    TraitRow(index, trait)
                }
            }
        }
    }
}

@Composable
private fun TraitRow(index: Int, trait: Persona.Trait) {
    val level = Persona.level(index)
    val active = level != 1
    val badge by animateColorAsState(if (active) DevxColors.Accent.copy(alpha = 0.14f) else DevxColors.CardInner, tween(260), label = "badge")
    val tint by animateColorAsState(if (active) DevxColors.TextPrimary else DevxColors.TextSecondary, tween(260), label = "tint")
    Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(34.dp).clip(RoundedCornerShape(11.dp)).background(badge), contentAlignment = Alignment.Center) {
                Icon(ImageVector.vectorResource(trait.icon), null, tint = tint, modifier = Modifier.size(18.dp))
            }
            Text(stringResource(trait.title), style = DevxType.Body, color = DevxColors.TextPrimary)
        }
        Segments(trait.levels.map { stringResource(it) }, level) { Persona.setLevel(index, it) }
        AnimatedContent(
            targetState = level,
            transitionSpec = {
                val dir = if (targetState > initialState) 1 else -1
                ((slideInHorizontally(spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessMediumLow)) { dir * it / 8 } + fadeIn(tween(220, delayMillis = 40))) togetherWith
                    (slideOutHorizontally(tween(160)) { -dir * it / 8 } + fadeOut(tween(120)))) using
                    SizeTransform(clip = false) { _, _ -> spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessMediumLow) }
            },
            label = "hint",
        ) { l ->
            Text(stringResource(trait.hints[l]), style = DevxType.Caption, color = DevxColors.TextSecondary, modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp))
        }
    }
}

/** Three choices in one bar, with a white thumb that glides to the chosen one. */
@Composable
private fun Segments(labels: List<String>, selected: Int, onPick: (Int) -> Unit) {
    val shape = RoundedCornerShape(50)
    val haptic = LocalHapticFeedback.current
    BoxWithConstraints(Modifier.fillMaxWidth().height(40.dp).clip(shape).background(DevxColors.CardInner).border(1.dp, DevxColors.Outline, shape)) {
        val w = maxWidth / labels.size
        val x by animateDpAsState(w * selected, spring(dampingRatio = 0.72f, stiffness = 420f), label = "thumb")
        Box(Modifier.offset(x = x).width(w).fillMaxHeight().padding(3.dp).clip(shape).background(DevxColors.Accent))
        Row(Modifier.fillMaxSize()) {
            labels.forEachIndexed { i, label ->
                val color by animateColorAsState(if (i == selected) DevxColors.OnAccent else DevxColors.TextSecondary, tween(220), label = "label")
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable(remember { MutableInteractionSource() }, indication = null) {
                            if (i != selected) {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onPick(i)
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(label, style = DevxType.Caption.copy(fontWeight = FontWeight.Medium), color = color, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 6.dp))
                }
            }
        }
    }
}

// ---------------------------------------------------------------- tips

/** Ready lines for the instructions: a tap adds one, and it leaves the list once it is there. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Tips() {
    val tips = listOf(R.string.persona_tip_1, R.string.persona_tip_2, R.string.persona_tip_3, R.string.persona_tip_4, R.string.persona_tip_5, R.string.persona_tip_6).map { stringResource(it) }
    val left = tips.filter { it !in Persona.instructions }
    Column(Modifier.animateContentSize(spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessMediumLow)), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (left.isNotEmpty()) Text(stringResource(R.string.persona_tips), style = DevxType.Caption, color = DevxColors.TextSecondary, modifier = Modifier.padding(start = 8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            tips.forEach { tip ->
                AnimatedVisibility(tip in left, enter = fadeIn(tween(200)) + scaleIn(initialScale = 0.8f), exit = fadeOut(tween(140)) + scaleOut(targetScale = 0.8f)) {
                    val shape = RoundedCornerShape(50)
                    Row(
                        Modifier
                            .pressable(scaleTo = 0.94f) {
                                val now = Persona.instructions.trimEnd()
                                Persona.saveInstructions(if (now.isEmpty()) tip else "$now\n$tip")
                            }
                            .clip(shape)
                            .border(1.dp, DevxColors.Outline, shape)
                            .heightIn(min = 36.dp)
                            .padding(start = 10.dp, end = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(ImageVector.vectorResource(R.drawable.ic_c_plus), null, tint = DevxColors.TextSecondary, modifier = Modifier.size(16.dp))
                        Text(tip, style = DevxType.Caption, color = DevxColors.TextPrimary)
                    }
                }
            }
        }
    }
}
