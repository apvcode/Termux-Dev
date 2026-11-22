package com.apvcode.devx.ui.chat

import com.apvcode.devx.data.logged
import com.apvcode.devx.data.SpaceBook
import com.apvcode.devx.data.branchPosition
import com.apvcode.devx.data.addContinuation
import com.apvcode.devx.data.continuationNote
import com.apvcode.devx.data.SpaceRules
import com.apvcode.devx.data.attempt
import com.apvcode.devx.data.Artifact
import com.apvcode.devx.ui.chat.MentionKind as MK
import com.apvcode.devx.data.PhoneTools
import com.apvcode.devx.data.WorkTools
import com.apvcode.devx.data.ArtifactStore
import com.apvcode.devx.data.Workspace
import com.apvcode.devx.data.TextToolCalls
import com.apvcode.devx.data.WireToolResult
import com.apvcode.devx.data.WireToolCall
import com.apvcode.devx.data.WireTool
import com.apvcode.devx.data.Source
import com.apvcode.devx.data.ToolActivity
import com.apvcode.devx.data.ToolReport
import com.apvcode.devx.data.ShopTools
import com.apvcode.devx.data.WebTools
import com.apvcode.devx.data.Toolbox
import com.apvcode.devx.data.ChatDigest
import com.apvcode.devx.data.DigestMessage
import com.apvcode.devx.data.MemoryStore
import com.apvcode.devx.data.MemoryTools
import com.apvcode.devx.data.SkillStore
import com.apvcode.devx.data.SkillTools
import com.apvcode.devx.data.ProjectInfo
import com.apvcode.devx.data.ProjectLook
import com.apvcode.devx.data.ErrorLog
import com.apvcode.devx.data.ProjectStore
import android.app.Application
import android.net.Uri
import android.text.format.DateFormat
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.apvcode.devx.R
import com.apvcode.devx.data.Api
import com.apvcode.devx.data.Attachment
import com.apvcode.devx.data.AttachmentKind
import com.apvcode.devx.data.AttachmentStore
import com.apvcode.devx.data.Piece
import com.apvcode.devx.data.ReasoningCatalog
import com.apvcode.devx.data.WireDocument
import com.apvcode.devx.data.WireImage
import com.apvcode.devx.data.ApiException
import com.apvcode.devx.data.Chat
import com.apvcode.devx.data.ErrorKind
import com.apvcode.devx.data.McpStore
import com.apvcode.devx.data.ToolApproval
import com.apvcode.devx.data.PendingApproval
import com.apvcode.devx.data.ApprovalDecision
import com.apvcode.devx.data.McpTools
import com.apvcode.devx.data.ImageTools
import com.apvcode.devx.data.TitleChunk
import com.apvcode.devx.data.Level
import com.apvcode.devx.data.ProviderKind
import com.apvcode.devx.data.ReasoningCaps
import com.apvcode.devx.data.formatOf
import com.apvcode.devx.data.resolveReasoning
import com.apvcode.devx.data.ModelRef
import com.apvcode.devx.data.Msg
import com.apvcode.devx.data.Provider
import com.apvcode.devx.data.Secrets
import com.apvcode.devx.data.Store
import com.apvcode.devx.data.Prefs
import com.apvcode.devx.data.SystemPrompt
import com.apvcode.devx.data.WireMessage
import com.apvcode.devx.data.activePath
import com.apvcode.devx.data.select
import com.apvcode.devx.data.siblings
import com.apvcode.devx.data.toJson
import com.apvcode.devx.model.ChatStatus
import com.apvcode.devx.model.ChatSummary
import com.apvcode.devx.model.Role
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Date
import java.util.UUID
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.SystemClock
import androidx.compose.runtime.snapshots.Snapshot
import com.apvcode.devx.data.AppLock
import com.apvcode.devx.data.ApprovalBridge
import com.apvcode.devx.data.CardDraft
import com.apvcode.devx.data.FileHit
import com.apvcode.devx.data.FileText
import com.apvcode.devx.data.InsideFinder
import com.apvcode.devx.data.InsideHit
import com.apvcode.devx.data.Lesson
import com.apvcode.devx.data.Library
import com.apvcode.devx.data.LibraryEntry
import com.apvcode.devx.data.LibraryMeta
import com.apvcode.devx.data.MapTools
import com.apvcode.devx.data.ModelLimits
import com.apvcode.devx.data.NotebookExport
import com.apvcode.devx.data.NotebookPrompt
import com.apvcode.devx.data.NotebookTools
import com.apvcode.devx.data.PrefFiles
import com.apvcode.devx.data.ScheduledTask
import com.apvcode.devx.data.SearchHit
import com.apvcode.devx.data.Tasks
import com.apvcode.devx.data.Trash
import com.apvcode.devx.data.TrashItem
import java.net.URI
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred

/**
 * Everything the screens need: chats (as trees), projects, providers with their models, and the
 * running generations. A chat can generate in the background while you look at another one.
 */
class ChatViewModel(app: Application) : AndroidViewModel(app) {
    private val context = app.applicationContext
    private val store = Store(context)
    private val secrets = Secrets(context)
    private val trash = Trash(java.io.File(context.filesDir, "trash"))
    private val api = Api()
    private val attachmentStore = AttachmentStore(context)
    private val workspace = Workspace(context)
    private val artifacts = ArtifactStore(context)

    /** What the assistant remembers about the person (the notes and the overview). */
    val memory = MemoryStore(context)
    private val projectStore = ProjectStore(context)

    /** What each project is and what the assistant knows about it, by the project's name. */
    val projectInfo = mutableStateMapOf<String, ProjectInfo>()

    /** The guides the assistant may read, built in and imported. */
    val skills = SkillStore(context)
    /** Servers the person allowed for the rest of a chat (per chat; forgotten when the app closes). */
    private val mcpAllowed = HashMap<String, MutableSet<String>>()

    /** The MCP servers the person added (GitHub and the like), reached by web address. */
    val mcp = McpStore(context, secrets, viewModelScope)
    private val webTools = WebTools(context)
    private val shopTools = ShopTools(context, webTools)

    /** What the assistant said about each file (what it took from it); the library reads it. */
    val libraryMeta = LibraryMeta(context)
    private val toolbox = Toolbox(
        context, attachmentStore, webTools, WorkTools(context, workspace, artifacts),
        MemoryTools(memory, { projectInfo[it]?.memory }) { chatDigests() },
        SkillTools(skills, workspace),
        McpTools(mcp),
        ImageTools(context, attachmentStore, artifacts),
        PhoneTools(context),
        NotebookTools({ projectInfo[it] }, { library }, { chatId, file -> useLibraryFile(chatId, file) }, { saveProjects() }, { name, args -> updateSpace(name, args) }, { name, urls -> withContext(Dispatchers.Main) { addSites(name, urls) } },
        { chatId, a -> withContext(Dispatchers.Main) { createSpace(chatId, a) } },
        { kind -> when (kind) { "source" -> context.getString(R.string.source_ask_remove) to ""; "study" -> context.getString(R.string.space_ask_study) to context.getString(R.string.space_ask_study_label); "ideas" -> context.getString(R.string.space_ask_ideas) to context.getString(R.string.space_ask_ideas_label); else -> context.getString(R.string.space_ask_project) to context.getString(R.string.space_ask_project_label) } },
        { name, a -> remindStudy(name, a) },
        { name, ids -> withContext(Dispatchers.Main) { ids.forEach { removeSource(name, it) } } },
        { name, id, a -> withContext(Dispatchers.Main) { editSource(name, id, a) } },
        { name, title, text -> withContext(Dispatchers.Main) { addTextSource(name, title, text) } },
    ), shopTools)

    /** A file the assistant handed over, by its id (null if it is gone). */
    fun artifact(id: String): Artifact? = artifacts.get(id)

    fun artifactFile(a: Artifact): java.io.File = artifacts.fileOf(a)
    private val prefs = context.getSharedPreferences(PrefFiles.SETTINGS, Context.MODE_PRIVATE)

    // ---------- state ----------

    /** How many photos one message may carry (1..50); set in the settings. */
    var maxPhotos by mutableIntStateOf(prefs.getInt("maxPhotos", 10).coerceIn(1, 50))
        private set

    /** Photos and files chosen for the message that is being written. */
    val draftAttachments = mutableStateListOf<Attachment>()

    /** What @ can point at: the person's notebooks and projects (in the order of the drawer), and the files of the library. */
    fun mentionItems(): List<MentionItem> {
        val spaces = projects.mapNotNull { name ->
            projectInfo[name]?.let { MentionItem(name, name, it.goal.ifBlank { it.description }.replace("\n", " ").trim(), if (it.isNotebook) MK.Notebook else MK.Project, it) }
        }
        val files = library.filter { it.attachment != null }.take(60).map { e ->
            MentionItem("file:" + e.id, e.name, e.summary.replace("\n", " ").trim(), MK.File, attachment = e.attachment)
        }
        val extra = buildList {
            Tasks.all(context).forEach { t -> add(MentionItem("task:" + t.id, t.title, Tasks.daysText(t.days) + " %02d:%02d".format(t.hour, t.minute), MK.Tasks)) }
        }
        return spaces + files + extra
    }

    /** How a kept mention looks (null when what it pointed at is gone). */
    fun mentionLook(key: String): MentionLook? {
        if (key.startsWith("task:")) return Tasks.get(context, key.removePrefix("task:").toIntOrNull() ?: -1)?.let { MentionLook(it.title, MK.Tasks, null) }
        return projectInfo[key]?.let { MentionLook(key, if (it.isNotebook) MK.Notebook else MK.Project, it) }
    }

    /** The notebooks and projects a text points at with "@Name" (their keys). */
    fun mentionsIn(text: String): List<String> {
        if ('@' !in text) return emptyList()
        val keys = HashMap<String, String>()
        projects.forEach { keys[it] = it }
        Tasks.all(context).forEach { keys.putIfAbsent(it.title, "task:" + it.id) }
        return com.apvcode.devx.ui.chat.mentionSpans(text, keys.keys).mapNotNull { keys[it.name] }.distinct()
    }

    /** A file of the library goes into the message being written, as if it had just been picked. */
    fun attachLibraryFile(a: Attachment) { if (draftAttachments.none { it.id == a.id }) draftAttachments += a }

    /** How many are still being prepared (a photo is shrunk first). */
    var importing by mutableIntStateOf(0)
        private set

    /** True once the saved data has been read from the phone. */
    var loaded by mutableStateOf(false)
        private set

    val providers = mutableStateListOf<Provider>()
    val projects = mutableStateListOf<String>()
    private val chatStates = mutableStateListOf<Chat>()

    /** The rules for the list of notebooks and projects and their chats live in [SpaceBook] (tested on their own); this model writes what changed. */
    private val book = SpaceBook(projects, projectInfo, chatStates)

    var currentId by mutableStateOf<String?>(null)
        private set
    /** The model new chats start with: the one chosen last. */
    private var defaultModel by mutableStateOf<ModelRef?>(null)

    /** The model of the open chat (every chat keeps its own); a new chat shows the default one. */
    val model: ModelRef? get() = current?.model ?: defaultModel

    /** chat id -> id of the assistant message that is being written right now. */
    private val streaming = mutableStateMapOf<String, String>()
    private val jobs = HashMap<String, Job>()

    /** Models being refreshed (provider id) and the last refresh error of each provider. */
    /** The reasoning level chosen for each model (by model id). Missing = the model's own default. */
    val efforts = mutableStateMapOf<String, String>()

    val refreshing = mutableStateMapOf<String, Boolean>()
    val modelsError = mutableStateMapOf<String, ErrorKind>()

    /** Messages added just now: the screen plays their entrance animation once and forgets them. */
    private val fresh = HashSet<String>()

    init {
        mcp.load()
        viewModelScope.launch {
            val data = withContext(Dispatchers.IO) {
                attempt("chatviewmodel") { ReasoningCatalog.load(context) }
                attempt("chatviewmodel") { ModelLimits.load(context) }
                val info = projectStore.load(store.loadProjects()).also { projectStore.save(it) }
                // Names come from the loaded details, so a project that was only in the details is not dropped.
                listOf(store.loadProviders(), info.keys.toList(), store.loadModel(), store.loadChats(), store.loadEfforts(), info)
            }
            @Suppress("UNCHECKED_CAST")
            providers += data[0] as List<Provider>
            @Suppress("UNCHECKED_CAST")
            projects += (data[1] as List<String>).filter { it !in projects }
            defaultModel = data[2] as ModelRef?
            @Suppress("UNCHECKED_CAST")
            chatStates += data[3] as List<Chat>
            @Suppress("UNCHECKED_CAST")
            efforts.putAll(data[4] as Map<String, String>)
            @Suppress("UNCHECKED_CAST")
            projectInfo.putAll(data[5] as Map<String, ProjectInfo>)
            memory.load()
            skills.load()
            projectInfo.values.forEach { it.memory.load() }
            hookSummaries()
            loaded = true
            healRenamedProjects()
            adoptOrphanChats()
            refreshTrash()
            purgeOldTrash()
            store.damagedChats.takeIf { it > 0 }?.let { Toast.makeText(context, context.getString(R.string.chats_damaged, it), Toast.LENGTH_LONG).show() }
        }
    }

    /**
     * A rename that was cut short (the app was killed after the project had its new name but before every chat was written) leaves
     * chats with the old name. Each chat also knows the id of its project, so it is put under the project's present name.
     */
    private fun healRenamedProjects() {
        book.healRenamed().forEach { chat ->
            ErrorLog.warn("projects", "chat ${chat.id} was put under \"${chat.project}\" after a rename that was cut short")
            save(chat)
        }
    }

    /**
     * A chat that points at a project that is not in the list (the list or the details were damaged once) would be in no section of
     * the side panel at all. The project is made again with the plain defaults, so the chat is back where it was.
     */
    private fun adoptOrphanChats() {
        val made = book.adoptOrphans({ newId().take(8) }) { id -> ProjectInfo(id, "", "", ProjectLook.DEFAULT_ICON, ProjectLook.colors.first(), projectStore.memoryOf(id)) }
        if (made > 0) saveProjects()
    }

    // ---------- what the screens read ----------

    val current: Chat? get() = chatStates.firstOrNull { it.id == currentId }

    /** The messages of the open chat along the selected branch. */
    val path: List<Msg> get() = current?.activePath().orEmpty()

    val generating: Boolean get() = currentId?.let { it in streaming } == true

    val streamingId: String? get() = currentId?.let { streaming[it] }

    fun isStreaming(msg: Msg): Boolean = msg.id == streamingId

    fun consumeFresh(id: String): Boolean = fresh.remove(id)

    /** "2 of 3": where this message sits among its alternatives, or null when it has none. */
    fun branchOf(msg: Msg): Pair<Int, Int>? = current?.branchPosition(msg)

    val chats: List<ChatSummary>
        get() = chatStates.filter { !it.archived && !it.temporary }.sortedByDescending { it.updatedAt }.map { chat ->
            ChatSummary(
                id = chat.id,
                title = chat.title,
                project = chat.project,
                // A locked chat shows nothing of what is in it.
                // Read without being watched: the words of an answer that is being written must not make the list of chats (which the
                // menu keeps ready) be built again with every little piece. It is made again when the answer is finished.
                preview = if (chat.locked) "" else Snapshot.withoutReadObservation { previewOf(chat) },
                time = age(chat.updatedAt),
                status = if (chat.id in streaming) ChatStatus.Working else ChatStatus.Idle,
                pinned = chat.pinned,
                locked = chat.locked,
                naming = chat.naming,
            )
        }

