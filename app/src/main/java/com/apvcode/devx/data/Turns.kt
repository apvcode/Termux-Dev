package com.apvcode.devx.data

/** Where a message stands among its alternatives (the answers made again, the messages edited): "2 of 3", or null when it has none. */
fun Chat.branchPosition(msg: Msg): Pair<Int, Int>? {
    val list = siblings(msg).sortedBy { it.createdAt }
    return if (list.size > 1) (list.indexOfFirst { it.id == msg.id } + 1) to list.size else null
}

/** What the model is told after a connection broke in the middle of its answer: what it had already done, and to go on from there. */
fun continuationNote(done: Msg): String {
    val steps = done.steps.joinToString("\n") { st ->
        "- " + st.kind.name.lowercase() + (if (st.detail.isNotBlank()) ": " + st.detail else "") + (if (st.note.isNotBlank()) " (" + st.note + ")" else "")
    }
    return "(The connection broke while you were answering." +
        (if (steps.isNotEmpty()) " The steps you had already taken:\n$steps\n" else " ") +
        "Continue from where you stopped. Do not repeat what is done or what you have already said.)"
}

/** A user turn that tells the model to go on: joined to the last one when that is a user turn too (turns must alternate). */
fun MutableList<WireMessage>.addContinuation(note: String) {
    val last = lastOrNull()
    if (last != null && last.role == "user") {
        this[lastIndex] = WireMessage("user", last.text + "\n\n" + note, last.images, last.documents)
    } else {
        this += WireMessage("user", note)
    }
}
