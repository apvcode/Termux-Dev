package com.apvcode.devx.ui.screens

import com.apvcode.devx.ui.components.pressable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.apvcode.devx.R
import androidx.compose.material.icons.outlined.Person
import com.apvcode.devx.data.AppLanguage
import com.apvcode.devx.data.Prefs
import com.apvcode.devx.data.ThemeMode
import com.apvcode.devx.ui.components.ChoiceDialog
import com.apvcode.devx.ui.components.DevxDialog
import com.apvcode.devx.ui.components.DevxTextField
import com.apvcode.devx.ui.components.PillButton
import com.apvcode.devx.ui.components.ScreenScaffold
import com.apvcode.devx.ui.components.SectionLabel
import com.apvcode.devx.ui.components.SettingsDivider
import com.apvcode.devx.ui.components.SettingsGroup
import com.apvcode.devx.ui.components.SettingsRow
import com.apvcode.devx.ui.components.SettingsSwitchRow
import com.apvcode.devx.ui.theme.DevxColors
import com.apvcode.devx.ui.theme.DevxType

@Composable
internal fun EmptyNote(text: String) {
    Text(
        text,
        style = DevxType.Body,
        color = DevxColors.TextSecondary,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(top = 96.dp),
    )
}

@Composable
fun TasksScreen(onBack: () -> Unit) {
    ScreenScaffold(stringResource(R.string.tasks_title), onBack) {
        item { EmptyNote(stringResource(R.string.tasks_empty)) }
    }
}

