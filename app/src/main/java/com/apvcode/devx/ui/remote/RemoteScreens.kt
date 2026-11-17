package com.apvcode.devx.ui.remote

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.apvcode.devx.R
import com.apvcode.devx.remote.Computer
import com.apvcode.devx.remote.LinkState
import com.apvcode.devx.remote.Pairing
import com.apvcode.devx.remote.RemoteConnection
import com.apvcode.devx.remote.RemoteHub
import com.apvcode.devx.ui.components.CircleIconButton
import com.apvcode.devx.ui.components.DevxDialog
import com.apvcode.devx.ui.components.DevxIcons
import com.apvcode.devx.ui.components.PillButton
import com.apvcode.devx.ui.components.SectionLabel
import com.apvcode.devx.ui.components.SettingsDivider
import com.apvcode.devx.ui.components.SettingsGroup
import com.apvcode.devx.ui.components.SettingsSwitchRow
import com.apvcode.devx.data.Prefs
import com.apvcode.devx.ui.components.pressable
import com.apvcode.devx.ui.theme.DevxColors
import com.apvcode.devx.ui.theme.DevxType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.foundation.layout.PaddingValues
import com.apvcode.devx.remote.PendingPairing
import com.apvcode.devx.remote.RemoteLink
import com.apvcode.devx.remote.RemoteService

internal fun LinkState.visual(): LinkVisual = when (this) {
    LinkState.Online -> LinkVisual.Online
    LinkState.Connecting -> LinkVisual.Connecting
    else -> LinkVisual.Offline
}

/** The phone is still trying: connecting now, or waiting for its next try (it makes [com.apvcode.devx.remote.MAX_TRIES], then gives up). */
internal val RemoteConnection.trying: Boolean
    get() = state == LinkState.Connecting || (state == LinkState.Offline && !gaveUp && !revoked)

internal fun RemoteConnection.visual(): LinkVisual = when {
    state == LinkState.Online -> LinkVisual.Online
    trying -> LinkVisual.Connecting
    else -> LinkVisual.Offline
}

/** The clock the "a minute ago" labels read: it moves on its own while a screen with such labels is open. */
internal object RemoteClock {
    var now by androidx.compose.runtime.mutableLongStateOf(System.currentTimeMillis())
}

/** Keeps [RemoteClock] moving (and fresh when the screen comes back): the labels change by themselves, not only when something is touched. */
@Composable
internal fun ClockTicker() {
    LaunchedEffect(Unit) {
        while (true) {
            RemoteClock.now = System.currentTimeMillis()
            kotlinx.coroutines.delay(10_000)
        }
    }
}

@Composable
internal fun relativeTime(t: Long): String {
    if (t <= 0) return stringResource(R.string.remote_never)
    val s = ((RemoteClock.now - t) / 1000).coerceAtLeast(0)
    return when {
        s < 60 -> stringResource(R.string.remote_just_now)
        s < 3600 -> stringResource(R.string.remote_minutes_ago, (s / 60).toInt())
        s < 86400 -> stringResource(R.string.remote_hours_ago, (s / 3600).toInt())
        else -> stringResource(R.string.remote_days_ago, (s / 86400).toInt())
    }
}

@Composable
internal fun viaLabel(via: String?): String = when (via) {
    "direct" -> stringResource(R.string.remote_via_direct)
    "relay" -> stringResource(R.string.remote_via_relay)
    else -> ""
}

/** The header of every remote screen: round back button, title, optional action. */
@Composable
internal fun RemoteTopBar(title: String, onBack: () -> Unit, subtitle: String? = null, trailing: (@Composable () -> Unit)? = null, status: LinkVisual? = null) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        CircleIconButton(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.cd_back), onBack)
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (status != null) StatusDot(status)
                Text(title, style = DevxType.Title, color = DevxColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (subtitle != null) Text(subtitle, style = DevxType.Caption, color = DevxColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        trailing?.invoke()
    }
}

