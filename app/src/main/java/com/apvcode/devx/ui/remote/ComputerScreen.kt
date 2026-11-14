package com.apvcode.devx.ui.remote

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.apvcode.devx.R
import com.apvcode.devx.remote.LinkState
import com.apvcode.devx.remote.RemoteConnection
import com.apvcode.devx.remote.RemoteHub
import com.apvcode.devx.ui.components.ChoiceDialog
import com.apvcode.devx.ui.components.CircleIconButton
import com.apvcode.devx.ui.components.DevxIcons
import com.apvcode.devx.ui.components.DevxTextField
import com.apvcode.devx.ui.components.PillButton
import com.apvcode.devx.ui.components.SettingsDivider
import com.apvcode.devx.ui.components.SettingsGroup
import com.apvcode.devx.ui.components.pressable
import com.apvcode.devx.ui.theme.DevxColors
import com.apvcode.devx.ui.theme.DevxType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** One saved chat on the computer, as the list shows it. */
class RemoteChatInfo(val id: String, val title: String, val cwd: String?, val updatedAt: Long, val messages: Int, val open: Boolean, val running: Boolean, val snippet: String, val status: String? = null, val terminal: Boolean = false)

private fun parseSessions(o: JSONObject): List<RemoteChatInfo> {
    val a = o.optJSONArray("sessions") ?: return emptyList()
    return List(a.length()) { i -> a.getJSONObject(i).let { s ->
        RemoteChatInfo(s.getString("id"), s.optString("title", "Chat"), s.optString("cwd").takeIf { it.isNotEmpty() && it != "null" }, s.optLong("updatedAt"), s.optInt("messages"), s.optBoolean("open"), s.optBoolean("running"), s.optString("snippet"), s.optString("status").takeIf { it.isNotEmpty() && it != "null" }, s.optBoolean("terminal"))
    } }
}