@Composable
fun SettingsScreen(vm: com.apvcode.devx.ui.chat.ChatViewModel, onBack: () -> Unit, onProviders: () -> Unit, onNotifications: () -> Unit, onAbout: () -> Unit, onMemory: () -> Unit, onSkills: () -> Unit, onAssistant: () -> Unit, onAppLock: () -> Unit = {}, onPersona: () -> Unit = {}) {
    var dialog by remember { mutableStateOf<String?>(null) }
    // Backup: everything of the person's in one file, and back (keys and tokens stay behind: they belong to this phone).
    val appContext = androidx.compose.ui.platform.LocalContext.current
    val backupScope = androidx.compose.runtime.rememberCoroutineScope()
    var restoreFrom by remember { mutableStateOf<android.net.Uri?>(null) }
    var restoring by remember { mutableStateOf(false) }
    fun say(res: Int) = android.widget.Toast.makeText(appContext, res, android.widget.Toast.LENGTH_LONG).show()
    val makeBackup = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) backupScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val ok = runCatching {
                val version = appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName ?: ""
                appContext.contentResolver.openOutputStream(uri)!!.use { out -> com.apvcode.devx.data.Backup.export(appContext.filesDir, java.io.File(appContext.applicationInfo.dataDir, "shared_prefs"), out, version) }
            }.isSuccess
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { say(if (ok) R.string.backup_done else R.string.backup_failed) }
        }
    }
    val pickBackup = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri -> restoreFrom = uri }
    val activity = androidx.compose.ui.platform.LocalContext.current as? android.app.Activity
    val languageNames = mapOf(
        AppLanguage.System to stringResource(R.string.lang_system),
        AppLanguage.English to stringResource(R.string.lang_en),
        AppLanguage.Russian to stringResource(R.string.lang_ru),
    )
    val themeNames = mapOf(
        ThemeMode.System to stringResource(R.string.theme_system),
        ThemeMode.Black to stringResource(R.string.theme_black),
        ThemeMode.Gray to stringResource(R.string.theme_gray),
        ThemeMode.Light to stringResource(R.string.theme_light),
    )
    restoreFrom?.let { uri ->
        DevxDialog(stringResource(R.string.backup_restore), onDismiss = { if (!restoring) restoreFrom = null }) {
            androidx.compose.foundation.layout.Column(Modifier.padding(horizontal = 24.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(14.dp)) {
                Text(stringResource(R.string.backup_restore_ask), style = com.apvcode.devx.ui.theme.DevxType.Body, color = com.apvcode.devx.ui.theme.DevxColors.TextSecondary)
                PillButton(stringResource(R.string.backup_restore), enabled = !restoring, onClick = {
                    restoring = true
                    backupScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                        val result = runCatching { appContext.contentResolver.openInputStream(uri)!!.use { com.apvcode.devx.data.Backup.restore(appContext.filesDir, java.io.File(appContext.applicationInfo.dataDir, "shared_prefs"), it) } }
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                            restoring = false
                            restoreFrom = null
                            if (result.isSuccess) {
                                say(R.string.backup_restored)
                                // The app starts again so that it reads what was restored.
                                val launch = appContext.packageManager.getLaunchIntentForPackage(appContext.packageName)?.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK)
                                launch?.let { appContext.startActivity(it) }
                                Runtime.getRuntime().exit(0)
                            } else say(R.string.backup_bad)
                        }
                    }
                })
                PillButton(stringResource(R.string.settings_cancel), filled = false, enabled = !restoring, onClick = { restoreFrom = null })
            }
        }
    }
    when (dialog) {
        "language" -> ChoiceDialog(
            stringResource(R.string.settings_language_title),
            AppLanguage.values().map { it to languageNames.getValue(it) },
            Prefs.language,
            onPick = {
                dialog = null
                if (it != Prefs.language) {
                    Prefs.saveLanguage(it)
                    activity?.recreate()
                }
            },
            onDismiss = { dialog = null },
        )
        "voice" -> {
            val app = stringResource(R.string.voice_lang_app)
            val auto = stringResource(R.string.voice_lang_auto)
            ChoiceDialog(
                stringResource(R.string.settings_voice_title),
                listOf("" to app, com.apvcode.devx.data.VoiceInput.AUTO to auto) + com.apvcode.devx.data.VoiceInput.LANGUAGES,
                Prefs.voiceLanguage,
                onPick = {
                    Prefs.saveVoiceLanguage(it)
                    dialog = null
                },
                onDismiss = { dialog = null },
            )
        }
        "theme" -> ChoiceDialog(
            stringResource(R.string.settings_theme_title),
            ThemeMode.values().map { it to themeNames.getValue(it) },
            Prefs.theme,
            onPick = {
                Prefs.saveTheme(it)
                dialog = null
            },
            onDismiss = { dialog = null },
        )
        "name" -> NameDialog(onDismiss = { dialog = null })
    }
    ScreenScaffold(stringResource(R.string.settings_title), onBack) {
        item {
            SettingsGroup {
                SettingsRow(
                    title = stringResource(R.string.settings_name),
                    icon = Icons.Outlined.Person,
                    value = Prefs.nickname.ifBlank { null },
                    showChevron = true,
                    onClick = { dialog = "name" },
                )
            }
        }
        item { SectionLabel(stringResource(R.string.settings_personal)) }
        item {
            SettingsGroup {
                SettingsRow(
                    title = stringResource(R.string.settings_character),
                    icon = androidx.compose.ui.graphics.vector.ImageVector.vectorResource(R.drawable.ic_c_mood_smile),
                    value = com.apvcode.devx.data.Persona.current()?.let { stringResource(it.title) } ?: stringResource(R.string.persona_style_custom),
                    showChevron = true,
                    onClick = onPersona,
                )
                SettingsDivider()
                SettingsRow(
                    title = stringResource(R.string.settings_memory),
                    icon = com.apvcode.devx.ui.components.DevxIcons.Notebook,
                    value = if (Prefs.memory) null else stringResource(R.string.settings_pin_value),
                    showChevron = true,
                    onClick = onMemory,
                )
                SettingsDivider()
                SettingsRow(
                    title = stringResource(R.string.settings_skills),
                    icon = projectIcon("bolt"),
                    value = vm.skills.enabled().size.takeIf { it > 0 }?.toString(),
                    showChevron = true,
                    onClick = onSkills,
                )
            }
        }
        item { SectionLabel(stringResource(R.string.settings_assistant)) }
        item {
            SettingsGroup {
                SettingsRow(
                    title = stringResource(R.string.settings_providers),
                    icon = Icons.Outlined.Cloud,
                    showChevron = true,
                    onClick = onProviders,
                )
                SettingsDivider()
                SettingsRow(
                    title = stringResource(R.string.settings_abilities),
                    icon = com.apvcode.devx.ui.components.DevxIcons.Brain,
                    showChevron = true,
                    onClick = onAssistant,
                )
            }
        }
        item { SectionLabel(stringResource(R.string.settings_general)) }
        item {
            SettingsGroup {
                SettingsRow(
                    title = stringResource(R.string.settings_language),
                    icon = Icons.Outlined.Language,
                    value = languageNames.getValue(Prefs.language),
                    showChevron = true,
                    onClick = { dialog = "language" },
                )
                SettingsDivider()
                SettingsRow(
                    title = stringResource(R.string.settings_appearance),
                    icon = Icons.Outlined.Palette,
                    value = themeNames.getValue(Prefs.theme),
                    showChevron = true,
                    onClick = { dialog = "theme" },
                )
                SettingsDivider()
                SettingsRow(
                    title = stringResource(R.string.settings_voice),
                    icon = Icons.Outlined.Mic,
                    value = com.apvcode.devx.data.VoiceInput.nameOf(Prefs.voiceLanguage, stringResource(R.string.voice_lang_app), stringResource(R.string.voice_lang_auto)),
                    showChevron = true,
                    onClick = { dialog = "voice" },
                )
                SettingsDivider()
                SettingsRow(
                    title = stringResource(R.string.settings_notifications),
                    icon = Icons.Outlined.Notifications,
                    showChevron = true,
                    onClick = onNotifications,
                )
            }
        }
        item { SectionLabel(stringResource(R.string.settings_data)) }
        item {
            SettingsGroup {
                SettingsRow(
                    title = stringResource(R.string.settings_pin),
                    icon = Icons.Outlined.Lock,
                    value = stringResource(if (com.apvcode.devx.data.AppLock.enabled) R.string.settings_pin_on else R.string.settings_pin_value),
                    showChevron = true,
                    onClick = onAppLock,
                )
                SettingsDivider()
                SettingsRow(
                    title = stringResource(R.string.backup_create),
                    subtitle = stringResource(R.string.backup_create_hint),
                    icon = com.apvcode.devx.ui.components.DevxIcons.FilePlus,
                    onClick = { makeBackup.launch("devx-backup-" + java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date()) + ".zip") },
                )
                SettingsDivider()
                SettingsRow(
                    title = stringResource(R.string.backup_restore),
                    subtitle = stringResource(R.string.backup_restore_hint),
                    icon = com.apvcode.devx.ui.components.DevxIcons.Undo,
                    onClick = { pickBackup.launch(arrayOf("application/zip", "application/octet-stream")) },
                )
            }
        }
        item {
            SettingsGroup {
                SettingsRow(
                    title = stringResource(R.string.settings_about),
                    icon = Icons.Outlined.Info,
                    showChevron = true,
                    onClick = onAbout,
                )
            }
        }
    }
}