    private val previewMarks = Regex("[*`#>|]")
    private val previewSpaces = Regex("\\s+")

    private fun previewOf(chat: Chat): String =
        chat.activePath().lastOrNull { it.text.isNotBlank() }?.text?.replace(previewMarks, "")?.replace(previewSpaces, " ")?.trim()?.take(90).orEmpty()

    /** What the chosen model can do with reasoning; null when no model is chosen. */
    val reasoningCaps: ReasoningCaps? get() = capsFor(model)

    private fun capsFor(ref: ModelRef?): ReasoningCaps? {
        ref ?: return null
        val provider = providers.firstOrNull { it.id == ref.providerId } ?: return null
        return resolveReasoning(ref.model, provider.baseUrl, formatOf(provider, ref.model), provider.reasoningInfo[ref.model])
    }

    private fun levelFor(ref: ModelRef?): Level? {
        ref ?: return null
        val caps = capsFor(ref) ?: return null
        return Level.fromKey(efforts[ref.model.lowercase()])?.takeIf { it in caps.levels }
    }

    /** The level in force: the saved choice, but only if this model still offers it. */
    val reasoningLevel: Level? get() = levelFor(model)

    /** null = the model's own default (nothing is sent). */
    fun selectReasoning(level: Level?) {
        val ref = model ?: return
        if (level == null) efforts.remove(ref.model.lowercase()) else efforts[ref.model.lowercase()] = level.key
        val copy = efforts.toMap()
        viewModelScope.launch(Dispatchers.IO) { store.saveEfforts(copy) }
    }

    /** Every model of every provider, for the picker. */
    val modelCount: Int get() = providers.sumOf { it.models.size }

    // ---------- attachments ----------

    fun fileOf(attachment: Attachment) = attachmentStore.fileOf(attachment)

    /** What to show as the picture of an attachment: the photo itself, or the still of a video. */
    fun previewOf(attachment: Attachment) =
        if (attachment.kind == AttachmentKind.Video) attachmentStore.thumbOf(attachment) else attachmentStore.fileOf(attachment)

    fun changeMaxPhotos(count: Int) {
        maxPhotos = count.coerceIn(1, 50)
        prefs.edit().putInt("maxPhotos", maxPhotos).apply()
    }

    fun attachImages(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val have = draftAttachments.count { it.kind == AttachmentKind.Image } + importing
        val room = (maxPhotos - have).coerceAtLeast(0)
        if (room == 0 || uris.size > room) {
            Toast.makeText(context, context.getString(R.string.attach_limit, maxPhotos), Toast.LENGTH_SHORT).show()
        }
        val take = uris.take(room)
        if (take.isEmpty()) return
        importing += take.size
        // Many photos get a smaller long side, so that one request stays within what providers accept.
        val many = have + take.size > 20
        viewModelScope.launch {
            for (uri in take) {
                try {
                    attachmentStore.importImage(uri, if (many) 1280 else 1600, if (many) 80 else 86, if (many) 2560 else 4096)?.let { draftAttachments += it }
                } finally {
                    importing -= 1
                }
            }
        }
    }

    fun attachFiles(chosen: List<Uri>) {
        if (chosen.isEmpty()) return
        // A picture picked as a "file" is still a picture: it is shrunk and looked at like any photo.
        val (pictures, uris) = chosen.partition {
            val type = context.contentResolver.getType(it).orEmpty()
            type.startsWith("image/") && !type.contains("svg")
        }
        attachImages(pictures)
        if (uris.isEmpty()) return
        importing += uris.size
        viewModelScope.launch {
            for (uri in uris) {
                try {
                    val isVideo = context.contentResolver.getType(uri)?.startsWith("video/") == true
                    if (isVideo) {
                        val video = attachmentStore.importVideo(uri)
                        if (video == null) Toast.makeText(context, context.getString(R.string.attach_video_failed), Toast.LENGTH_LONG).show()
                        else draftAttachments += video
                    } else {
                        val file = attachmentStore.importFile(uri)
                        if (file == null) Toast.makeText(context, context.getString(R.string.attach_unsupported), Toast.LENGTH_SHORT).show()
                        else draftAttachments += uniquelyNamed(file)
                    }
                } finally {
                    importing -= 1
                }
            }
        }
    }

    /** Two files with one name could not be told apart by the model (the tools find a file by its name): the second becomes "name (2)". */
    private fun uniquelyNamed(file: Attachment): Attachment {
        val taken = draftAttachments.map { it.name } + current?.messages.orEmpty().flatMap { m -> m.attachments.map { it.name } }
        val name = SpaceRules.numberedName(file.name, taken)
        return if (name == file.name) file else file.copy(name = name)
    }

    /** What the gallery picker returns: photos and videos together. Photos are shrunk, videos are kept whole. */
    fun attachMedia(uris: List<Uri>) {
        val (videos, photos) = uris.partition { context.contentResolver.getType(it)?.startsWith("video/") == true }
        attachImages(photos)
        attachFiles(videos)
    }

    /** "Ask Devx" on selected words: they join the next message as a file of their own (as many as wanted). */
    fun attachSnippet(text: String) {
        val clipped = text.trim().take(MAX_SNIPPET)
        if (clipped.isEmpty()) return
        val taken = draftAttachments.map { it.name }.toSet()
        var n = 1
        while (context.getString(R.string.snippet_name, n) in taken) n++
        draftAttachments += attachmentStore.saveText(context.getString(R.string.snippet_name, n), clipped)
    }

    fun removeDraftAttachment(id: String) {
        draftAttachments.firstOrNull { it.id == id }?.let {
            draftAttachments.remove(it)
            // A file taken again from the library is kept: it is still in the library (and in the chats that used it).
            if (!usedElsewhere(it, null)) attachmentStore.delete(it)
        }
    }

    // ---------- chats ----------

    /** The project the chat that the next message starts will belong to (null: none). */
    var draftProject by mutableStateOf<String?>(null)
        private set

    /** Words shared from another app: the new chat's field takes them (and clears this). */
    var sharedDraft by mutableStateOf<String?>(null)

    /** Something shared from another app starts a new chat: its words in the field, its pictures and files attached. Nothing is sent. */
    fun startFromShare(text: String, uris: List<Uri>) {
        newChat()
        sharedDraft = text.ifBlank { null }
        val (images, others) = uris.partition { context.contentResolver.getType(it).orEmpty().startsWith("image/") || context.contentResolver.getType(it).orEmpty().startsWith("video/") }
        if (images.isNotEmpty()) attachMedia(images)
        if (others.isNotEmpty()) attachFiles(others)
    }

    fun newChat(project: String? = null) {
        discardTemporary()
        temporary = false
        currentId = null
        draftProject = project
    }

    fun openChat(id: String) {
        discardTemporary()
        temporary = false
        currentId = id
    }

    /** Looks for words in every chat (see [ChatFinder]); the work is done off the main thread by the caller. */
    fun search(query: String): List<SearchHit> = com.apvcode.devx.data.ChatFinder.run(chatStates.toList(), query)

    /** The notebooks and projects whose name, description or goal fit the words, the best first. */
    /** Words found inside notebooks and projects: cards, lessons, instructions, memory notes. */
    fun searchInside(query: String): List<InsideHit> = InsideFinder.run(projectInfo.toMap(), query, general = memory.notes.toList())

    fun searchSpaces(query: String): List<Pair<String, ProjectInfo>> = projects.mapNotNull { n ->
        projectInfo[n]?.let { i -> com.apvcode.devx.data.spaceMatch(n, i.description, i.goal, query).takeIf { it > 0 }?.let { score -> Triple(n, i, score) } }
    }.sortedByDescending { it.third }.take(8).map { it.first to it.second }

    /** The text of files read once for searching (by file id), cut to a size that fits in memory. */
    private val sourceText = ConcurrentHashMap<String, String>()

    private fun plainTextOf(a: Attachment): String = sourceText.getOrPut(a.id) {
        val file = attachmentStore.fileOf(a)
        val ext = a.name.substringAfterLast('.', "").lowercase()
        runCatching {
            when {
                a.kind == AttachmentKind.Pdf -> com.tom_roush.pdfbox.pdmodel.PDDocument.load(file).use { doc -> com.tom_roush.pdfbox.text.PDFTextStripper().apply { endPage = doc.numberOfPages.coerceAtMost(60) }.getText(doc) }
                ext in setOf("docx", "xlsx", "pptx", "odt", "ods", "odp") -> FileText.office(file, ext, 150_000).orEmpty()
                a.kind == AttachmentKind.Text -> file.readText()
                else -> ""
            }
        }.logged("chatviewmodel").getOrDefault("").take(150_000)
    }

    /**
     * The files that the words fit: by name, by what the assistant said about them, by the chat they were in; then by what they say
     * inside (the text of documents, PDFs and notes), with a line around the words. Blocking: it reads files the first time.
     */
    fun searchFiles(query: String): List<FileHit> {
        val named = Library.search(library, query).take(8)
        val out = named.map { FileHit(it, null) }.toMutableList()
        val q = query.trim()
        if (q.length >= 3) {
            val seen = named.map { it.id }.toSet()
            for (e in library) {
                if (out.size >= 14) break
                val a = e.attachment ?: continue
                if (e.id in seen || a.kind == AttachmentKind.Image || a.kind == AttachmentKind.Video) continue
                val cut = com.apvcode.devx.data.ChatFinder.snippet(plainTextOf(a), q) ?: continue
                out += FileHit(e, cut.text)
            }
        }
        return out
    }

    /** A message the chat should scroll to and flash once it is on the screen (chat id, message id), from a search. */
    var reveal by mutableStateOf<Pair<String, String>?>(null)

    /** Opens a chat at one message: the branch that has it is shown, and the screen brings it into view. */
    fun openChatAt(chatId: String, messageId: String?) {
        openChat(chatId)
        val chat = chatStates.firstOrNull { it.id == chatId } ?: return
        val byId = chat.messages.associateBy { it.id }
        var m = messageId?.let { byId[it] } ?: return
        // The branch that holds the message is made the shown one, all the way up.
        while (true) {
            chat.select(m)
            m = m.parentId?.let { byId[it] } ?: break
        }
        reveal = chatId to messageId
    }

    /** The chat that the next message starts will be temporary (not saved). Only from an empty screen. */
    var temporary by mutableStateOf(false)
        private set

    /** What the screen shows: the open chat is temporary, or the empty screen is waiting for one. */
    val isTemporary: Boolean get() = current?.temporary ?: temporary

    /** Turns the temporary mode on and off; inside a temporary chat it throws the chat away and starts afresh. */
    fun toggleTemporary() {
        val chat = current
        if (chat?.temporary == true) {
            discardTemporary()
            temporary = false
            currentId = null
        } else if (chat == null) {
            temporary = !temporary
        }
    }

    private fun discardTemporary() {
        current?.takeIf { it.temporary }?.let { deleteChat(it.id) }
    }

    fun send(text: String) {
        val trimmed = text.trim()
        val attachments = draftAttachments.toList()
        if ((trimmed.isEmpty() && attachments.isEmpty()) || generating || importing > 0) return
        // What is sent is read once and described, for the library and for the assistant.
        attachments.forEach { summarizeFile(it) }
        val chat = current ?: Chat(newId(), (trimmed.ifEmpty { attachments.first().name }).take(40), null, now()).also {
            // A talk with the voice assistant may use a quicker model of its own.
            it.model = (if (voiceMode) Prefs.voiceModel else null) ?: defaultModel
            it.temporary = temporary
            it.project = draftProject?.takeIf { p -> !temporary && p in projects }
            chatStates += it
            currentId = it.id
        }
        if (chat.model == null) chat.model = defaultModel
        draftProject = null
        val user = Msg(newId(), chat.activePath().lastOrNull()?.id, Role.User, trimmed, now(), attachments = attachments, mentions = mentionsIn(trimmed).ifEmpty { if (voiceMode) com.apvcode.devx.ui.chat.spokenNames(trimmed, projects).filter { it in projectInfo } else emptyList() })
        draftAttachments.clear()
        chat.messages += user
        chat.select(user)
        fresh += user.id
        touch(chat)
        generate(chat, user)
    }

    /**
     * Starts a chat of its own that nobody is looking at (a scheduled task): the question is sent to [ref] (or the model that is
     * chosen now) and the answer is written into it. The chat on the screen does not change. Returns the chat's id.
     */
    fun startBackgroundChat(title: String, prompt: String, ref: ModelRef?, project: String? = null): String? {
        if (prompt.isBlank()) return null
        val chat = Chat(newId(), title.ifBlank { prompt }.take(40), project?.takeIf { it in projectInfo }, now()).also {
            it.model = ref ?: defaultModel
            it.titleFinal = true
        }
        chatStates += chat
        val user = Msg(newId(), null, Role.User, prompt.trim(), now())
        chat.messages += user
        chat.select(user)
        fresh += user.id
        touch(chat)
        generate(chat, user)
        return chat.id
    }

    /** True while the chat's answer is still being written. */
    fun isRunning(chatId: String): Boolean = chatId in streaming

    /** Stops the answer of a chat that is not the open one. */
    fun stopChat(chatId: String) {
        jobs[chatId]?.cancel()
        streaming.remove(chatId)
    }

    /** Opens the connection to the provider of the default model ahead of the first question (the handshake takes a few hundred milliseconds). */
    fun warmConnection() {
        val ref = defaultModel ?: return
        val provider = providers.firstOrNull { it.id == ref.providerId } ?: return
        viewModelScope.launch(Dispatchers.IO) { runCatching { api.warm(provider) }.logged("chatviewmodel") }
    }

    /** The voice assistant is talking to the model: answers are short and spoken, and thinking is skipped. */
    var voiceMode = false

    fun stop() {
        val id = currentId ?: return
        jobs[id]?.cancel()
        // The button turns back into "send" at once; the rest of the clean-up follows as the work winds down.
        streaming.remove(id)
    }

    /** Another answer to the same question; the old one stays as an alternative. */
    fun regenerate(assistant: Msg) {
        val chat = current ?: return
        if (generating) return
        val user = chat.messages.firstOrNull { it.id == assistant.parentId } ?: return
        generate(chat, user)
    }

    /** Try a failed answer again: the failed one is dropped. */
    fun retry(assistant: Msg) {
        val chat = current ?: return
        if (generating) return
        val user = chat.messages.firstOrNull { it.id == assistant.parentId } ?: return
        // What was already done and said stays: the model goes on from there instead of starting over.
        if (assistant.text.isNotBlank() || assistant.steps.isNotEmpty()) {
            generate(chat, user, resume = assistant)
            return
        }
        chat.messages.remove(assistant)
        chat.selected.remove(user.id)
        generate(chat, user)
    }

    /** Editing a message starts a new branch from it; everything written before stays as it was. */
    fun edit(message: Msg, newText: String) {
        val chat = current ?: return
        val trimmed = newText.trim()
        if ((trimmed.isEmpty() && message.attachments.isEmpty()) || generating) return
        val edited = Msg(newId(), message.parentId, Role.User, trimmed, now(), attachments = message.attachments, mentions = mentionsIn(trimmed))
        chat.messages += edited
        chat.select(edited)
        fresh += edited.id
        touch(chat)
        generate(chat, edited)
    }

    fun switchBranch(message: Msg, delta: Int) {
        val chat = current ?: return
        val list = chat.siblings(message).sortedBy { it.createdAt }
        if (list.size < 2) return
        val index = list.indexOfFirst { it.id == message.id }
        chat.select(list[(index + delta + list.size) % list.size])
        save(chat)
    }

    fun togglePin(id: String) = withChat(id) { it.pinned = !it.pinned }