@Composable
fun ComputerScreen(hostId: String, onBack: () -> Unit, onOpenSession: (String) -> Unit, onNewSession: (String) -> Unit, onForgotten: () -> Unit, openPicker: Boolean = false, onPickerShown: () -> Unit = {}) {
    val conn = remember(hostId) { RemoteHub.connection(hostId) }
    if (conn == null) { LaunchedEffect(Unit) { onForgotten() }; return }
    LaunchedEffect(conn) { conn.start(); conn.nudge() }
    ClockTicker()
    val scope = rememberCoroutineScope()
    val sessions = remember { mutableStateListOf<RemoteChatInfo>() }
    var query by remember { mutableStateOf("") }
    var loaded by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var menu by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }
    LaunchedEffect(openPicker) { if (openPicker) { picking = true; onPickerShown() } }
    var deleting by remember { mutableStateOf<RemoteChatInfo?>(null) }
    var forgetting by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    var starting by remember { mutableStateOf(false) }
    var startError by remember { mutableStateOf<String?>(null) }

    suspend fun refresh() {
        try {
            val r = withContext(Dispatchers.IO) { conn.call("sessions.list", JSONObject().put("q", query.trim())) }
            val list = parseSessions(r)
            sessions.clear(); sessions.addAll(list)
            loaded = true; error = null
        } catch (e: Exception) {
            error = e.message
        }
    }
    // The list is asked for when the link is up, when the search words change, and whenever the computer says it changed.
    LaunchedEffect(conn.state, query) { if (conn.state == LinkState.Online) { if (query.isNotBlank()) delay(250); refresh() } }
    LaunchedEffect(conn) { conn.global.collectLatest { if (it.optString("e") == "sessions") { delay(300); if (conn.state == LinkState.Online) refresh() } } }

    // Idle is only the first moment, before the connection has started.
    val waiting = conn.trying || conn.state == LinkState.Idle
    val visual = if (conn.state == LinkState.Idle) LinkVisual.Connecting else conn.visual()
    Box(Modifier.fillMaxSize().background(DevxColors.Bg)) {
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        RemoteTopBar(
            conn.computer.name, onBack,
            subtitle = when {
                conn.state == LinkState.Online -> stringResource(R.string.remote_online) + (conn.host?.optString("version")?.takeIf { it.isNotEmpty() }?.let { " · devx $it" } ?: "")
                waiting -> stringResource(R.string.remote_connecting)
                else -> stringResource(R.string.remote_offline_seen, relativeTime(conn.computer.lastSeen))
            },
            status = visual,
            trailing = { CircleIconButton(Icons.Outlined.MoreVert, null, { menu = true }) },
        )
        LazyColumn(Modifier.fillMaxSize().navigationBarsPadding(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (conn.state != LinkState.Online) {
                item {
                    Column(Modifier.fillMaxWidth().animateContentSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        LinkHero(visual, Modifier.padding(top = 4.dp))
                        if (waiting) {
                            Text(stringResource(R.string.remote_reaching_computer), style = DevxType.Body, color = DevxColors.TextSecondary, textAlign = TextAlign.Center)
                            // Which of the two tries this is.
                            Text(
                                stringResource(R.string.remote_attempt, (conn.attempts + 1).coerceAtMost(com.apvcode.devx.remote.MAX_TRIES), com.apvcode.devx.remote.MAX_TRIES),
                                style = DevxType.Caption, color = DevxColors.TextDisabled, textAlign = TextAlign.Center,
                            )
                        } else {
                            Text(stringResource(R.string.remote_offline_title), style = DevxType.Title, color = DevxColors.TextPrimary)
                            Text(stringResource(R.string.remote_offline_seen_long, relativeTime(conn.computer.lastSeen)), style = DevxType.Body, color = DevxColors.TextPrimary, textAlign = TextAlign.Center)
                            Text(stringResource(R.string.remote_offline_body, conn.computer.name, com.apvcode.devx.remote.MAX_TRIES), style = DevxType.Body, color = DevxColors.TextSecondary, textAlign = TextAlign.Center)
                            if (conn.revoked) Text(stringResource(R.string.remote_err_removed), style = DevxType.Caption, color = DevxColors.Danger, textAlign = TextAlign.Center)
                            PillButton(stringResource(R.string.remote_try_now), onClick = { conn.nudge() })
                        }
                    }
                }
            } else {
                item {
                    PillButton(stringResource(R.string.remote_new_session), onClick = { picking = true })
                }
                if (startError != null) item { Text(startError!!, style = DevxType.Caption, color = DevxColors.Danger, modifier = Modifier.padding(horizontal = 12.dp)) }
                item { SearchField(query, { query = it }) }
                if (loaded && sessions.isEmpty()) {
                    item {
                        Text(if (query.isBlank()) stringResource(R.string.remote_no_sessions) else stringResource(R.string.search_empty), style = DevxType.Body, color = DevxColors.TextSecondary, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 36.dp))
                    }
                }
                if (error != null) item { Text(friendly(error!!), style = DevxType.Caption, color = DevxColors.Danger, modifier = Modifier.padding(horizontal = 12.dp)) }
                item {
                    if (sessions.isNotEmpty()) SettingsGroup {
                        sessions.forEachIndexed { i, s ->
                            if (i > 0) SettingsDivider()
                            SessionRow(s, onClick = { onOpenSession(s.id) }, onLong = { deleting = s })
                        }
                    }
                }
            }
        }
    }
    CornerMenu(
        open = menu, onDismiss = { menu = false },
        items = listOf(
            CornerItem(DevxIcons.Plug, stringResource(R.string.remote_add_address)) { adding = true },
            CornerItem(DevxIcons.Unlink, stringResource(R.string.remote_forget), danger = true) { forgetting = true },
        ),
    )
    ConfirmCard(
        open = forgetting, onDismiss = { forgetting = false },
        icon = DevxIcons.Unlink, title = stringResource(R.string.remote_forget), body = stringResource(R.string.remote_forget_body, conn.computer.name),
        confirm = stringResource(R.string.remote_forget_confirm), cancel = stringResource(R.string.dialog_cancel),
        onConfirm = { forgetting = false; RemoteHub.forget(hostId); onForgotten() },
    )
    }

    // Nothing is created on the computer yet: the chat opens empty and exists after the first message.
    if (picking) FolderPicker(conn, onDismiss = { picking = false }, onPick = { cwd -> picking = false; onNewSession(cwd) })
    deleting?.let { s ->
        RemoteDialog(stringResource(R.string.dialog_delete_chat), { deleting = null }) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.remote_delete_body, s.title), style = DevxType.Body, color = DevxColors.TextSecondary)
                PillButton(stringResource(R.string.menu_delete), onClick = {
                    deleting = null
                    scope.launch { runCatching { withContext(Dispatchers.IO) { conn.call("session.delete", JSONObject().put("id", s.id)) } }; refresh() }
                }, container = DevxColors.Danger, content = androidx.compose.ui.graphics.Color.White)
                PillButton(stringResource(R.string.dialog_cancel), onClick = { deleting = null }, filled = false)
            }
        }
    }
    if (adding) AddAddressDialog(conn, onDismiss = { adding = false })
}

@Composable
private fun SearchField(value: String, onChange: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().clip(CircleShape).background(DevxColors.Card).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(DevxIcons.ChatSearch, null, tint = DevxColors.TextSecondary, modifier = Modifier.size(22.dp))
        Box(Modifier.weight(1f)) {
            BasicTextField(value, onChange, singleLine = true, textStyle = DevxType.Body.copy(color = DevxColors.TextPrimary), cursorBrush = SolidColor(DevxColors.Accent), modifier = Modifier.fillMaxWidth())
            if (value.isEmpty()) Text(stringResource(R.string.search_placeholder), style = DevxType.Body, color = DevxColors.TextDisabled)
        }
    }
}

