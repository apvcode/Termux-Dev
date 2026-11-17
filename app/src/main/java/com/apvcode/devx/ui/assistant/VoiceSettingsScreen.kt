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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.apvcode.devx.ui.components.pressable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.apvcode.devx.R
import com.apvcode.devx.assist.AssistantActivity
import com.apvcode.devx.assist.AssistantStatus
import com.apvcode.devx.data.CloudVoice
import com.apvcode.devx.data.Prefs
import com.apvcode.devx.ui.components.ScreenScaffold
import com.apvcode.devx.ui.components.SectionLabel
import com.apvcode.devx.ui.components.SettingsDivider
import com.apvcode.devx.ui.components.SettingsGroup
import com.apvcode.devx.ui.components.SettingsRow
import com.apvcode.devx.ui.components.SettingsSwitchRow
import com.apvcode.devx.ui.theme.DevxColors
import com.apvcode.devx.ui.theme.DevxType
import android.app.NotificationManager
import android.os.Build
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Alignment
import androidx.documentfile.provider.DocumentFile
import com.apvcode.devx.assist.VoiceWarm
import com.apvcode.devx.data.PermissionBridge
import com.apvcode.devx.ui.chat.ChatViewModel
import com.apvcode.devx.ui.chat.ModelPicker
import com.apvcode.devx.ui.components.DevxDialog
import java.util.Locale

/**
 * Settings of the voice assistant: whether the phone really calls Devx (checked again every time the page is in view),
 * whether the microphone, speech recognition and the voice are there, how it behaves, how to call it and what it can do.
 */
