package com.apvcode.devx.ui.screens

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.apvcode.devx.R
import com.apvcode.devx.data.Skill
import com.apvcode.devx.data.SkillStore
import com.apvcode.devx.ui.chat.ChatViewModel
import com.apvcode.devx.ui.components.ScreenScaffold
import com.apvcode.devx.ui.components.SectionLabel
import com.apvcode.devx.ui.components.SettingsDivider
import com.apvcode.devx.ui.components.SettingsGroup
import com.apvcode.devx.ui.components.SettingsRow
import com.apvcode.devx.ui.components.pressable
import com.apvcode.devx.ui.theme.DevxColors
import com.apvcode.devx.ui.theme.DevxType
import kotlinx.coroutines.launch

/** The skills the assistant may read: switch each on or off, import your own. */
@Composable
fun SkillsScreen(vm: ChatViewModel, onBack: () -> Unit, onWrite: () -> Unit = {}) {
    val store = vm.skills
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val text = when (val r = store.import(uri)) {
                is SkillStore.Imported.Done -> context.getString(R.string.skills_imported, r.name)
                is SkillStore.Imported.Failed -> context.getString(
                    when (r.reason) {
                        SkillStore.Reason.NoSkill -> R.string.skills_failed
                        SkillStore.Reason.TooBig -> R.string.skills_too_big
                        SkillStore.Reason.Unreadable -> R.string.skills_unreadable
                    },
                )
            }
            Toast.makeText(context, text, Toast.LENGTH_LONG).show()
        }
    }
    val builtIn = store.skills.filter { it.builtin }
    val yours = store.skills.filter { !it.builtin }
    ScreenScaffold(stringResource(R.string.skills_title), onBack) {
        item { Text(stringResource(R.string.skills_hint), style = DevxType.Caption, color = DevxColors.TextSecondary, modifier = Modifier.padding(horizontal = 12.dp)) }
        if (yours.isNotEmpty()) {
            item { SectionLabel(stringResource(R.string.skills_yours)) }
            item {
                SettingsGroup {
                    yours.forEachIndexed { i, skill ->
                        if (i > 0) SettingsDivider()
                        SkillRow(skill, store, removable = true, onRemove = { scope.launch { store.delete(skill) } })
                    }
                }
            }
        }
        if (builtIn.isNotEmpty()) {
            item { SectionLabel(stringResource(R.string.skills_builtin)) }
            item {
                SettingsGroup {
                    builtIn.forEachIndexed { i, skill ->
                        if (i > 0) SettingsDivider()
                        SkillRow(skill, store, removable = false, onRemove = {})
                    }
                }
            }
        }
        item {
            SettingsGroup {
                SettingsRow(
                    title = stringResource(R.string.skills_write),
                    subtitle = stringResource(R.string.skills_write_hint),
                    icon = com.apvcode.devx.ui.components.DevxIcons.Pencil,
                    onClick = onWrite,
                )
                SettingsDivider()
                SettingsRow(
                    title = stringResource(R.string.skills_import),
                    subtitle = stringResource(R.string.skills_import_hint),
                    icon = Icons.Outlined.Add,
                    onClick = { picker.launch(arrayOf("*/*")) },
                )
            }
        }
    }
}

@Composable
private fun SkillRow(skill: Skill, store: SkillStore, removable: Boolean, onRemove: () -> Unit) {
    val on = store.isEnabled(skill)
    SettingsRow(
        title = skill.name,
        subtitle = skill.description.let { if (it.length > 150) it.take(150).trimEnd() + "…" else it },
        onClick = { store.setEnabled(skill, !on) },
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (removable) {
                    Icon(
                        Icons.Outlined.Close,
                        stringResource(R.string.skills_remove),
                        tint = DevxColors.TextSecondary,
                        modifier = Modifier.size(34.dp).pressable(scaleTo = 0.85f, onClick = onRemove).padding(6.dp),
                    )
                }
                Switch(
                    checked = on,
                    onCheckedChange = null,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = DevxColors.Bg,
                        checkedTrackColor = DevxColors.Accent,
                        checkedBorderColor = DevxColors.Accent,
                        uncheckedThumbColor = DevxColors.TextSecondary,
                        uncheckedTrackColor = DevxColors.Card,
                        uncheckedBorderColor = DevxColors.TextDisabled,
                    ),
                )
            }
        },
    )
}

/** A skill written by the person: instructions, and a name and a description that may be left to the assistant. */
@Composable
fun SkillEditScreen(vm: ChatViewModel, onClose: () -> Unit, onDone: () -> Unit) {
    var name by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }
    var description by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }
    var body by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }
    var busy by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    androidx.compose.foundation.layout.Column(
        Modifier
            .fillMaxSize()
            .background(DevxColors.Bg)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding(),
    ) {
        androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
            com.apvcode.devx.ui.components.CircleIconButton(
                icon = Icons.Outlined.Close,
                contentDescription = stringResource(R.string.cd_close),
                onClick = { if (!busy) onClose() },
                modifier = Modifier.align(Alignment.CenterStart),
            )
            Text(stringResource(R.string.skills_new), style = DevxType.Title, color = DevxColors.TextPrimary)
        }
        androidx.compose.foundation.layout.Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Text(stringResource(R.string.skills_write_hint), style = DevxType.Caption, color = DevxColors.TextSecondary, modifier = Modifier.padding(horizontal = 6.dp))
            com.apvcode.devx.ui.components.DevxTextField(
                stringResource(R.string.skills_body), body, { if (it.length <= 30_000) body = it },
                placeholder = stringResource(R.string.skills_body_hint), singleLine = false, minLines = 8,
            )
            com.apvcode.devx.ui.components.DevxTextField(
                stringResource(R.string.skills_name), name, { if (it.length <= 48) name = it.replace("\n", "") },
                placeholder = stringResource(R.string.skills_name_hint),
            )
            com.apvcode.devx.ui.components.DevxTextField(
                stringResource(R.string.skills_desc), description, { if (it.length <= 300) description = it },
                placeholder = stringResource(R.string.skills_desc_hint), singleLine = false, minLines = 2,
            )
        }
        com.apvcode.devx.ui.components.PillButton(
            stringResource(if (busy) R.string.skills_creating else R.string.skills_create),
            enabled = body.isNotBlank() && !busy,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
            onClick = {
                busy = true
                scope.launch {
                    val made = vm.createSkill(name, description, body)
                    busy = false
                    Toast.makeText(context, context.getString(R.string.skills_imported, made), Toast.LENGTH_SHORT).show()
                    onDone()
                }
            },
        )
    }
}
