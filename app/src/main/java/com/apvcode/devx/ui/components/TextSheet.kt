package com.apvcode.devx.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apvcode.devx.R
import com.apvcode.devx.ui.theme.DevxColors
import com.apvcode.devx.ui.theme.DevxType

/**
 * Writing a longer text (a note of the knowledge base, the instructions): a tall sheet from below with a roomy page to write on,
 * the keyboard up at once, how much is written, and Save at the top where the thumb is. Replaces the small dialog with one field.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TextEditorSheet(
    title: String,
    hint: String,
    initial: String,
    maxChars: Int,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
    onDelete: (() -> Unit)? = null,
    explanation: String? = null,
) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var text by remember { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { kotlinx.coroutines.delay(300); runCatching { focus.requestFocus() } }
    val ready = text.isNotBlank() && text != initial
    val saveColor by animateColorAsState(if (ready) DevxColors.Accent else DevxColors.Card, tween(220), label = "save")
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = DevxColors.Drawer, contentColor = DevxColors.TextPrimary, scrimColor = Color.Black.copy(alpha = 0.6f)) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.92f).imePadding().navigationBarsPadding().padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircleIconButton(Icons.Outlined.Close, stringResource(R.string.cd_close), onDismiss)
                Text(title, style = DevxType.BodyStrong, color = DevxColors.TextPrimary, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
                Text(
                    stringResource(R.string.memory_save), style = DevxType.BodyStrong,
                    color = if (ready) DevxColors.OnAccent else DevxColors.TextDisabled,
                    modifier = Modifier.pressable(enabled = ready, scaleTo = 0.94f) { onSave(text.trim()); onDismiss() }.clip(CircleShape).background(saveColor).padding(horizontal = 18.dp, vertical = 11.dp),
                )
            }
            if (explanation != null) Text(explanation, style = DevxType.Caption, color = DevxColors.TextSecondary, modifier = Modifier.padding(horizontal = 4.dp))
            Box(Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(26.dp)).background(DevxColors.Card)) {
                BasicTextField(
                    value = text,
                    onValueChange = { if (it.length <= maxChars) text = it },
                    textStyle = DevxType.Body.copy(fontSize = 17.sp, lineHeight = 26.sp, color = DevxColors.TextPrimary),
                    cursorBrush = SolidColor(DevxColors.Accent),
                    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp).focusRequester(focus),
                    decorationBox = { inner ->
                        Box {
                            if (text.isEmpty()) Text(hint, style = DevxType.Body.copy(fontSize = 17.sp, lineHeight = 26.sp), color = DevxColors.TextDisabled)
                            inner()
                        }
                    },
                )
            }
            Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${text.length} / $maxChars", style = DevxType.Caption, color = DevxColors.TextDisabled, modifier = Modifier.weight(1f).padding(start = 6.dp))
                if (onDelete != null) Text(
                    stringResource(R.string.menu_delete), style = DevxType.Caption, color = DevxColors.Danger,
                    modifier = Modifier.pressable(scaleTo = 0.94f) { onDelete(); onDismiss() }.clip(CircleShape).padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
        }
    }
}
