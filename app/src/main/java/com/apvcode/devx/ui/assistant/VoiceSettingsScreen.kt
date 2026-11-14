package com.apvcode.devx.ui.assistant

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.apvcode.devx.R
import com.apvcode.devx.assist.AssistantActivity
import com.apvcode.devx.assist.AssistantStatus
import com.apvcode.devx.data.Prefs
import com.apvcode.devx.ui.components.ScreenScaffold
import com.apvcode.devx.ui.components.SectionLabel
import com.apvcode.devx.ui.components.SettingsDivider
import com.apvcode.devx.ui.components.SettingsGroup
import com.apvcode.devx.ui.components.SettingsRow
import com.apvcode.devx.ui.components.SettingsSwitchRow
import com.apvcode.devx.ui.theme.DevxColors
import com.apvcode.devx.ui.theme.DevxType

/**
 * Settings of the voice assistant: whether the phone really calls Devx (checked again every time the page is in view),
 * whether the microphone, speech recognition and the voice are there, how it behaves, how to call it and what it can do.
 */
@Composable
fun VoiceSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { tick++ }
    @Suppress("UNUSED_EXPRESSION") tick
    val isDefault = AssistantStatus.isDefault(context)
    val mic = AssistantStatus.micAllowed(context)
    val recognizer = AssistantStatus.recognizerThere(context)
    val speech = AssistantStatus.speechEngineThere(context)
    val phone = Prefs.phoneTools

    val chooseAssistant = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { tick++ }
    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        tick++
        // Refused for good: the system shows no question any more, only the app's own page of settings.
        if (!granted) runCatching {
            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
        }
    }

    fun open(intent: Intent) {
        runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    ScreenScaffold(stringResource(R.string.va_title), onBack) {
        item { SectionLabel(stringResource(R.string.va_section_status)) }
        item {
            SettingsGroup {
                // Every row can be tapped: it leads to where the thing is set.
                StatusRow(
                    title = stringResource(R.string.va_default),
                    hint = if (isDefault) null else stringResource(R.string.va_default_no),
                    ok = isDefault,
                    value = stringResource(if (isDefault) R.string.va_yes else R.string.va_no),
                    // The phone's own page "Digital assistant app" (the system's quick question is silent on some phones).
                    onClick = {
                        runCatching { chooseAssistant.launch(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)) }.onFailure {
                            runCatching { chooseAssistant.launch(AssistantStatus.chooseIntent(context)) }
                        }
                    },
                )
                SettingsDivider()
                StatusRow(
                    title = stringResource(R.string.va_mic),
                    hint = if (mic) null else stringResource(R.string.va_mic_no),
                    ok = mic,
                    value = stringResource(if (mic) R.string.va_allowed else R.string.va_not_allowed),
                    onClick = {
                        if (mic) open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
                        else askMic.launch(Manifest.permission.RECORD_AUDIO)
                    },
                )
                SettingsDivider()
                StatusRow(
                    title = stringResource(R.string.va_recognizer),
                    hint = if (recognizer) null else stringResource(R.string.va_recognizer_no),
                    ok = recognizer,
                    value = stringResource(if (recognizer) R.string.va_found else R.string.va_missing),
                    onClick = { open(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)) },
                )
                SettingsDivider()
                StatusRow(
                    title = stringResource(R.string.va_tts),
                    hint = if (speech) null else stringResource(R.string.va_tts_no),
                    ok = speech,
                    value = stringResource(if (speech) R.string.va_found else R.string.va_missing),
                    onClick = { open(Intent("com.android.settings.TTS_SETTINGS")) },
                )
                SettingsDivider()
                SettingsSwitchRow(
                    title = stringResource(R.string.va_phone),
                    subtitle = if (phone) null else stringResource(R.string.va_phone_no),
                    checked = phone,
                    onCheckedChange = { Prefs.savePhoneTools(it) },
                )
            }
        }
        item {
            SettingsGroup {
                SettingsRow(
                    title = stringResource(R.string.va_try),
                    showChevron = true,
                    onClick = { AssistantActivity.open(context) },
                )
            }
        }
        item { SectionLabel(stringResource(R.string.va_section_behaviour)) }
        item {
            SettingsGroup {
                SettingsSwitchRow(
                    title = stringResource(R.string.va_speak),
                    subtitle = stringResource(R.string.va_speak_hint),
                    checked = !Prefs.assistantMuted,
                    onCheckedChange = { Prefs.saveAssistantMuted(!it) },
                )
                SettingsDivider()
                SettingsSwitchRow(
                    title = stringResource(R.string.settings_voice_hands_free),
                    subtitle = stringResource(R.string.settings_voice_hands_free_hint),
                    checked = Prefs.voiceHandsFree,
                    onCheckedChange = { Prefs.saveVoiceHandsFree(it) },
                )
            }
        }
        item { SectionLabel(stringResource(R.string.va_section_call)) }
        item {
            SettingsGroup {
                SettingsRow(title = stringResource(R.string.va_call_power), subtitle = stringResource(R.string.va_call_power_hint))
                SettingsDivider()
                SettingsRow(title = stringResource(R.string.va_call_tile), subtitle = stringResource(R.string.va_call_tile_hint))
                SettingsDivider()
                SettingsRow(title = stringResource(R.string.va_call_widget), subtitle = stringResource(R.string.va_call_widget_hint))
            }
        }
        item { SectionLabel(stringResource(R.string.va_section_can)) }
        item {
            SettingsGroup {
                SettingsRow(title = stringResource(R.string.va_can_clock), subtitle = stringResource(R.string.va_can_clock_hint))
                SettingsDivider()
                SettingsRow(title = stringResource(R.string.va_can_apps), subtitle = stringResource(R.string.va_can_apps_hint))
                SettingsDivider()
                SettingsRow(title = stringResource(R.string.va_can_people), subtitle = stringResource(R.string.va_can_people_hint))
                SettingsDivider()
                SettingsRow(title = stringResource(R.string.va_can_calendar), subtitle = stringResource(R.string.va_can_calendar_hint))
                SettingsDivider()
                SettingsRow(title = stringResource(R.string.va_can_media), subtitle = stringResource(R.string.va_can_media_hint))
                SettingsDivider()
                SettingsRow(title = stringResource(R.string.va_can_maps), subtitle = stringResource(R.string.va_can_maps_hint))
                SettingsDivider()
                SettingsRow(title = stringResource(R.string.va_can_chat), subtitle = stringResource(R.string.va_can_chat_hint))
            }
        }
        item { Box(Modifier.padding(bottom = 24.dp)) {} }
    }
}

/** One thing that must be in place: what it is now (in plain words, in a warning colour when it is not right), and a tap leads to where it is set. */
@Composable
private fun StatusRow(title: String, hint: String?, ok: Boolean, value: String, onClick: () -> Unit) {
    SettingsRow(
        title = title,
        subtitle = hint,
        showChevron = true,
        onClick = onClick,
        trailing = { Text(value, style = DevxType.Body, color = if (ok) DevxColors.TextSecondary else DevxColors.Warning) },
    )
}
