package com.apvcode.devx.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.apvcode.devx.R
import com.apvcode.devx.data.ProjectInfo
import com.apvcode.devx.ui.screens.ProjectBadge
import com.apvcode.devx.ui.theme.DevxColors
import com.apvcode.devx.ui.theme.DevxType

/**
 * What the Share menu of another app opens when the person has notebooks: where the shared thing goes. A new chat (as always), or
 * one of the notebooks, as a source: a link is read and kept, a text is kept as a note, a file is added as it is.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareTargetSheet(
    text: String,
    files: Int,
    notebooks: List<Pair<String, ProjectInfo>>,
    onNewChat: () -> Unit,
    onNotebook: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = DevxColors.Drawer, contentColor = DevxColors.TextPrimary, scrimColor = Color.Black.copy(alpha = 0.6f)) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 18.dp).padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.share_to_title), style = DevxType.Title, color = DevxColors.TextPrimary, modifier = Modifier.padding(horizontal = 4.dp))
            // What is being shared, in a line or two.
            val what = text.replace("\n", " ").trim().take(140)
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(DevxColors.Card).padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (what.isNotEmpty()) Text(what, style = DevxType.Body, color = DevxColors.TextPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (files > 0) Text(pluralStringResource(R.plurals.share_files, files, files), style = DevxType.Caption, color = DevxColors.TextSecondary)
            }
            Row(
                Modifier.fillMaxWidth().pressable(scaleTo = 0.98f, onClick = onNewChat).clip(RoundedCornerShape(20.dp)).background(DevxColors.Accent.copy(alpha = 0.14f)).padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(Modifier.size(42.dp).clip(CircleShape).background(DevxColors.Accent.copy(alpha = 0.2f)), contentAlignment = Alignment.Center) {
                    Icon(DevxIcons.ChatPlus, null, tint = DevxColors.Accent, modifier = Modifier.size(22.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.share_new_chat), style = DevxType.BodyStrong, color = DevxColors.TextPrimary)
                    Text(stringResource(R.string.share_new_chat_hint), style = DevxType.Caption, color = DevxColors.TextSecondary)
                }
            }
            Text(stringResource(R.string.share_add_to_notebook), style = DevxType.Caption, color = DevxColors.TextSecondary, modifier = Modifier.padding(start = 4.dp, top = 4.dp))
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                notebooks.forEach { (name, info) ->
                    Row(
                        Modifier.fillMaxWidth().pressable(scaleTo = 0.98f) { onNotebook(name) }.clip(RoundedCornerShape(20.dp)).padding(horizontal = 6.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        ProjectBadge(info.icon, info.color, 44.dp)
                        Column(Modifier.weight(1f)) {
                            Text(name, style = DevxType.BodyStrong, color = DevxColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            val sub = info.goal.ifBlank { info.description }.replace("\n", " ").trim()
                            if (sub.isNotEmpty()) Text(sub, style = DevxType.Caption, color = DevxColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}
