package com.apvcode.devx.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.apvcode.devx.R
import com.apvcode.devx.data.ErrorKind
import com.apvcode.devx.ui.theme.DevxColors
import com.apvcode.devx.ui.theme.DevxType

/**
 * A failed answer, explained quietly: one neutral card, no alarm colours. It says what happened in
 * a sentence, and offers the one action that can help.
 */
@Composable
fun ErrorCard(
    kind: ErrorKind,
    detail: String?,
    onRetry: () -> Unit,
    onOpenSettings: () -> Unit,
    onPickModel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val (icon, title, body) = when (kind) {
        ErrorKind.NoInternet -> Triple(Icons.Outlined.WifiOff, R.string.err_title_no_internet, R.string.err_body_no_internet)
        ErrorKind.Timeout -> Triple(Icons.Outlined.Schedule, R.string.err_title_timeout, R.string.err_body_timeout)
        ErrorKind.InvalidKey -> Triple(Icons.Outlined.Key, R.string.err_title_invalid_key, R.string.err_body_invalid_key)
        ErrorKind.RateLimit -> Triple(Icons.Outlined.Speed, R.string.err_title_rate, R.string.err_body_rate)
        ErrorKind.Quota -> Triple(Icons.Outlined.AccountBalanceWallet, R.string.err_title_quota, R.string.err_body_quota)
        ErrorKind.ProviderDown -> Triple(Icons.Outlined.CloudOff, R.string.err_title_down, R.string.err_body_down)
        ErrorKind.BadRequest -> Triple(Icons.Outlined.Block, R.string.err_title_bad, R.string.err_body_bad)
        ErrorKind.NoModel -> Triple(Icons.Outlined.Tune, R.string.err_title_no_model, R.string.err_body_no_model)
        ErrorKind.NoProvider -> Triple(Icons.Outlined.Tune, R.string.err_title_no_provider, R.string.err_body_no_provider)
        ErrorKind.Interrupted -> Triple(Icons.Outlined.Schedule, R.string.err_title_interrupted, R.string.err_body_interrupted)
        ErrorKind.Unknown -> Triple(Icons.Outlined.ErrorOutline, R.string.err_title_unknown, R.string.err_body_unknown)
    }
    val shape = RoundedCornerShape(24.dp)
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(DevxColors.Card)
            .border(1.dp, DevxColors.Outline, shape)
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Top) {
            Box(
                Modifier.size(40.dp).clip(CircleShape).background(DevxColors.Bg),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, null, tint = DevxColors.TextSecondary, modifier = Modifier.size(22.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(title), style = DevxType.BodyStrong, color = DevxColors.TextPrimary)
                Text(stringResource(body), style = DevxType.Body, color = DevxColors.TextSecondary)
                if (!detail.isNullOrBlank() && (kind == ErrorKind.BadRequest || kind == ErrorKind.Unknown || kind == ErrorKind.ProviderDown)) {
                    Text(
                        detail,
                        style = DevxType.Caption,
                        color = DevxColors.TextDisabled,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
        when (kind) {
            ErrorKind.InvalidKey, ErrorKind.NoProvider ->
                PillButton(stringResource(R.string.err_open_settings), onClick = onOpenSettings)
            ErrorKind.NoModel -> PillButton(stringResource(R.string.err_pick_model), onClick = onPickModel)
            else -> PillButton(stringResource(R.string.err_retry), onClick = onRetry)
        }
    }
}

/** The short headline of an error, for places other than the card (a list of providers, say). */
fun errorTitleRes(kind: ErrorKind): Int = when (kind) {
    ErrorKind.NoInternet -> R.string.err_title_no_internet
    ErrorKind.Timeout -> R.string.err_title_timeout
    ErrorKind.InvalidKey -> R.string.err_title_invalid_key
    ErrorKind.RateLimit -> R.string.err_title_rate
    ErrorKind.Quota -> R.string.err_title_quota
    ErrorKind.ProviderDown -> R.string.err_title_down
    ErrorKind.BadRequest -> R.string.err_title_bad
    ErrorKind.NoModel -> R.string.err_title_no_model
    ErrorKind.NoProvider -> R.string.err_title_no_provider
    ErrorKind.Interrupted -> R.string.err_title_interrupted
    ErrorKind.Unknown -> R.string.err_title_unknown
}