/** What the assistant may do and how it looks while it works: search, files, the terminal, the glow, photos. */
@Composable
fun AssistantScreen(vm: com.apvcode.devx.ui.chat.ChatViewModel, onBack: () -> Unit) {
    ScreenScaffold(stringResource(R.string.settings_assistant), onBack) {
        item {
            SettingsGroup {
                SettingsSwitchRow(
                    title = stringResource(R.string.settings_web_search),
                    subtitle = stringResource(R.string.settings_web_search_hint),
                    checked = Prefs.webSearch,
                    onCheckedChange = { Prefs.saveWebSearch(it) },
                )
                SettingsDivider()
                SettingsSwitchRow(
                    title = stringResource(R.string.settings_work),
                    subtitle = stringResource(R.string.settings_work_hint),
                    checked = Prefs.workTools,
                    onCheckedChange = { Prefs.saveWorkTools(it) },
                )
                SettingsDivider()
                SettingsSwitchRow(
                    title = stringResource(R.string.settings_terminal),
                    subtitle = stringResource(R.string.settings_terminal_hint),
                    checked = Prefs.terminal,
                    onCheckedChange = { Prefs.saveTerminal(it) },
                )
                SettingsDivider()
                SettingsSwitchRow(
                    title = stringResource(R.string.settings_phone),
                    subtitle = stringResource(R.string.settings_phone_hint),
                    checked = Prefs.phoneTools,
                    onCheckedChange = { Prefs.savePhoneTools(it) },
                )
                SettingsDivider()
                SettingsSwitchRow(
                    title = stringResource(R.string.settings_glow),
                    subtitle = stringResource(R.string.settings_glow_hint),
                    checked = Prefs.thinkingGlow,
                    onCheckedChange = { Prefs.saveThinkingGlow(it) },
                )
                SettingsDivider()
                SettingsRow(
                    title = stringResource(R.string.settings_max_photos),
                    subtitle = stringResource(R.string.settings_max_photos_hint),
                    trailing = {
                        Stepper(vm.maxPhotos, onChange = vm::changeMaxPhotos, min = 1, max = 50)
                    },
                )
            }
        }
    }
}