@Composable
private fun SessionRow(s: RemoteChatInfo, onClick: () -> Unit, onLong: () -> Unit) {
    Row(Modifier.fillMaxWidth().pressable(scaleTo = 0.98f, haptic = false, onLongClick = onLong, onClick = onClick).padding(horizontal = 18.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Text(s.title, style = DevxType.Body, color = DevxColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val where = s.cwd?.trimEnd('/', '\\')?.substringAfterLast('/')?.substringAfterLast('\\').orEmpty()
            Text(listOfNotNull(where.takeIf { it.isNotEmpty() }, relativeTime(s.updatedAt)).joinToString(" · "), style = DevxType.Caption, color = DevxColors.TextSecondary, maxLines = 1)
            if (s.snippet.isNotEmpty()) Text(s.snippet, style = DevxType.Caption, color = DevxColors.TextDisabled, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (s.terminal) Icon(DevxIcons.Terminal, stringResource(R.string.remote_in_terminal), tint = DevxColors.TextSecondary, modifier = Modifier.size(18.dp))
        // Green: the agent finished and nobody has read it. Orange: it waits for you (a question, a permission). Red: the provider failed.
        val tone = when (s.status) { "unread" -> DevxColors.Success; "ask" -> DevxColors.Warning; "error" -> DevxColors.Danger; else -> null }
        if (s.running && tone == null) Text(stringResource(R.string.remote_working), style = DevxType.Caption, color = DevxColors.TextSecondary)
        if (tone != null) Box(Modifier.size(11.dp).clip(CircleShape).background(tone))
        Icon(Icons.Outlined.ChevronRight, null, tint = DevxColors.TextSecondary, modifier = Modifier.size(20.dp))
    }
}

/** Choose the folder on the computer where a new chat works: recent ones first, or browse. */
@Composable
internal fun FolderPicker(conn: RemoteConnection, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    var path by remember { mutableStateOf<String?>(null) }
    var dirs by remember { mutableStateOf<List<String>>(emptyList()) }
    var parent by remember { mutableStateOf<String?>(null) }
    var recent by remember { mutableStateOf<List<String>>(emptyList()) }
    var current by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(path) {
        try {
            val r = withContext(Dispatchers.IO) { conn.call("dirs.list", JSONObject().apply { path?.let { put("path", it) } }) }
            current = r.optString("path")
            parent = r.optString("parent").takeIf { it.isNotEmpty() && it != "null" }
            dirs = r.optJSONArray("dirs")?.let { a -> List(a.length()) { a.getString(it) } } ?: emptyList()
            if (recent.isEmpty()) recent = r.optJSONArray("recent")?.let { a -> List(a.length()) { a.getString(it) } } ?: emptyList()
            error = null
        } catch (e: Exception) { error = e.message }
    }
    RemoteDialog(stringResource(R.string.remote_choose_folder), onDismiss) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(current, style = DevxType.Mono, color = DevxColors.TextSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Column(Modifier.heightIn(max = 320.dp).androidxVerticalScroll(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (path == null && recent.isNotEmpty()) {
                    Text(stringResource(R.string.remote_recent), style = DevxType.Section, color = DevxColors.TextSecondary, modifier = Modifier.padding(top = 4.dp))
                    recent.forEach { r -> FolderLine(r.trimEnd('/', '\\').substringAfterLast('/').substringAfterLast('\\').ifEmpty { r }, r) { onPick(r) } }
                    Text(stringResource(R.string.remote_browse), style = DevxType.Section, color = DevxColors.TextSecondary, modifier = Modifier.padding(top = 10.dp))
                }
                parent?.let { FolderLine("..", it) { path = it } }
                dirs.forEach { d -> FolderLine(d, null) { path = current.trimEnd('/', '\\') + (if (current.contains('\\')) "\\" else "/") + d } }
            }
            if (error != null) Text(friendly(error!!), style = DevxType.Caption, color = DevxColors.Danger)
            PillButton(stringResource(R.string.remote_start_here), onClick = { onPick(current) }, enabled = current.isNotEmpty())
        }
    }
}

@Composable
private fun Modifier.androidxVerticalScroll(): Modifier = this.then(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()))

@Composable
private fun FolderLine(name: String, full: String?, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().pressable(scaleTo = 0.98f, haptic = false, onClick = onClick).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(DevxIcons.Folder, null, tint = DevxColors.TextSecondary, modifier = Modifier.size(22.dp))
        Column(Modifier.weight(1f)) {
            Text(name, style = DevxType.Body, color = DevxColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (full != null && name != "..") Text(full, style = DevxType.Caption, color = DevxColors.TextDisabled, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun AddAddressDialog(conn: RemoteConnection, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    RemoteDialog(stringResource(R.string.remote_add_address), onDismiss) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.remote_add_address_hint), style = DevxType.Caption, color = DevxColors.TextSecondary)
            DevxTextField(stringResource(R.string.remote_address), text, { text = it }, placeholder = "100.64.0.5:47821")
            val ok = Regex("^[A-Za-z0-9.\\-]+(:\\d{2,5})?$").matches(text.trim())
            PillButton(stringResource(R.string.dialog_save), enabled = ok, onClick = {
                val a = text.trim().let { if (it.contains(':')) it else "$it:47821" }
                val updated = conn.computer.copy(addresses = (listOf(a) + conn.computer.addresses).distinct())
                RemoteHub.store.put(updated)
                conn.computer = updated
                conn.nudge()
                onDismiss()
            })
        }
    }
}
