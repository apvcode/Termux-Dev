package com.apvcode.devx.ui.chat

import com.apvcode.devx.data.Artifact
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
import com.apvcode.devx.data.ProjectStore
import android.app.Application
import android.net.Uri
import android.text.format.DateFormat
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
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
import com.apvcode.devx.data.TitleChunk
import com.apvcode.devx.data.Level
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

/**
 * Everything the screens need: chats (as trees), projects, providers with their models, and the
 * running generations. A chat can generate in the background while you look at another one.
 */
class ChatViewModel(app: Application) : AndroidViewModel(app) {
    private val context = app.applicationContext
    private val store = Store(context)
    private val secrets = Secrets(context)
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
    private val toolbox = Toolbox(
        context, attachmentStore, WebTools(context), WorkTools(context, workspace, artifacts),
        MemoryTools(memory, { projectInfo[it]?.memory }) { chatDigests() },
        SkillTools(skills, workspace),
        McpTools(mcp),
    )

    /** A file the assistant handed over, by its id (null if it is gone). */
    fun artifact(id: String): Artifact? = artifacts.get(id)

    fun artifactFile(a: Artifact): java.io.File = artifacts.fileOf(a)
    private val prefs = context.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)

    // ---------- state ----------

    /** How many photos one message may carry (1..50); set in the settings. */
    var maxPhotos by mutableIntStateOf(prefs.getInt("maxPhotos", 10).coerceIn(1, 50))
        private set

    /** Photos and files chosen for the message that is being written. */
    val draftAttachments = mutableStateListOf<Attachment>()

    /** How many are still being prepared (a photo is shrunk first). */
    var importing by mutableIntStateOf(0)
        private set

    /** True once the saved data has been read from the phone. */
    var loaded by mutableStateOf(false)
        private set

    val providers = mutableStateListOf<Provider>()
    val projects = mutableStateListOf<String>()
    private val chatStates = mutableStateListOf<Chat>()

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
                runCatching { ReasoningCatalog.load(context) }
                val names = store.loadProjects()
                val info = projectStore.load(names).also { projectStore.save(it) }
                listOf(store.loadProviders(), names, store.loadModel(), store.loadChats(), store.loadEfforts(), info)
            }
            @Suppress("UNCHECKED_CAST")
            providers += data[0] as List<Provider>
            @Suppress("UNCHECKED_CAST")
            projects += data[1] as List<String>
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
            loaded = true
        }
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
    fun branchOf(msg: Msg): Pair<Int, Int>? {
        val chat = current ?: return null
        val list = chat.siblings(msg).sortedBy { it.createdAt }
        return if (list.size > 1) (list.indexOfFirst { it.id == msg.id } + 1) to list.size else null
    }

    val chats: List<ChatSummary>
        get() = chatStates.filter { !it.archived && !it.temporary }.sortedByDescending { it.updatedAt }.map { chat ->
            ChatSummary(
                id = chat.id,
                title = chat.title,
                project = chat.project,
                // A locked chat shows nothing of what is in it.
                preview = if (chat.locked) "" else chat.activePath().lastOrNull { it.text.isNotBlank() }?.text?.replace(Regex("[*`#>|]"), "")?.replace(Regex("\\s+"), " ")?.trim()?.take(90).orEmpty(),
                time = age(chat.updatedAt),
                status = if (chat.id in streaming) ChatStatus.Working else ChatStatus.Idle,
                pinned = chat.pinned,
                locked = chat.locked,
            )
        }

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
                    attachmentStore.importImage(uri, if (many) 1280 else 1600, if (many) 80 else 86)?.let { draftAttachments += it }
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
                        else draftAttachments += file
                    }
                } finally {
                    importing -= 1
                }
            }
        }
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
            attachmentStore.delete(it)
        }
    }

    // ---------- chats ----------

    /** The project the chat that the next message starts will belong to (null: none). */
    var draftProject by mutableStateOf<String?>(null)
        private set

    /** Words shared from another app: the new chat's field takes them (and clears this). */
    var sharedDraft by mutableStateOf<String?>(null)

    /** Something shared from another app starts a new chat: its words in the field, its pictures and files attached. Nothing is sent. */
    fun startFromShare(text: String, uris: List<android.net.Uri>) {
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
        val chat = current ?: Chat(newId(), (trimmed.ifEmpty { attachments.first().name }).take(40), null, now()).also {
            it.model = defaultModel
            it.temporary = temporary
            it.project = draftProject?.takeIf { p -> !temporary && p in projects }
            chatStates += it
            currentId = it.id
        }
        if (chat.model == null) chat.model = defaultModel
        draftProject = null
        val user = Msg(newId(), chat.activePath().lastOrNull()?.id, Role.User, trimmed, now(), attachments = attachments)
        draftAttachments.clear()
        chat.messages += user
        chat.select(user)
        fresh += user.id
        touch(chat)
        generate(chat, user)
    }

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
        val edited = Msg(newId(), message.parentId, Role.User, trimmed, now(), attachments = message.attachments)
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
        if (locked) { if (com.apvcode.devx.data.AppLock.locksChats) unlocked -= id else if (id !in unlocked) unlocked += id } else unlocked -= id
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

    fun deleteChat(id: String) {
        jobs[id]?.cancel()
        chatStates.firstOrNull { it.id == id }?.let { chat ->
            val files = chat.messages.flatMap { it.attachments }.distinctBy { it.id }
            viewModelScope.launch(Dispatchers.IO) { files.forEach(attachmentStore::delete) }
        }
        chatStates.firstOrNull { it.id == id }?.let { chat ->
            // The files the assistant made for this chat go with it, and so does its working folder.
            val ids = Regex("```devx-file\\n([^\\n`]+)\\n```")
            chat.messages.forEach { m -> ids.findAll(m.text).forEach { artifacts.delete(it.groupValues[1]) } }
            workspace.delete(chat.id)
        }
        chatStates.removeAll { it.id == id }
        memory.invalidateSummary()
        if (currentId == id) currentId = null
        viewModelScope.launch(Dispatchers.IO) { store.deleteChat(id) }
    }

    fun createProject(name: String, description: String = "", icon: String = ProjectLook.DEFAULT_ICON, color: Long = ProjectLook.colors.first()): Boolean {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || trimmed in projects) return false
        projects += trimmed
        val id = newId().take(8)
        projectInfo[trimmed] = ProjectInfo(id, description.trim(), "", icon, color, projectStore.memoryOf(id))
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

    fun renameProject(old: String, new: String) {
        val name = new.trim()
        if (name.isEmpty() || name == old) return
        val index = projects.indexOf(old)
        if (index >= 0) projects[index] = name
        projectInfo.remove(old)?.let { projectInfo[name] = it }
        chatStates.filter { it.project == old }.forEach { it.project = name; save(it) }
        saveProjects()
    }

    /** Deleting a project deletes the chats inside it. */
    fun deleteProject(name: String) {
        chatStates.filter { it.project == name }.map { it.id }.forEach { deleteChat(it) }
        projects.remove(name)
        projectInfo.remove(name)?.memory?.destroy()
        saveProjects()
    }

    // ---------- providers and models ----------

    fun providerKey(id: String): String = secrets.get(id).orEmpty()

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
        val caps = capsFor(ref)
        val level = levelFor(ref)
        val reasoningParams = if (caps != null && level != null) caps.build(level) else null

        syncService()
        jobs[chat.id] = viewModelScope.launch {
            val self = coroutineContext[kotlinx.coroutines.Job]
            try {
                // The tools it may use: the files attached to this chat (never anything else on the phone), and
                // the web when the person has allowed it. Without any tool this is the plain request it always was.
                val files = filesOf(chat, user)
                // Memory is for the chats that are kept: a temporary chat neither reads it nor writes to it.
                val memoryOn = Prefs.memory && !chat.temporary
                val projectName = chat.project?.takeIf { !chat.temporary && it in projectInfo }
                val project = projectName?.let { projectInfo[it] }
                val skillsOn = skills.enabled().isNotEmpty()
                var tools = toolbox.definitions(files, Prefs.webSearch, Prefs.workTools, Prefs.terminal, memoryOn, project != null, skillsOn)
                fun systemNow() = SystemPrompt.forUser(
                    Prefs.nickname,
                    if (memoryOn) memory.forPrompt() else null,
                    project?.let { SystemPrompt.ProjectContext(projectName!!, it.description, it.instructions, if (memoryOn) it.memory.forPrompt() else null, if (memoryOn) it.memory.summary else null) },
                    if (skillsOn) skills.forPrompt() else null,
                    ref.model,
                    if (memoryOn) memory.summary else null,
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
                        val answer = kotlinx.coroutines.CompletableDeferred<ApprovalDecision>()
                        val pendingApproval = PendingApproval(request) { answer.complete(it) }
                        withContext(Dispatchers.Main.immediate) { assistant.approval = pendingApproval }
                        // With the app away, a notification carries the question and its buttons.
                        com.apvcode.devx.data.ApprovalBridge.put(chat.id, pendingApproval)
                        if (!com.apvcode.devx.AppState.foreground) com.apvcode.devx.GenerationService.announceApproval(context, chat.id, chat.title, request)
                        try {
                            // Nobody answers for a long time: it counts as "cancel".
                            val said = kotlinx.coroutines.withTimeoutOrNull(10 * 60_000L) { answer.await() } ?: ApprovalDecision.Deny
                            if (said == ApprovalDecision.ThisChat) mcpAllowed.getOrPut(chat.id) { HashSet() } += request.serverId
                            return said
                        } finally {
                            withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main.immediate) { assistant.approval = null }
                            com.apvcode.devx.data.ApprovalBridge.remove(chat.id)
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
                        }
                        // Keep what is written so far on disk, so a killed app loses almost nothing.
                        if (now() - savedAt > 2000) {
                            savedAt = now()
                            save(chat)
                        }
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
            if (!chat.temporary && !chat.titleFinal && assistant.error == null && assistant.text.isNotBlank()) {
                chat.titleFinal = true
                launch { nameChat(chat, wire, key, ref.model, user.text.ifBlank { user.attachments.joinToString { it.name } }, assistant.text) }
            }
        }
    }

    /** A user turn that tells the model to go on: joined to the last one when that is a user turn too (turns must alternate). */
    private fun MutableList<WireMessage>.addContinuation(note: String) {
        val last = lastOrNull()
        if (last != null && last.role == "user") {
            this[lastIndex] = WireMessage("user", last.text + "\n\n" + note, last.images, last.documents)
        } else {
            this += WireMessage("user", note)
        }
    }

    /** What the model is told when an answer that broke off is continued: the steps it had already taken. */
    private fun continuationNote(done: Msg): String {
        val steps = done.steps.joinToString("\n") { st ->
            "- " + st.kind.name.lowercase() + (if (st.detail.isNotBlank()) ": " + st.detail else "") + (if (st.note.isNotBlank()) " (" + st.note + ")" else "")
        }
        return "(The connection broke while you were answering." +
            (if (steps.isNotEmpty()) " The steps you had already taken:\n$steps\n" else " ") +
            "Continue from where you stopped. Do not repeat what is done or what you have already said.)"
    }

    /** Waits before the next try: until the network is back if there is none, or for [ms]. */
    private suspend fun awaitConnection(ms: Long) {
        val cm = context.getSystemService(android.net.ConnectivityManager::class.java)
        val offline = runCatching { cm?.activeNetwork == null }.getOrDefault(false)
        if (!offline || cm == null) { delay(ms); return }
        kotlinx.coroutines.withTimeoutOrNull(ms * 2) {
            kotlinx.coroutines.suspendCancellableCoroutine<Unit> { cont ->
                val callback = object : android.net.ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: android.net.Network) { if (cont.isActive) cont.resume(Unit) {} }
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
    private fun filesOf(chat: Chat, upTo: Msg): List<Attachment> {
        val path = chat.activePath()
        val end = path.indexOfFirst { it.id == upTo.id }.let { if (it < 0) path.size - 1 else it }
        return path.take(end + 1).filter { it.role == Role.User }.flatMap { it.attachments }.filter { !it.isQuote }.distinctBy { it.id }
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
        val tools = toolbox.definitions(files, Prefs.webSearch, Prefs.workTools, Prefs.terminal, memoryOn, project != null, skillsOn)
        val system = SystemPrompt.forUser(
            Prefs.nickname,
            if (memoryOn) memory.forPrompt() else null,
            project?.let { SystemPrompt.ProjectContext(projectName!!, it.description, it.instructions, if (memoryOn) it.memory.forPrompt() else null, if (memoryOn) it.memory.summary else null) },
            if (skillsOn) skills.forPrompt() else null,
            null,
            if (memoryOn) memory.summary else null,
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
        val slice = path.take(end + 1)
        val recent = slice.filter { it.role == Role.User }.takeLast(RESEND_LAST_MESSAGES).map { it.id }.toSet()
        var bytes = 0L
        val out = ArrayList<WireMessage>()
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
                        if (m.id in recent) images += WireImage(a.mime, attachmentStore.readBase64(a).also { bytes += it.length }) else skipped += a.name
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
                            text += "\n\n[Attached file: ${a.name}: PDF, ${pages?.let { "$it pages" } ?: "pages unknown"}, ${a.size / 1024} KB. Its contents are not included: use the file tools (read_pdf_pages, search_in_file, render_pdf_page) to read what you need.]"
                        } else if (m.id in recent) documents += WireDocument(a.name, a.mime, attachmentStore.readBase64(a).also { bytes += it.length }) else skipped += a.name
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

    private suspend fun nameChat(chat: Chat, provider: Provider, key: String, model: String, question: String, answer: String) {
        val prompt = "Write a very short title (at most 5 words) for this conversation, in the same language as the user. " +
            "Answer with the title only: no quotes, no full stop.\n\nUser: ${question.take(500)}\n\nAssistant: ${answer.take(500)}"
        val title = runCatching { api.complete(provider, key, model, "", listOf(WireMessage("user", prompt))) }
            .getOrNull()?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim()?.trim('"', '«', '»', '.', '*', '#', ' ')?.take(60)
        if (!title.isNullOrBlank()) {
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
            val made = runCatching { describeSkill(body) }.getOrNull()
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
            val said = c.activePath().filter { it.role == Role.User && it.text.isNotBlank() }.take(4)
            if (said.isEmpty()) continue
            val block = "### ${c.title}\n" + said.joinToString("\n") { "- " + it.text.replace(Regex("\\s+"), " ").take(240) } + "\n\n"
            if (chats.length + block.length > 14_000) break
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
        val prompt = (if (project == null) SUMMARY_PROMPT else PROJECT_SUMMARY_PROMPT) +
            (if (project != null) "\n\n## The project: $project\n" + about.ifBlank { "(no description)" } else "") +
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

    private fun save(chat: Chat) {
        if (chat.temporary) return
        val json = chat.toJson().toString()
        viewModelScope.launch(Dispatchers.IO) { store.saveChatJson(chat.id, json) }
    }

    private fun saveProviders() {
        val copy = providers.toList()
        viewModelScope.launch(Dispatchers.IO) { store.saveProviders(copy) }
    }

    private fun saveProjects() {
        val copy = projects.toList()
        viewModelScope.launch(Dispatchers.IO) { store.saveProjects(copy) }
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
            "web_search", "open_url", "search_chats", "read_chat", "memory_list",
            "list_files", "file_info", "read_pdf_pages", "render_pdf_page", "read_text", "search_in_file", "view_image",
        )

        /** The most characters of one excerpt that "Ask Devx" keeps. */
        const val MAX_SNIPPET = 20_000

        /** Anthropic accepts 32 MB per request; stay below it. */
        const val MAX_REQUEST_BYTES = 30_000_000L
    }

    // ---------- small things ----------

    private val PROJECT_SUMMARY_PROMPT = """
Below is what a chat assistant knows about one of the person's projects: what it is, the person's instructions for it, notes the assistant wrote down, and the person's own messages from the project's chats. Write a short overview of the project, to show on the project's page.
- Markdown: 2 to 5 short sections, each with a heading of one to three words ("## Heading") and a short paragraph or a few lines under it (for example: the goal, the current state, decisions made, what comes next). At most 1200 characters in all.
- Say only what is really there. Do not guess or invent. Leave out small talk and anything sensitive (passwords, keys).
- Write in the language the person mostly writes in.
- Answer with the overview only.
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