@Composable
fun VoiceSettingsScreen(vm: ChatViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { tick++ }
    @Suppress("UNUSED_EXPRESSION") tick
    val isDefault = AssistantStatus.isDefault(context)
    val mic = AssistantStatus.micAllowed(context)
    val recognizer = AssistantStatus.recognizerThere(context)
    val speech = AssistantStatus.speechEngineThere(context)
    val phone = Prefs.phoneTools
    val canWrite = Settings.System.canWrite(context)
    val photosPermission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE
    val photosAllowed = PermissionBridge.has(context, photosPermission)
    val locationAllowed = PermissionBridge.has(context, Manifest.permission.ACCESS_FINE_LOCATION) || PermissionBridge.has(context, Manifest.permission.ACCESS_COARSE_LOCATION)
    val notificationsOn = AssistantStatus.notificationAccess(context)
    val dndAllowed = context.getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted

    val chooseAssistant = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { tick++ }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
            Prefs.saveFolder(uri.toString())
        }
        tick++
    }
    val askPhotos = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }
    val askLocation = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { tick++ }
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
                StatusRow(
                    title = stringResource(R.string.va_brightness),
                    hint = if (canWrite) null else stringResource(R.string.va_brightness_no),
                    ok = canWrite,
                    value = stringResource(if (canWrite) R.string.va_allowed else R.string.va_not_allowed),
                    onClick = { open(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:" + context.packageName))) },
                )
                SettingsDivider()
                StatusRow(
                    title = stringResource(R.string.va_dnd),
                    hint = if (dndAllowed) null else stringResource(R.string.va_dnd_no),
                    ok = dndAllowed,
                    value = stringResource(if (dndAllowed) R.string.va_allowed else R.string.va_not_allowed),
                    onClick = { open(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)) },
                )
                SettingsDivider()
                StatusRow(
                    title = stringResource(R.string.va_location),
                    hint = if (locationAllowed) null else stringResource(R.string.va_location_no),
                    ok = locationAllowed,
                    value = stringResource(if (locationAllowed) R.string.va_allowed else R.string.va_not_allowed),
                    onClick = {
                        if (locationAllowed) open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
                        else askLocation.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                    },
                )
                SettingsDivider()
                StatusRow(
                    title = stringResource(R.string.va_photos),
                    hint = if (photosAllowed) null else stringResource(R.string.va_photos_no),
                    ok = photosAllowed,
                    value = stringResource(if (photosAllowed) R.string.va_allowed else R.string.va_not_allowed),
                    onClick = {
                        if (photosAllowed) open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
                        else askPhotos.launch(photosPermission)
                    },
                )
                SettingsDivider()
                StatusRow(
                    title = stringResource(R.string.va_notifications),
                    hint = if (notificationsOn) null else stringResource(R.string.va_notifications_no),
                    ok = notificationsOn,
                    value = stringResource(if (notificationsOn) R.string.va_allowed else R.string.va_not_allowed),
                    onClick = { open(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) },
                )
                SettingsDivider()
                val folderName = Prefs.folderUri?.let { DocumentFile.fromTreeUri(context, Uri.parse(it))?.name }
                StatusRow(
                    title = stringResource(R.string.va_folder),
                    hint = if (folderName != null) stringResource(R.string.va_folder_yes) else stringResource(R.string.va_folder_no),
                    ok = folderName != null,
                    value = folderName ?: stringResource(R.string.va_not_chosen),
                    onClick = { pickFolder.launch(null) },
                )
                if (folderName != null) {
                    SettingsDivider()
                    SettingsRow(title = stringResource(R.string.va_folder_forget), onClick = { Prefs.saveFolder(null); tick++ })
                }
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
                    title = stringResource(R.string.settings_gallery),
                    subtitle = stringResource(R.string.settings_gallery_hint),
                    checked = Prefs.galleryTools,
                    onCheckedChange = { Prefs.saveGalleryTools(it) },
                )
                SettingsDivider()
                SettingsSwitchRow(
                    title = stringResource(R.string.settings_notif_read),
                    subtitle = stringResource(R.string.settings_notif_read_hint),
                    checked = Prefs.notificationTools,
                    onCheckedChange = { Prefs.saveNotificationTools(it) },
                )
                SettingsDivider()
                var modelPicker by remember { mutableStateOf(false) }
                SettingsRow(
                    title = stringResource(R.string.va_model),
                    subtitle = stringResource(R.string.va_model_hint),
                    value = Prefs.voiceModel?.model?.take(20) ?: stringResource(R.string.va_model_same),
                    showChevron = true,
                    onClick = { modelPicker = true },
                )
                if (modelPicker) {
                    ModelPicker(
                        vm = vm,
                        onDismiss = { modelPicker = false },
                        onOpenProviders = { modelPicker = false },
                        onPick = { Prefs.saveVoiceModel(it); modelPicker = false },
                        selected = Prefs.voiceModel,
                        autoLabel = stringResource(R.string.va_model_same),
                    )
                }
                SettingsDivider()
                val speaker = remember { VoiceWarm.speaker(context) }
                SettingsRow(
                    title = stringResource(R.string.va_voice_speed),
                    subtitle = stringResource(R.string.va_voice_speed_hint),
                    trailing = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("−", style = DevxType.Title, color = DevxColors.TextPrimary, modifier = Modifier.pressable(scaleTo = 0.9f, haptic = false) { Prefs.saveVoiceRate(Prefs.voiceRate - 0.05f); speaker.refresh() }.padding(horizontal = 14.dp, vertical = 6.dp))
                            Text("%.2f×".format(Prefs.voiceRate), style = DevxType.Body, color = DevxColors.TextSecondary)
                            Text("+", style = DevxType.Title, color = DevxColors.TextPrimary, modifier = Modifier.pressable(scaleTo = 0.9f, haptic = false) { Prefs.saveVoiceRate(Prefs.voiceRate + 0.05f); speaker.refresh() }.padding(horizontal = 14.dp, vertical = 6.dp))
                        }
                    },
                )
                SettingsDivider()
                var voiceDialog by remember { mutableStateOf(false) }
                SettingsRow(
                    title = stringResource(R.string.va_voice),
                    subtitle = stringResource(R.string.va_voice_hint),
                    value = if (Prefs.voiceName.isBlank()) stringResource(R.string.va_voice_best) else Prefs.voiceName.take(18),
                    showChevron = true,
                    onClick = { voiceDialog = true },
                )
                if (voiceDialog) {
                    val tag = Locale.getDefault().toLanguageTag()
                    val voices = remember(speaker.engineReady) { speaker.voicesFor(tag) }
                    DevxDialog(stringResource(R.string.va_voice), onDismiss = { voiceDialog = false }) {
                        Column(Modifier.padding(horizontal = 12.dp).heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                            @Composable
                            fun Choice(label: String, detail: String?, selected: Boolean, onPick: () -> Unit) {
                                SettingsRow(title = label, subtitle = detail, onClick = onPick, trailing = { if (selected) Text("✓", style = DevxType.Title, color = DevxColors.TextPrimary) })
                            }
                            Choice(stringResource(R.string.va_voice_best), stringResource(R.string.va_voice_best_hint), Prefs.voiceName.isBlank()) {
                                Prefs.saveVoiceName(""); speaker.refresh(); speaker.read(context.getString(R.string.va_voice_sample))
                            }
                            voices.forEachIndexed { i, v ->
                                val quality = when { v.quality >= 500 -> "★★★"; v.quality >= 400 -> "★★"; else -> "★" }
                                Choice(
                                    stringResource(R.string.va_voice_n, i + 1) + "  " + quality + "  " + v.name.substringAfter("-x-", v.name).take(14),
                                    v.locale.displayName + " · " + v.name + (if (v.isNetworkConnectionRequired) " · " + stringResource(R.string.va_voice_online) else ""),
                                    Prefs.voiceName == v.name,
                                ) { Prefs.saveVoiceName(v.name); speaker.refresh(); speaker.read(context.getString(R.string.va_voice_sample)) }
                            }
                        }
                    }
                }
                SettingsDivider()
                SettingsSwitchRow(
                    title = stringResource(R.string.settings_voice_hands_free),
                    subtitle = stringResource(R.string.settings_voice_hands_free_hint),
                    checked = Prefs.voiceHandsFree,
                    onCheckedChange = { Prefs.saveVoiceHandsFree(it) },
                )
            }
        }
        item { SectionLabel(stringResource(R.string.voice_section)) }
        item {
            SettingsGroup {
                val speaker = remember { VoiceWarm.speaker(context) }
                // Leaving the page cuts the voice off: a sample must never go on playing (or pile up) after it.
                androidx.compose.runtime.DisposableEffect(Unit) { onDispose { speaker.stop() } }
                var dialog by remember { mutableStateOf<String?>(null) }
                val cloud = Prefs.voiceEngine == Prefs.ENGINE_CLOUD
                val eligible = vm.providers.filter { CloudVoice.backendOf(it) != null }
                val chosen = eligible.firstOrNull { it.id == Prefs.cloudProvider }
                SettingsRow(
                    title = stringResource(R.string.voice_engine),
                    subtitle = stringResource(R.string.voice_engine_hint),
                    value = stringResource(if (cloud) R.string.voice_engine_cloud else R.string.voice_engine_phone),
                    showChevron = true,
                    onClick = { dialog = "engine" },
                )
                if (cloud) {
                    SettingsDivider()
                    SettingsRow(
                        title = stringResource(R.string.voice_provider),
                        subtitle = if (eligible.isEmpty()) stringResource(R.string.voice_provider_none) else stringResource(R.string.voice_cloud_note),
                        value = chosen?.name?.take(18),
                        showChevron = eligible.isNotEmpty(),
                        onClick = if (eligible.isNotEmpty()) ({ dialog = "provider" }) else null,
                    )
                    SettingsDivider()
                    val models = chosen?.let { CloudVoice.speechModels(it) }.orEmpty()
                    val activeModel = Prefs.cloudModel.ifBlank { models.firstOrNull().orEmpty() }.takeIf { it.isNotBlank() }
                    SettingsRow(
                        title = stringResource(R.string.voice_model),
                        subtitle = when {
                            chosen == null -> stringResource(R.string.voice_pick_provider)
                            models.isEmpty() -> stringResource(R.string.voice_no_models)
                            Prefs.cloudModel.isBlank() -> stringResource(R.string.voice_model_auto)
                            else -> null
                        },
                        value = activeModel?.take(24),
                        showChevron = chosen != null,
                        onClick = if (chosen != null) ({ dialog = "model" }) else null,
                    )
                    SettingsDivider()
                    SettingsRow(
                        title = stringResource(R.string.voice_cloud_voice),
                        subtitle = if (chosen == null) stringResource(R.string.voice_pick_provider) else null,
                        value = chosen?.let { Prefs.cloudVoice.ifBlank { CloudVoice.defaultVoice(CloudVoice.backendOf(it)!!) } },
                        showChevron = chosen != null,
                        onClick = if (chosen != null) ({ dialog = "voice" }) else null,
                    )
                    if (chosen != null) {
                        SettingsDivider()
                        SettingsRow(
                            title = stringResource(R.string.voice_test),
                            icon = com.apvcode.devx.ui.components.DevxIcons.Speaker,
                            onClick = { speaker.refresh(); speaker.read(context.getString(R.string.va_voice_sample)) },
                        )
                    }
                    speaker.cloudError?.let { reason ->
                        SettingsDivider()
                        SettingsRow(title = stringResource(R.string.voice_cloud_failed, reason.take(160)), titleColor = DevxColors.Danger)
                    }
                }
                SettingsDivider()
                SettingsRow(
                    title = stringResource(R.string.voice_better),
                    subtitle = stringResource(R.string.voice_better_hint),
                    showChevron = true,
                    onClick = {
                        val tts = Intent("com.android.settings.TTS_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        runCatching { context.startActivity(tts) }.onFailure { runCatching { context.startActivity(Intent(android.provider.Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
                    },
                )
                when (dialog) {
                    "engine" -> com.apvcode.devx.ui.components.ChoiceDialog(
                        stringResource(R.string.voice_engine),
                        listOf(Prefs.ENGINE_PHONE to stringResource(R.string.voice_engine_phone), Prefs.ENGINE_CLOUD to stringResource(R.string.voice_engine_cloud)),
                        Prefs.voiceEngine,
                        onPick = { Prefs.saveVoiceEngine(it); if (it == Prefs.ENGINE_CLOUD && Prefs.cloudProvider.isBlank()) eligible.firstOrNull()?.let { p -> Prefs.saveCloudProvider(p.id) }; speaker.refresh(); dialog = null },
                        onDismiss = { dialog = null },
                    )
                    "provider" -> com.apvcode.devx.ui.components.ChoiceDialog(
                        stringResource(R.string.voice_provider),
                        eligible.map { it.id to it.name },
                        Prefs.cloudProvider,
                        onPick = { Prefs.saveCloudProvider(it); speaker.refresh(); dialog = null },
                        onDismiss = { dialog = null },
                    )
                    "model" -> chosen?.let { p ->
                        val models = CloudVoice.speechModels(p)
                        com.apvcode.devx.ui.components.ChoiceDialog(
                            stringResource(R.string.voice_model),
                            listOf("" to stringResource(R.string.voice_model_auto)) + models.map { it to it } + listOf("\u0000type" to stringResource(R.string.voice_model_type)),
                            Prefs.cloudModel.takeIf { it.isNotBlank() }.orEmpty(),
                            onPick = { if (it == "\u0000type") dialog = "typemodel" else { Prefs.saveCloudModel(it); speaker.refresh(); dialog = null } },
                            onDismiss = { dialog = null },
                        )
                    }
                    "typemodel" -> {
                        var typed by remember { mutableStateOf(Prefs.cloudModel) }
                        DevxDialog(stringResource(R.string.voice_model), onDismiss = { dialog = null }) {
                            Column(Modifier.padding(horizontal = 24.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)) {
                                com.apvcode.devx.ui.components.DevxTextField(stringResource(R.string.voice_model_type_hint), typed, { typed = it.trim() })
                                com.apvcode.devx.ui.components.PillButton(stringResource(R.string.dialog_save), enabled = typed.isNotBlank(), onClick = { Prefs.saveCloudModel(typed); speaker.refresh(); dialog = null })
                                com.apvcode.devx.ui.components.PillButton(stringResource(R.string.dialog_cancel), filled = false, onClick = { dialog = null })
                            }
                        }
                    }
                    "voice" -> chosen?.let { p ->
                        val backend = CloudVoice.backendOf(p)!!
                        com.apvcode.devx.ui.components.ChoiceDialog(
                            stringResource(R.string.voice_cloud_voice),
                            CloudVoice.voicesOf(backend).map { it to it },
                            Prefs.cloudVoice.ifBlank { CloudVoice.defaultVoice(backend) },
                            onPick = { Prefs.saveCloudVoice(it); speaker.refresh(); dialog = null; speaker.read(context.getString(R.string.va_voice_sample)) },
                            onDismiss = { dialog = null },
                        )
                    }
                }
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
                SettingsRow(title = stringResource(R.string.va_can_screen), subtitle = stringResource(R.string.va_can_screen_hint))
                SettingsDivider()
                SettingsRow(title = stringResource(R.string.va_can_places), subtitle = stringResource(R.string.va_can_places_hint))
                SettingsDivider()
                SettingsRow(title = stringResource(R.string.va_can_photos), subtitle = stringResource(R.string.va_can_photos_hint))
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