@Composable
fun NotificationsScreen(onBack: () -> Unit) {
    var approvals by remember { mutableStateOf(true) }
    var questions by remember { mutableStateOf(true) }
    var done by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf(true) }
    ScreenScaffold(stringResource(R.string.settings_notifications), onBack) {
        item {
            SettingsGroup {
                SettingsSwitchRow(stringResource(R.string.notif_approvals), stringResource(R.string.notif_approvals_hint), approvals) { approvals = it }
                SettingsDivider()
                SettingsSwitchRow(stringResource(R.string.notif_questions), stringResource(R.string.notif_questions_hint), questions) { questions = it }
                SettingsDivider()
                SettingsSwitchRow(stringResource(R.string.notif_done), stringResource(R.string.notif_done_hint), done) { done = it }
                SettingsDivider()
                SettingsSwitchRow(stringResource(R.string.notif_failed), stringResource(R.string.notif_failed_hint), failed) { failed = it }
            }
        }
    }
}

@Composable
fun AboutScreen(onBack: () -> Unit) {
    ScreenScaffold(stringResource(R.string.settings_about), onBack) {
        item {
            SettingsGroup {
                SettingsRow(
                    title = stringResource(R.string.about_version),
                    icon = com.apvcode.devx.ui.components.DevxIcons.Eye,
                    value = com.apvcode.devx.BuildConfig.VERSION_NAME,
                )
                SettingsDivider()
                val context = androidx.compose.ui.platform.LocalContext.current
                SettingsRow(
                    title = stringResource(R.string.about_github),
                    icon = com.apvcode.devx.ui.components.DevxIcons.BrandGithub,
                    value = "apvcode",
                    showChevron = true,
                    onClick = {
                        runCatching {
                            context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://github.com/apvcode")).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                        }
                    },
                )
            }
        }
    }
}

/** A minus, the number, a plus. */
@Composable
private fun Stepper(value: Int, onChange: (Int) -> Unit, min: Int, max: Int) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        StepButton("–", enabled = value > min) { onChange(value - 1) }
        Text(
            "$value",
            style = DevxType.BodyStrong,
            color = DevxColors.TextPrimary,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(34.dp),
        )
        StepButton("+", enabled = value < max) { onChange(value + 1) }
    }
}

@Composable
private fun StepButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(36.dp)
            .pressable(enabled = enabled, onClick = onClick)
            .clip(CircleShape)
            .background(DevxColors.Bg),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = DevxType.Title, color = if (enabled) DevxColors.TextPrimary else DevxColors.TextDisabled)
    }
}

@Composable
private fun NameDialog(onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(Prefs.nickname) }
    DevxDialog(stringResource(R.string.settings_name), onDismiss) {
        androidx.compose.foundation.layout.Column(Modifier.padding(horizontal = 24.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(14.dp)) {
            DevxTextField(
                stringResource(R.string.settings_name_hint),
                text,
                { if (it.length <= 32) text = it.replace("\n", "") },
            )
            PillButton(
                stringResource(R.string.settings_name_save),
                enabled = text.isNotBlank(),
                onClick = {
                    Prefs.saveNickname(text)
                    onDismiss()
                },
            )
        }
    }
}
