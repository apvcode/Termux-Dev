package com.apvcode.devx.data

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

enum class ThemeMode { System, Black, Gray, Light, Midnight, Forest, Dusk, Ember, Ocean, Paper, Sakura, Mint }

enum class AppLanguage(val tag: String?) {
    System(null), English("en"), Russian("ru"), German("de"), Spanish("es"), French("fr"),
    Japanese("ja"), Portuguese("pt"), Ukrainian("uk"), Chinese("zh"),
}

/** Small personal settings: nickname, theme, language. Read on start, changed from Settings. */
object Prefs {
    const val ENGINE_PHONE = "phone"
    const val ENGINE_CLOUD = "cloud"

    private var store: SharedPreferences? = null
    internal val raw: SharedPreferences? get() = store

    var nickname by mutableStateOf("")
        private set
    var theme by mutableStateOf(ThemeMode.Black)

    /** The theme follows the time of day: [dayTheme] by day, [nightTheme] from [nightFrom] to [nightTo] (minutes of the day). */
    var themeSchedule by mutableStateOf(false)
    var dayTheme by mutableStateOf(ThemeMode.Light)
    var nightTheme by mutableStateOf(ThemeMode.Black)
    var nightFrom by mutableIntStateOf(ThemeSchedule.DEFAULT_FROM)
    var nightTo by mutableIntStateOf(ThemeSchedule.DEFAULT_TO)
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
    /** Glass panels blur what is behind them. On by default; off is for a slow phone (the panels are plain then). */
    var blur by mutableStateOf(true)

    var phoneTools by mutableStateOf(true)
    var twoGis by mutableStateOf(true)

    /** How fast the assistant speaks (1.0 is the voice's own speed) and the voice chosen by name (empty: the best one the phone has). */
    var voiceRate by mutableStateOf(1.05f)
    var voiceName by mutableStateOf("")

    /** The voice that reads answers aloud: [ENGINE_PHONE] (the phone's own) or [ENGINE_CLOUD] (a provider's neural voice, which sounds like a person). */
    var voiceEngine by mutableStateOf(ENGINE_PHONE)

    /** For the cloud voice: the provider (by id) and the name of its voice. */
    var cloudProvider by mutableStateOf("")
    var cloudVoice by mutableStateOf("")

    /** The speech model chosen by hand; empty means the newest one the provider lists. */
    var cloudModel by mutableStateOf("")

    /** The model of the voice assistant's talks (a quick one is best); null means the model chosen for chats. */
    var voiceModel by mutableStateOf<ModelRef?>(null)
        private set
    var folderUri by mutableStateOf<String?>(null)
    var galleryTools by mutableStateOf(true)
    var notificationTools by mutableStateOf(true)
        private set

    /** The voice assistant does not read its answers aloud. */
    var assistantMuted by mutableStateOf(false)

    /** The hint that the assistant opens the app with a swipe up was shown once (it is not shown again). */
    var assistantSwipeHintSeen by mutableStateOf(false)
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
        val p = context.applicationContext.getSharedPreferences(PrefFiles.APP, Context.MODE_PRIVATE)
        store = p
        Persona.load(p)
        nickname = p.getString("nickname", "").orEmpty()
        theme = runCatching { ThemeMode.valueOf(p.getString("theme", "") ?: "") }.getOrDefault(ThemeMode.Black)
        themeSchedule = p.getBoolean("themeSchedule", false)
        dayTheme = runCatching { ThemeMode.valueOf(p.getString("dayTheme", "") ?: "") }.getOrDefault(ThemeMode.Light)
        nightTheme = runCatching { ThemeMode.valueOf(p.getString("nightTheme", "") ?: "") }.getOrDefault(ThemeMode.Black)
        nightFrom = p.getInt("nightFrom", ThemeSchedule.DEFAULT_FROM).coerceIn(0, 1439)
        nightTo = p.getInt("nightTo", ThemeSchedule.DEFAULT_TO).coerceIn(0, 1439)
        language = runCatching { AppLanguage.valueOf(p.getString("language", "") ?: "") }.getOrDefault(AppLanguage.System)
        voiceLanguage = p.getString("voiceLanguage", "").orEmpty()
        onboarded = p.getBoolean("onboarded", false) || nickname.isNotBlank()
        thinkingGlow = p.getBoolean("thinkingGlow", false)
        webSearch = p.getBoolean("webSearch", true)
        workTools = p.getBoolean("workTools", true)
        terminal = p.getBoolean("terminal", true)
        phoneTools = p.getBoolean("phoneTools", true)
        blur = p.getBoolean("blur", true)
        twoGis = p.getBoolean("twoGis", true)
        voiceRate = p.getFloat("voiceRate", 1.05f)
        voiceName = p.getString("voiceName", "").orEmpty()
        voiceEngine = p.getString("voiceEngine", ENGINE_PHONE).orEmpty().takeIf { it == ENGINE_CLOUD } ?: ENGINE_PHONE
        cloudProvider = p.getString("cloudProvider", "").orEmpty()
        cloudVoice = p.getString("cloudVoice", "").orEmpty()
        cloudModel = p.getString("cloudModel", "").orEmpty()
        voiceModel = p.getString("voiceModelProvider", null)?.let { pr -> p.getString("voiceModelName", null)?.let { m -> ModelRef(pr, m) } }
        folderUri = p.getString("folderUri", null)
        galleryTools = p.getBoolean("galleryTools", true)
        notificationTools = p.getBoolean("notificationTools", true)
        assistantMuted = p.getBoolean("assistantMuted", false)
        assistantSwipeHintSeen = p.getBoolean("assistantSwipeHintSeen2", false)
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

