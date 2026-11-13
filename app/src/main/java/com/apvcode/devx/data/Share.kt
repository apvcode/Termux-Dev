package com.apvcode.devx.data

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** What another app shared with Devx ("Share" → Devx): the words and/or the files, until the new chat has taken them. */
object PendingShare {
    class Item(val text: String, val uris: List<Uri>)

    var item by mutableStateOf<Item?>(null)
}

/**
 * "Ask devx" from the quick-settings tile or the widget: a fresh chat opens with the keyboard up. [request] is taken by the
 * app (which opens the chat and sets [focus]); [focus] is taken by the message field once it is on the screen.
 */
object PendingAsk {
    var request by mutableStateOf(false)
    var focus by mutableStateOf(false)

    /** The keyboard is asked for in the chat that is open (a pull upwards at the end of it): no new chat, no waiting. */
    var keyboard by mutableStateOf(false)
}
