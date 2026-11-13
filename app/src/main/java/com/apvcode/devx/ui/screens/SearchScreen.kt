package com.apvcode.devx.ui.screens

import com.apvcode.devx.ui.components.pressable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.apvcode.devx.R
import com.apvcode.devx.model.ChatStatus
import com.apvcode.devx.model.ChatSummary
import com.apvcode.devx.ui.components.DevxIcons
import com.apvcode.devx.ui.theme.DevxColors
import com.apvcode.devx.ui.theme.DevxType
import kotlinx.coroutines.delay

/**
 * Full-screen list of all chats with a search field at the bottom (thumb reach), opened from the
 * menu. It slides up over everything; the keyboard opens a moment later so the motion stays smooth.
 */
@Composable
fun SearchScreen(
    chats: List<ChatSummary>,
    search: (String) -> List<com.apvcode.devx.data.SearchHit>,
    onClose: () -> Unit,
    onOpenChat: (id: String, messageId: String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    // The words are looked for in every message of every chat, a moment after the last key and off the main thread.
    val hits by androidx.compose.runtime.produceState(emptyList<com.apvcode.devx.data.SearchHit>(), query) {
        val q = query.trim()
        if (q.isEmpty()) { value = emptyList(); return@produceState }
        delay(140)
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { search(q) }
    }
    val byId = remember(chats.toList()) { chats.associateBy { it.id } }
    val searching = query.isNotBlank()

    LaunchedEffect(Unit) {
        delay(340)
        focus.requestFocus()
        keyboard?.show()
    }

    Column(
        modifier
            .fillMaxSize()
            .background(DevxColors.Bg)
            // Swallow touches so nothing underneath (the chat, the menu) reacts through this screen.
            .pointerInput(Unit) { detectTapGestures { } }
            .statusBarsPadding()
            .imePadding(),
    ) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 20.dp, bottom = 8.dp),
            ) {
                if (!searching) {
                    item {
                        Text(
                            stringResource(R.string.search_recent),
                            style = DevxType.Section,
                            color = DevxColors.TextSecondary,
                            modifier = Modifier.padding(start = 8.dp, bottom = 8.dp),
                        )
                    }
                }
                if (!searching) {
                    items(chats, key = { it.id }) { chat -> ChatRow(chat) { onOpenChat(chat.id, null) } }
                } else {
                    items(hits, key = { it.chatId }) { hit ->
                        byId[hit.chatId]?.let { chat -> HitRow(chat, hit) { onOpenChat(chat.id, hit.messageId) } }
                    }
                }
            }
            if (if (searching) hits.isEmpty() else chats.isEmpty()) {
                Text(
                    stringResource(R.string.search_empty),
                    style = DevxType.Body,
                    color = DevxColors.TextSecondary,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                Modifier
                    .weight(1f)
                    .clip(CircleShape)
                    .background(DevxColors.Card)
                    .padding(horizontal = 18.dp, vertical = 15.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(DevxIcons.ChatSearch, null, tint = DevxColors.TextSecondary, modifier = Modifier.size(24.dp))
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    textStyle = DevxType.Body.copy(color = DevxColors.TextPrimary),
                    cursorBrush = SolidColor(DevxColors.Accent),
                    modifier = Modifier.weight(1f).focusRequester(focus),
                    decorationBox = { inner ->
                        Box {
                            if (query.isEmpty()) {
                                Text(stringResource(R.string.search_placeholder), style = DevxType.Body, color = DevxColors.TextSecondary)
                            }
                            inner()
                        }
                    },
                )
            }
            Box(
                Modifier
                    .size(54.dp)
                    .pressable(onClick = onClose)
                    .clip(CircleShape)
                    .background(DevxColors.Card),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.Close, stringResource(R.string.cd_close), tint = DevxColors.TextPrimary, modifier = Modifier.size(24.dp))
            }
        }
    }
}

@Composable
private fun ChatRow(chat: ChatSummary, onClick: () -> Unit) {
    val (icon, tint) = when (chat.status) {
        ChatStatus.Replied -> DevxIcons.ChatAlert to DevxColors.TextPrimary
        ChatStatus.Waiting -> DevxIcons.ChatQuestion to DevxColors.Warning
        else -> DevxIcons.ChatList to DevxColors.TextSecondary
    }
    Row(
        Modifier
            .fillMaxWidth()
            .pressable(scaleTo = 0.97f, haptic = false, onClick = onClick)
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
            .padding(horizontal = 8.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(24.dp))
        Text(
            chat.title,
            style = DevxType.Body,
            color = DevxColors.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(chat.time, style = DevxType.Caption, color = DevxColors.TextSecondary)
    }
}

/** A chat that has the words: its title, and a line of what was said with the words marked. */
@Composable
private fun HitRow(chat: ChatSummary, hit: com.apvcode.devx.data.SearchHit, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .pressable(scaleTo = 0.97f, haptic = false, onClick = onClick)
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
            .padding(horizontal = 8.dp, vertical = 13.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Icon(if (chat.locked) DevxIcons.Lock else DevxIcons.ChatList, null, tint = DevxColors.TextSecondary, modifier = Modifier.size(24.dp))
            Text(chat.title, style = DevxType.Body, color = DevxColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text(chat.time, style = DevxType.Caption, color = DevxColors.TextSecondary)
        }
        hit.snippet?.let { line ->
            val marked = androidx.compose.ui.text.buildAnnotatedString {
                append(line.substring(0, hit.matchStart))
                withStyle(androidx.compose.ui.text.SpanStyle(color = DevxColors.TextPrimary, background = DevxColors.TextPrimary.copy(alpha = 0.16f), fontWeight = androidx.compose.ui.text.font.FontWeight.Medium)) {
                    append(line.substring(hit.matchStart, hit.matchEnd))
                }
                append(line.substring(hit.matchEnd))
            }
            Text(marked, style = DevxType.Caption, color = DevxColors.TextSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 40.dp))
            if (hit.count > 1) {
                Text(stringResource(R.string.search_more, hit.count), style = DevxType.Caption, color = DevxColors.TextDisabled, modifier = Modifier.padding(start = 40.dp))
            }
        }
    }
}
