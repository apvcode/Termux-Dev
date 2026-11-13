package com.apvcode.devx.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.apvcode.devx.R
import com.apvcode.devx.ui.theme.DevxColors
import com.apvcode.devx.ui.theme.DevxType

/** Round dark button, used for back / menu / actions in the header. */
@Composable
fun CircleIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    container: Color = DevxColors.Card,
    tint: Color = DevxColors.TextPrimary,
) {
    Box(
        modifier = modifier
            .size(size)
            .pressable(onClick = onClick)
            .clip(CircleShape)
            .background(container),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription, tint = tint, modifier = Modifier.size(22.dp))
    }
}

/** Full-width pill button. [filled] = white button with black text, otherwise an outlined one. */
@Composable
fun PillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    filled: Boolean = true,
    enabled: Boolean = true,
    container: Color = DevxColors.Accent,
    content: Color = DevxColors.OnAccent,
) {
    val shape = RoundedCornerShape(percent = 50)
    val base = modifier
        .fillMaxWidth()
        .heightIn(min = 48.dp)
        .pressable(enabled = enabled, scaleTo = 0.96f, onClick = onClick)
        .clip(shape)
        .alpha(if (enabled) 1f else 0.45f)
    val styled = if (filled) {
        base.background(container)
    } else {
        base.border(BorderStroke(1.dp, DevxColors.Outline), shape)
    }
    Box(
        modifier = styled.padding(horizontal = 20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = DevxType.Body,
            color = if (filled) content else DevxColors.TextPrimary,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = DevxType.Section,
        color = DevxColors.TextSecondary,
        modifier = modifier.padding(start = 12.dp, top = 8.dp),
    )
}

@Composable
fun SettingsGroup(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(28.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(DevxColors.Card)
            .border(1.dp, DevxColors.Outline, shape),
        content = content,
    )
}

@Composable
fun SettingsDivider() {
    HorizontalDivider(color = DevxColors.Outline, thickness = 0.5.dp)
}

@Composable
fun SettingsRow(
    title: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    subtitle: String? = null,
    value: String? = null,
    showChevron: Boolean = false,
    titleColor: Color = DevxColors.TextPrimary,
    /** A long title is folded to this many lines (the rest shows when it is opened). */
    titleMaxLines: Int = Int.MAX_VALUE,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val clickable = if (onClick != null) modifier.pressable(scaleTo = 0.98f, haptic = false, onClick = onClick) else modifier
    Row(
        modifier = clickable
            .fillMaxWidth()
            .heightIn(min = 60.dp)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (icon != null) {
            Icon(icon, null, tint = DevxColors.TextPrimary, modifier = Modifier.size(24.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = DevxType.Body, color = titleColor, maxLines = titleMaxLines, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            if (subtitle != null) {
                Text(subtitle, style = DevxType.Caption, color = DevxColors.TextSecondary)
            }
        }
        if (value != null) {
            Text(value, style = DevxType.Body, color = DevxColors.TextSecondary)
        }
        trailing?.invoke()
        if (showChevron) {
            Icon(
                Icons.Outlined.ChevronRight,
                null,
                tint = DevxColors.TextSecondary,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

@Composable
fun SettingsSwitchRow(
    title: String,
    subtitle: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    SettingsRow(
        title = title,
        subtitle = subtitle,
        onClick = { onCheckedChange(!checked) },
        trailing = {
            Switch(
                checked = checked,
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
        },
    )
}

/** Standard secondary screen: round back button, centred title, scrolling content. */
@Composable
fun ScreenScaffold(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    content: LazyListScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(DevxColors.Bg)
            .statusBarsPadding(),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            CircleIconButton(
                icon = Icons.AutoMirrored.Outlined.ArrowBack,
                contentDescription = stringResource(R.string.cd_back),
                onClick = onBack,
                modifier = Modifier.align(Alignment.CenterStart),
            )
            Text(title, style = DevxType.Title, color = DevxColors.TextPrimary)
        }
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            content = content,
        )
    }
}
