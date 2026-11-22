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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.verticalScroll
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
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.apvcode.devx.assist.AssistantStatus
import com.apvcode.devx.data.AppLock
import com.apvcode.devx.data.AutoBackup
import com.apvcode.devx.data.ErrorLog
import com.apvcode.devx.data.MapTools
import com.apvcode.devx.data.Persona
import com.apvcode.devx.data.SearchApi
import com.apvcode.devx.data.Secrets
import com.apvcode.devx.data.VoiceInput
import com.apvcode.devx.ui.chat.ChatViewModel
import com.apvcode.devx.ui.components.DevxIcons
import java.io.File
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers

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
fun SettingsScreen(vm: ChatViewModel, onBack: () -> Unit, onProviders: () -> Unit, onNotifications: () -> Unit, onAbout: () -> Unit, onMemory: () -> Unit, onSkills: () -> Unit, onAssistant: () -> Unit, onVoiceAssistant: () -> Unit = {}, onPersona: () -> Unit = {}, onData: () -> Unit = {}, onAppearance: () -> Unit = {}) {
    var dialog by remember { mutableStateOf<String?>(null) }
    val activity = LocalContext.current as? Activity
    val languageNames = mapOf(
        AppLanguage.System to stringResource(R.string.lang_system),
        AppLanguage.English to stringResource(R.string.lang_en),
        AppLanguage.Russian to stringResource(R.string.lang_ru),
        AppLanguage.German to stringResource(R.string.lang_de),
        AppLanguage.Spanish to stringResource(R.string.lang_es),
        AppLanguage.French to stringResource(R.string.lang_fr),
        AppLanguage.Japanese to stringResource(R.string.lang_ja),
        AppLanguage.Portuguese to stringResource(R.string.lang_pt),
        AppLanguage.Ukrainian to stringResource(R.string.lang_uk),
        AppLanguage.Chinese to stringResource(R.string.lang_zh),
    )
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
                listOf("" to app, VoiceInput.AUTO to auto) + VoiceInput.LANGUAGES,
                Prefs.voiceLanguage,
                onPick = {
                    Prefs.saveVoiceLanguage(it)
                    dialog = null
                },
                onDismiss = { dialog = null },
            )
        }
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
                    icon = ImageVector.vectorResource(R.drawable.ic_c_mood_smile),
                    value = Persona.current()?.let { stringResource(it.title) } ?: stringResource(R.string.persona_style_custom),
                    showChevron = true,
                    onClick = onPersona,
                )
                SettingsDivider()
                SettingsRow(
                    title = stringResource(R.string.settings_memory),
                    icon = DevxIcons.Notebook,
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
                    icon = DevxIcons.Brain,
                    showChevron = true,
                    onClick = onAssistant,
                )
                SettingsDivider()
                var voiceTick by remember { mutableStateOf(0) }
                LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { voiceTick++ }
                val voiceContext = LocalContext.current
                @Suppress("UNUSED_EXPRESSION") voiceTick
                SettingsRow(
                    title = stringResource(R.string.settings_voice_assistant),
                    icon = Icons.Outlined.Mic,
                    value = stringResource(if (AssistantStatus.isDefault(voiceContext)) R.string.settings_voice_assistant_on else R.string.settings_voice_assistant_off),
                    showChevron = true,
                    onClick = onVoiceAssistant,
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
                    value = themeLabel(Prefs.theme),
                    showChevron = true,
                    onClick = onAppearance,
                )
                SettingsDivider()
                SettingsRow(
                    title = stringResource(R.string.settings_voice),
                    icon = Icons.Outlined.Mic,
                    value = VoiceInput.nameOf(Prefs.voiceLanguage, stringResource(R.string.voice_lang_app), stringResource(R.string.voice_lang_auto)),
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
                    title = stringResource(R.string.data_security),
                    icon = Icons.Outlined.Lock,
                    value = if (AppLock.enabled) stringResource(R.string.settings_pin_on) else null,
                    showChevron = true,
                    onClick = onData,
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
fun AssistantScreen(vm: ChatViewModel, onBack: () -> Unit) {
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
                    title = stringResource(R.string.settings_shop),
                    subtitle = stringResource(R.string.settings_shop_hint),
                    checked = Prefs.shopTools,
                    onCheckedChange = { Prefs.saveShopTools(it) },
                )
                SettingsDivider()
                SettingsSwitchRow(
                    title = stringResource(R.string.settings_glow),
                    subtitle = stringResource(R.string.settings_glow_hint),
                    checked = Prefs.thinkingGlow,
                    onCheckedChange = { Prefs.saveThinkingGlow(it) },
                )
                SettingsDivider()
                SettingsSwitchRow(
                    title = stringResource(R.string.settings_twogis),
                    subtitle = stringResource(R.string.settings_twogis_hint),
                    checked = Prefs.twoGis,
                    onCheckedChange = { Prefs.saveTwoGis(it) },
                )
                SettingsDivider()
                var twoGisKeyDialog by remember { mutableStateOf(false) }
                val twoGisContext = LocalContext.current
                var twoGisHasKey by remember { mutableStateOf(!Secrets(twoGisContext).get(MapTools.KEY_ID).isNullOrBlank()) }
                SettingsRow(
                    title = stringResource(R.string.settings_twogis_key),
                    value = stringResource(if (twoGisHasKey) R.string.settings_twogis_key_set else R.string.settings_twogis_key_none),
                    showChevron = true,
                    onClick = { twoGisKeyDialog = true },
                )
                if (twoGisKeyDialog) {
                    var text by remember { mutableStateOf("") }
                    DevxDialog(stringResource(R.string.settings_twogis_key), onDismiss = { twoGisKeyDialog = false }) {
                        Column(Modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            DevxTextField(stringResource(R.string.settings_twogis_key_hint), text, { text = it }, password = true)
                            PillButton(stringResource(R.string.task_save), enabled = text.isNotBlank(), onClick = {
                                Secrets(twoGisContext).put(MapTools.KEY_ID, text.trim())
                                twoGisHasKey = true
                                twoGisKeyDialog = false
                            })
                            if (twoGisHasKey) PillButton(stringResource(R.string.menu_delete), filled = false, onClick = {
                                Secrets(twoGisContext).remove(MapTools.KEY_ID)
                                twoGisHasKey = false
                                twoGisKeyDialog = false
                            })
                        }
                    }
                }
                SettingsDivider()
                var searchKeyDialog by remember { mutableStateOf(false) }
                var searchHasKey by remember { mutableStateOf(!Secrets(twoGisContext).get(SearchApi.KEY_ID).isNullOrBlank()) }
                SettingsRow(
                    title = stringResource(R.string.settings_search_key),
                    subtitle = stringResource(R.string.settings_search_key_hint),
                    value = stringResource(if (searchHasKey) R.string.settings_twogis_key_set else R.string.settings_twogis_key_none),
                    showChevron = true,
                    onClick = { searchKeyDialog = true },
                )
                if (searchKeyDialog) {
                    var text by remember { mutableStateOf("") }
                    val valid = SearchApi.kindOf(text.trim()) != null
                    DevxDialog(stringResource(R.string.settings_search_key), onDismiss = { searchKeyDialog = false }) {
                        Column(Modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            Text(stringResource(R.string.settings_search_key_about), style = DevxType.Caption, color = DevxColors.TextSecondary)
                            DevxTextField("tvly-… / BSA…", text, { text = it }, password = true)
                            if (text.isNotBlank() && !valid) Text(stringResource(R.string.settings_search_key_bad), style = DevxType.Caption, color = DevxColors.Warning)
                            PillButton(stringResource(R.string.task_save), enabled = valid, onClick = {
                                Secrets(twoGisContext).put(SearchApi.KEY_ID, text.trim())
                                searchHasKey = true
                                searchKeyDialog = false
                            })
                            if (searchHasKey) PillButton(stringResource(R.string.menu_delete), filled = false, onClick = {
                                Secrets(twoGisContext).remove(SearchApi.KEY_ID)
                                searchHasKey = false
                                searchKeyDialog = false
                            })
                        }
                    }
                }
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
    var diagnostics by remember { mutableStateOf(false) }
    if (diagnostics) DiagnosticsDialog { diagnostics = false }
    ScreenScaffold(stringResource(R.string.settings_about), onBack) {
        item {
            SettingsGroup {
                SettingsRow(
                    title = stringResource(R.string.about_version),
                    icon = DevxIcons.Eye,
                    value = com.apvcode.devx.BuildConfig.VERSION_NAME,
                )
                SettingsDivider()
                val context = LocalContext.current
                SettingsRow(
                    title = stringResource(R.string.about_github),
                    icon = DevxIcons.BrandGithub,
                    value = "termux-dev",
                    showChevron = true,
                    onClick = {
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/apvcode/Termux-Dev")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }
                    },
                )
                SettingsDivider()
                SettingsRow(
                    title = stringResource(R.string.about_diagnostics),
                    icon = DevxIcons.FileText,
                    value = ErrorLog.count.takeIf { it > 0 }?.toString(),
                    showChevron = true,
                    onClick = { diagnostics = true },
                )
            }
        }
    }
}

