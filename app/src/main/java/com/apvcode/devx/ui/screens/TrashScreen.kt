package com.apvcode.devx.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Icon
import com.apvcode.devx.R
import com.apvcode.devx.data.TrashItem
import com.apvcode.devx.ui.chat.ChatViewModel
import com.apvcode.devx.ui.components.DevxDialog
import com.apvcode.devx.ui.components.DevxIcons
import com.apvcode.devx.ui.components.PillButton
import com.apvcode.devx.ui.components.ScreenScaffold
import com.apvcode.devx.ui.components.SettingsGroup
import com.apvcode.devx.ui.components.pressable
import com.apvcode.devx.ui.theme.DevxColors
import com.apvcode.devx.ui.theme.DevxType
import kotlinx.coroutines.delay
import android.widget.Toast
import androidx.compose.ui.text.style.TextAlign
import com.apvcode.devx.ui.components.SettingsDivider

/** Settings, Trash: what was deleted lately, to put back or to remove for good. */
@Composable
fun TrashScreen(vm: ChatViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    var confirmAll by remember { mutableStateOf(false) }
    var confirmOne by remember { mutableStateOf<TrashItem?>(null) }
    val now = remember { System.currentTimeMillis() }
    if (confirmAll) ConfirmForever(stringResource(R.string.trash_empty_all), stringResource(R.string.trash_empty_ask), onDismiss = { confirmAll = false }) { vm.emptyTrash() }
    confirmOne?.let { item -> ConfirmForever(item.title, stringResource(R.string.trash_empty_ask), onDismiss = { confirmOne = null }) { vm.purgeTrash(item.id) } }
    ScreenScaffold(stringResource(R.string.trash_title), onBack) {
        item { Text(stringResource(R.string.trash_hint), style = DevxType.Caption, color = DevxColors.TextSecondary, modifier = Modifier.padding(horizontal = 12.dp)) }
        if (vm.trashItems.isEmpty()) {
            item { Text(stringResource(R.string.trash_empty), style = DevxType.Body, color = DevxColors.TextSecondary, modifier = Modifier.fillMaxWidth().padding(top = 48.dp), textAlign = TextAlign.Center) }
        } else {
            item {
                SettingsGroup {
                    vm.trashItems.toList().forEachIndexed { i, entry ->
                        if (i > 0) SettingsDivider()
                        TrashRow(entry, now,
                            onRestore = { if (vm.restoreFromTrash(entry.id)) Toast.makeText(context, R.string.trash_restored, Toast.LENGTH_SHORT).show() },
                            onDelete = { confirmOne = entry })
                    }
                }
            }
            item { PillButton(stringResource(R.string.trash_empty_all), filled = false, onClick = { confirmAll = true }) }
        }
    }
}

@Composable
private fun TrashRow(item: TrashItem, now: Long, onRestore: () -> Unit, onDelete: () -> Unit) {
    val days = item.daysLeft(now)
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Icon(if (item.isProject) DevxIcons.FolderOff else DevxIcons.ChatOff, null, tint = DevxColors.TextSecondary, modifier = Modifier.size(24.dp))
            Column(Modifier.weight(1f)) {
                Text(item.title.ifBlank { "…" }, style = DevxType.Body, color = DevxColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    if (item.isProject) stringResource(R.string.trash_meta_project, days, item.chats) else stringResource(R.string.trash_meta_chat, days),
                    style = DevxType.Caption, color = DevxColors.TextSecondary,
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.weight(1f)) { PillButton(stringResource(R.string.trash_restore), onClick = onRestore) }
            Box(Modifier.weight(1f)) { PillButton(stringResource(R.string.trash_delete_forever), filled = false, onClick = onDelete) }
        }
    }
}

@Composable
private fun ConfirmForever(title: String, text: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    DevxDialog(title, onDismiss) {
        Column(Modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(text, style = DevxType.Body, color = DevxColors.TextSecondary)
            PillButton(stringResource(R.string.trash_delete_forever), onClick = { onConfirm(); onDismiss() })
            PillButton(stringResource(R.string.dialog_cancel), filled = false, onClick = onDismiss)
        }
    }
}

/** "Moved to trash: …  Undo" for a few seconds after something is deleted. */
@Composable
fun UndoBar(vm: ChatViewModel, modifier: Modifier = Modifier) {
    val item = vm.lastTrashed
    LaunchedEffect(item?.id) {
        if (item != null) { delay(6_000); if (vm.lastTrashed?.id == item.id) vm.lastTrashed = null }
    }
    var shown by remember { mutableStateOf<TrashItem?>(null) }
    if (item != null) shown = item
    AnimatedVisibility(
        visible = item != null,
        enter = fadeIn() + slideInVertically { it / 2 },
        exit = fadeOut() + slideOutVertically { it / 2 },
        modifier = modifier,
    ) {
        val shape = RoundedCornerShape(24.dp)
        Row(
            Modifier
                .padding(horizontal = 16.dp)
                .navigationBarsPadding()
                .fillMaxWidth()
                .clip(shape)
                .background(DevxColors.Card)
                .border(1.dp, DevxColors.Outline, shape)
                .padding(start = 20.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.trash_moved, shown?.title?.ifBlank { "…" }.orEmpty()), style = DevxType.Body, color = DevxColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Box(Modifier.pressable(scaleTo = 0.95f) { shown?.let { vm.restoreFromTrash(it.id) } }.padding(horizontal = 14.dp, vertical = 16.dp)) {
                Text(stringResource(R.string.trash_undo), style = DevxType.Body, color = DevxColors.Accent)
            }
        }
    }
}
