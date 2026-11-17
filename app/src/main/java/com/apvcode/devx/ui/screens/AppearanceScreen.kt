package com.apvcode.devx.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material3.Icon
import com.apvcode.devx.ui.components.SettingsRow
import com.apvcode.devx.ui.components.PillButton
import com.apvcode.devx.ui.components.DevxDialog
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.apvcode.devx.R
import com.apvcode.devx.data.Prefs
import com.apvcode.devx.data.ThemeMode
import com.apvcode.devx.ui.components.SettingsDivider
import com.apvcode.devx.data.ThemeSchedule
import com.apvcode.devx.ui.components.DevxIcons
import com.apvcode.devx.ui.components.ScreenScaffold
import com.apvcode.devx.ui.components.SectionLabel
import com.apvcode.devx.ui.components.SettingsGroup
import com.apvcode.devx.ui.components.SettingsSwitchRow
import com.apvcode.devx.ui.components.pressable
import com.apvcode.devx.ui.theme.DevxColors
import com.apvcode.devx.ui.theme.DevxType
import com.apvcode.devx.ui.theme.Palette

/** Settings, Appearance: the theme (as small previews to tap) and the blur of the glass panels. */
@Composable
fun AppearanceScreen(onBack: () -> Unit, onOthers: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var picking by remember { mutableStateOf<String?>(null) }
    fun pickTime(current: Int, onTime: (Int) -> Unit) {
        android.app.TimePickerDialog(context, { _, h, m -> onTime(h * 60 + m) }, current / 60, current % 60, true).show()
    }
    picking?.let { which ->
        val day = which == "day"
        com.apvcode.devx.ui.components.ChoiceDialog(
            stringResource(if (day) R.string.appearance_day else R.string.appearance_night),
            ThemeMode.values().filter { it != ThemeMode.System }.map { it to themeLabel(it) },
            if (day) Prefs.dayTheme else Prefs.nightTheme,
            onPick = { if (day) Prefs.saveDayTheme(it) else Prefs.saveNightTheme(it); picking = null },
            onDismiss = { picking = null },
        )
    }
    ScreenScaffold(stringResource(R.string.settings_appearance), onBack) {
        item { SectionLabel(stringResource(R.string.appearance_theme)) }
        // Three to pick at once; the rest are in a window of their own.
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                QuickThemes.forEach { mode -> ThemeTile(mode, themeLabel(mode), Modifier.weight(1f)) }
            }
        }
        item {
            SettingsGroup {
                SettingsRow(
                    title = stringResource(R.string.appearance_other),
                    icon = Icons.Outlined.Palette,
                    value = Prefs.theme.takeIf { it !in QuickThemes }?.let { themeLabel(it) },
                    showChevron = true,
                    onClick = onOthers,
                )
            }
        }
        item { SectionLabel(stringResource(R.string.appearance_schedule_title)) }
        item {
            SettingsGroup {
                SettingsSwitchRow(
                    title = stringResource(R.string.appearance_schedule),
                    subtitle = stringResource(R.string.appearance_schedule_hint),
                    checked = Prefs.themeSchedule,
                    onCheckedChange = { Prefs.saveThemeSchedule(it) },
                )
                if (Prefs.themeSchedule) {
                    SettingsDivider()
                    SettingsRow(title = stringResource(R.string.appearance_day), value = themeLabel(Prefs.dayTheme), showChevron = true, onClick = { picking = "day" })
                    SettingsDivider()
                    SettingsRow(title = stringResource(R.string.appearance_night), value = themeLabel(Prefs.nightTheme), showChevron = true, onClick = { picking = "night" })
                    SettingsDivider()
                    SettingsRow(title = stringResource(R.string.appearance_night_from), value = ThemeSchedule.label(Prefs.nightFrom), showChevron = true, onClick = { pickTime(Prefs.nightFrom) { Prefs.saveNightTimes(it, Prefs.nightTo) } })
                    SettingsDivider()
                    SettingsRow(title = stringResource(R.string.appearance_night_to), value = ThemeSchedule.label(Prefs.nightTo), showChevron = true, onClick = { pickTime(Prefs.nightTo) { Prefs.saveNightTimes(Prefs.nightFrom, it) } })
                }
            }
        }
        item { SectionLabel(stringResource(R.string.appearance_effects)) }
        item {
            SettingsGroup {
                SettingsSwitchRow(
                    title = stringResource(R.string.settings_blur),
                    subtitle = stringResource(R.string.settings_blur_hint),
                    checked = Prefs.blur,
                    onCheckedChange = { Prefs.saveBlur(it) },
                )
            }
        }
    }
}

