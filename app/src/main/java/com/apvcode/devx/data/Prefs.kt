package com.apvcode.devx.data

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

enum class ThemeMode { System, Black, Gray, Light }

enum class AppLanguage(val tag: String?) { System(null), English("en"), Russian("ru") }

/** Small personal settings: nickname, theme, language. Read on start, changed from Settings. */
object Prefs {
    private var store: SharedPreferences? = null
    internal val raw: SharedPreferences? get() = store

    var nickname by mutableStateOf("")
        private set
    var theme by mutableStateOf(ThemeMode.Black)
        private set
    var language by mutableStateOf(AppLanguage.System)
        private set

    /** The language the person speaks to the microphone in (a tag like "ru-RU"); empty follows the language of the app. */
    var voiceLanguage by mutableStateOf("")
        private set
    var onboarded by mutableStateOf(false)
        private set

    /** A coloured light at the top of the chat while an answer is awaited. Off until switched on. */
    var thinkingGlow by mutableStateOf(false)
        private set

    /** Lets the assistant search the web and read pages. On until switched off. */
    var webSearch by mutableStateOf(true)
        private set

    /** Lets the assistant make files, unpack and pack archives and APKs, and edit pictures. On until switched off. */
    var workTools by mutableStateOf(true)
        private set

    /** Lets the assistant run shell commands and Python inside the app. On until switched off. */
    var terminal by mutableStateOf(true)
        private set

    /** Lets the assistant act on the phone: alarms, timers, reminders, apps, contacts, calendar, music. On until switched off. */
    var phoneTools by mutableStateOf(true)
        private set

    /** The voice assistant does not read its answers aloud. */
    var assistantMuted by mutableStateOf(false)
        private set

    /** The voice assistant listens again after it has read an answer. */
    var voiceHandsFree by mutableStateOf(true)
        private set

    /** The assistant remembers the person across chats and may search the past ones. On until switched off. */
    var memory by mutableStateOf(true)
        private set

    /** The assistant on the computer may read the notes of the memory (through the phone, one request at a time). On until switched off. */
    var remoteMemory by mutableStateOf(true)
        private set

    /** Keeps the connection to the computers while the app is in the background (a quiet notification says so), so that approvals and answers reach the phone. */
    var remoteKeepAlive by mutableStateOf(true)
        private set

    /** The model that writes the memory summary; null means the one the chat uses. */
    var summaryModel by mutableStateOf<ModelRef?>(null)
        private set

    fun init(context: Context) {
        if (store != null) return
        val p = context.applicationContext.getSharedPreferences("devx_prefs", Context.MODE_PRIVATE)
        store = p
        Persona.load(p)
        nickname = p.getString("nickname", "").orEmpty()
        theme = runCatching { ThemeMode.valueOf(p.getString("theme", "") ?: "") }.getOrDefault(ThemeMode.Black)
        language = runCatching { AppLanguage.valueOf(p.getString("language", "") ?: "") }.getOrDefault(AppLanguage.System)
        voiceLanguage = p.getString("voiceLanguage", "").orEmpty()
        onboarded = p.getBoolean("onboarded", false) || nickname.isNotBlank()
        thinkingGlow = p.getBoolean("thinkingGlow", false)
        webSearch = p.getBoolean("webSearch", true)
        workTools = p.getBoolean("workTools", true)
        terminal = p.getBoolean("terminal", true)
        phoneTools = p.getBoolean("phoneTools", true)
        assistantMuted = p.getBoolean("assistantMuted", false)
        voiceHandsFree = p.getBoolean("voiceHandsFree", true)
        memory = p.getBoolean("memory", true)
        remoteMemory = p.getBoolean("remoteMemory", true)
        remoteKeepAlive = p.getBoolean("remoteKeepAlive", true)
        val provider = p.getString("summaryProvider", null)
        val model = p.getString("summaryModel", null)
        summaryModel = if (provider != null && model != null) ModelRef(provider, model) else null
    }

    fun saveSummaryModel(ref: ModelRef?) {
        summaryModel = ref
        store?.edit()?.putString("summaryProvider", ref?.providerId)?.putString("summaryModel", ref?.model)?.apply()
    }

    fun saveMemory(value: Boolean) {
        memory = value
        store?.edit()?.putBoolean("memory", value)?.apply()
    }

    fun saveRemoteKeepAlive(value: Boolean) {
        remoteKeepAlive = value
        store?.edit()?.putBoolean("remoteKeepAlive", value)?.apply()
    }

    fun saveRemoteMemory(value: Boolean) {
        remoteMemory = value
        store?.edit()?.putBoolean("remoteMemory", value)?.apply()
    }

    fun saveWorkTools(value: Boolean) {
        workTools = value
        store?.edit()?.putBoolean("workTools", value)?.apply()
    }

    fun saveTerminal(value: Boolean) {
        terminal = value
        store?.edit()?.putBoolean("terminal", value)?.apply()
    }

    fun saveAssistantMuted(value: Boolean) {
        assistantMuted = value
        store?.edit()?.putBoolean("assistantMuted", value)?.apply()
    }

    fun saveVoiceHandsFree(value: Boolean) {
        voiceHandsFree = value
        store?.edit()?.putBoolean("voiceHandsFree", value)?.apply()
    }

    fun savePhoneTools(value: Boolean) {
        phoneTools = value
        store?.edit()?.putBoolean("phoneTools", value)?.apply()
    }

    fun saveThinkingGlow(value: Boolean) {
        thinkingGlow = value
        store?.edit()?.putBoolean("thinkingGlow", value)?.apply()
    }

    fun saveWebSearch(value: Boolean) {
        webSearch = value
        store?.edit()?.putBoolean("webSearch", value)?.apply()
    }

    /** The notification permission is asked for once, with the first message; never again after that. */
    var askedNotifications: Boolean
        get() = store?.getBoolean("askedNotifications", false) ?: true
        set(value) { store?.edit()?.putBoolean("askedNotifications", value)?.apply() }

    fun saveNickname(value: String) {
        nickname = value.trim()
        store?.edit()?.putString("nickname", nickname)?.putBoolean("onboarded", true)?.apply()
        onboarded = true
    }

    fun saveTheme(value: ThemeMode) {
        theme = value
        store?.edit()?.putString("theme", value.name)?.apply()
    }

    fun saveVoiceLanguage(tag: String) {
        voiceLanguage = tag
        store?.edit()?.putString("voiceLanguage", tag)?.apply()
    }

    fun saveLanguage(value: AppLanguage) {
        language = value
        store?.edit()?.putString("language", value.name)?.commit()
    }
}
