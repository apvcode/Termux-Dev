package com.apvcode.devx.ui.components

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.contextmenu.builder.item
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuItem
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuKeys
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuSeparator
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuSession
import androidx.compose.foundation.text.contextmenu.modifier.appendTextContextMenuComponents
import androidx.compose.foundation.text.contextmenu.modifier.filterTextContextMenuComponents
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.apvcode.devx.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Puts "Ask Devx" into the menu that floats over selected text (next to Copy and Select all) for all the
 * text inside: [onAsk] gets the words that were selected.
 *
 * The selected words are not handed out, only copied. So the menu's own Copy is run and the words are taken
 * from the clipboard (where they stay, as after any Copy).
 */
@Composable
fun ProvideAskToolbar(onAsk: (String) -> Unit, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val label = stringResource(R.string.ask_devx)
    val latest = rememberUpdatedState(onAsk)
    val focus = LocalFocusManager.current
    // The menu's own Copy, noticed as the menu is built, so that "Ask Devx" can press it.
    val copy = remember { arrayOfNulls<(TextContextMenuSession) -> Unit>(1) }
    val modifier = Modifier
        .fillMaxSize()
        .filterTextContextMenuComponents { component ->
            if (component.key == TextContextMenuKeys.CopyKey && component is TextContextMenuItem) copy[0] = component.onClick
            // Only what a message needs: Copy, Select all and ours. The system's extras ("Read aloud" and the like) are left out.
            component.key == TextContextMenuKeys.CopyKey || component.key == TextContextMenuKeys.SelectAllKey ||
                component.key == ASK_KEY || component is TextContextMenuSeparator
        }
        .appendTextContextMenuComponents {
            item(key = ASK_KEY, label = label) {
                val session = this
                grab(context, scope, { copy[0]?.invoke(session) }) {
                    latest.value(it)
                    // Letting go of the focus is what makes the text let go of its selection and its menu.
                    focus.clearFocus(force = true)
                }
                close()
            }
        }
    Box(modifier) { content() }
}

private const val ASK_KEY = "devx-ask"

private fun grab(context: Context, scope: CoroutineScope, copy: () -> Unit, deliver: (String) -> Unit) {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    val stamp = clipboard.primaryClipDescription?.timestamp ?: 0L
    copy()
    scope.launch {
        var text: String? = null
        var tries = 0
        while (text == null && tries++ < 15) {
            val now = clipboard.primaryClipDescription?.timestamp ?: 0L
            val clip = clipboard.primaryClip
            if (now != stamp && clip != null && clip.itemCount > 0) text = clip.getItemAt(0).coerceToText(context)?.toString()
            else delay(40)
        }
        text?.takeIf { it.isNotBlank() }?.let(deliver)
    }
}
