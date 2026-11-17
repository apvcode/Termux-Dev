package com.apvcode.devx.ui.screens

import com.apvcode.devx.data.logged
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
import androidx.compose.material.icons.outlined.CloudUpload
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

/** Settings, Data and security: the lock of the app, the copies of everything (by hand and weekly) and the trash. */
@Composable
fun DataScreen(vm: ChatViewModel, onBack: () -> Unit, onAppLock: () -> Unit, onTrash: () -> Unit) {
    // Backup: everything of the person's in one file, and back (keys and tokens stay behind: they belong to this phone).
    val appContext = LocalContext.current
    val backupScope = androidx.compose.runtime.rememberCoroutineScope()
    var restoreFrom by remember { mutableStateOf<Uri?>(null) }
    var restoring by remember { mutableStateOf(false) }
    fun say(res: Int) = Toast.makeText(appContext, res, Toast.LENGTH_LONG).show()
    // The password of the backup being made (null: without the keys), and the dialog that asks for it.
    var exportAsk by remember { mutableStateOf(false) }
    var exportPassword by remember { mutableStateOf<String?>(null) }
    val makeBackup = androidx.activity.compose.rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val password = exportPassword
        exportPassword = null
        if (uri != null) backupScope.launch(Dispatchers.IO) {
            val ok = runCatching {
                val version = appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName ?: ""
                val keys = if (password != null) kotlinx.coroutines.withContext(Dispatchers.Main) { vm.exportSecrets() } else emptyMap()
                appContext.contentResolver.openOutputStream(uri)!!.use { out ->
                    com.apvcode.devx.data.Backup.export(appContext.filesDir, File(appContext.applicationInfo.dataDir, "shared_prefs"), out, version, secrets = keys, password = password?.toCharArray())
                }
            }.isSuccess
            kotlinx.coroutines.withContext(Dispatchers.Main) { say(if (ok) R.string.backup_done else R.string.backup_failed) }
        }
    }
    val backupFileName = { "devx-backup-" + SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()) + ".zip" }
    // Restoring: does the file hold keys (then its password is asked), and the password typed.
    var restoreKeys by remember { mutableStateOf(false) }
    var restorePassword by remember { mutableStateOf("") }
    var restoreWrong by remember { mutableStateOf(false) }
    var restorePreview by remember { mutableStateOf<com.apvcode.devx.data.Backup.Preview?>(null) }
    val pickBackup = androidx.activity.compose.rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        restorePassword = ""; restoreWrong = false; restoreKeys = false; restorePreview = null
        if (uri != null) backupScope.launch(Dispatchers.IO) {
            // Looked at first, changing nothing: what is in the file and what it would replace. A file that is not a backup stops here.
            val preview = runCatching {
                appContext.contentResolver.openInputStream(uri)!!.use { com.apvcode.devx.data.Backup.inspect(appContext.filesDir, File(appContext.applicationInfo.dataDir, "shared_prefs"), it) }
            }.logged("datascreen").getOrNull()
            kotlinx.coroutines.withContext(Dispatchers.Main) {
                if (preview == null) say(R.string.backup_bad) else { restorePreview = preview; restoreKeys = preview.hasSecrets; restoreFrom = uri }
            }
        }
    }
    // The weekly copy by itself: a folder is chosen once, the first copy is made at once.
    var autoOn by remember { mutableStateOf(AutoBackup.folder(appContext) != null) }
    var davOn by remember { mutableStateOf(AutoBackup.webdavUrl(appContext) != null) }
    var davAsk by remember { mutableStateOf(false) }
    var autoTick by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    var autoKeys by remember { mutableStateOf(AutoBackup.keysEnabled(appContext)) }
    var autoKeysAsk by remember { mutableStateOf(false) }
    var autoLean by remember { mutableStateOf(AutoBackup.lean(appContext)) }
    val pickFolder = androidx.activity.compose.rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            AutoBackup.setFolder(appContext, uri)
            autoOn = true
            backupScope.launch(Dispatchers.IO) {
                val ok = AutoBackup.backupNow(appContext)
                kotlinx.coroutines.withContext(Dispatchers.Main) { autoTick++; say(if (ok) R.string.backup_done else R.string.backup_failed) }
            }
        }
    }
    if (davAsk) {
        var url by remember { mutableStateOf(AutoBackup.webdavUrl(appContext).orEmpty()) }
        var user by remember { mutableStateOf(AutoBackup.webdavUser(appContext)) }
        var pass by remember { mutableStateOf(if (davOn) AutoBackup.webdavPassword(appContext) else "") }
        var busy by remember { mutableStateOf(false) }
        var problem by remember { mutableStateOf<Int?>(null) }
        DevxDialog(stringResource(R.string.webdav_title), onDismiss = { if (!busy) davAsk = false }) {
            Column(Modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.webdav_info), style = DevxType.Body, color = DevxColors.TextSecondary)
                DevxTextField(stringResource(R.string.webdav_url), url, { url = it; problem = null })
                DevxTextField(stringResource(R.string.webdav_user), user, { user = it; problem = null })
                DevxTextField(stringResource(R.string.backup_password), pass, { pass = it; problem = null }, password = true)
                problem?.let { Text(stringResource(it), style = DevxType.Body, color = DevxColors.Danger) }
                PillButton(stringResource(R.string.webdav_save), enabled = !busy && url.isNotBlank() && user.isNotBlank() && pass.isNotEmpty(), onClick = {
                    val folder = com.apvcode.devx.data.WebDav.folderUrl(url)
                    if (folder == null) { problem = R.string.webdav_not_secure; return@PillButton }
                    busy = true
                    backupScope.launch(Dispatchers.IO) {
                        val status = com.apvcode.devx.data.WebDav.check(folder, user.trim(), pass)
                        kotlinx.coroutines.withContext(Dispatchers.Main) {
                            busy = false
                            when (status) {
                                com.apvcode.devx.data.WebDav.Status.Ok -> {
                                    AutoBackup.setWebdav(appContext, folder.toString(), user.trim(), pass)
                                    davOn = true; davAsk = false
                                    backupScope.launch(Dispatchers.IO) {
                                        val ok = AutoBackup.backupNow(appContext)
                                        kotlinx.coroutines.withContext(Dispatchers.Main) { autoTick++; say(if (ok) R.string.backup_done else R.string.backup_failed) }
                                    }
                                }
                                com.apvcode.devx.data.WebDav.Status.BadLogin -> problem = R.string.webdav_bad_login
                                com.apvcode.devx.data.WebDav.Status.NotFound -> problem = R.string.webdav_not_found
                                com.apvcode.devx.data.WebDav.Status.NotSecure -> problem = R.string.webdav_not_secure
                                com.apvcode.devx.data.WebDav.Status.Failed -> problem = R.string.webdav_failed
                            }
                        }
                    }
                })
                if (davOn) PillButton(stringResource(R.string.webdav_remove), filled = false, enabled = !busy, onClick = {
                    AutoBackup.clearWebdav(appContext)
                    davOn = false; davAsk = false
                    if (!autoOn) { AutoBackup.setPassword(appContext, null); autoKeys = false }
                })
                PillButton(stringResource(R.string.dialog_cancel), filled = false, enabled = !busy, onClick = { davAsk = false })
            }
        }
    }
    if (autoKeysAsk) {
        var first by remember { mutableStateOf("") }
        var second by remember { mutableStateOf("") }
        val ready = first.length >= 4 && first == second
        DevxDialog(stringResource(R.string.backup_auto_keys), onDismiss = { autoKeysAsk = false }) {
            Column(Modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.backup_keys_info), style = DevxType.Body, color = DevxColors.TextSecondary)
                DevxTextField(stringResource(R.string.backup_password), first, { first = it }, password = true)
                DevxTextField(stringResource(R.string.backup_password_again), second, { second = it }, password = true)
                if (!ready) Text(stringResource(R.string.backup_password_need), style = DevxType.Caption, color = DevxColors.TextSecondary)
                PillButton(stringResource(R.string.dialog_save), enabled = ready, onClick = {
                    AutoBackup.setPassword(appContext, first)
                    autoKeys = true
                    autoKeysAsk = false
                    backupScope.launch(Dispatchers.IO) {
                        val ok = AutoBackup.backupNow(appContext)
                        kotlinx.coroutines.withContext(Dispatchers.Main) { autoTick++; say(if (ok) R.string.backup_done else R.string.backup_failed) }
                    }
                })
                PillButton(stringResource(R.string.dialog_cancel), filled = false, onClick = { autoKeysAsk = false })
            }
        }
    }
    if (exportAsk) {
        var first by remember { mutableStateOf("") }
        var second by remember { mutableStateOf("") }
        val ready = first.length >= 4 && first == second
        DevxDialog(stringResource(R.string.backup_keys_title), onDismiss = { exportAsk = false }) {
            Column(Modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.backup_keys_info), style = DevxType.Body, color = DevxColors.TextSecondary)
                DevxTextField(stringResource(R.string.backup_password), first, { first = it }, password = true)
                DevxTextField(stringResource(R.string.backup_password_again), second, { second = it }, password = true)
                if (!ready) Text(stringResource(R.string.backup_password_need), style = DevxType.Caption, color = DevxColors.TextSecondary)
                PillButton(stringResource(R.string.backup_with_keys), enabled = ready, onClick = { exportPassword = first; exportAsk = false; makeBackup.launch(backupFileName()) })
                PillButton(stringResource(R.string.backup_without_keys), filled = false, onClick = { exportPassword = null; exportAsk = false; makeBackup.launch(backupFileName()) })
            }
        }
    }
    restoreFrom?.let { uri ->
        DevxDialog(stringResource(R.string.backup_restore), onDismiss = { if (!restoring) restoreFrom = null }) {
            Column(Modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                restorePreview?.let { p ->
                    val made = if (p.at > 0) DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(p.at)) else "?"
                    Text(
                        stringResource(R.string.backup_preview, made, p.version.ifBlank { "?" }, p.chats, p.projects, p.memoryFiles, p.attachments, p.settings, p.replaced, p.files),
                        style = DevxType.Body, color = DevxColors.TextPrimary,
                    )
                }
                Text(stringResource(R.string.backup_restore_ask), style = DevxType.Body, color = DevxColors.TextSecondary)
                if (restoreKeys) {
                    Text(stringResource(R.string.backup_restore_password), style = DevxType.Body, color = DevxColors.TextSecondary)
                    DevxTextField(stringResource(R.string.backup_password), restorePassword, { restorePassword = it; restoreWrong = false }, password = true)
                    if (restoreWrong) Text(stringResource(R.string.backup_wrong_password), style = DevxType.Body, color = DevxColors.Danger)
                }
                PillButton(stringResource(R.string.backup_restore), enabled = !restoring, onClick = {
                    restoring = true
                    val password = restorePassword.takeIf { restoreKeys && it.isNotEmpty() }?.toCharArray()
                    backupScope.launch(Dispatchers.IO) {
                        val result = runCatching {
                            val opened = arrayOfNulls<com.apvcode.devx.data.Backup.Vault>(1)
                            val n = appContext.contentResolver.openInputStream(uri)!!.use {
                                com.apvcode.devx.data.Backup.restore(appContext.filesDir, File(appContext.applicationInfo.dataDir, "shared_prefs"), it, password) { v -> opened[0] = v }
                            }
                            opened[0]?.let { v -> kotlinx.coroutines.withContext(Dispatchers.Main) { vm.importSecrets(v.secrets) } }
                            n
                        }.logged("datascreen")
                        kotlinx.coroutines.withContext(Dispatchers.Main) {
                            restoring = false
                            if (result.exceptionOrNull() is com.apvcode.devx.data.Backup.WrongPassword) { restoreWrong = true; return@withContext }
                            restoreFrom = null
                            if (result.isSuccess) {
                                say(R.string.backup_restored)
                                // The app starts again so that it reads what was restored.
                                val launch = appContext.packageManager.getLaunchIntentForPackage(appContext.packageName)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
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
    ScreenScaffold(stringResource(R.string.data_security), onBack) {
        item { SectionLabel(stringResource(R.string.data_section_security)) }
        item {
            SettingsGroup {
                SettingsRow(
                    title = stringResource(R.string.settings_pin),
                    icon = Icons.Outlined.Lock,
                    value = stringResource(if (AppLock.enabled) R.string.settings_pin_on else R.string.settings_pin_value),
                    showChevron = true,
                    onClick = onAppLock,
                )
            }
        }
        item { SectionLabel(stringResource(R.string.data_section_auto)) }
        item {
            SettingsGroup {
                SettingsSwitchRow(
                    title = stringResource(R.string.autobackup_title),
                    subtitle = if (autoOn) {
                        val folder = AutoBackup.folder(appContext)?.let { AutoBackup.label(it) }.orEmpty()
                        val at = remember(autoTick, autoOn) { AutoBackup.lastAt(appContext) }
                        stringResource(R.string.autobackup_on, folder, if (at == 0L) stringResource(R.string.autobackup_never) else DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(at)))
                    } else stringResource(R.string.autobackup_off),
                    checked = autoOn,
                    onCheckedChange = { on -> if (on) pickFolder.launch(null) else { AutoBackup.disable(appContext); if (!davOn) { AutoBackup.setPassword(appContext, null); autoKeys = false }; autoOn = false } },
                )
                SettingsDivider()
                SettingsRow(
                    title = stringResource(R.string.webdav_title),
                    subtitle = if (davOn) stringResource(R.string.webdav_on, AutoBackup.webdavUrl(appContext).orEmpty().substringAfter("://").trimEnd('/')) else stringResource(R.string.webdav_off),
                    icon = Icons.Outlined.CloudUpload,
                    showChevron = true,
                    onClick = { davAsk = true },
                )
                if (autoOn || davOn) {
                    SettingsDivider()
                    SettingsSwitchRow(
                        title = stringResource(R.string.backup_auto_keys),
                        subtitle = stringResource(R.string.backup_auto_keys_hint),
                        checked = autoKeys,
                        onCheckedChange = { on -> if (on) autoKeysAsk = true else { AutoBackup.setPassword(appContext, null); autoKeys = false } },
                    )
                    SettingsDivider()
                    SettingsSwitchRow(
                        title = stringResource(R.string.backup_auto_lean),
                        subtitle = stringResource(R.string.backup_auto_lean_hint),
                        checked = autoLean,
                        onCheckedChange = { autoLean = it; AutoBackup.setLean(appContext, it) },
                    )
                }
            }
        }
        item { SectionLabel(stringResource(R.string.data_section_backup)) }
        item {
            SettingsGroup {
                SettingsRow(
                    title = stringResource(R.string.backup_create),
                    subtitle = stringResource(R.string.backup_create_hint),
                    icon = DevxIcons.FilePlus,
                    onClick = { exportAsk = true },
                )
                SettingsDivider()
                SettingsRow(
                    title = stringResource(R.string.backup_restore),
                    subtitle = stringResource(R.string.backup_restore_hint),
                    icon = DevxIcons.Undo,
                    onClick = { pickBackup.launch(arrayOf("application/zip", "application/octet-stream")) },
                )
            }
        }
        item { SectionLabel(stringResource(R.string.data_section_trash)) }
        item {
            SettingsGroup {
                SettingsRow(
                    title = stringResource(R.string.trash_title),
                    icon = DevxIcons.FolderOff,
                    value = vm.trashItems.size.takeIf { it > 0 }?.let { stringResource(R.string.trash_row_items, it) },
                    showChevron = true,
                    onClick = onTrash,
                )
            }
        }
    }
}
