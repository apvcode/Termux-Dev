package com.apvcode.devx.ui.screens

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDefaults
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.apvcode.devx.R
import com.apvcode.devx.data.ModelRef
import com.apvcode.devx.data.ScheduledTask
import com.apvcode.devx.data.Tasks
import com.apvcode.devx.ui.chat.ChatViewModel
import com.apvcode.devx.ui.chat.ModelPicker
import com.apvcode.devx.ui.components.DevxDialog
import com.apvcode.devx.ui.components.DevxTextField
import com.apvcode.devx.ui.components.PillButton
import com.apvcode.devx.ui.components.ScreenScaffold
import com.apvcode.devx.ui.components.SectionLabel
import com.apvcode.devx.ui.components.SettingsDivider
import com.apvcode.devx.ui.components.SettingsGroup
import com.apvcode.devx.ui.components.SettingsRow
import com.apvcode.devx.ui.components.pressable
import com.apvcode.devx.ui.theme.DevxColors
import com.apvcode.devx.ui.theme.DevxType
import java.time.DayOfWeek
import java.time.format.TextStyle

@Composable
private fun daysLabel(days: Int): String {
    val locale = LocalConfiguration.current.locales[0]
    return when (days and ScheduledTask.ALL) {
        ScheduledTask.ALL -> stringResource(R.string.tasks_every_day)
        0b0011111 -> stringResource(R.string.tasks_weekdays)
        0b1100000 -> stringResource(R.string.tasks_weekends)
        else -> (0..6).filter { days and (1 shl it) != 0 }.joinToString(", ") { DayOfWeek.of(it + 1).getDisplayName(TextStyle.SHORT, locale) }
    }
}

private fun timeText(h: Int, m: Int) = "%02d:%02d".format(h, m)

/** The scheduled tasks: what runs by itself, when, and on which model. A tap opens one; the switch turns it on or off. */
@Composable
fun TasksScreen(vm: ChatViewModel, onBack: () -> Unit, onEdit: (Int?) -> Unit) {
    val context = LocalContext.current
    var version by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { version++ }
    val tasks = remember(version) { Tasks.all(context) }
    ScreenScaffold(stringResource(R.string.tasks_title), onBack) {
        item {
            SettingsGroup {
                SettingsRow(
                    title = stringResource(R.string.tasks_add),
                    icon = Icons.Outlined.Add,
                    showChevron = true,
                    onClick = { onEdit(null) },
                )
            }
        }
        if (tasks.isEmpty()) {
            item { EmptyNote(stringResource(R.string.tasks_empty_hint)) }
        } else {
            item {
                SettingsGroup {
                    tasks.forEachIndexed { i, task ->
                        if (i > 0) SettingsDivider()
                        val model = task.model?.model ?: stringResource(R.string.task_model_default)
                        SettingsRow(
                            title = task.title.ifBlank { task.prompt.take(40) },
                            subtitle = timeText(task.hour, task.minute) + " · " + daysLabel(task.days) + "\n" + model,
                            onClick = { onEdit(task.id) },
                            trailing = {
                                Switch(
                                    checked = task.enabled,
                                    onCheckedChange = {
                                        task.enabled = it
                                        Tasks.update(context, task)
                                        version++
                                    },
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = DevxColors.Bg,
                                        checkedTrackColor = DevxColors.Accent,
                                        checkedBorderColor = DevxColors.Accent,
                                        uncheckedThumbColor = DevxColors.TextSecondary,
                                        uncheckedTrackColor = DevxColors.Card,
                                        uncheckedBorderColor = DevxColors.TextDisabled,
                                    ),
                                )
                            },
                        )
                    }
                }
            }
        }
    }
}