/** The themes shown on the page itself. */
private val QuickThemes = listOf(ThemeMode.System, ThemeMode.Light, ThemeMode.Black)

/** The page of all the other themes, in rows of three. Picking one changes the app at once. */
@Composable
fun ThemesScreen(onBack: () -> Unit) {
    val rest = ThemeMode.values().filter { it !in QuickThemes }
    ScreenScaffold(stringResource(R.string.appearance_other), onBack) {
        rest.chunked(3).forEach { row ->
            item(key = row.first().name) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { mode -> ThemeTile(mode, themeLabel(mode), Modifier.weight(1f)) }
                    repeat(3 - row.size) { Box(Modifier.weight(1f)) }
                }
            }
        }
    }
}

/** The name of a theme as the person reads it. */
@Composable
fun themeLabel(mode: ThemeMode): String = stringResource(
    when (mode) {
        ThemeMode.System -> R.string.theme_system
        ThemeMode.Black -> R.string.theme_black
        ThemeMode.Gray -> R.string.theme_gray
        ThemeMode.Light -> R.string.theme_light
        ThemeMode.Midnight -> R.string.theme_midnight
        ThemeMode.Forest -> R.string.theme_forest
        ThemeMode.Dusk -> R.string.theme_dusk
        ThemeMode.Ember -> R.string.theme_ember
        ThemeMode.Ocean -> R.string.theme_ocean
        ThemeMode.Paper -> R.string.theme_paper
        ThemeMode.Sakura -> R.string.theme_sakura
        ThemeMode.Mint -> R.string.theme_mint
    },
)

/** One theme, drawn small: its ground, a card and two lines of text. "System" shows the dark and the light side by side. */
@Composable
private fun ThemeTile(mode: ThemeMode, label: String, modifier: Modifier = Modifier) {
    val selected = Prefs.theme == mode
    val ring by animateColorAsState(if (selected) DevxColors.Accent else DevxColors.Outline, tween(180), label = "ring")
    val shape = RoundedCornerShape(18.dp)
    // Where the tile is on the screen: the change of theme spreads from its middle.
    var middle by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(0.8f).clip(shape).border(if (selected) 2.dp else 1.dp, ring, shape)
                .onGloballyPositioned { c -> middle = c.positionInWindow() + androidx.compose.ui.geometry.Offset(c.size.width / 2f, c.size.height / 2f) }
                .pressable(scaleTo = 0.96f, haptic = false) { if (!selected) com.apvcode.devx.ui.theme.ThemeReveal.start(middle) { Prefs.saveTheme(mode) } },
        ) {
            if (mode == ThemeMode.System) Row(Modifier.fillMaxSize()) {
                Preview(Palette.Black, Modifier.weight(1f).fillMaxHeight())
                Preview(Palette.Light, Modifier.weight(1f).fillMaxHeight())
            } else Preview(Palette.of(mode, false), Modifier.fillMaxSize())
            if (selected) Box(Modifier.align(Alignment.BottomEnd).padding(6.dp).size(22.dp).clip(CircleShape).background(DevxColors.Accent), contentAlignment = Alignment.Center) {
                Icon(DevxIcons.Check, null, tint = DevxColors.Bg, modifier = Modifier.size(15.dp))
            }
        }
        Text(label, style = DevxType.Caption, color = if (selected) DevxColors.TextPrimary else DevxColors.TextSecondary, textAlign = TextAlign.Center, maxLines = 2)
    }
}

/** A tiny screen in a palette's colours. */
@Composable
private fun Preview(p: Palette, modifier: Modifier) {
    Column(modifier.background(p.bg).padding(horizontal = 8.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.fillMaxWidth().height(10.dp).clip(CircleShape).background(p.text.copy(alpha = 0.85f)))
        Box(Modifier.fillMaxWidth(0.6f).height(6.dp).clip(CircleShape).background(p.textSecondary))
        Box(Modifier.fillMaxWidth().weight(1f).padding(top = 4.dp).clip(RoundedCornerShape(10.dp)).background(p.card))
        Box(Modifier.fillMaxWidth().height(12.dp).clip(CircleShape).background(p.accent))
    }
}