    fun renameChat(id: String, title: String) = withChat(id) {
        if (title.isNotBlank()) {
            it.title = title.trim()
            it.titleFinal = true
        }
    }

    // ---------- one chat's own actions ----------

    /** Locked chats that were opened with the screen lock this time; they shut again when the app goes away. */
    val unlocked = mutableStateListOf<String>()

    fun isShut(chat: Chat?): Boolean = chat != null && chat.locked && chat.id !in unlocked

    fun setLocked(id: String, locked: Boolean) {
        withChat(id) { it.locked = locked }
        // With the app code the chat closes at once (the cover slides in); with the device lock it stays open until the next relock.
        if (locked) { if (AppLock.locksChats) unlocked -= id else if (id !in unlocked) unlocked += id } else unlocked -= id
    }

    fun unlock(id: String) {
        if (id !in unlocked) unlocked += id
    }

    fun relockAll() = unlocked.clear()

    fun chatExists(id: String): Boolean = chatStates.any { it.id == id && !it.temporary }

    /** Everything the person attached in this chat, newest last. */
    fun uploadedFiles(id: String): List<Attachment> =
        chatStates.firstOrNull { it.id == id }?.messages?.filter { it.role == Role.User }?.flatMap { it.attachments }?.filter { !it.isQuote }.orEmpty()

    /** The chat as plain text, for sharing. */
    fun transcript(id: String): String {
        val chat = chatStates.firstOrNull { it.id == id } ?: return ""
        return buildString {
            append(chat.title).append("\n\n")
            for (m in chat.activePath()) {
                val who = if (m.role == Role.User) Prefs.nickname.ifBlank { "Me" } else "Devx"
                val files = m.attachments.joinToString { it.name }
                append(who).append(":\n").append(m.text)
                if (files.isNotEmpty()) append("\n[").append(files).append("]")
                append("\n\n")
            }
        }.trim()
    }

    fun moveToProject(id: String, project: String?) = withChat(id) { it.project = project }

    fun archiveChat(id: String) = withChat(id) { it.archived = true }

    /** The files a chat was the only user of: its attachments, what the assistant made for it, and its working folder. */
    private fun purgeChatFiles(chat: Chat) {
        val files = chat.messages.flatMap { it.attachments }.distinctBy { it.id }.filter { !usedElsewhere(it, chat.id) }
        viewModelScope.launch(Dispatchers.IO) { files.forEach(attachmentStore::delete) }
        SpaceBook.madeFiles(chat).forEach { artifacts.delete(it) }
        workspace.delete(chat.id)
    }

    /** What a chat leaves on disk besides itself: the ids to keep while it is in the trash. */
    private fun keepOf(chat: Chat): Set<String> = SpaceBook.filesOf(chat)

    /** The chat leaves the list and the disk but goes to the trash; its files stay until it is removed from there. */
    private fun detachChat(chat: Chat) {
        jobs[chat.id]?.cancel()
        chatStates.removeAll { it.id == chat.id }
        memory.invalidateSummary()
        if (currentId == chat.id) currentId = null
    }

    fun deleteChat(id: String) {
        val chat = chatStates.firstOrNull { it.id == id } ?: return
        if (chat.temporary) {
            // Never saved, never in the trash.
            purgeChatFiles(chat)
            detachChat(chat)
            return
        }
        val json = chat.toJson().toString()
        val keep = keepOf(chat)
        val title = chat.title
        detachChat(chat)
        viewModelScope.launch(Dispatchers.IO + NonCancellable) {
            val item = trash.putChat(title, json, keep)
            store.deleteChat(id)
            withContext(Dispatchers.Main) { refreshTrash(); lastTrashed = item }
        }
    }

    fun createProject(name: String, description: String = "", icon: String = ProjectLook.DEFAULT_ICON, color: Long = ProjectLook.colors.first(), kind: String = ProjectInfo.KIND_PROJECT): Boolean {
        val made = book.create(name, newId().take(8)) { id -> ProjectInfo(id, description.trim(), "", icon, color, projectStore.memoryOf(id), kind = kind) }
        if (!made) return false
        hookSummaries()
        saveProjects()
        return true
    }

    /** Changes what a project is: its name (the chats follow), what it is for, its look. False if the new name is taken. */
    fun editProject(old: String, name: String, description: String, icon: String, color: Long): Boolean {
        val info = projectInfo[old] ?: return false
        val renamed = name.trim()
        if (renamed.isEmpty() || (renamed != old && renamed in projects)) return false
        info.description = description.trim()
        info.icon = icon
        info.color = color
        if (renamed != old) renameProject(old, renamed) else saveProjects()
        return true
    }

    fun setProjectInstructions(name: String, text: String) {
        projectInfo[name]?.instructions = text.trim()
        saveProjects()
    }

    /** False when nothing changed: the name is empty or the same, or another project already has it (that one would be overwritten). */
    fun renameProject(old: String, new: String): Boolean {
        val touched = book.rename(old, new) ?: return false
        touched.forEach(::save)
        saveProjects()
        return true
    }

    /** Deleting a project takes the chats inside it with it. All of it goes to the trash, so it can be put back. */
    fun deleteProject(name: String) {
        val info = projectInfo[name] ?: return
        val inside = chatStates.filter { it.project == name }
        val chatJsons = inside.map { it.toJson().toString() }
        val keep = (inside.flatMap { keepOf(it) } + info.sources.map { it.id }).toSet()
        val meta = projectStore.encode(info).toString()
        val memoryFiles = listOf("project_${info.id}.json", "project_${info.id}_summary.json")
            .mapNotNull { n -> java.io.File(context.filesDir, n).takeIf { it.exists() }?.let { n to it.readBytes() } }.toMap()
        val chatIds = inside.map { it.id }
        inside.forEach { jobs[it.id]?.cancel(); if (it.temporary) purgeChatFiles(it) }
        if (currentId in chatIds) currentId = null
        book.take(name)
        if (inside.isNotEmpty()) memory.invalidateSummary()
        info.memory.destroy()
        saveProjects()
        viewModelScope.launch(Dispatchers.IO + NonCancellable) {
            val item = trash.putProject(name, meta, chatJsons, memoryFiles, keep)
            chatIds.forEach { store.deleteChat(it) }
            withContext(Dispatchers.Main) { refreshTrash(); lastTrashed = item }
        }
    }

    /** Puts a notebook or a project away (or back): it leaves the lists of the side panel, nothing is deleted. */
    fun setArchived(name: String, on: Boolean) {
        projectInfo[name]?.archived = on
        saveProjects()
    }

    /**
     * A new notebook or project made from this one, to start again: the description, instructions, goal, look, the files and sites,
     * the lessons (not done) and the cards (new). The chats, the memory notes, the days of study and the dates are not copied.
     * Gives the name of the copy.
     */
    fun duplicateProject(name: String): String? {
        if (projectInfo[name] == null) return null
        val base = context.getString(R.string.space_copy_name, name).take(60)
        val copy = book.duplicate(name, base, newId().take(8)) { id, plan -> ProjectInfo(id, memory = projectStore.memoryOf(id), plan = plan) } ?: return null
        hookSummaries()
        saveProjects()
        return copy
    }

    // ---------- the trash ----------

    /** What was deleted lately, newest first. */
    val trashItems = mutableStateListOf<TrashItem>()

    /** The last thing put in the trash: the screen offers to undo it for a few seconds. */
    var lastTrashed by mutableStateOf<TrashItem?>(null)

    private fun refreshTrash() {
        val list = trash.list()
        trashItems.clear()
        trashItems += list
    }

    /** Puts a chat, a project or a notebook back. A name that is taken gets a number; a chat whose project is gone goes to the plain list. */
    fun restoreFromTrash(id: String): Boolean {
        val item = trashItems.firstOrNull { it.id == id } ?: return false
        if (lastTrashed?.id == id) lastTrashed = null
        return if (item.isProject) restoreProject(item) else restoreChat(item)
    }

    private fun restoreChat(item: TrashItem): Boolean {
        val chat = runCatching { com.apvcode.devx.data.chatFromJson(org.json.JSONObject(trash.chat(item.id) ?: return false)) }.logged("chatviewmodel").getOrNull() ?: return false
        if (!book.restore(chat)) return false
        save(chat)
        removeFromTrash(item)
        return true
    }

    private fun restoreProject(item: TrashItem): Boolean {
        val bundle = trash.project(item.id) ?: return false
        val info = runCatching { projectStore.decode(org.json.JSONObject(bundle.meta)) }.logged("chatviewmodel").getOrNull() ?: return false
        bundle.files.forEach { (n, bytes) -> runCatching { java.io.File(context.filesDir, n).writeBytes(bytes) }.logged("chatviewmodel") }
        viewModelScope.launch { info.memory.load() }
        val chats = bundle.chats.mapNotNull { json -> runCatching { com.apvcode.devx.data.chatFromJson(org.json.JSONObject(json)) }.logged("chatviewmodel").getOrNull() }
        val name = book.restore(bundle.name, info, chats)
        chats.filter { it.project == name }.forEach(::save)
        hookSummaries()
        saveProjects()
        removeFromTrash(item)
        return true
    }

    private fun removeFromTrash(item: TrashItem) {
        trashItems.removeAll { it.id == item.id }
        viewModelScope.launch(Dispatchers.IO + NonCancellable) { trash.remove(item.id) }
    }

    /** Removes it for good, with the files that only it used. */
    fun purgeTrash(id: String) {
        val item = trashItems.firstOrNull { it.id == id } ?: return
        if (lastTrashed?.id == id) lastTrashed = null
        viewModelScope.launch {
            val parsed = withContext(Dispatchers.IO + NonCancellable) {
                val chats = ArrayList<Chat>()
                var sources = emptyList<Attachment>()
                if (item.isProject) {
                    trash.project(id)?.let { b ->
                        b.chats.forEach { j -> runCatching { com.apvcode.devx.data.chatFromJson(org.json.JSONObject(j)) }.logged("chatviewmodel").getOrNull()?.let(chats::add) }
                        sources = runCatching { projectStore.decode(org.json.JSONObject(b.meta)).sources }.logged("chatviewmodel").getOrDefault(emptyList())
                    }
                } else trash.chat(id)?.let { j -> runCatching { com.apvcode.devx.data.chatFromJson(org.json.JSONObject(j)) }.logged("chatviewmodel").getOrNull()?.let(chats::add) }
                trash.remove(id)
                chats to sources
            }
            trashItems.removeAll { it.id == id }
            // Now that it is not listed, what was kept for it is free to go (unless something else uses it).
            val (chats, sources) = parsed
            chats.forEach(::purgeChatFiles)
            val gone = sources.filter { !usedElsewhere(it, null) }
            withContext(Dispatchers.IO) { gone.forEach(attachmentStore::delete) }
            sources.forEach { libraryMeta.forget(it.id) }
        }
    }

    fun emptyTrash() = trashItems.toList().forEach { purgeTrash(it.id) }

    /** Items that have been in the trash for the whole time are removed for good. */
    private fun purgeOldTrash() {
        val old = trash.expired(System.currentTimeMillis()).map { it.id }.toSet()
        trashItems.filter { it.id in old }.forEach { purgeTrash(it.id) }
    }


    // ---------- notebooks and the library ----------

    /** Every file the app knows (see [com.apvcode.devx.data.Library]): sent in chats, made by the assistant, added to notebooks. */
    val library: List<LibraryEntry>
        get() = Library.build(chatStates.toList(), projectInfo.toMap(), { artifacts.get(it) }, libraryMeta.summaries, libraryMeta::seen)

    /** True when the file is still wanted by something else: a notebook, another chat, or the files taken from the library. */
    private fun usedElsewhere(a: Attachment, exceptChat: String?): Boolean =
        trashItems.any { a.id in it.keep } ||
            projectInfo.values.any { p -> p.sources.any { it.id == a.id } } ||
            chatStates.any { c -> c.id != exceptChat && (c.messages.any { m -> m.attachments.any { it.id == a.id } } || c.libraryFiles.any { it.id == a.id }) }

    /** The file to show, download or share: the document as it was given (for a Word file, the .docx itself), not the text the model reads. */
    fun attachmentFile(a: Attachment): java.io.File = attachmentStore.viewFile(a)

    /** The still picture of a video. */
    fun attachmentThumb(a: Attachment): java.io.File = attachmentStore.thumbOf(a)

    /** The notebook the assistant is told about: its sources with what they are about, its other chats, and the study plan. */
    private fun notebookPrompt(chat: Chat, name: String, info: ProjectInfo): String {
        val others = if (!info.useNotes) emptyList() else chatStates.filter { it.project == name && it.id != chat.id && !it.temporary && !it.locked }
            .sortedByDescending { it.updatedAt }.take(10).map { c ->
                val said = c.activePath().lastOrNull { it.role == Role.Assistant && it.text.isNotBlank() }?.text.orEmpty().replace(Regex("\\s+"), " ").trim().take(160)
                NotebookPrompt.ChatLine(c.title, said)
            }
        val sources = info.sources.map { a -> NotebookPrompt.SourceLine(a.name, com.apvcode.devx.data.libKindOf(a.name, a.mime, a.kind, a.url), libraryMeta.summaries[a.id].orEmpty(), a.url) }
        return NotebookPrompt.build(name, info.isStudy, info.goal, sources, others, info.useNotes, info.plan, info.deadline)
    }

    /** The assistant takes a file of the library into a chat: the file tools can read it from now on. False if it was there already. */
    private fun useLibraryFile(chatId: String, a: Attachment): Boolean {
        val chat = chatStates.firstOrNull { it.id == chatId } ?: return false
        if (chat.libraryFiles.any { it.id == a.id } || filesOf(chat, chat.activePath().lastOrNull() ?: return false).any { it.id == a.id }) return false
        chat.libraryFiles += a
        save(chat)
        return true
    }

    /** The assistant sets (or clears) the reminder to study: a scheduled task that opens a chat in this notebook at the time, with the cards that are due. */
    private fun remindStudy(name: String, a: org.json.JSONObject): String {
        val marker = "study:$name"
        val existing = Tasks.all(context).firstOrNull { it.project == name && it.prompt.startsWith("[$marker]") }
        if (a.optBoolean("off", false)) {
            if (existing == null) return "There was no reminder."
            Tasks.remove(context, existing.id)
            return "The reminder is removed."
        }
        val parts = a.optString("time").trim().split(':', '.')
        val h = parts.getOrNull(0)?.trim()?.toIntOrNull()
        val min = parts.getOrNull(1)?.trim()?.toIntOrNull()
        if (h == null || min == null || h !in 0..23 || min !in 0..59) return "Write the time as HH:mm (24-hour)."
        val list = a.optJSONArray("days")
        val days = list?.let { arr -> Tasks.daysFrom((0 until arr.length()).map { arr.optString(it) }) }?.takeIf { it != 0 } ?: ScheduledTask.ALL
        val prompt = "[$marker] " + context.getString(R.string.study_prompt_reminder)
        val task = if (existing != null) {
            existing.hour = h; existing.minute = min; existing.days = days; existing.enabled = true; existing.prompt = prompt
            Tasks.update(context, existing); existing
        } else Tasks.add(context, name, prompt, h, min, days, null, project = name)
        return "The reminder is set: %02d:%02d %s. At that time a notification says how many cards are due; a tap opens them.".format(task.hour, task.minute, Tasks.daysText(task.days))
    }

    /** An audio overview was asked for: the answer is read aloud when it is ready. */
    var autoListen by mutableStateOf(false)

    /** What reading a file of cards gave: how many cards the file has and how many were new to the notebook; or why it could not be read. */
    class CardImport(val inFile: Int, val added: Int, val problem: Int? = null)

