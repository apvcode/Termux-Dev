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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.apvcode.devx.R
import androidx.compose.ui.platform.ClipboardManager as ClipboardManagerCompose
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import android.os.Build
import androidx.compose.ui.text.AnnotatedString

/**
 * Puts "Ask Devx" into the menu that floats over selected text (next to Copy and Select all) for all the
 * text inside: [onAsk] gets the words that were selected.
 *
 * The selected words are not handed out, only copied. So the menu's own Copy is run, and what it copies is caught on the way: the
 * clipboard the selection writes to is a stand-in that, while "Ask Devx" is working, keeps the words for itself instead of putting
 * them into the phone's clipboard. Nothing of what was picked reaches the clipboard (or the keyboard's clipboard history). A Copy the
 * person presses themself goes to the real clipboard as always.
 */
@Composable
fun ProvideAskToolbar(onAsk: (String) -> Unit, extraLabel: String? = null, onExtra: (String) -> Unit = {}, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val label = stringResource(R.string.ask_devx)
    val latest = rememberUpdatedState(onAsk)
    val latestExtra = rememberUpdatedState(onExtra)
    val focus = LocalFocusManager.current
    // The menu's own Copy, noticed as the menu is built, so that "Ask Devx" can press it.
    val copy = remember { arrayOfNulls<(TextContextMenuSession) -> Unit>(1) }
    val real = LocalClipboard.current
    @Suppress("DEPRECATION")
    val realManager = LocalClipboardManager.current
    val catcher = remember { Catcher() }
    val clipboard = remember(real) { CatchingClipboard(real, catcher, context) }
    @Suppress("DEPRECATION")
    val manager = remember(realManager) { CatchingManager(realManager, catcher) }
    val modifier = Modifier
        .fillMaxSize()
        .filterTextContextMenuComponents { component ->
            if (component.key == TextContextMenuKeys.CopyKey && component is TextContextMenuItem) copy[0] = component.onClick
            // Only what a message needs: Copy, Select all and ours. The system's extras ("Read aloud" and the like) are left out.
            component.key == TextContextMenuKeys.CopyKey || component.key == TextContextMenuKeys.SelectAllKey ||
                component.key == ASK_KEY || component.key == EXTRA_KEY || component is TextContextMenuSeparator
        }
        .appendTextContextMenuComponents {
            // The words that were picked go to [deliver]; see the note above on how they are caught.
            fun TextContextMenuSession.pick(deliver: (String) -> Unit) {
                val session = this
                val wanted = CompletableDeferred<String?>()
                catcher.waiting = wanted
                copy[0]?.invoke(session)
                scope.launch {
                    var text = withTimeoutOrNull(1_200) { wanted.await() }
                    catcher.waiting = null
                    // The selection copied some other way than through the stand-in: the clipboard is read, and put back as it was.
                    if (text == null) text = grab(context, { copy[0]?.invoke(session) })
                    text?.takeIf { it.isNotBlank() }?.let {
                        deliver(it)
                        // Letting go of the focus is what makes the text let go of its selection and its menu.
                        focus.clearFocus(force = true)
                    }
                }
                close()
            }
            item(key = ASK_KEY, label = label) { pick { latest.value(it) } }
            if (extraLabel != null) item(key = EXTRA_KEY, label = extraLabel) { pick { latestExtra.value(it) } }
        }
    @Suppress("DEPRECATION")
    CompositionLocalProvider(LocalClipboard provides clipboard, LocalClipboardManager provides manager) {
        Box(modifier) { content() }
    }
}

private const val ASK_KEY = "devx-ask"
private const val EXTRA_KEY = "devx-extra"

/** Where the words of "Ask Devx" are caught; null when nothing is waiting for them. */
private class Catcher {
    @Volatile var waiting: CompletableDeferred<String?>? = null

    fun take(text: String?): Boolean {
        val w = waiting ?: return false
        w.complete(text)
        return true
    }
}

/** The clipboard the selected text sees: it writes into the catcher while "Ask Devx" waits, and into the real one otherwise. */
private class CatchingClipboard(private val real: Clipboard, private val catcher: Catcher, private val context: Context) : Clipboard {
    override suspend fun getClipEntry(): ClipEntry? = real.getClipEntry()

    override suspend fun setClipEntry(clipEntry: ClipEntry?) {
        val clip = clipEntry?.clipData
        val text = if (clip != null && clip.itemCount > 0) clip.getItemAt(0).coerceToText(context)?.toString() else null
        if (!catcher.take(text)) real.setClipEntry(clipEntry)
    }

    override val nativeClipboard get() = real.nativeClipboard
}

/** The same, for the older way a selection copies (the clipboard manager). */
@Suppress("DEPRECATION")
private class CatchingManager(private val real: ClipboardManagerCompose, private val catcher: Catcher) : ClipboardManagerCompose {
    override fun setText(annotatedString: AnnotatedString) {
        if (!catcher.take(annotatedString.text)) real.setText(annotatedString)
    }

    override fun getText(): AnnotatedString? = real.getText()

    override fun getClip(): ClipEntry? = real.getClip()

    override fun setClip(clipEntry: ClipEntry?) {
        val clip = clipEntry?.clipData
        val text = if (clip != null && clip.itemCount > 0) clip.getItemAt(0).text?.toString() else null
        if (!catcher.take(text)) real.setClip(clipEntry)
    }

    override fun hasText(): Boolean = real.hasText()

    override val nativeClipboard get() = real.nativeClipboard
}

/** The fallback: copy, read the clipboard, give it back as it was. */
private suspend fun grab(context: Context, copy: () -> Unit): String? {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return null
    val stamp = clipboard.primaryClipDescription?.timestamp ?: 0L
    val before = clipboard.primaryClip
    copy()
    var text: String? = null
    var tries = 0
    while (text == null && tries++ < 15) {
        val now = clipboard.primaryClipDescription?.timestamp ?: 0L
        val clip = clipboard.primaryClip
        if (now != stamp && clip != null && clip.itemCount > 0) text = clip.getItemAt(0).coerceToText(context)?.toString()
        else delay(40)
    }
    runCatching {
        if (before != null) clipboard.setPrimaryClip(before)
        else if (Build.VERSION.SDK_INT >= 28) clipboard.clearPrimaryClip()
    }
    return text
}
