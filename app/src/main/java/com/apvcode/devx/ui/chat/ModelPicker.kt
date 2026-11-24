package com.apvcode.devx.ui.chat

import com.apvcode.devx.ui.components.pressable
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.apvcode.devx.R
import com.apvcode.devx.data.Level
import com.apvcode.devx.data.ModelRef
import com.apvcode.devx.data.displayName
import com.apvcode.devx.ui.components.DevxIcons
import com.apvcode.devx.ui.components.PillButton
import com.apvcode.devx.ui.theme.DevxColors
import com.apvcode.devx.ui.theme.DevxType
import androidx.compose.animation.AnimatedContent

/** The models of every provider, searchable, in a sheet from the bottom. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelPicker(
    vm: ChatViewModel,
    onDismiss: () -> Unit,
    onOpenProviders: () -> Unit,
    /** When given, the picker chooses a model for something else (not for the chat): [selected] is ticked and [onPick] gets the choice. */
    onPick: ((ModelRef?) -> Unit)? = null,
    selected: ModelRef? = null,
    /** A first row that stands for "no choice of its own" (only with [onPick]). */
    autoLabel: String? = null,
) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var query by remember { mutableStateOf("") }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheet,
        containerColor = DevxColors.Drawer,
        contentColor = DevxColors.TextPrimary,
        scrimColor = Color.Black.copy(alpha = 0.6f),
    ) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.72f).navigationBarsPadding().padding(horizontal = 16.dp)) {
            if (onPick == null) ReasoningSection(vm)
            run {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(CircleShape)
                        .background(DevxColors.Card)
                        .padding(horizontal = 18.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(DevxIcons.ChatSearch, null, tint = DevxColors.TextSecondary, modifier = Modifier.size(22.dp))
                    BasicTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        textStyle = DevxType.Body.copy(color = DevxColors.TextPrimary),
                        cursorBrush = SolidColor(DevxColors.Accent),
                        modifier = Modifier.weight(1f),
                        decorationBox = { inner ->
                            Box {
                                if (query.isEmpty()) {
                                    Text(stringResource(R.string.model_search), style = DevxType.Body, color = DevxColors.TextSecondary)
                                }
                                inner()
                            }
                        },
                    )
                }
                LazyColumn(Modifier.fillMaxWidth().weight(1f).padding(top = 6.dp)) {
                    if (onPick != null && autoLabel != null) item(key = "auto") {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .pressable(scaleTo = 0.97f) {
                                    onPick(null)
                                    onDismiss()
                                }
                                .clip(RoundedCornerShape(14.dp))
                                .padding(horizontal = 8.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(autoLabel, style = DevxType.Body, color = DevxColors.TextPrimary, modifier = Modifier.weight(1f))
                            if (selected == null) Icon(DevxIcons.Check, null, tint = DevxColors.TextPrimary, modifier = Modifier.size(22.dp))
                        }
                    }
                    vm.providers.forEach { provider ->
                        val queryText = query.trim()
                        val models = provider.models.filter { query.isBlank() || it.contains(queryText, ignoreCase = true) || provider.displayName(it).contains(queryText, ignoreCase = true) }
                        if (models.isNotEmpty()) {
                            item(key = "h-${provider.id}") {
                                Text(
                                    provider.name,
                                    style = DevxType.Section,
                                    color = DevxColors.TextSecondary,
                                    modifier = Modifier.padding(start = 6.dp, top = 16.dp, bottom = 6.dp),
                                )
                            }
                            items(models, key = { "${provider.id}/$it" }) { model ->
                                val ref = ModelRef(provider.id, model)
                                val isSelected = if (onPick != null) selected == ref else vm.model == ref
                                val shown = provider.displayName(model)
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .pressable(scaleTo = 0.97f) {
                                            if (onPick != null) onPick(ref) else vm.selectModel(ref)
                                            onDismiss()
                                        }
                                        .clip(RoundedCornerShape(14.dp))
                                        .padding(horizontal = 8.dp, vertical = 14.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            shown,
                                            style = DevxType.Body,
                                            color = DevxColors.TextPrimary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        if (shown != model) {
                                            Text(model, style = DevxType.Caption, color = DevxColors.TextDisabled, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        }
                                    }
                                    if (model in provider.anthropicModels) {
                                        Text(
                                            stringResource(R.string.model_format_anthropic),
                                            style = DevxType.Caption,
                                            color = DevxColors.TextDisabled,
                                            modifier = Modifier.padding(horizontal = 8.dp),
                                        )
                                    }
                                    if (isSelected) {
                                        Icon(DevxIcons.Check, null, tint = DevxColors.TextPrimary, modifier = Modifier.size(22.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

internal fun levelLabelRes(level: Level): Int = when (level) {
    Level.Off -> R.string.lvl_off
    Level.Low -> R.string.lvl_low
    Level.Normal -> R.string.lvl_normal
    Level.High -> R.string.lvl_high
    Level.XHigh -> R.string.lvl_xhigh
    Level.Max -> R.string.lvl_max
}

internal fun levelMeaningRes(level: Level): Int = when (level) {
    Level.Off -> R.string.ld_off
    Level.Low -> R.string.ld_low
    Level.Normal -> R.string.ld_normal
    Level.High -> R.string.ld_high
    Level.XHigh -> R.string.ld_xhigh
    Level.Max -> R.string.ld_max
}

/** The reasoning levels of the chosen model: only those the model really has, as in devx. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReasoningSection(vm: ChatViewModel) {
    val caps = vm.reasoningCaps ?: return
    if (!caps.supported && !caps.alwaysOn) return
    Column(Modifier.fillMaxWidth().padding(bottom = 12.dp).animateContentSize(androidx.compose.animation.core.spring(dampingRatio = 0.88f, stiffness = 300f)), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!caps.supported) {
            Text(stringResource(R.string.reasoning_always_on), style = DevxType.Caption, color = DevxColors.TextSecondary, modifier = Modifier.padding(start = 6.dp))
            return
        }
        val current = vm.reasoningLevel
        Text(
            stringResource(R.string.model_reasoning),
            style = DevxType.Section,
            color = DevxColors.TextSecondary,
            modifier = Modifier.padding(start = 6.dp),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            LevelChip(stringResource(R.string.reasoning_auto), selected = current == null) { vm.selectReasoning(null) }
            caps.levels.forEach { level ->
                LevelChip(stringResource(levelLabelRes(level)), selected = current == level) { vm.selectReasoning(level) }
            }
        }
        // The words under the chips change with a soft crossfade, and the sheet gives or takes the room smoothly.
        AnimatedContent(
            targetState = current,
            transitionSpec = { androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(320, delayMillis = 60)) togetherWith androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(200)) },
            label = "level-meaning",
        ) { shown ->
            if (shown != null) Text(stringResource(levelMeaningRes(shown)), style = DevxType.Caption, color = DevxColors.TextSecondary, modifier = Modifier.padding(start = 6.dp))
            else Box(Modifier.size(0.dp))
        }
    }
}

@Composable
private fun LevelChip(text: String, selected: Boolean, onClick: () -> Unit) {
    // The chosen chip fills in and the old one empties with a glide, the word changes colour with it.
    val fill by androidx.compose.animation.animateColorAsState(if (selected) DevxColors.Accent else Color.Transparent, androidx.compose.animation.core.tween(320), label = "fill")
    val line by androidx.compose.animation.animateColorAsState(if (selected) DevxColors.Accent else DevxColors.Outline, androidx.compose.animation.core.tween(320), label = "line")
    val ink by androidx.compose.animation.animateColorAsState(if (selected) DevxColors.OnAccent else DevxColors.TextPrimary, androidx.compose.animation.core.tween(320), label = "ink")
    Text(
        text,
        style = DevxType.Body,
        color = ink,
        modifier = Modifier.pressable(scaleTo = 0.9f, onClick = onClick).clip(CircleShape).background(fill).border(1.dp, line, CircleShape).padding(horizontal = 14.dp, vertical = 7.dp),
    )
}