    /** Cards from a file (text: tab, semicolon or comma separated, Anki plain text; or an Anki package) go into the notebook. Off the main thread. */
    suspend fun importCards(name: String, uri: android.net.Uri): CardImport {
        val plan = projectInfo[name]?.plan ?: return CardImport(0, 0, R.string.cards_import_failed)
        val drafts = withContext(Dispatchers.IO) {
            runCatching {
                val label = context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }.orEmpty()
                val stream = context.contentResolver.openInputStream(uri) ?: error("no stream")
                stream.use {
                    if (label.endsWith(".apkg", true) || label.endsWith(".colpkg", true)) com.apvcode.devx.data.ApkgImport.read(context, it)
                    else com.apvcode.devx.data.CardIO.parseText(it.readBytes().take(8_000_000).toByteArray().decodeToString())
                }
            }.logged("chatviewmodel")
        }
        val list = drafts.getOrElse { e ->
            ErrorLog.warn("cards", "a file of cards could not be read", e)
            return CardImport(0, 0, if (e is com.apvcode.devx.data.ApkgImport.Unsupported) R.string.cards_import_new_format else R.string.cards_import_failed)
        }
        if (list.isEmpty()) return CardImport(0, 0, R.string.cards_import_none)
        val added = plan.addDrafts(list, dueAt = System.currentTimeMillis(), limit = 2000)
        if (added > 0) saveProjects()
        return CardImport(list.size, added)
    }

    /** The cards as text for a file: Anki's plain text (tab separated) or comma separated values. */
    fun exportCards(name: String, csv: Boolean): String {
        val cards = projectInfo[name]?.plan?.cards?.toList().orEmpty()
        return if (csv) com.apvcode.devx.data.CardIO.toCsv(cards) else com.apvcode.devx.data.CardIO.toTsv(cards)
    }

    /** The cards as an Anki package (with their pictures), written to [out]. Gives how many cards went in. */
    fun exportApkg(name: String, out: java.io.OutputStream): Int =
        com.apvcode.devx.data.ApkgExport.write(context, projectInfo[name]?.plan?.cards?.toList().orEmpty(), name, out)

    /** A notebook or a project as a Markdown text (for the file the person saves). */
    fun exportNotebook(name: String): String {
        val info = projectInfo[name] ?: return ""
        val l = { id: Int -> context.getString(id) }
        val labels = NotebookExport.Labels(l(R.string.notebook_goal), l(R.string.project_instructions), l(R.string.nb_progress), l(R.string.export_cards), l(R.string.project_knowledge), l(R.string.memory_summary), l(R.string.notebook_sources), l(R.string.export_streak))
        return NotebookExport.markdown(
            name, info.description, info.goal, info.instructions,
            info.plan.lessons.map { Triple(it.title, it.status, it.note) },
            info.plan.cards.map { it.front to it.back },
            info.memory.notes.map { it.text },
            info.memory.summary,
            info.sources.map { a -> NotebookExport.Source(a.name, libraryMeta.summaries[a.id].orEmpty(), a.url) },
            labels, java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(Date()), info.plan.log.streak(),
        )
    }

    /** An answer to a card on the cards screen: the card is moved to its next day, the day is counted for the streak, and it is saved. */
    fun reviewCard(name: String, cardId: String, result: String) {
        val plan = projectInfo[name]?.plan ?: return
        val card = plan.cards.firstOrNull { it.id == cardId } ?: return
        if (card.review(result)) { plan.log.record(); saveProjects() }
    }

    /** What is said to the assistant to make the first cards of a notebook. */
    /** A card being made from words picked in a message: the notebook and the words (they become the answer). The screen asks for the question. */
    var cardDraft by mutableStateOf<Pair<String, String>?>(null)

    /** A card the person wrote (or picked from a message) goes to the study notebook's cards. False when it is empty, there is one with the same question, or the notebook is full. */
    fun addCard(name: String, front: String, back: String, image: String = ""): Boolean {
        val plan = projectInfo[name]?.plan ?: return false
        val added = plan.addDrafts(listOf(CardDraft(front, back, "", image)), dueAt = System.currentTimeMillis()) > 0
        if (added) saveProjects()
        return added
    }

    /** A string of the app in its language with values put in (for a prompt sent from a screen). */
    fun text(id: Int, vararg args: Any): String = context.getString(id, *args)

    fun graphBuildPrompt(): String = context.getString(R.string.graph_build_prompt)

    fun cardsMakePrompt(): String = context.getString(R.string.cards_make_prompt)

    /**
     * The source an answer cited by name: among the files of the open chat (what was attached, the sources of its notebook and of the ones
     * pointed at with @), then every notebook, then the library. The name matches whole first, then as a part.
     */
    fun findSource(name: String): Attachment? {
        val want = name.trim().lowercase()
        val chat = current
        val here = chat?.activePath()?.lastOrNull()?.let { filesOf(chat, it) }.orEmpty()
        val all = here + projectInfo.values.flatMap { it.sources } + library.mapNotNull { it.attachment }
        return all.firstOrNull { it.name.lowercase() == want } ?: all.firstOrNull { want.length >= 3 && it.name.lowercase().contains(want) }
    }

    /** The first message of a review of the cards that are due (the widget, the notebook's page). */
    fun cardsPrompt(): String = context.getString(R.string.study_prompt_cards)

    /** The first message of a study notebook once the goal is set: the assistant finds out the level, then writes the plan. */
    fun goalPrompt(goal: String): String = context.getString(R.string.study_prompt_first, goal)

    /** What the assistant changes in a project or notebook with project_update; gives what it did, in words for the model. */
    private suspend fun updateSpace(name: String, a: org.json.JSONObject): String = runCatching {
        withContext(Dispatchers.Main) {
            val info = projectInfo[name] ?: return@withContext "There is no such project."
            val done = ArrayList<String>()
            a.optString("icon").trim().takeIf { it.isNotEmpty() }?.let { icon ->
                if (icon !in ProjectLook.icons) return@withContext "Unknown icon \"$icon\". The icons are: " + ProjectLook.icons.joinToString(", ")
                info.icon = icon; done += "icon $icon"
            }
            a.optString("color").trim().lowercase().takeIf { it.isNotEmpty() }?.let { c ->
                val i = NotebookTools.PALETTE.indexOf(c)
                if (i < 0) return@withContext "Unknown colour \"$c\". The colours are: " + NotebookTools.PALETTE.joinToString(", ")
                info.color = ProjectLook.colors[i]; done += "colour $c"
            }
            a.optString("description").trim().takeIf { it.isNotEmpty() }?.let { info.description = it.take(600); done += "description" }
            a.optString("instructions").trim().takeIf { it.isNotEmpty() }?.let { info.instructions = it.take(4000); done += "instructions" }
            a.optString("goal").trim().takeIf { it.isNotEmpty() }?.let { if (info.isNotebook) { info.goal = it.take(300); done += "goal" } }
            a.optString("deadline").trim().takeIf { it.isNotEmpty() }?.let { text ->
                if (!info.isStudy) return@let
                if (text.equals("none", true)) { info.deadline = 0L; done += "deadline removed" }
                else {
                    val day = runCatching { LocalDate.parse(text) }.getOrNull() ?: return@withContext "The deadline must be a day as YYYY-MM-DD, or none."
                    info.deadline = day.toEpochDay(); done += "deadline $day"
                }
            }
            var finalName = name
            a.optString("title").trim().takeIf { it.isNotEmpty() && it != name }?.let { t ->
                val short = t.take(60)
                if (short in projects || !renameProject(name, short)) return@withContext "The name \"$short\" is taken by another project or notebook."
                finalName = short; done += "name \"$finalName\""
            }
            saveProjects()
            if (done.isEmpty()) "Nothing was changed: give at least one field." else "Changed: " + done.joinToString(", ") + ". The page shows it now."
        }
    }.logged("chatviewmodel").getOrDefault("Could not change it.")

    /** A notebook or a project made by the assistant, after the person confirmed: the chat it was talked over in moves into it. */
    private fun createSpace(chatId: String, a: org.json.JSONObject): String {
        val kind = a.optString("kind").trim().lowercase().let { if (it in setOf("study", "ideas", "project")) it else "project" }
        var name = a.optString("title").trim().take(60)
        if (name.isEmpty()) return "Give the name."
        name = SpaceRules.uniqueName(name) { it in projects }
        val icon = a.optString("icon").trim().takeIf { it in ProjectLook.icons } ?: when (kind) { "study" -> "school"; "ideas" -> "bulb"; else -> "folder" }
        val ci = NotebookTools.PALETTE.indexOf(a.optString("color").trim().lowercase())
        val color = if (ci >= 0) ProjectLook.colors[ci] else when (kind) { "study" -> 0xFF6C9BE8L; "ideas" -> 0xFFE8A05CL; else -> ProjectLook.colors.first() }
        val k = when (kind) { "study" -> ProjectInfo.KIND_STUDY; "ideas" -> ProjectInfo.KIND_IDEAS; else -> ProjectInfo.KIND_PROJECT }
        if (!createProject(name, a.optString("description"), icon, color, k)) return "Could not make it."
        projectInfo[name]?.let { info ->
            if (kind == "study") info.goal = a.optString("goal").trim().take(300)
            a.optString("instructions").trim().takeIf { it.isNotEmpty() }?.let { info.instructions = it.take(4000) }
        }
        moveToProject(chatId, name)
        saveProjects()
        Toast.makeText(context, context.getString(R.string.space_created, name), Toast.LENGTH_SHORT).show()
        return "Made " + (if (kind == "project") "the project" else "the notebook") + " \"$name\" and moved this chat into it. Tell the person briefly, say where it is (the side panel, under " + (if (kind == "project") "Projects" else "Notebooks") + ") and what to do next" + (if (kind == "study") " (set up the plan and start the first lesson there)." else ".")
    }

    fun setNotebookGoal(name: String, goal: String) { projectInfo[name]?.goal = goal.trim(); saveProjects() }

    /** The day (a day number) the person wants to reach the goal of a study notebook by; 0 removes it. */
    fun setNotebookDeadline(name: String, epochDay: Long) { projectInfo[name]?.deadline = epochDay.coerceAtLeast(0L); saveProjects() }

    fun setNotebookUseNotes(name: String, on: Boolean) { projectInfo[name]?.useNotes = on; saveProjects() }

    fun setStudyLesson(name: String, lesson: String, status: String) {
        val plan = projectInfo[name]?.plan
        if (plan?.mark(lesson, status, null)?.status == Lesson.DONE) plan.log.record()
        saveProjects()
    }

    /** Photos, files and videos added to a notebook: copied into the app, then read and described (see [summarizeFile]). */
    fun addSources(name: String, uris: List<Uri>) {
        val info = projectInfo[name] ?: return
        if (uris.isEmpty()) return
        importing += uris.size
        viewModelScope.launch {
            for (uri in uris) {
                try {
                    val type = context.contentResolver.getType(uri).orEmpty()
                    val made = when {
                        type.startsWith("image/") && !type.contains("svg") -> attachmentStore.importImage(uri, 1600, 86, 4096)
                        type.startsWith("video/") -> attachmentStore.importVideo(uri)
                        else -> attachmentStore.importFile(uri)
                    }
                    if (made == null) Toast.makeText(context, context.getString(R.string.attach_unsupported), Toast.LENGTH_SHORT).show()
                    else {
                        val named = made.copy(name = uniqueSourceName(info, made.name))
                        info.sources += named
                        libraryMeta.seen(named.id, System.currentTimeMillis())
                        saveProjects()
                        summarizeFile(named)
                    }
                } finally {
                    importing -= 1
                }
            }
        }
    }

    private fun uniqueSourceName(info: ProjectInfo, name: String): String = SpaceRules.numberedName(name, info.sources.map { it.name })

    /**
     * Something shared from another app goes into a notebook as sources: the links are read and kept, a text of its own is kept as a
     * note, files are added as they are. Gives how many things were taken (the page tells that it is done).
     */
    fun addShared(name: String, text: String, uris: List<Uri>) {
        val info = projectInfo[name] ?: return
        val (urls, rest) = com.apvcode.devx.data.splitShare(text)
        addSources(name, uris)
        if (rest.isNotEmpty()) viewModelScope.launch {
            val title = rest.lineSequence().first().take(40).trim().trimEnd('.', ',', ':').ifBlank { context.getString(R.string.share_note_name) }
            val saved = withContext(Dispatchers.IO) { attachmentStore.saveText(uniqueSourceName(info, "$title.txt"), rest) }
            info.sources += saved
            libraryMeta.seen(saved.id, System.currentTimeMillis())
            saveProjects()
            summarizeFile(saved)
        }
        if (urls.isNotEmpty()) viewModelScope.launch {
            val failed = addSites(name, urls)
            if (failed > 0) Toast.makeText(context, context.getString(R.string.share_link_failed, failed), Toast.LENGTH_LONG).show()
        }
    }

    /** Web pages added to a notebook by their addresses: the text that can be seen on each page is kept as a source. Gives how many could not be read. */
    suspend fun addSites(name: String, urls: List<String>): Int {
        val info = projectInfo[name] ?: return urls.size
        var failed = 0
        for (raw in urls.map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(10)) {
            val url = if (raw.startsWith("http")) raw else "https://$raw"
            if (info.sources.any { it.url == url }) continue
            val page = runCatching { webTools.readPage(url) }.logged("chatviewmodel").getOrNull()
            if (page == null) { failed++; continue }
            val title = page.title.ifBlank { runCatching { URI(url).host.orEmpty().removePrefix("www.") }.getOrDefault(url) }.take(80)
            val saved = withContext(Dispatchers.IO) { attachmentStore.saveText("$title.txt", "$title\n$url\n\n${page.text.take(200_000)}") }
            val att = saved.copy(url = url)
            info.sources += att
            libraryMeta.seen(att.id, System.currentTimeMillis())
            saveProjects()
            summarizeFile(att)
        }
        return failed
    }

    fun removeSource(name: String, id: String) {
        val info = projectInfo[name] ?: return
        val a = info.sources.firstOrNull { it.id == id } ?: return
        info.sources.remove(a)
        saveProjects()
        if (!usedElsewhere(a, null)) {
            viewModelScope.launch(Dispatchers.IO) { attachmentStore.delete(a) }
            libraryMeta.forget(a.id)
        }
    }

    /** A text kept as a source (by the assistant, with source_add_text). Gives words for the model. */
    private suspend fun addTextSource(name: String, title: String, text: String): String = runCatching {
        val info = projectInfo[name] ?: return@runCatching "There is no such notebook."
        val base = title.trim().take(80).replace(Regex("[\\\\/:*?\"<>|\\n]"), " ").trim().ifEmpty { "Note" }
        val fileName = if (base.contains('.') && base.substringAfterLast('.').length in 1..5) base else "$base.txt"
        val saved = withContext(Dispatchers.IO) { attachmentStore.saveText(uniqueSourceName(info, fileName), text.take(200_000)) }
        info.sources += saved
        libraryMeta.seen(saved.id, System.currentTimeMillis())
        saveProjects()
        summarizeFile(saved)
        "Saved as the source \"${saved.name}\" (${saved.size} bytes). The person sees it on the notebook's page."
    }.logged("chatviewmodel").getOrDefault("Could not save the source.")

    /** The person changes the concept map by hand; [change] gives true when it did something, and then the notebook is saved. */
    fun editGraph(name: String, change: (com.apvcode.devx.data.StudyPlan) -> Boolean): Boolean {
        val info = projectInfo[name] ?: return false
        val done = change(info.plan)
        if (done) saveProjects()
        return done
    }

    /**
     * What the assistant changes in a source with source_edit: its name, or the text of a text source (one passage, or all of it).
     * Gives what was done, in words for the model.
     */
    private suspend fun editSource(name: String, id: String, a: org.json.JSONObject): String = runCatching {
        val info = projectInfo[name] ?: return@runCatching "There is no such notebook."
        val at = info.sources.indexOfFirst { it.id == id }
        if (at < 0) return@runCatching "The source is gone."
        var source = info.sources[at]
        val done = ArrayList<String>()
        val newName = a.optString("new_name").trim().take(120)
        if (newName.isNotEmpty() && newName != source.name) {
            if (info.sources.any { it.id != id && it.name.equals(newName, true) }) return@runCatching "The name \"$newName\" is taken by another source."
            source = source.copy(name = newName)
            done += "renamed to \"$newName\""
        }
        val whole = if (a.has("content")) a.optString("content") else null
        val find = a.optString("find")
        if (whole != null || find.isNotEmpty()) {
            if (source.kind != AttachmentKind.Text || source.original.isNotEmpty() || source.isQuote) {
                return@runCatching "\"${source.name}\" is not a plain text source, so its text cannot be changed" + (if (done.isEmpty()) " (it can only be renamed)." else ".")
            }
            val file = attachmentStore.fileOf(source)
            val old = withContext(Dispatchers.IO) { runCatching { file.readText() }.getOrDefault("") }
            val text = if (whole != null) whole else {
                val first = old.indexOf(find)
                if (first < 0) return@runCatching "That passage is not in \"${source.name}\": read the source again and copy it exactly."
                if (old.indexOf(find, first + find.length) >= 0 && !a.optBoolean("all")) return@runCatching "That passage occurs more than once: give a longer one so that it is unique."
                old.replace(find, a.optString("replace"))
            }
            if (text.isBlank()) return@runCatching "The text cannot be empty: to take the source away, remove it."
            val bytes = withContext(Dispatchers.IO) { file.writeText(text); file.length() }
            source = source.copy(size = bytes)
            done += if (whole != null) "text rewritten" else "passage replaced"
        }
        if (done.isEmpty()) return@runCatching "Nothing was changed."
        info.sources[at] = source
        saveProjects()
        if (source.kind == AttachmentKind.Text && done.any { it.startsWith("text") || it.startsWith("passage") }) summarizeFile(source, force = true)
        "Done: " + done.joinToString(", ") + ". The notebook's page shows it now."
    }.logged("chatviewmodel").getOrDefault("Could not change the source.")

    /**
     * Reads a file once and writes down, in a sentence or two, what it is about: that is what the notebook and the library show next to it,
     * and what the assistant is told about each source. The model chosen for summaries does it (or the one of the chats).
     */
    fun summarizeFile(a: Attachment, force: Boolean = false) {
        if (a.isQuote) return
        if (!force && libraryMeta.summaries.containsKey(a.id)) return
        if (libraryMeta.states[a.id] == "pending") return
        val ref = Prefs.summaryModel?.takeIf { r -> providers.any { it.id == r.providerId } } ?: model ?: return
        val provider = providers.firstOrNull { it.id == ref.providerId } ?: return
        libraryMeta.states[a.id] = "pending"
        viewModelScope.launch {
            try {
                val (text, images) = withContext(Dispatchers.IO) { contentForSummary(a) }
                val prompt = "Describe this file for the index of a notebook, in one or two short sentences (at most 220 characters): what it is and what it is about. " +
                    "Write in the language of the file's text (in ${java.util.Locale.getDefault().displayLanguage} if it has none). Answer with the description only: no preamble, no quotes.\n\n" +
                    "File: ${a.name}\n\n" + text
                val key = secrets.get(provider.id).orEmpty()
                val wire = provider.copy(kind = formatOf(provider, ref.model))
                val answer = kotlinx.coroutines.withTimeoutOrNull(90_000) {
                    api.complete(wire, key, ref.model, "", listOf(WireMessage("user", prompt, images)))
                }
                val line = answer?.trim()?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim()?.trim('"', '«', '»')?.take(260)
                if (line.isNullOrBlank()) libraryMeta.states[a.id] = "failed" else libraryMeta.setSummary(a.id, line)
            } catch (e: CancellationException) {
                libraryMeta.states.remove(a.id)
                throw e
            } catch (e: Exception) {
                libraryMeta.states[a.id] = "failed"
            }
        }
    }

    /** What the model is shown of a file to describe it: its text (cut), or the picture itself. */
    private fun contentForSummary(a: Attachment): Pair<String, List<WireImage>> {
        val file = attachmentStore.fileOf(a)
        val ext = a.name.substringAfterLast('.', "").lowercase()
        return when {
            a.kind == AttachmentKind.Image -> "(the picture is attached)" to listOf(WireImage(a.mime, attachmentStore.readBase64(a)))
            a.kind == AttachmentKind.Video -> "(a video, three frames are attached)" to attachmentStore.videoFrames(a, 3).map { WireImage("image/jpeg", it) }
            a.kind == AttachmentKind.Pdf -> {
                val text = runCatching {
                    com.tom_roush.pdfbox.pdmodel.PDDocument.load(file).use { doc ->
                        com.tom_roush.pdfbox.text.PDFTextStripper().apply { endPage = doc.numberOfPages.coerceAtMost(6) }.getText(doc)
                    }
                }.logged("chatviewmodel").getOrNull().orEmpty().trim()
                (if (text.length > 40) text.take(7_000) else "(a PDF of ${attachmentStore.pdfPages(a) ?: "?"} pages with almost no text: a scan or a drawing)") to emptyList()
            }
            ext in setOf("docx", "xlsx", "pptx", "odt", "ods", "odp") -> (FileText.office(file, ext, 7_000) ?: "(a document whose text could not be read)") to emptyList()
            a.kind == AttachmentKind.Text -> runCatching { file.readText().take(7_000) }.logged("chatviewmodel").getOrDefault("") to emptyList()
            else -> "(a file of ${a.size / 1024} KB that the app cannot read)" to emptyList()
        }
    }

    /** Takes a file out of the library everywhere: from the chats' messages, from the notebooks, and from the phone. */
    fun deleteLibraryEntry(entry: LibraryEntry) {
        val a = entry.attachment
        if (a != null) {
            chatStates.forEach { c ->
                if (c.messages.any { m -> m.attachments.any { it.id == a.id } }) {
                    c.messages.forEach { m -> if (m.attachments.any { it.id == a.id }) m.attachments = m.attachments.filter { it.id != a.id } }
                    save(c)
                }
                if (c.libraryFiles.removeAll { it.id == a.id }) save(c)
            }
            var any = false
            projectInfo.values.forEach { p -> if (p.sources.removeAll { it.id == a.id }) any = true }
            if (any) saveProjects()
            viewModelScope.launch(Dispatchers.IO) { attachmentStore.delete(a) }
        }
        entry.artifact?.let { art ->
            val block = Regex("```devx-file\\n" + Regex.escape(art.id) + "\\n```\\s*")
            chatStates.forEach { c ->
                var touched = false
                c.messages.forEach { m -> if (block.containsMatchIn(m.text)) { m.text = m.text.replace(block, ""); touched = true } }
                if (touched) save(c)
            }
            artifacts.delete(art.id)
        }
        libraryMeta.forget(entry.id)
    }

    /** Puts a file of the library on the next message (the same file, not a copy: it stays one file in the library). */
    fun attachFromLibrary(entry: LibraryEntry): Boolean {
        val a = entry.attachment ?: return false
        if (draftAttachments.any { it.id == a.id }) return true
        draftAttachments += a
        return true
    }

    // ---------- providers and models ----------

    fun providerKey(id: String): String = secrets.get(id).orEmpty()

    /** Every key and token, opened: only for a backup that the person locks with a password. */
    fun exportSecrets(): Map<String, String> = secrets.all()

    /** The keys and tokens of a backup go into this phone's keystore. */
    fun importSecrets(map: Map<String, String>) = secrets.putAll(map)

    /** [key] null leaves the saved key as it is. */
    fun saveProvider(provider: Provider, key: String?) {
        val index = providers.indexOfFirst { it.id == provider.id }
        if (index >= 0) providers[index] = provider else providers += provider
        if (key != null) secrets.put(provider.id, key)
        saveProviders()
    }

    fun deleteProvider(id: String) {
        providers.removeAll { it.id == id }
        secrets.remove(id)
        if (defaultModel?.providerId == id) selectModel(null, remember = true)
        chatStates.filter { it.model?.providerId == id }.forEach { c ->
            c.model = null
            save(c)
        }
        saveProviders()
    }

    /** The choice belongs to the open chat and is kept with it; it also becomes the model new chats start with. */
    fun selectModel(ref: ModelRef?, remember: Boolean = false) {
        if (!remember) current?.let { chat ->
            chat.model = ref
            save(chat)
        }
        if (ref != null || remember) {
            defaultModel = ref
            viewModelScope.launch(Dispatchers.IO) { store.saveModel(ref) }
        }
    }

    /** Pull the list of models again from one provider. */
    fun refreshModels(id: String): Job = viewModelScope.launch { fetchModels(id) }

    /** All providers at once; the splash waits for this, but only for a few seconds. */
    fun refreshAllModels(): Job = viewModelScope.launch {
        providers.map { it.id }.map { async { fetchModels(it) } }.awaitAll()
    }

    private suspend fun fetchModels(id: String) {
        val provider = providers.firstOrNull { it.id == id } ?: return
        refreshing[id] = true
        modelsError.remove(id)
        try {
            val lists = api.discoverModels(provider, secrets.get(id).orEmpty())
            val index = providers.indexOfFirst { it.id == id }
            if (index >= 0) {
                providers[index] = providers[index].copy(
                    models = lists.models,
                    anthropicModels = lists.anthropicOnly,
                    reasoningInfo = lists.info,
                    modelsUpdatedAt = now(),
                )
            }
            saveProviders()
        } catch (e: ApiException) {
            modelsError[id] = e.kind
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            modelsError[id] = ErrorKind.Unknown
        } finally {
            refreshing.remove(id)
        }
    }

    // ---------- generation ----------

    private fun generate(chat: Chat, user: Msg, resume: Msg? = null) {
        val assistant = resume ?: Msg(newId(), user.id, Role.Assistant, "", now())
        if (resume == null) {
            chat.messages += assistant
            fresh += assistant.id
        } else {
            // The answer that broke off goes on in the same message.
            assistant.error = null
            assistant.errorDetail = null
            assistant.stopped = false
        }
        chat.select(assistant)
        streaming[chat.id] = assistant.id
        assistant.inflight = true
        save(chat)

        val ref = chat.model ?: model
        val provider = ref?.let { r -> providers.firstOrNull { it.id == r.providerId } }
        if (ref == null || provider == null) {
            assistant.error = if (providers.isEmpty()) ErrorKind.NoProvider else ErrorKind.NoModel
            streaming.remove(chat.id)
            assistant.inflight = false
            save(chat)
            return
        }
        val key = secrets.get(provider.id).orEmpty()
        val wire = provider.copy(kind = formatOf(provider, ref.model))
        // The title is written at the same time as the answer, from the question alone: it is there (and animated) while the answer is
        // still coming, not after it.
        if (resume == null && !chat.temporary && !chat.titleFinal) {
            chat.titleFinal = true
            chat.naming = true
            val asked = user.text.ifBlank { user.attachments.joinToString { it.name } }
            viewModelScope.launch {
                try {
                    nameChat(chat, wire, key, ref.model, asked)
                } finally {
                    chat.naming = false
                }
            }
        }
        val caps = capsFor(ref)
        val level = levelFor(ref)
        // The voice assistant wants its answer fast: no long thinking.
        val reasoningParams = if (voiceMode) {
            // By voice the answer must start quickly: the least thinking the model allows ("off", else its lowest level). Gateways of
            // OpenAI-style APIs that reason by default often ignore "off" but know a low effort, so that is asked as well.
            val quick = caps?.takeIf { it.supported }?.let { c -> c.build(if (Level.Off in c.levels) Level.Off else c.levels.first()) }
            if (caps != null && caps.supported && wire.kind == ProviderKind.OpenAI) (quick ?: org.json.JSONObject()).also { if (!it.has("reasoning_effort")) it.put("reasoning_effort", "low") } else quick
        } else if (caps != null && level != null) caps.build(level) else null

        syncService()
        jobs[chat.id] = viewModelScope.launch {
            val self = coroutineContext[Job]
            try {
                // The tools it may use: the files attached to this chat (never anything else on the phone), and
                // the web when the person has allowed it. Without any tool this is the plain request it always was.
                val files = filesOf(chat, user)
                // Memory is for the chats that are kept: a temporary chat neither reads it nor writes to it.
                val memoryOn = Prefs.memory && !chat.temporary
                val projectName = chat.project?.takeIf { !chat.temporary && it in projectInfo }
                val project = projectName?.let { projectInfo[it] }
                // By voice the request is kept small (a small request is answered sooner): the phone, the web, the memory and the servers of
                // the person; without files, the workshop, the terminal and the guides.
                val byVoice = voiceMode
                val skillsOn = !byVoice && skills.enabled().isNotEmpty()
                mcp.refreshLocal()
                var tools = toolbox.definitions(if (byVoice) emptyList() else files, Prefs.webSearch, !byVoice && Prefs.workTools, !byVoice && Prefs.terminal, memoryOn, project != null && !byVoice, skillsOn, Prefs.phoneTools, !byVoice && library.isNotEmpty(), !byVoice && project?.isStudy == true, !byVoice && project?.isNotebook == true, !byVoice && project == null && !chat.temporary, !byVoice && Prefs.shopTools)
                fun systemNow() = if (byVoice) SystemPrompt.forVoice(
                    Prefs.nickname,
                    if (memoryOn) memory.forPrompt(hint = user.text) else null,
                    ref.model,
                    if (memoryOn) memory.summary else null,
                    if (Prefs.phoneTools) PhoneTools.promptSection(Prefs.twoGis && !secrets.get(MapTools.KEY_ID).isNullOrBlank()) else null,
                    mentions = mentionSection(chat, user, memoryOn),
                ) else SystemPrompt.forUser(
                    Prefs.nickname,
                    if (memoryOn) memory.forPrompt(hint = user.text) else null,
                    project?.let { SystemPrompt.ProjectContext(projectName!!, it.description, it.instructions, if (memoryOn) it.memory.forPrompt(hint = user.text) else null, if (memoryOn) it.memory.summary else null, if (it.isNotebook) notebookPrompt(chat, projectName!!, it) else null) },
                    if (skillsOn) skills.forPrompt() else null,
                    ref.model,
                    if (memoryOn) memory.summary else null,
                    phone = if (Prefs.phoneTools) PhoneTools.promptSection(Prefs.twoGis && !secrets.get(MapTools.KEY_ID).isNullOrBlank()) else null,
                    voice = if (voiceMode) SystemPrompt.VOICE else null,
                    guidesAsTool = tools.isNotEmpty(),
                    spaces = !byVoice && project == null && !chat.temporary,
                    mentions = mentionSection(chat, user, memoryOn),
                )
                if (Prefs.workTools || Prefs.terminal) withContext(Dispatchers.IO) { workspace.syncUploads(chat.id, files, attachmentStore) }
                var messages = withContext(Dispatchers.IO) { historyFor(chat, user, withTools = tools.isNotEmpty()) }.toMutableList()
                if (resume != null) {
                    // Going on after a break: the model is shown what it had said and done, and told to continue.
                    val partial = assistant.text.trim()
                    if (partial.isNotEmpty()) messages += WireMessage("assistant", partial)
                    messages.addContinuation(continuationNote(assistant))
                }
                var system = systemNow()
                var params = reasoningParams
                val report = object : ToolReport {
                    override fun allowed(serverId: String) = serverId in (mcpAllowed[chat.id] ?: emptySet())

                    override suspend fun approve(request: ToolApproval): ApprovalDecision {
                        val answer = CompletableDeferred<ApprovalDecision>()
                        val pendingApproval = PendingApproval(request) { answer.complete(it) }
                        withContext(Dispatchers.Main.immediate) { assistant.approval = pendingApproval }
                        // With the app away, a notification carries the question and its buttons.
                        ApprovalBridge.put(chat.id, pendingApproval)
                        if (!com.apvcode.devx.AppState.foreground) com.apvcode.devx.GenerationService.announceApproval(context, chat.id, chat.title, request)
                        try {
                            // Nobody answers for a long time: it counts as "cancel".
                            val said = kotlinx.coroutines.withTimeoutOrNull(10 * 60_000L) { answer.await() } ?: ApprovalDecision.Deny
                            if (said == ApprovalDecision.ThisChat) mcpAllowed.getOrPut(chat.id) { HashSet() } += request.serverId
                            return said
                        } finally {
                            withContext(NonCancellable + Dispatchers.Main.immediate) { assistant.approval = null }
                            ApprovalBridge.remove(chat.id)
                            com.apvcode.devx.GenerationService.withdrawApproval(context, chat.id)
                        }
                    }

                    override fun activity(activity: ToolActivity?) {
                        viewModelScope.launch(Dispatchers.Main.immediate) {
                            assistant.activity = activity
                            // Every step stays on the record; the same step again only fills in what came of it.
                            if (activity != null) {
                                val last = assistant.steps.lastOrNull()
                                val same = { s: ToolActivity -> s.kind == activity.kind && s.detail == activity.detail }
                                val earlier = if (activity.note.isNotBlank()) assistant.steps.indexOfLast(same) else -1
                                when {
                                    earlier >= 0 -> assistant.steps[earlier] = assistant.steps[earlier].copy(note = activity.note)
                                    last != null && same(last) -> Unit
                                    else -> assistant.steps += activity.copy(at = assistant.reasoning.length)
                                }
                            }
                        }
                    }

                    override fun artifact(artifact: Artifact) {
                        // The card stands in the answer where the assistant made it: a fenced block the screen turns into a card.
                        viewModelScope.launch(Dispatchers.Main.immediate) {
                            assistant.text += "\n\n```devx-file\n" + artifact.id + "\n```\n\n"
                        }
                    }

                    override fun source(source: Source) {
                        viewModelScope.launch(Dispatchers.Main.immediate) {
                            val at = assistant.sources.indexOfFirst { it.url == source.url }
                            if (at < 0) assistant.sources += source
                            else if (source.opened && !assistant.sources[at].opened) assistant.sources[at] = source
                        }
                    }
                }

                // Thinking arrives first, then the answer; how long the thinking took is kept for the "Thinking" page.
                var thinkingFrom = 0L
                var savedAt = now()
                // What came last: 0 nothing, 1 words of the answer, 2 thinking, 3 a step. A new paragraph starts after 2 and 3.
                var lastKind = if (assistant.text.isNotEmpty()) 3 else 0
                class Round {
                    val calls = ArrayList<Piece.ToolCall>()
                    val thinking = ArrayList<Piece.ThinkingBlock>()
                    val text = StringBuilder()
                    val reasoning = StringBuilder()
                }
                suspend fun runRound(round: Round, sent: List<WireMessage>, extra: org.json.JSONObject?, offered: List<WireTool>): Round {
                    var usedIn = 0
                    var usedOut = 0
                    api.stream(wire, key, ref.model, system, sent, extra, offered).collect { piece ->
                        if (assistant.reconnect != 0) assistant.reconnect = 0
                        if (assistant.activity != null && piece !is Piece.ToolCall) assistant.activity = null
                        when (piece) {
                            is Piece.Reasoning -> {
                                if (thinkingFrom == 0L) thinkingFrom = now()
                                assistant.reasoning += piece.text
                                round.reasoning.append(piece.text)
                                lastKind = 2
                            }
                            is Piece.Text -> {
                                if (thinkingFrom != 0L && assistant.thoughtSeconds == 0) {
                                    assistant.thoughtSeconds = ((now() - thinkingFrom) / 1000).toInt().coerceAtLeast(1)
                                }
                                val glued = assistant.text.isNotEmpty() && !assistant.text.last().isWhitespace()
                                if (TitleChunk.looksLikeSummary(piece.text, glued || lastKind == 2 && round.reasoning.endsWith("\n\n"))) {
                                    // A gateway handed a thinking summary over as words of the answer: it belongs to the thinking.
                                    if (thinkingFrom == 0L) thinkingFrom = now()
                                    val title = piece.text.trim() + "\n\n"
                                    assistant.reasoning += title
                                    round.reasoning.append(title)
                                    lastKind = 2
                                } else {
                                    // After thinking or a step the next words start a paragraph, they are not glued to the last ones.
                                    if (lastKind != 1 && assistant.text.isNotEmpty() && !assistant.text.endsWith("\n")) assistant.text += "\n\n"
                                    assistant.text += piece.text
                                    round.text.append(piece.text)
                                    lastKind = 1
                                }
                            }
                            is Piece.ToolCall -> { round.calls += piece; lastKind = 3 }
                            is Piece.ThinkingBlock -> round.thinking += piece
                            is Piece.Usage -> { usedIn = piece.input; usedOut = piece.output }
                        }
                        // Keep what is written so far on disk, so a killed app loses almost nothing.
                        if (now() - savedAt > 2000) {
                            savedAt = now()
                            save(chat)
                        }
                    }
                    // What the provider counted for this request is added to the answer (a tool round makes several).
                    if (usedIn > 0 || usedOut > 0) {
                        assistant.tokensIn += usedIn
                        assistant.tokensOut += usedOut
                        assistant.contextTokens = usedIn + usedOut
                    }
                    return round
                }

                var toolRounds = 0
                var netTries = 0
                while (true) {
                    var attempt = Round()
                    val round = try {
                        runRound(attempt, messages, params, tools)
                    } catch (e: ApiException) {
                        val nothingYet = assistant.text.isEmpty() && assistant.reasoning.isEmpty()
                        if (e.kind == ErrorKind.BadRequest && nothingYet && tools.isNotEmpty() && toolRounds == 0) {
                            // The provider does not take tools: go on without them, with the files given whole as before.
                            tools = emptyList()
                            messages = withContext(Dispatchers.IO) { historyFor(chat, user, withTools = false) }.toMutableList()
                            continue
                        }
                        // The endpoint may not know the reasoning setting: the request is repeated once without it.
                        if (e.kind == ErrorKind.BadRequest && params != null && nothingYet) {
                            params = null
                            continue
                        }
                        // The line broke (a tunnel, a bad signal): try again, and again, and go on from where the words stopped.
                        val limit = when (e.kind) { ErrorKind.NoInternet, ErrorKind.Timeout -> MAX_NET_TRIES; ErrorKind.ProviderDown -> 3; else -> 0 }
                        if (netTries < limit) {
                            netTries++
                            assistant.reconnect = netTries
                            assistant.activity = null
                            val partial = attempt.text.toString().trim()
                            if (partial.isNotEmpty()) {
                                messages += WireMessage("assistant", partial)
                                messages.addContinuation(RECONNECT_NOTE)
                            }
                            save(chat)
                            awaitConnection(RECONNECT_DELAYS_MS[(netTries - 1).coerceAtMost(RECONNECT_DELAYS_MS.lastIndex)])
                            continue
                        }
                        throw e
                    }
                    netTries = 0
                    // A call that was written out as text instead of being made: take it as the call it meant to be.
                    var calls: List<Piece.ToolCall> = round.calls
                    var roundText = round.text.toString()
                    if (calls.isEmpty() && tools.isNotEmpty()) {
                        val found = TextToolCalls.find(round.reasoning.toString() + "\n" + roundText, tools)
                        if (found.isNotEmpty()) {
                            calls = found
                            if (TextToolCalls.has(roundText)) {
                                roundText = TextToolCalls.strip(roundText)
                                assistant.text = TextToolCalls.strip(assistant.text)
                            }
                        }
                    }
                    if (calls.isEmpty() || tools.isEmpty()) break

                    toolRounds++
                    messages += WireMessage(
                        "assistant",
                        roundText,
                        toolCalls = calls.map { WireToolCall(it.id, it.name, it.arguments) },
                        thinking = round.thinking,
                        reasoningContent = if (wire.baseUrl.contains("openai.com")) "" else round.reasoning.toString(),
                    )
                    // Reading and searching do not depend on each other: when that is all it asks for, it is done at once.
                    val outcomes = if (calls.size > 1 && calls.all { it.name in PARALLEL_SAFE }) {
                        kotlinx.coroutines.coroutineScope {
                            calls.map { c ->
                                async { toolbox.run(WireToolCall(c.id, c.name, c.arguments), files, report, chat.id, projectName) }
                            }.awaitAll()
                        }
                    } else {
                        calls.map { c -> toolbox.run(WireToolCall(c.id, c.name, c.arguments), files, report, chat.id, projectName) }
                    }
                    val results = calls.mapIndexed { i, c -> WireToolResult(c.id, c.name, outcomes[i].text, outcomes[i].images) }
                    // The tools are done: what is left is the model reading what they found, and that is shown as thinking.
                    assistant.activity = null
                    messages += WireMessage("tool", "", toolResults = results)
                    // The notes may have changed during this round: the next one is told how they stand now.
                    if (memoryOn && calls.any { it.name in MEMORY_WRITES }) system = systemNow()
                    if (toolRounds >= MAX_TOOL_ROUNDS) {
                        tools = emptyList()
                        messages += WireMessage("user", "(The limit of tool calls is reached. Answer now with what you have found.)")
                    }
                }
                assistant.activity = null
            } catch (e: ApiException) {
                assistant.error = e.kind
                assistant.errorDetail = e.detail
            } catch (e: CancellationException) {
                assistant.stopped = true
                throw e
            } catch (e: Exception) {
                assistant.error = ErrorKind.Unknown
                assistant.errorDetail = e.message
            } finally {
                withContext(NonCancellable) {
                    // Only if it is still ours: after "stop" a new answer may already have begun.
                    if (streaming[chat.id] == assistant.id) streaming.remove(chat.id)
                    if (jobs[chat.id] === self) jobs.remove(chat.id)
                    assistant.inflight = false
                    assistant.activity = null
                    syncService()
                    if (!com.apvcode.devx.AppState.foreground && assistant.error == null && assistant.text.isNotBlank() && !chat.temporary) {
                        com.apvcode.devx.GenerationService.announceAnswer(context, chat.id, chat.title, assistant.text)
                    }
                    if (assistant.text.isEmpty() && assistant.reasoning.isEmpty() && assistant.error == null && assistant.steps.isEmpty() && assistant.sources.isEmpty()) {
                        // Stopped before the first word: leave no empty answer behind.
                        chat.messages.remove(assistant)
                        chat.selected.remove(user.id)
                    }
                    touch(chat)
                }
            }
        }
    }

    /** Waits before the next try: until the network is back if there is none, or for [ms]. */
    private suspend fun awaitConnection(ms: Long) {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val offline = runCatching { cm?.activeNetwork == null }.getOrDefault(false)
        if (!offline || cm == null) { delay(ms); return }
        kotlinx.coroutines.withTimeoutOrNull(ms * 2) {
            kotlinx.coroutines.suspendCancellableCoroutine<Unit> { cont ->
                val callback = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) { if (cont.isActive) cont.resume(Unit) {} }
                }
                val ok = runCatching { cm.registerDefaultNetworkCallback(callback) }.isSuccess
                if (!ok) cont.resume(Unit) {}
                cont.invokeOnCancellation { runCatching { cm.unregisterNetworkCallback(callback) } }
            }
        }
        // The network says it is back a moment before it carries anything.
        delay(700)
    }

    /**
     * What the model is sent: the path up to this message. Text files are always put into the
     * text; photos and PDFs go along only for the last few of my messages (older ones are named in
     * a note), so a long chat does not carry every picture again and again.
     */
    /** The files the model may look at: everything attached in this chat up to the message it answers. */
    /** The notebooks and projects pointed at with @ in the messages of this conversation up to [upTo] (that still exist; not the place the chat is in). */
    private fun mentionedIn(chat: Chat, upTo: Msg): List<String> {
        val path = chat.activePath()
        val end = path.indexOfFirst { it.id == upTo.id }.let { if (it < 0) path.size - 1 else it }
        return path.take(end + 1).filter { it.role == Role.User }.flatMap { it.mentions }.distinct()
            .filter { it.startsWith("task:") || (it in projectInfo && it != chat.project) }
    }

    /** What the assistant is told about what was pointed at with @: a notebook or project (what it is, its sources, plan, notes and chats). */
    private fun mentionSection(chat: Chat, upTo: Msg, memoryOn: Boolean): String? {
        val hint = upTo.text
        val keys = mentionedIn(chat, upTo)
        val list = keys.filter { !it.startsWith("task:") }.mapNotNull { name ->
            val info = projectInfo[name] ?: return@mapNotNull null
            val chats = chatStates.filter { it.project == name && it.id != chat.id && !it.temporary && !it.locked }.sortedByDescending { it.updatedAt }.take(8).map { c ->
                val said = c.activePath().lastOrNull { it.role == Role.Assistant && it.text.isNotBlank() }?.text.orEmpty().replace(Regex("\\s+"), " ").trim().take(140)
                NotebookPrompt.ChatLine(c.title, said)
            }
            NotebookPrompt.Mentioned(
                name, info.isNotebook, info.isStudy, info.description, info.instructions, info.goal,
                info.sources.map { a -> NotebookPrompt.SourceLine(a.name, com.apvcode.devx.data.libKindOf(a.name, a.mime, a.kind, a.url), libraryMeta.summaries[a.id].orEmpty(), a.url) },
                chats, if (info.isStudy) info.plan else null,
                if (memoryOn) info.memory.forPrompt(limit = 1800, hint = hint) else "", if (memoryOn) info.memory.summary else "",
            )
        }
        val extras = ArrayList<Pair<String, String>>()
        keys.filter { it.startsWith("task:") }.forEach { key ->
            val t = Tasks.get(context, key.removePrefix("task:").toIntOrNull() ?: -1) ?: return@forEach
            val last = t.lastChatId?.let { id -> chatStates.firstOrNull { it.id == id } }?.activePath()?.lastOrNull { it.role == Role.Assistant && it.text.isNotBlank() }?.text.orEmpty().trim().take(1200)
            extras += t.title to ("A scheduled task: " + Tasks.daysText(t.days) + " at %02d:%02d, ".format(t.hour, t.minute) + (if (t.enabled) "on" else "off") + ".\nIts prompt: " + t.prompt.take(600) + (if (last.isNotEmpty()) "\nThe last answer it gave:\n$last" else ""))
        }
        return NotebookPrompt.mentions(list, extras)
    }

    private fun filesOf(chat: Chat, upTo: Msg): List<Attachment> {
        val path = chat.activePath()
        val end = path.indexOfFirst { it.id == upTo.id }.let { if (it < 0) path.size - 1 else it }
        // What was attached in the chat, what the notebook it is in was given, and what was taken from the library for it.
        val sources = chat.project?.let { projectInfo[it]?.sources }.orEmpty()
        // And the sources of the notebooks and projects pointed at with @ in this conversation.
        val pointed = mentionedIn(chat, upTo).flatMap { projectInfo[it]?.sources.orEmpty() }
        return (path.take(end + 1).filter { it.role == Role.User }.flatMap { it.attachments }.filter { !it.isQuote } + sources + pointed + chat.libraryFiles).distinctBy { it.id }
    }

    /**
     * A rough size, in tokens, of what the next request in this chat would carry: the system prompt (with memory, the
     * project and the skills), the tools it offers, the whole conversation as it is sent, and the pictures. About four
     * letters make a token; a picture is counted as a fixed amount. It is a guide, not the provider's own count.
     */
    suspend fun contextEstimate(chat: Chat): Int = withContext(Dispatchers.IO) {
        val upTo = chat.activePath().lastOrNull() ?: return@withContext 0
        val files = filesOf(chat, upTo)
        val memoryOn = Prefs.memory && !chat.temporary
        val projectName = chat.project?.takeIf { !chat.temporary && it in projectInfo }
        val project = projectName?.let { projectInfo[it] }
        val skillsOn = skills.enabled().isNotEmpty()
        val tools = toolbox.definitions(files, Prefs.webSearch, Prefs.workTools, Prefs.terminal, memoryOn, project != null, skillsOn, Prefs.phoneTools, library.isNotEmpty(), project?.isStudy == true, project?.isNotebook == true, project == null && !chat.temporary, Prefs.shopTools)
        val system = SystemPrompt.forUser(
            Prefs.nickname,
            if (memoryOn) memory.forPrompt(hint = upTo.text) else null,
            project?.let { SystemPrompt.ProjectContext(projectName!!, it.description, it.instructions, if (memoryOn) it.memory.forPrompt(hint = upTo.text) else null, if (memoryOn) it.memory.summary else null, if (it.isNotebook) notebookPrompt(chat, projectName!!, it) else null) },
            if (skillsOn) skills.forPrompt() else null,
            null,
            if (memoryOn) memory.summary else null,
            phone = if (Prefs.phoneTools) PhoneTools.promptSection(Prefs.twoGis && !secrets.get(MapTools.KEY_ID).isNullOrBlank()) else null,
            guidesAsTool = tools.isNotEmpty(),
            spaces = project == null && !chat.temporary,
            mentions = mentionSection(chat, upTo, memoryOn),
        )
        val messages = historyFor(chat, upTo, withTools = tools.isNotEmpty())
        val letters = system.length +
            tools.sumOf { it.name.length + it.description.length + it.parameters.toString().length } +
            messages.sumOf { it.text.length }
        val pictures = messages.sumOf { it.images.size } * 1_100 + messages.sumOf { it.documents.size } * 2_000
        letters / 4 + pictures
    }

    private fun historyFor(chat: Chat, upTo: Msg, withTools: Boolean = false): List<WireMessage> {
        val path = chat.activePath()
        val end = path.indexOfFirst { it.id == upTo.id }.let { if (it < 0) path.size - 1 else it }
        var slice = path.take(end + 1)
        // A shortened chat: what came before the cut is not sent, its summary is sent instead.
        val cut = chat.summaryUpTo?.let { id -> slice.indexOfFirst { it.id == id } } ?: -1
        val told = chat.summary.isNotBlank() && cut >= 0
        if (told) slice = slice.drop(cut + 1)
        val recent = slice.filter { it.role == Role.User }.takeLast(RESEND_LAST_MESSAGES).map { it.id }.toSet()
        var bytes = 0L
        val out = ArrayList<WireMessage>()
        if (told) {
            out += WireMessage("user", "The earlier part of this conversation was shortened to save room. This is what it said:\n\n" + chat.summary)
            out += WireMessage("assistant", "Understood. I will go on from there.")
        }
        for (m in slice) {
            if (m.text.isBlank() && m.attachments.isEmpty()) continue
            val role = if (m.role == Role.User) "user" else "assistant"
            var text = m.text
            // What the person quoted comes first, as a reply does: the question is about it.
            val quoted = m.attachments.filter { it.isQuote }.joinToString("\n\n") { q ->
                "[The person quotes this from the conversation:]\n" + attachmentStore.readText(q).lines().joinToString("\n") { "> $it" }
            }
            if (quoted.isNotEmpty()) text = quoted + (if (text.isBlank()) "" else "\n\n" + text)
            val images = ArrayList<WireImage>()
            val documents = ArrayList<WireDocument>()
            val skipped = ArrayList<String>()
            for (a in m.attachments.filter { !it.isQuote }) {
                when (a.kind) {
                    AttachmentKind.Text -> {
                        val body = attachmentStore.readText(a)
                        if (withTools && body.length > INLINE_TEXT_LIMIT) {
                            // Long: the model gets a glance and the tools to read the rest, not all of it at once.
                            text += "\n\n[Attached file: ${a.name}: text, ${body.length} characters. Only the beginning is shown; use read_text or search_in_file for the rest.]\n```\n${body.take(1_500)}\n```"
                        } else {
                            text += "\n\n[File: ${a.name}]\n```\n$body\n```"
                        }
                    }
                    AttachmentKind.Image ->
                        if (m.id in recent) {
                            images += WireImage(a.mime, attachmentStore.readBase64(a).also { bytes += it.length })
                            // A reduced copy is what the model sees: it is told the name and that the full one can be looked at closer.
                            if (withTools && attachmentStore.originalOf(a) != attachmentStore.fileOf(a)) {
                                text += "\n\n[Photo: ${a.name}. The picture is a reduced copy; the full-size one is kept: if something is too small, dark or unclear, look closer with edit_image (cut out an area, rotate, brighten) instead of guessing.]"
                            }
                        } else skipped += a.name
                    AttachmentKind.File ->
                        text += "\n\n[Attached file: ${a.name}, ${a.size / 1024} KB. The app cannot read this type of file, so only its name and size are known.]"
                    AttachmentKind.Video ->
                        if (m.id in recent) {
                            val frames = attachmentStore.videoFrames(a)
                            frames.forEach { bytes += it.length }
                            images += frames.map { WireImage("image/jpeg", it) }
                            text += "\n\n[Video: ${a.name}, ${a.durationMs / 1000} s, shown as ${frames.size} frames in order]"
                        } else skipped += a.name
                    AttachmentKind.Pdf ->
                        if (withTools) {
                            val pages = attachmentStore.pdfPages(a)
                            text += "\n\n[Attached file: ${a.name}: PDF, ${pages?.let { "$it pages" } ?: "pages unknown"}, ${a.size / 1024} KB. Its contents are not included: use the file tools (read_pdf_pages, search_in_file, render_pdf_page; list_pdf_images and edit_image for the pictures inside) to read what you need.]"
                        } else if (m.id in recent && a.size <= WHOLE_PDF_MAX) documents += WireDocument(a.name, a.mime, attachmentStore.readBase64(a).also { bytes += it.length }) else skipped += a.name
                }
            }
            if (skipped.isNotEmpty()) text += "\n\n[Attached earlier and not sent again: ${skipped.joinToString()}]"
            val last = out.lastOrNull()
            if (last != null && last.role == role) {
                out[out.size - 1] = WireMessage(role, last.text + "\n\n" + text, last.images + images, last.documents + documents)
            } else {
                out += WireMessage(role, text, images, documents)
            }
        }
        if (bytes > MAX_REQUEST_BYTES) {
            throw ApiException(ErrorKind.BadRequest, null, "The attachments are too large for one request (${bytes / 1_000_000} MB). Remove some photos.")
        }
        return out
    }

    /** The chat whose context is being compressed now (null when none). */
    var compacting by mutableStateOf<String?>(null)
        private set

    /** What the card in the chat shows while it runs: the summary as the model writes it, since when, and how much is being read. */
    var compressText by mutableStateOf("")
        private set
    var compressStartedAt by mutableLongStateOf(0L)
        private set
    var compressCount by mutableIntStateOf(0)
        private set
    var compressTokens by mutableIntStateOf(0)
        private set

    /** The last message that is being told short: the card is shown under it, where the result will stay. */
    var compressAfter by mutableStateOf<String?>(null)
        private set

    /** The chat that was compressed a moment ago: its card opens on the result and plays the count-down once (null when none). */
    var compactedNow by mutableStateOf<String?>(null)

    /** What a compression did: the size before and after, in rough tokens. */
    class Shortened(val before: Int, val after: Int)

    private var compactJob: Job? = null

    /** Starts the compression; [onFailed] is called when it could not be done (not when it was cancelled). */
    fun startCompaction(id: String, onFailed: () -> Unit) {
        if (compactJob?.isActive == true) return
        compactJob = viewModelScope.launch {
            val done = try {
                compactChat(id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            if (done == null) onFailed()
        }
    }

    /** Stops a compression that is running: the model is no longer waited for and the chat stays as it was. */
    fun cancelCompaction() {
        compactJob?.cancel()
    }

    /**
     * Compresses the context of a long chat: the model tells the earlier part short (and the card in the chat shows the notes as
     * they are written), and from then on only that summary and the last few messages are sent. The messages stay on the
     * screen. Null when there is nothing to compress or no model to ask.
     */
    private suspend fun compactChat(id: String): Shortened? {
        val chat = chatStates.firstOrNull { it.id == id } ?: return null
        if (id in streaming || compacting != null) return null
        val path = chat.activePath()
        val from = chat.summaryUpTo?.let { up -> path.indexOfFirst { it.id == up } }?.takeIf { it >= 0 }?.plus(1) ?: 0
        // Everything so far is told short, up to the very last message: the card stands under the end of the chat, where the person is.
        val cut = path.size
        if (cut - from < 2) return null
        val ref = chat.model ?: model ?: return null
        val provider = providers.firstOrNull { it.id == ref.providerId } ?: return null
        val key = secrets.get(provider.id).orEmpty()
        val wire = provider.copy(kind = formatOf(provider, ref.model))
        val told = path.subList(from, cut).filter { it.text.isNotBlank() }
        compressText = ""
        compressCount = told.size
        compressStartedAt = SystemClock.elapsedRealtime()
        compressAfter = path[cut - 1].id
        compacting = id
        try {
            val before = contextEstimate(chat)
            compressTokens = before
            // Long messages are cut short in the middle so that the whole fits in one request.
            val per = (200_000 / told.size.coerceAtLeast(1)).coerceIn(600, 6_000)
            val transcript = told.joinToString("\n\n") { m ->
                val who = if (m.role == Role.User) "User" else "Assistant"
                val text = m.text.trim().let { if (it.length > per) it.take(per * 2 / 3) + "\n[...]\n" + it.takeLast(per / 3) else it }
                "$who: $text"
            }
            val prompt = buildString {
                append("Shorten the conversation below into notes that let it continue without the original messages. ")
                append("Keep: what the person wants, decisions made, facts and names, file names, commands and code that matter, open questions, and the person's preferences. ")
                append("The end of the conversation matters most: say exactly what was being discussed last, what was decided, and what the person is waiting for or about to do. ")
                append("Drop pleasantries and repetition. Write in the language of the conversation, as plain text with short paragraphs or a list, no preamble.\n\n")
                if (chat.summary.isNotBlank() && from > 0) append("Notes so far:\n").append(chat.summary).append("\n\n")
                append("Conversation:\n").append(transcript)
            }
            // The notes are read as they arrive, so that the card shows them being written.
            api.stream(wire, key, ref.model, "", listOf(WireMessage("user", prompt))).collect { piece ->
                if (piece is Piece.Text) compressText += piece.text
            }
            val summary = compressText.trim()
            if (summary.isBlank()) return null
            chat.summary = summary
            chat.summaryUpTo = path[cut - 1].id
            // What the provider counted for the answers so far was the size before this: it no longer says how big the chat is.
            path.forEach { it.contextTokens = 0 }
            val after = contextEstimate(chat)
            chat.summaryBefore = before
            chat.summaryAfter = after
            chat.summaryCount = told.size
            chat.summarySeconds = ((SystemClock.elapsedRealtime() - compressStartedAt) / 1000).toInt().coerceAtLeast(1)
            // The card opens on the result, and its sizes count down from the old number to the new one, this once.
            compactedNow = id
            save(chat)
            return Shortened(before, after)
        } finally {
            compacting = null
            compressAfter = null
            compressText = ""
        }
    }

    private suspend fun nameChat(chat: Chat, provider: Provider, key: String, model: String, question: String) {
        val prompt = "Write a very short title (at most 5 words) for a conversation that begins with the message below, in the same language as the message. " +
            "Say what it is about, not what is asked of the assistant. Answer with the title only: no quotes, no full stop.\n\nMessage: ${question.take(700)}"
        val before = chat.title
        val title = runCatching { api.complete(provider, key, model, "", listOf(WireMessage("user", prompt))) }.logged("chatviewmodel")
            .getOrNull()?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim()?.trim('"', '«', '»', '.', '*', '#', ' ')?.take(60)
        // Not if the person gave the chat a name of their own while the model was thinking.
        if (!title.isNullOrBlank() && chat.title == before) {
            chat.title = title
            save(chat)
        }
    }

    // ---------- saving ----------

    // ---------- skills ----------

    /**
     * Makes a skill from instructions the person wrote or pasted. A name or a description that was left empty is
     * written by the model from the instructions (or, if it cannot be asked, made from the first words). Gives the
     * skill's name.
     */
    suspend fun createSkill(name: String, description: String, body: String): String {
        var finalName = name.trim()
        var finalDescription = description.trim()
        if (finalName.isEmpty() || finalDescription.isEmpty()) {
            val made = runCatching { describeSkill(body) }.logged("chatviewmodel").getOrNull()
            if (finalName.isEmpty()) {
                finalName = made?.first?.let { skillFolderNameOrNull(it) } ?: skillFolderNameOrNull(body.trim().split(Regex("\\s+")).take(3).joinToString("-")) ?: "skill"
            }
            if (finalDescription.isEmpty()) {
                finalDescription = made?.second?.takeIf { it.isNotBlank() } ?: body.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().take(150)
            }
        }
        return skills.create(finalName, finalDescription, body)
    }

    /** A usable name, or null when nothing of it is left after the cleaning (a name in other letters, say). */
    private fun skillFolderNameOrNull(raw: String): String? =
        raw.lowercase().replace(Regex("[^a-z0-9_-]+"), "-").trim('-').take(40).ifEmpty { null }

    private suspend fun describeSkill(body: String): Pair<String, String>? {
        val ref = Prefs.summaryModel?.takeIf { r -> providers.any { it.id == r.providerId } } ?: model ?: return null
        val provider = providers.firstOrNull { it.id == ref.providerId } ?: return null
        val wire = provider.copy(kind = formatOf(provider, ref.model))
        val prompt = "Below are instructions a person wrote for an AI assistant: a \"skill\". Give it a name and a description.\n" +
            "- name: two or three words in lower case latin letters joined by hyphens (for example \"weekly-report\").\n" +
            "- description: one or two sentences, at most 200 characters, in the language of the instructions: what the skill is for and when the assistant should use it.\n" +
            "Answer with one JSON object and nothing else: {\"name\": \"...\", \"description\": \"...\"}\n\nInstructions:\n" + body.take(6000)
        val text = kotlinx.coroutines.withTimeoutOrNull(60_000) {
            api.complete(wire, secrets.get(provider.id).orEmpty(), ref.model, "", listOf(WireMessage("user", prompt)))
        } ?: return null
        val from = text.indexOf('{')
        val to = text.lastIndexOf('}')
        if (from < 0 || to <= from) return null
        val json = org.json.JSONObject(text.substring(from, to + 1))
        return json.optString("name") to json.optString("description")
    }

    // ---------- memory ----------

    /** The chats the assistant may search: kept ones that are not locked. A snapshot, safe to read anywhere. */
    private fun chatDigests(): List<ChatDigest> = chatStates.filter { !it.temporary && !it.locked }.map { c ->
        ChatDigest(
            c.id, c.title, c.updatedAt,
            c.activePath().filter { it.text.isNotBlank() }.map { DigestMessage(it.role, it.text, it.createdAt) },
            c.project,
        )
    }

    private fun memoryOf(project: String?): MemoryStore? = if (project == null) memory else projectInfo[project]?.memory

    /** True when the overview (of the person, or of a project) is missing or out of date and there is something to write it from. */
    fun memorySummaryStale(project: String? = null): Boolean {
        val store = memoryOf(project) ?: return false
        // A summary the person wrote is theirs: it is not rewritten behind their back.
        if ((project == null && !Prefs.memory) || store.summarizing || store.summaryManual) return false
        val kept = chatStates.filter { !it.temporary && !it.locked && it.messages.isNotEmpty() && (project == null || it.project == project) }
        val info = project?.let { projectInfo[it] }
        val material = store.notes.isNotEmpty() || kept.isNotEmpty() || (info != null && (info.description.isNotBlank() || info.instructions.isNotBlank()))
        if (!material) return store.summary.isNotEmpty()
        if (store.summary.isEmpty() || store.summaryStale) return true
        val newest = kept.maxOfOrNull { it.updatedAt } ?: 0L
        return newest > store.summaryAt && now() - store.summaryAt > 30 * 60_000
    }

    /**
     * Writes the overview again. For the person: from the notes and their own words in their latest chats.
     * For a project: from what the project is, its instructions, its knowledge base and what was said in its chats.
     */
    fun refreshMemorySummary(project: String? = null) {
        val store = memoryOf(project) ?: return
        if (store.summarizing) return
        val info = project?.let { projectInfo[it] }
        val notes = store.notes.sortedBy { it.createdAt }.joinToString("\n") { "- " + it.text }
        val chats = StringBuilder()
        for (c in chatStates.filter { !it.temporary && !it.locked && (project == null || it.project == project) }.sortedByDescending { it.updatedAt }.take(40)) {
            val path = c.activePath()
            val mine = path.filter { it.role == Role.User && it.text.isNotBlank() }
            if (mine.isEmpty()) continue
            // How it began and where it is now: the first two of the person's messages and the last three, so that the overview is not
            // blind to what happened later in a long chat; and the first words of the last answer, where a decision is often stated.
            val said = (mine.take(2) + mine.takeLast(3)).distinctBy { it.id }
            val lastAnswer = path.lastOrNull { it.role == Role.Assistant && it.text.isNotBlank() }?.text.orEmpty().replace(Regex("\\s+"), " ").trim().take(200)
            val block = "### ${c.title}\n" + said.joinToString("\n") { "- " + it.text.replace(Regex("\\s+"), " ").take(240) } +
                (if (lastAnswer.isNotEmpty()) "\n(the assistant's last answer began: $lastAnswer)" else "") + "\n\n"
            if (chats.length + block.length > 18_000) break
            chats.append(block)
        }
        val about = if (info == null) "" else buildString {
            if (info.description.isNotBlank()) append("What the project is: ${info.description.trim()}\n")
            if (info.instructions.isNotBlank()) append("The person's instructions for it: ${info.instructions.trim()}\n")
        }
        if (notes.isEmpty() && chats.isEmpty() && about.isEmpty()) {
            store.saveSummary("")
            return
        }
        // The model chosen for summaries, if it is still there; otherwise the one the chat uses.
        val ref = Prefs.summaryModel?.takeIf { r -> providers.any { it.id == r.providerId } } ?: model
        val provider = ref?.let { r -> providers.firstOrNull { it.id == r.providerId } }
        if (ref == null || provider == null) {
            store.summaryError = if (providers.isEmpty()) ErrorKind.NoProvider else ErrorKind.NoModel
            return
        }
        val key = secrets.get(provider.id).orEmpty()
        val wire = provider.copy(kind = formatOf(provider, ref.model))
        val prompt = (if (project == null) SUMMARY_PROMPT else PROJECT_SUMMARY_PROMPT) + "\n- " + TIME_RULE.format(SimpleDateFormat("EEEE d MMMM yyyy", Locale.ENGLISH).format(Date())) +
            (if (project != null) "\n\n## The project: $project\n" + about.ifBlank { "(no description)" } else "") +
            (if (store.summary.isNotBlank() && !store.summaryManual) "\n\n## The overview written last time (it may be out of date: keep what the notes and messages below still support, and change or drop what they contradict or no longer mention)\n" + store.summary else "") +
            "\n\n## Notes the assistant saved\n" + notes.ifBlank { "(none)" } +
            "\n\n## The person's messages in " + (if (project == null) "recent chats" else "this project's chats") + "\n" + chats.toString().ifBlank { "(none)" }
        // The overview is worth thinking about: the model reasons as hard as it offers (high where there is a high).
        val caps = capsFor(ref)
        val strong = caps?.strongLevel()?.let { caps.build(it) }
        store.summarizing = true
        store.summaryError = null
        viewModelScope.launch {
            try {
                val history = listOf(WireMessage("user", prompt))
                val text = try {
                    api.complete(wire, key, ref.model, "", history, strong)
                } catch (e: ApiException) {
                    // An endpoint that does not know the reasoning setting: once more without it.
                    if (e.kind == ErrorKind.BadRequest && strong != null) api.complete(wire, key, ref.model, "", history) else throw e
                }.trim()
                if (text.isNotBlank()) store.saveSummary(text) else store.summaryError = ErrorKind.Unknown
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                store.summaryError = e.kind
            } catch (e: Exception) {
                store.summaryError = ErrorKind.Unknown
            } finally {
                store.summarizing = false
            }
        }
    }

    /**
     * The person asks for a correction of the overview ("I am not a student", "make it shorter"): the model gets the overview as it is,
     * the notes, and the request, and writes the overview again. What comes out is kept as the person's own (it is not rewritten
     * behind their back, as an edit by hand is not); "Update" still makes a new one from scratch.
     */
    fun reviseMemorySummary(project: String?, request: String) {
        val store = memoryOf(project) ?: return
        val ask = request.trim()
        if (store.summarizing || ask.isEmpty() || store.summary.isBlank()) return
        val ref = Prefs.summaryModel?.takeIf { r -> providers.any { it.id == r.providerId } } ?: model
        val provider = ref?.let { r -> providers.firstOrNull { it.id == r.providerId } }
        if (ref == null || provider == null) {
            store.summaryError = if (providers.isEmpty()) ErrorKind.NoProvider else ErrorKind.NoModel
            return
        }
        val key = secrets.get(provider.id).orEmpty()
        val wire = provider.copy(kind = formatOf(provider, ref.model))
        val notes = store.notes.sortedBy { it.createdAt }.joinToString("\n") { "- " + it.text }
        val prompt = REVISE_PROMPT + "\n\n## The overview now\n" + store.summary +
            "\n\n## Notes the assistant saved (for reference)\n" + notes.ifBlank { "(none)" } +
            "\n\n## What the person asks\n" + ask.take(1_500)
        store.summarizing = true
        store.summaryError = null
        viewModelScope.launch {
            try {
                val text = api.complete(wire, key, ref.model, "", listOf(WireMessage("user", prompt))).trim()
                if (text.isNotBlank()) store.editSummary(text) else store.summaryError = ErrorKind.Unknown
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                store.summaryError = e.kind
            } catch (e: Exception) {
                store.summaryError = ErrorKind.Unknown
            } finally {
                store.summarizing = false
            }
        }
    }

    // ---------- the overviews renew themselves ----------

    private val summaryJobs = HashMap<String?, Job>()
    private val hooked = HashSet<String>()

    /** Notes saved, changed or removed (by the assistant or the person) wake the overview of their own memory a little later. */
    private fun hookSummaries() {
        if (hooked.add("")) memory.onNotesChanged = { scheduleSummary(null) }
        projectInfo.forEach { (name, info) ->
            if (hooked.add(info.id)) info.memory.onNotesChanged = { scheduleSummary(projectInfo.entries.firstOrNull { it.value === info }?.key ?: name) }
        }
    }

    /** After a quiet minute and a quarter (a run of notes makes one request, not ten) the overview is written again if it is out of date. */
    private fun scheduleSummary(project: String?) {
        viewModelScope.launch {
            summaryJobs[project]?.cancel()
            summaryJobs[project] = launch {
                delay(75_000)
                if (memorySummaryStale(project)) refreshMemorySummary(project)
            }
        }
    }

    /** Whether any overview is being written now. */
    val summarizingAny: Boolean get() = memory.summarizing || projectInfo.values.any { it.memory.summarizing }

    /** Renews every overview that is out of date (at the start of the app, and by the alarm that comes twice a day). */
    fun refreshStaleSummaries() {
        if (providers.isEmpty()) return
        (listOf<String?>(null) + projectInfo.keys).forEach { p -> if (memorySummaryStale(p)) refreshMemorySummary(p) }
    }

    /** While any answer is being written the app runs as a foreground service, so it keeps going in the background. */
    private fun syncService() {
        // While the app is in front it needs no keep-alive (and no notification). The activity starts the
        // service when you leave it mid-answer; here it is only stopped when the last answer is done,
        // or started if an answer begins while the app is already out of sight.
        if (streaming.isEmpty()) com.apvcode.devx.GenerationService.stop(context)
        else if (!com.apvcode.devx.AppState.resumed) com.apvcode.devx.GenerationService.start(context)
    }

    /** True while at least one answer is being written. */
    val busy: Boolean get() = streaming.isNotEmpty()

    private fun withChat(id: String, change: (Chat) -> Unit) {
        chatStates.firstOrNull { it.id == id }?.let {
            change(it)
            save(it)
        }
    }

    private fun touch(chat: Chat) {
        chat.updatedAt = now()
        save(chat)
    }

    // One save at a time and in order, so that an older copy can never be written over a newer one.
    private val saver = Dispatchers.IO.limitedParallelism(1)

    /** The chat is written to disk. Turning it into text (a long chat is a lot of it) is done off the main thread too. */
    private fun save(chat: Chat) {
        if (chat.temporary) return
        chat.projectId = chat.project?.let { projectInfo[it]?.id }
        viewModelScope.launch(saver) { store.saveChatJson(chat.id, chat.toJson().toString()) }
    }

    private fun saveProviders() {
        val copy = providers.toList()
        viewModelScope.launch(Dispatchers.IO) { store.saveProviders(copy) }
    }

    private fun saveProjects() {
        // Before the saved lists are read, the lists here are empty: writing them would wipe the saved ones.
        if (!loaded) return
        com.apvcode.devx.Widgets.refreshSoon(context)
        val copy = projects.toList()
        // Not tied to the model's life: closing the app right after a change must not drop the write.
        viewModelScope.launch(Dispatchers.IO + NonCancellable) { store.saveProjects(copy) }
        projectStore.save(projectInfo.toMap())
    }

    private companion object {
        /** Photos and PDFs go along only for this many of my latest messages. */
        const val RESEND_LAST_MESSAGES = 3

        /** A text longer than this is not put into the message whole when the model has tools to read it. */
        const val INLINE_TEXT_LIMIT = 20_000

        /** How many times in one answer the model may call tools. */
        const val MAX_TOOL_ROUNDS = 12

        /** A broken line is tried again this many times, with these waits between (milliseconds), before the person is told. */
        const val MAX_NET_TRIES = 12
        val RECONNECT_DELAYS_MS = longArrayOf(1_000, 2_000, 3_000, 5_000, 8_000, 12_000, 15_000, 20_000, 20_000, 30_000, 30_000, 30_000)
        const val RECONNECT_NOTE = "(The connection broke while you were answering. Continue from the exact place where you stopped. Do not repeat what you have already said.)"

        val MEMORY_WRITES = setOf("memory_save", "memory_update", "memory_delete")

        /** Tools that only look: several of them in one turn can run side by side. */
        val PARALLEL_SAFE = setOf(
            "web_search", "open_url", "search_apps", "shop_search", "shop_product", "search_chats", "read_chat", "memory_list",
            "list_files", "file_info", "read_pdf_pages", "render_pdf_page", "read_text", "search_in_file", "view_image", "list_pdf_images",
            "phone_info", "list_apps", "find_contact", "calendar_events", "list_reminders", "task_list", "get_brightness", "get_location", "find_places", "get_route", "get_weather", "list_photos", "read_notifications", "folder_list", "folder_read",
        )

        /** The most characters of one excerpt that "Ask Devx" keeps. */
        const val MAX_SNIPPET = 20_000

        /** Anthropic accepts 32 MB per request; stay below it. */
        const val MAX_REQUEST_BYTES = 30_000_000L
        /** Without the file tools a PDF goes whole inside the request: a bigger one is not sent (and not even read into memory). */
        const val WHOLE_PDF_MAX = 20L * 1024 * 1024
    }

    // ---------- small things ----------

    private val PROJECT_SUMMARY_PROMPT = """
Below is what a chat assistant knows about one of the person's projects: what it is, the person's instructions for it, notes the assistant wrote down, and the person's own messages from the project's chats. Write a short overview of the project, to show on the project's page.
- Markdown: 2 to 5 short sections, each with a heading of one to three words ("## Heading") and a short paragraph or a few lines under it (for example: the goal, the current state, decisions made, what comes next). At most 1200 characters in all.
- Say only what is really there. Do not guess or invent. Leave out small talk and anything sensitive (passwords, keys).
- Write in the language the person mostly writes in.
- Answer with the overview only.
""".trim()

    private val TIME_RULE = "Today is %s. Facts about something that has already passed (an exam that was taken, a trip that ended, a deadline gone) are not kept as current: leave them out or say they are over. What was said later matters more than what was said earlier."

    private val REVISE_PROMPT = """
Below is the overview of what a chat assistant knows about the person (shown on their "Memory" or project screen), the notes it saved, and a correction or request from the person. Change the overview as the person asks: fix what is wrong, add what they say, remove what they want gone, or rewrite it the way they ask.
- Keep everything that they did not ask to change, with the same format (Markdown sections with "## Heading") and the same language. Keep it short: at most 1800 characters in all.
- What the person says about themself is true: put it in as it is. Do not invent anything else. Leave out anything sensitive (passwords, keys).
- Answer with the whole new overview only, with no comment.
""".trim()

    private val SUMMARY_PROMPT = """
Below is what a chat assistant knows about one person: notes it wrote down, and the person's own messages from their recent chats. Write an overview of what is known about them, to show on a "Memory" screen.
- Markdown: 3 to 6 short sections, each with a heading of one to three words ("## Heading") and a short paragraph under it. At most 1500 characters in all. The first section is the general picture.
- Speak to the person directly, in the second person (in Russian use "ты"), calmly, with no flattery.
- Say only what the notes and messages really say. Do not guess or invent. Leave out one-off requests, small talk and anything sensitive (health, money, politics, religion, passwords, keys).
- Write in the language the person mostly writes in.
- Answer with the overview only.
""".trim()

    private fun newId() = UUID.randomUUID().toString()

    private fun now() = System.currentTimeMillis()

    private fun age(timestamp: Long): String {
        val minutes = (now() - timestamp) / 60_000
        return when {
            minutes < 1 -> context.getString(R.string.age_now)
            minutes < 60 -> "$minutes ${context.getString(R.string.age_min)}"
            minutes < 24 * 60 -> "${minutes / 60} ${context.getString(R.string.age_hour)}"
            minutes < 7 * 24 * 60 -> "${minutes / (24 * 60)} ${context.getString(R.string.age_day)}"
            else -> DateFormat.format("d MMM", Date(timestamp)).toString()
        }
    }
}
