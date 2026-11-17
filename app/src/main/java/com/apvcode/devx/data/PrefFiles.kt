package com.apvcode.devx.data

/**
 * The names of every SharedPreferences file of the app, in one place. A backup takes [backedUp]; each of the others is left out on
 * purpose (see the note on it). A new file must be added here, and the test of this list says when one is forgotten.
 */
object PrefFiles {
    const val SETTINGS = "settings"
    const val APP = "devx_prefs"
    const val SKILLS = "skills"
    const val CARDS = "cards"
    const val RICH_STATE = "rich_state"
    const val APP_LOCK = "applock"

    /** The API keys, encrypted with the phone's keystore: they travel only inside the password-locked block of a backup. */
    const val SECRETS = "secrets"

    /** Where the weekly copy goes: a folder permission that belongs to this phone. */
    const val AUTO_BACKUP = "autobackup"

    /** The volume that was lowered while listening: a state of this phone, not a setting. */
    const val EARCON = "earcon"

    /** Do Not Disturb that the assistant turned on until a time: a state of this phone. */
    const val DND = "dnd"

    val backedUp = listOf(SETTINGS, APP, SKILLS, CARDS, RICH_STATE, APP_LOCK)
    val leftOut = listOf(SECRETS, AUTO_BACKUP, EARCON, DND)

    fun fileName(name: String) = "$name.xml"
}