    fun markAssistantSwipeHintSeen() {
        assistantSwipeHintSeen = true
        store?.edit()?.putBoolean("assistantSwipeHintSeen2", true)?.apply()
    }

    fun saveAssistantMuted(value: Boolean) {
        assistantMuted = value
        store?.edit()?.putBoolean("assistantMuted", value)?.apply()
    }

    fun saveVoiceRate(value: Float) {
        voiceRate = value.coerceIn(0.7f, 1.5f)
        store?.edit()?.putFloat("voiceRate", voiceRate)?.apply()
    }

    fun saveVoiceModel(ref: ModelRef?) {
        voiceModel = ref
        store?.edit()?.apply {
            if (ref == null) { remove("voiceModelProvider"); remove("voiceModelName") } else { putString("voiceModelProvider", ref.providerId); putString("voiceModelName", ref.model) }
        }?.apply()
    }

    fun saveVoiceName(value: String) {
        voiceName = value
        store?.edit()?.putString("voiceName", value)?.apply()
    }

    fun saveVoiceHandsFree(value: Boolean) {
        voiceHandsFree = value
        store?.edit()?.putBoolean("voiceHandsFree", value)?.apply()
    }

    fun saveVoiceEngine(value: String) { voiceEngine = value; store?.edit()?.putString("voiceEngine", value)?.apply() }

    /** A new provider has its own voices: the voice is forgotten (the first of the list is used until one is picked). */
    fun saveCloudProvider(id: String) { cloudProvider = id; cloudVoice = ""; cloudModel = ""; store?.edit()?.putString("cloudProvider", id)?.putString("cloudVoice", "")?.putString("cloudModel", "")?.apply() }

    fun saveCloudModel(id: String) { cloudModel = id; store?.edit()?.putString("cloudModel", id)?.apply() }

    fun saveCloudVoice(name: String) { cloudVoice = name; store?.edit()?.putString("cloudVoice", name)?.apply() }

    fun saveBlur(value: Boolean) {
        blur = value
        store?.edit()?.putBoolean("blur", value)?.apply()
    }

    fun savePhoneTools(value: Boolean) {
        phoneTools = value
        store?.edit()?.putBoolean("phoneTools", value)?.apply()
    }

    fun saveGalleryTools(value: Boolean) {
        galleryTools = value
        store?.edit()?.putBoolean("galleryTools", value)?.apply()
    }

    fun saveNotificationTools(value: Boolean) {
        notificationTools = value
        store?.edit()?.putBoolean("notificationTools", value)?.apply()
    }

    fun saveFolder(uri: String?) {
        folderUri = uri
        store?.edit()?.apply { if (uri == null) remove("folderUri") else putString("folderUri", uri) }?.apply()
    }

    fun saveTwoGis(value: Boolean) {
        twoGis = value
        store?.edit()?.putBoolean("twoGis", value)?.apply()
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

    /** Picking a theme by hand puts the schedule aside: what was tapped is what is shown. */
    fun saveTheme(value: ThemeMode) {
        theme = value
        themeSchedule = false
        store?.edit()?.putString("theme", value.name)?.putBoolean("themeSchedule", false)?.apply()
    }

    fun saveThemeSchedule(on: Boolean) {
        themeSchedule = on
        store?.edit()?.putBoolean("themeSchedule", on)?.apply()
    }

    fun saveDayTheme(value: ThemeMode) { dayTheme = value; store?.edit()?.putString("dayTheme", value.name)?.apply() }

    fun saveNightTheme(value: ThemeMode) { nightTheme = value; store?.edit()?.putString("nightTheme", value.name)?.apply() }

    fun saveNightTimes(from: Int, to: Int) {
        nightFrom = from.coerceIn(0, 1439)
        nightTo = to.coerceIn(0, 1439)
        store?.edit()?.putInt("nightFrom", nightFrom)?.putInt("nightTo", nightTo)?.apply()
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
