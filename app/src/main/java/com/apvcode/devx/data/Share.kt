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