/** What went wrong inside the app and was recovered from (see [com.apvcode.devx.data.ErrorLog]): to read, copy to send, or clear. */
@Composable
private fun DiagnosticsDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var lines by remember { mutableStateOf(ErrorLog.recent()) }
    DevxDialog(stringResource(R.string.about_diagnostics), onDismiss) {
        Column(Modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (lines.isEmpty()) Text(stringResource(R.string.about_diagnostics_none), style = DevxType.Body, color = DevxColors.TextSecondary)
            else Column(Modifier.heightIn(max = 280.dp).verticalScroll(androidx.compose.foundation.rememberScrollState())) {
                lines.forEach { Text(it, style = DevxType.Caption, color = DevxColors.TextSecondary, modifier = Modifier.padding(bottom = 6.dp)) }
            }
            if (lines.isNotEmpty()) {
                PillButton(stringResource(R.string.msg_copy), onClick = {
                    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                        .setPrimaryClip(ClipData.newPlainText("devx", lines.joinToString("\n")))
                })
                PillButton(stringResource(R.string.about_diagnostics_clear), filled = false, onClick = { ErrorLog.clear(); lines = emptyList() })
            }
            PillButton(stringResource(R.string.cd_close), filled = false, onClick = onDismiss)
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
        Column(Modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
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
