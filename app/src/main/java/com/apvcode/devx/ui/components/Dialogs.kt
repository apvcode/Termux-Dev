package com.apvcode.devx.ui.components

import com.apvcode.devx.ui.components.pressable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.apvcode.devx.ui.theme.DevxColors
import com.apvcode.devx.ui.theme.DevxType

/** A small centred sheet on a dimmed screen, in the app's own look. */
@Composable
fun DevxDialog(title: String, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(28.dp))
                .background(DevxColors.Drawer)
                .padding(vertical = 20.dp),
        ) {
            Text(title, style = DevxType.Title, color = DevxColors.TextPrimary, modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp))
            Column(Modifier.padding(top = 10.dp)) { content() }
        }
    }
}

/** One option per row, the current one ticked. */
@Composable
fun <T> ChoiceDialog(title: String, options: List<Pair<T, String>>, selected: T, onPick: (T) -> Unit, onDismiss: () -> Unit) {
    DevxDialog(title, onDismiss) {
        options.forEach { (value, label) ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp)
                    .pressable(scaleTo = 0.97f, haptic = false) { onPick(value) }
                    .padding(horizontal = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(label, style = DevxType.Body, color = DevxColors.TextPrimary, modifier = Modifier.weight(1f))
                if (value == selected) Icon(DevxIcons.Check, null, tint = DevxColors.TextPrimary, modifier = Modifier.size(22.dp))
            }
        }
    }
}