/** One task: its name, what to ask, the time, the days and the model; save, run it now, or delete it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskEditScreen(vm: ChatViewModel, taskId: Int?, onBack: () -> Unit, onOpenChat: (String) -> Unit) {
    val context = LocalContext.current
    val existing = remember(taskId) { taskId?.let { Tasks.get(context, it) } }
    var title by remember { mutableStateOf(existing?.title.orEmpty()) }
    var prompt by remember { mutableStateOf(existing?.prompt.orEmpty()) }
    var hour by remember { mutableIntStateOf(existing?.hour ?: 8) }
    var minute by remember { mutableIntStateOf(existing?.minute ?: 0) }
    var days by remember { mutableIntStateOf(existing?.days ?: ScheduledTask.ALL) }
    var model by remember { mutableStateOf<ModelRef?>(existing?.model) }
    var pickTime by remember { mutableStateOf(false) }
    var pickModel by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val locale = LocalConfiguration.current.locales[0]

    // A task brings its answer as a notification: on new Android that needs the person's yes, once.
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val save = {
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        val name = title.trim().ifBlank { prompt.trim().take(40) }
        if (existing == null) {
            Tasks.add(context, name, prompt, hour, minute, days, model)
        } else {
            existing.title = name
            existing.prompt = prompt.trim()
            existing.hour = hour
            existing.minute = minute
            existing.days = days
            existing.model = model
            Tasks.update(context, existing)
        }
        onBack()
    }

    ScreenScaffold(stringResource(if (existing == null) R.string.tasks_add else R.string.task_edit_title), onBack) {
        item { DevxTextField(stringResource(R.string.task_title_hint), title, { title = it.take(60) }) }
        item { DevxTextField(stringResource(R.string.task_prompt_hint), prompt, { prompt = it }, singleLine = false, minLines = 4) }
        item {
            SettingsGroup {
                SettingsRow(
                    title = stringResource(R.string.task_time),
                    value = timeText(hour, minute),
                    showChevron = true,
                    onClick = { pickTime = true },
                )
                SettingsDivider()
                SettingsRow(
                    title = stringResource(R.string.task_model),
                    value = model?.model ?: stringResource(R.string.task_model_default),
                    showChevron = true,
                    onClick = { pickModel = true },
                )
            }
        }
        item {
            SectionLabel(stringResource(R.string.task_days))
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (i in 0..6) {
                    val on = days and (1 shl i) != 0
                    Box(
                        Modifier
                            .weight(1f)
                            .pressable(scaleTo = 0.92f) {
                                val next = days xor (1 shl i)
                                if (next and ScheduledTask.ALL != 0) days = next
                            }
                            .clip(CircleShape)
                            .background(if (on) DevxColors.Accent else DevxColors.Card)
                            .padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            DayOfWeek.of(i + 1).getDisplayName(TextStyle.SHORT, locale).take(2),
                            style = DevxType.Caption,
                            color = if (on) DevxColors.OnAccent else DevxColors.TextPrimary,
                        )
                    }
                }
            }
        }
        item { PillButton(stringResource(R.string.task_save), enabled = prompt.isNotBlank(), onClick = save) }
        if (existing != null) {
            item {
                PillButton(
                    stringResource(R.string.task_run_now),
                    filled = false,
                    onClick = {
                        Tasks.runNow(context, existing.id)
                        android.widget.Toast.makeText(context, R.string.task_started, android.widget.Toast.LENGTH_LONG).show()
                    },
                )
            }
            val last = existing.lastChatId?.takeIf { vm.chatExists(it) }
            if (last != null) {
                item { PillButton(stringResource(R.string.task_open_last), filled = false, onClick = { onOpenChat(last) }) }
            }
            item { PillButton(stringResource(R.string.menu_delete), filled = false, onClick = { confirmDelete = true }) }
        }
    }

    if (pickTime) {
        val state = rememberTimePickerState(initialHour = hour, initialMinute = minute, is24Hour = true)
        DevxDialog(stringResource(R.string.task_time), onDismiss = { pickTime = false }) {
            Box(Modifier.fillMaxWidth().padding(horizontal = 24.dp), contentAlignment = Alignment.Center) {
                TimePicker(
                    state = state,
                    colors = TimePickerDefaults.colors(
                        clockDialColor = DevxColors.Card,
                        clockDialSelectedContentColor = DevxColors.OnAccent,
                        clockDialUnselectedContentColor = DevxColors.TextPrimary,
                        selectorColor = DevxColors.Accent,
                        timeSelectorSelectedContainerColor = DevxColors.Accent,
                        timeSelectorUnselectedContainerColor = DevxColors.Card,
                        timeSelectorSelectedContentColor = DevxColors.OnAccent,
                        timeSelectorUnselectedContentColor = DevxColors.TextPrimary,
                    ),
                )
            }
            Box(Modifier.padding(start = 24.dp, end = 24.dp, top = 12.dp)) {
                PillButton(stringResource(R.string.task_save), onClick = {
                    hour = state.hour
                    minute = state.minute
                    pickTime = false
                })
            }
        }
    }
    if (pickModel) {
        ModelPicker(
            vm = vm,
            onDismiss = { pickModel = false },
            onOpenProviders = { pickModel = false },
            onPick = { model = it; pickModel = false },
            selected = model,
            autoLabel = stringResource(R.string.task_model_default),
        )
    }
    if (confirmDelete && existing != null) {
        DevxDialog(stringResource(R.string.task_delete_title), onDismiss = { confirmDelete = false }) {
            Box(Modifier.padding(horizontal = 24.dp)) {
                PillButton(stringResource(R.string.menu_delete), onClick = {
                    Tasks.remove(context, existing.id)
                    confirmDelete = false
                    onBack()
                })
            }
        }
    }
}