// ───────────────────────── the list of computers ─────────────────────────

@Composable
fun RemoteHomeScreen(onBack: () -> Unit, onOpen: (String) -> Unit, onAdd: () -> Unit) {
    val computers = RemoteHub.store.items
    ClockTicker()
    // All of them try to be reachable while this screen is open.
    LaunchedEffect(computers.size) {
        computers.forEach { if (it.hostId != RemoteLink.offHost) RemoteHub.connection(it.hostId)?.start() }
    }
    val conns = computers.map { RemoteHub.connection(it.hostId) }
    val overall = when {
        conns.any { it?.state == LinkState.Online } -> LinkVisual.Online
        conns.isEmpty() || conns.any { it?.trying == true } -> LinkVisual.Connecting
        else -> LinkVisual.Offline
    }

    Column(Modifier.fillMaxSize().background(DevxColors.Bg).statusBarsPadding()) {
        RemoteTopBar(stringResource(R.string.remote_title), onBack)
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item { LinkHero(overall, Modifier.padding(top = 8.dp)) }
            if (computers.isEmpty()) {
                item {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.remote_empty_title), style = DevxType.Title, color = DevxColors.TextPrimary, textAlign = TextAlign.Center)
                        Text(stringResource(R.string.remote_empty_body), style = DevxType.Body, color = DevxColors.TextSecondary, textAlign = TextAlign.Center)
                    }
                }
            } else {
                item {
                    SettingsGroup {
                        computers.forEachIndexed { i, c ->
                            if (i > 0) SettingsDivider()
                            ComputerRow(c, RemoteHub.connection(c.hostId)) { onOpen(c.hostId) }
                        }
                    }
                }
            }
            item { PillButton(stringResource(R.string.remote_add), onClick = onAdd) }
            if (computers.isNotEmpty()) item {
                val context = LocalContext.current
                SettingsGroup {
                    SettingsSwitchRow(
                        title = stringResource(R.string.remote_keepalive), subtitle = stringResource(R.string.remote_keepalive_hint), checked = Prefs.remoteKeepAlive,
                        onCheckedChange = { Prefs.saveRemoteKeepAlive(it); if (!it) RemoteService.stop(context) },
                    )
                }
            }
        }
        // The note about the encryption sits at the very bottom, out of the way.
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top,
        ) {
            Icon(DevxIcons.ShieldLock, null, tint = DevxColors.TextSecondary, modifier = Modifier.size(20.dp))
            Text(stringResource(R.string.remote_security_note), style = DevxType.Caption, color = DevxColors.TextSecondary)
        }
    }
}

@Composable
private fun ComputerRow(c: Computer, conn: RemoteConnection?, onClick: () -> Unit) {
    val visual = conn?.visual() ?: LinkVisual.Offline
    Row(
        Modifier.fillMaxWidth().pressable(scaleTo = 0.98f, haptic = false, onClick = onClick).padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.size(46.dp).clip(CircleShape).background(DevxColors.CardInner), contentAlignment = Alignment.Center) {
            Icon(DevxIcons.Desktop, null, tint = DevxColors.TextPrimary, modifier = Modifier.size(24.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(c.name, style = DevxType.Body, color = DevxColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val line = when {
                conn?.state == LinkState.Online -> stringResource(R.string.remote_online)
                conn?.trying == true -> stringResource(R.string.remote_connecting)
                else -> stringResource(R.string.remote_offline_seen, relativeTime(c.lastSeen))
            }
            Text(line, style = DevxType.Caption, color = DevxColors.TextSecondary, maxLines = 1)
        }
        StatusDot(visual)
        Icon(Icons.Outlined.ChevronRight, null, tint = DevxColors.TextSecondary, modifier = Modifier.size(22.dp))
    }
}

// ───────────────────────── adding a computer ─────────────────────────

private sealed interface PairStage {
    data object Scan : PairStage
    data class Reaching(val name: String) : PairStage
    data class Verify(val name: String, val sas: String) : PairStage
    data class Done(val name: String, val hostId: String) : PairStage
    data class Failed(val why: String) : PairStage
}

@Composable
fun PairScreen(onBack: () -> Unit, onDone: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var stage by remember { mutableStateOf<PairStage>(PairStage.Scan) }
    var asking by remember { mutableStateOf(false) }
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) permission.launch(Manifest.permission.CAMERA) }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val pending = PendingPairing.code

    fun begin(text: String) {
        val p = Pairing.parse(text)
        if (p == null) { stage = PairStage.Failed(context.getString(R.string.remote_bad_code)); return }
        stage = PairStage.Reaching(p.name)
        scope.launch(Dispatchers.IO) {
            try {
                val conn = RemoteHub.pair(p, Build.MODEL ?: "Phone") { sas -> stage = PairStage.Verify(p.name, sas) }
                stage = PairStage.Done(p.name, conn.computer.hostId)
                // Approvals and answers reach the phone as notifications: the system asks once.
                if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    withContext(Dispatchers.Main) { notifications.launch(Manifest.permission.POST_NOTIFICATIONS) }
                }
                delay(1900)
                onDone(conn.computer.hostId)
            } catch (e: Exception) {
                stage = PairStage.Failed(e.message ?: "")
            }
        }
    }

    LaunchedEffect(pending) {
        if (pending != null && stage == PairStage.Scan) { PendingPairing.code = null; begin(pending) }
    }

    Column(Modifier.fillMaxSize().background(DevxColors.Bg).statusBarsPadding()) {
        RemoteTopBar(stringResource(R.string.remote_add), onBack)
        AnimatedContent(
            stage, Modifier.fillMaxSize().navigationBarsPadding(), label = "pair",
            transitionSpec = { (fadeIn(tween(350)) + slideInVertically(tween(450)) { it / 14 }) togetherWith fadeOut(tween(200)) },
            contentKey = { it::class },
        ) { s ->
            when (s) {
                PairStage.Scan -> Column(Modifier.fillMaxSize().padding(horizontal = 20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    Text(stringResource(R.string.remote_scan_hint), style = DevxType.Body, color = DevxColors.TextSecondary, textAlign = TextAlign.Center)
                    Box(Modifier.fillMaxWidth().weight(1f, fill = false).height(340.dp).clip(RoundedCornerShape(32.dp)).background(DevxColors.Card), contentAlignment = Alignment.Center) {
                        if (granted) {
                            QrScanner(onCode = { if (stage == PairStage.Scan && it.startsWith("devx1:")) begin(it) })
                            ScanFrame(Modifier.size(230.dp))
                        } else {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(24.dp)) {
                                Icon(DevxIcons.Qrcode, null, tint = DevxColors.TextDisabled, modifier = Modifier.size(64.dp))
                                Text(stringResource(R.string.remote_camera_needed), style = DevxType.Body, color = DevxColors.TextSecondary, textAlign = TextAlign.Center)
                                PillButton(stringResource(R.string.remote_allow_camera), onClick = { permission.launch(Manifest.permission.CAMERA) }, filled = false)
                            }
                        }
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(stringResource(R.string.remote_scan_command), style = DevxType.Caption, color = DevxColors.TextSecondary, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                        PillButton(stringResource(R.string.remote_paste_code), onClick = { asking = true }, filled = false)
                    }
                    Spacer(Modifier.height(12.dp))
                }
                is PairStage.Reaching, is PairStage.Verify -> Column(Modifier.fillMaxSize().padding(horizontal = 20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    val name = (s as? PairStage.Reaching)?.name ?: (s as PairStage.Verify).name
                    LinkHero(LinkVisual.Connecting)
                    Spacer(Modifier.height(24.dp))
                    if (s is PairStage.Verify) {
                        Text(stringResource(R.string.remote_verify_title, name), style = DevxType.Title, color = DevxColors.TextPrimary, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(18.dp))
                        SasDigits(s.sas)
                        Spacer(Modifier.height(18.dp))
                        Text(stringResource(R.string.remote_verify_body), style = DevxType.Body, color = DevxColors.TextSecondary, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 12.dp))
                    } else {
                        Text(stringResource(R.string.remote_reaching, name), style = DevxType.Title, color = DevxColors.TextPrimary, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(8.dp))
                        Text(stringResource(R.string.remote_reaching_body), style = DevxType.Body, color = DevxColors.TextSecondary, textAlign = TextAlign.Center)
                    }
                }
                is PairStage.Done -> Column(Modifier.fillMaxSize().padding(horizontal = 20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    SuccessMark(size = 150.dp)
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(R.string.remote_paired, s.name), style = DevxType.Title, color = DevxColors.TextPrimary, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(6.dp))
                    Text(stringResource(R.string.remote_paired_body), style = DevxType.Body, color = DevxColors.TextSecondary, textAlign = TextAlign.Center)
                }
                is PairStage.Failed -> Column(Modifier.fillMaxSize().padding(horizontal = 20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    LinkHero(LinkVisual.Offline)
                    Spacer(Modifier.height(20.dp))
                    Text(stringResource(R.string.remote_pair_failed), style = DevxType.Title, color = DevxColors.TextPrimary, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(8.dp))
                    Text(friendly(s.why), style = DevxType.Body, color = DevxColors.TextSecondary, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(24.dp))
                    PillButton(stringResource(R.string.remote_try_again), onClick = { stage = PairStage.Scan })
                }
            }
        }
    }

    if (asking) PasteCodeDialog(onDismiss = { asking = false }, onCode = { asking = false; begin(it) })
}

@Composable
internal fun friendly(raw: String): String {
    val r = raw.lowercase()
    return when {
        "nobody answered" in r -> stringResource(R.string.remote_err_nobody)
        "refused" in r -> stringResource(R.string.remote_err_refused)
        "token" in r -> stringResource(R.string.remote_err_token)
        "unknown device" in r || "removed" in r -> stringResource(R.string.remote_err_removed)
        "not the one" in r -> stringResource(R.string.remote_err_wrong_host)
        raw.isBlank() -> stringResource(R.string.remote_err_generic)
        "no answer" in r || "timeout" in r || "does not answer" in r || "closed" in r || "no connection" in r -> stringResource(R.string.remote_err_unreachable)
        else -> raw
    }
}

@Composable
private fun PasteCodeDialog(onDismiss: () -> Unit, onCode: (String) -> Unit) {
    val context = LocalContext.current
    var text by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        // A code that was copied a moment ago is already there.
        val clip = (context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)?.primaryClip?.getItemAt(0)?.text?.toString().orEmpty()
        if (clip.startsWith("devx1:")) text = clip
    }
    RemoteDialog(stringResource(R.string.remote_paste_code), onDismiss) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(stringResource(R.string.remote_paste_hint), style = DevxType.Caption, color = DevxColors.TextSecondary)
            Box(Modifier.fillMaxWidth().height(110.dp).clip(RoundedCornerShape(18.dp)).border(1.dp, DevxColors.Outline, RoundedCornerShape(18.dp)).padding(14.dp)) {
                BasicTextField(text, { text = it }, textStyle = DevxType.Mono.copy(color = DevxColors.TextPrimary), cursorBrush = SolidColor(DevxColors.Accent), modifier = Modifier.fillMaxSize())
                if (text.isEmpty()) Text("devx1:…", style = DevxType.Mono, color = DevxColors.TextDisabled)
            }
            PillButton(stringResource(R.string.remote_connect), onClick = { onCode(text.trim()) }, enabled = text.trim().startsWith("devx1:"))
        }
    }
}

/** The app's dialog with room at the sides for the content. */
@Composable
internal fun RemoteDialog(title: String, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    DevxDialog(title, onDismiss) { Column(Modifier.padding(horizontal = 20.dp)) { content() } }
}
