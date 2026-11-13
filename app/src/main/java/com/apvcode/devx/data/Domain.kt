package com.apvcode.devx.data

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.apvcode.devx.model.Role
import org.json.JSONArray
import org.json.JSONObject

enum class ProviderKind { OpenAI, Anthropic }

/** The API key is not here: it lives encrypted in [Secrets]. */
data class Provider(
    val id: String,
    val name: String,
    val kind: ProviderKind,
    val baseUrl: String,
    /** Every model the provider offers (both formats together). */
    val models: List<String> = emptyList(),
    /** The ones that only the Anthropic-format list has: they are sent in that format. */
    val anthropicModels: List<String> = emptyList(),
    /** What the provider's own model list says about reasoning, by model id. */
    val reasoningInfo: Map<String, ReasoningData> = emptyMap(),
    val modelsUpdatedAt: Long = 0L,
)

data class ModelRef(val providerId: String, val model: String)

enum class AttachmentKind { Image, Text, Pdf, Video, File }

/** The marker of a quote: words picked out of a message with "Ask Devx", kept as a small text that goes with the next message. */
const val QUOTE_MIME = "text/x-devx-quote"

/** A photo or a file on a message. The bytes live in the app's own folder, [file] is the name there. */
data class Attachment(
    val id: String,
    val kind: AttachmentKind,
    val name: String,
    val mime: String,
    val file: String,
    val size: Long,
    /** Length of a video, in milliseconds (0 for everything else). */
    val durationMs: Long = 0,
) {
    val isQuote: Boolean get() = mime == QUOTE_MIME
}

/** A page the assistant found while searching, or opened and read. */
data class Source(val url: String, val title: String, val opened: Boolean = false) {
    val host: String get() = runCatching { java.net.URI(url).host.orEmpty().removePrefix("www.") }.getOrDefault("")
}

enum class ToolKind { Search, Read, File, Memory, History, Skill, Mcp }

/** What the assistant is doing with a tool right now, for the line shown while it works. */
data class ToolActivity(
    val kind: ToolKind,
    val detail: String,
    /** What came of it (the sites a search found, the title of a page): filled in when the step is done. */
    val note: String = "",
    /** How much of the thinking was written when the step began: places the step among the thoughts. */
    val at: Int = 0,
)

/** Why a request failed, in terms a person understands. */
enum class ErrorKind { NoInternet, Timeout, InvalidKey, RateLimit, Quota, ProviderDown, BadRequest, NoModel, NoProvider, Interrupted, Unknown }

@Stable
class Msg(
    val id: String,
    val parentId: String?,
    val role: Role,
    text: String,
    val createdAt: Long,
    error: ErrorKind? = null,
    errorDetail: String? = null,
    val attachments: List<Attachment> = emptyList(),
    reasoning: String = "",
    thoughtSeconds: Int = 0,
) {
    var text by mutableStateOf(text)

    /** True while the answer is being written; saved with the chat, so a killed app can tell it was cut off. */
    var inflight = false

    /** What the model thought before it answered (when the provider sends it). */
    var reasoning by mutableStateOf(reasoning)
    var thoughtSeconds by mutableStateOf(thoughtSeconds)
    var error by mutableStateOf(error)
    var errorDetail by mutableStateOf(errorDetail)

    /**
     * What the provider counted for this answer (0 when it does not say): [tokensIn] is all it read over the requests
     * made for it, [tokensOut] all it wrote, [contextTokens] the size of the conversation after the last request.
     */
    var tokensIn by mutableIntStateOf(0)
    var tokensOut by mutableIntStateOf(0)
    var contextTokens by mutableIntStateOf(0)

    /** The tool the assistant is using this moment (null when it is not using one). Not saved. */
    var activity by mutableStateOf<ToolActivity?>(null)

    /** What the assistant did, step by step (each tool it used); kept, so that the steps can be read after the answer. */
    val steps = mutableStateListOf<ToolActivity>()

    /** The assistant waits for the person to allow what it is about to do with a server. Not saved. */
    var approval by mutableStateOf<PendingApproval?>(null)

    /** The connection broke while answering and is being tried again: which try it is (0 when all is well). Not saved. */
    var reconnect by mutableStateOf(0)

    /** The person stopped the answer: what was written until then, and the steps, stay. */
    var stopped by mutableStateOf(false)

    /** The pages it found and read to answer this; kept, so that the list can be opened under the answer. */
    val sources = mutableStateListOf<Source>()
}

/**
 * A conversation is a tree: editing a message, or asking again for an answer, adds a sibling and
 * leaves the old branch intact. [selected] remembers which child to follow at each node.
 */
@Stable
class Chat(val id: String, title: String, project: String?, val createdAt: Long) {
    var title by mutableStateOf(title)
    var project by mutableStateOf(project)
    var pinned by mutableStateOf(false)
    var archived by mutableStateOf(false)
    var updatedAt by mutableLongStateOf(createdAt)

    /** False until the model has named the chat (or the user did): then it is not renamed again. */
    var titleFinal by mutableStateOf(false)

    /** The model this chat talks to: kept with the chat, so every chat remembers its own. */
    var model by mutableStateOf<ModelRef?>(null)
    val messages = mutableStateListOf<Msg>()

    /** A temporary chat is never written to the disk and is thrown away as soon as it is left. */
    var temporary = false

    /** A locked chat opens only after the phone's screen lock (PIN, pattern or fingerprint) is passed. */
    var locked by mutableStateOf(false)
    val selected = mutableStateMapOf<String, String>()

    /**
     * The earlier part of a long conversation, told short by the model: the messages up to [summaryUpTo] (an id on the shown
     * branch) are no longer sent, this is sent in their place. Empty when the chat was never shortened.
     */
    var summary by mutableStateOf("")
    var summaryUpTo by mutableStateOf<String?>(null)

    /** The size of the chat in rough tokens before and after the last shortening (0 when not known). */
    var summaryBefore by mutableStateOf(0)
    var summaryAfter by mutableStateOf(0)

    /** How many messages the last shortening told short, and how many seconds it took (0 when not known). */
    var summaryCount by mutableStateOf(0)
    var summarySeconds by mutableStateOf(0)
}

const val ROOT = "root"

/** The messages shown on screen: from the first one, following the selected child at each step. */
fun Chat.activePath(): List<Msg> {
    val byParent = messages.groupBy { it.parentId ?: ROOT }
    val out = ArrayList<Msg>()
    var key = ROOT
    while (true) {
        val kids = byParent[key] ?: break
        val next = kids.firstOrNull { it.id == selected[key] } ?: kids.last()
        out += next
        key = next.id
    }
    return out
}

/** The alternatives for a message: other edits of it, or other answers to the same question. */
fun Chat.siblings(m: Msg): List<Msg> = messages.filter { it.parentId == m.parentId && it.role == m.role }

fun Chat.select(m: Msg) {
    selected[m.parentId ?: ROOT] = m.id
}

// ---------- JSON (the phone's own storage) ----------

fun Provider.toJson(): JSONObject = JSONObject()
    .put("id", id).put("name", name).put("kind", kind.name).put("baseUrl", baseUrl)
    .put("models", JSONArray(models)).put("anthropicModels", JSONArray(anthropicModels)).put("modelsUpdatedAt", modelsUpdatedAt)
    .put("reasoningInfo", JSONObject().also { o -> reasoningInfo.forEach { (k, v) -> o.put(k, v.toJson()) } })

fun providerFromJson(o: JSONObject): Provider = Provider(
    id = o.getString("id"),
    name = o.getString("name"),
    kind = runCatching { ProviderKind.valueOf(o.getString("kind")) }.getOrDefault(ProviderKind.OpenAI),
    baseUrl = o.getString("baseUrl"),
    models = o.optJSONArray("models")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList(),
    anthropicModels = o.optJSONArray("anthropicModels")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList(),
    reasoningInfo = o.optJSONObject("reasoningInfo")?.let { r -> r.keys().asSequence().associateWith { reasoningDataFromJson(r.getJSONObject(it)) } } ?: emptyMap(),
    modelsUpdatedAt = o.optLong("modelsUpdatedAt"),
)

fun Chat.toJson(): JSONObject = JSONObject()
    .put("id", id).put("title", title).put("project", project).put("createdAt", createdAt)
    .put("pinned", pinned).put("archived", archived).put("updatedAt", updatedAt).put("titleFinal", titleFinal).put("locked", locked).put("summary", summary).put("summaryUpTo", summaryUpTo).put("summaryBefore", summaryBefore).put("summaryAfter", summaryAfter).put("summaryCount", summaryCount).put("summarySeconds", summarySeconds)
    .put("model", model?.let { JSONObject().put("providerId", it.providerId).put("model", it.model) })
    .put("selected", JSONObject().also { o -> selected.forEach { (k, v) -> o.put(k, v) } })
    .put(
        "messages",
        JSONArray().also { arr ->
            messages.forEach { m ->
                arr.put(
                    JSONObject()
                        .put("id", m.id).put("parentId", m.parentId).put("role", m.role.name)
                        .put("text", m.text).put("createdAt", m.createdAt)
                        .put("error", m.error?.name).put("errorDetail", m.errorDetail)
                        .put("reasoning", m.reasoning).put("thoughtSeconds", m.thoughtSeconds).put("inflight", m.inflight)
                        .put("stopped", m.stopped).put("tokensIn", m.tokensIn).put("tokensOut", m.tokensOut).put("contextTokens", m.contextTokens)
                        .put("steps", JSONArray().also { a -> m.steps.forEach { st -> a.put(JSONObject().put("kind", st.kind.name).put("detail", st.detail).put("note", st.note).put("at", st.at)) } })
                        .put("sources", JSONArray().also { a -> m.sources.forEach { sr -> a.put(JSONObject().put("url", sr.url).put("title", sr.title).put("opened", sr.opened)) } })
                        .put(
                            "attachments",
                            JSONArray().also { a ->
                                m.attachments.forEach { at ->
                                    a.put(
                                        JSONObject().put("id", at.id).put("kind", at.kind.name).put("name", at.name)
                                            .put("mime", at.mime).put("file", at.file).put("size", at.size).put("duration", at.durationMs),
                                    )
                                }
                            },
                        ),
                )
            }
        },
    )

fun chatFromJson(o: JSONObject): Chat {
    val chat = Chat(o.getString("id"), o.getString("title"), o.optString("project").takeIf { it.isNotEmpty() && it != "null" }, o.optLong("createdAt"))
    chat.pinned = o.optBoolean("pinned")
    chat.archived = o.optBoolean("archived")
    chat.updatedAt = o.optLong("updatedAt", chat.createdAt)
    chat.titleFinal = o.optBoolean("titleFinal")
    chat.locked = o.optBoolean("locked")
    chat.summary = o.optString("summary")
    chat.summaryBefore = o.optInt("summaryBefore")
    chat.summaryAfter = o.optInt("summaryAfter")
    chat.summaryCount = o.optInt("summaryCount")
    chat.summarySeconds = o.optInt("summarySeconds")
    chat.summaryUpTo = o.optString("summaryUpTo").takeIf { it.isNotEmpty() && it != "null" }
    chat.model = o.optJSONObject("model")?.let { runCatching { ModelRef(it.getString("providerId"), it.getString("model")) }.getOrNull() }
    o.optJSONObject("selected")?.let { s -> s.keys().forEach { k -> chat.selected[k] = s.getString(k) } }
    val arr = o.optJSONArray("messages") ?: JSONArray()
    for (i in 0 until arr.length()) {
        val m = arr.getJSONObject(i)
        chat.messages += Msg(
            id = m.getString("id"),
            parentId = m.optString("parentId").takeIf { it.isNotEmpty() && it != "null" },
            role = runCatching { Role.valueOf(m.getString("role")) }.getOrDefault(Role.User),
            text = m.optString("text"),
            createdAt = m.optLong("createdAt"),
            error = m.optString("error").takeIf { it.isNotEmpty() && it != "null" }?.let { runCatching { ErrorKind.valueOf(it) }.getOrNull() },
            errorDetail = m.optString("errorDetail").takeIf { it.isNotEmpty() && it != "null" },
            attachments = m.optJSONArray("attachments")?.let { a ->
                (0 until a.length()).mapNotNull { i ->
                    runCatching {
                        val o = a.getJSONObject(i)
                        Attachment(o.getString("id"), AttachmentKind.valueOf(o.getString("kind")), o.getString("name"), o.getString("mime"), o.getString("file"), o.optLong("size"), o.optLong("duration"))
                    }.getOrNull()
                }
            } ?: emptyList(),
            reasoning = m.optString("reasoning"),
            thoughtSeconds = m.optInt("thoughtSeconds"),
        ).also { msg ->
            msg.stopped = m.optBoolean("stopped")
            msg.tokensIn = m.optInt("tokensIn")
            msg.tokensOut = m.optInt("tokensOut")
            msg.contextTokens = m.optInt("contextTokens")
            m.optJSONArray("steps")?.let { a ->
                for (k in 0 until a.length()) {
                    val so = a.optJSONObject(k) ?: continue
                    val kind = runCatching { ToolKind.valueOf(so.optString("kind")) }.getOrNull() ?: continue
                    msg.steps += ToolActivity(kind, so.optString("detail"), so.optString("note"), so.optInt("at"))
                }
            }
            m.optJSONArray("sources")?.let { a ->
                for (k in 0 until a.length()) {
                    val so = a.optJSONObject(k) ?: continue
                    msg.sources += Source(so.optString("url"), so.optString("title"), so.optBoolean("opened"))
                }
            }
            // The app was closed or killed while this answer was being written: say so, offer a retry.
            if (m.optBoolean("inflight")) {
                if (msg.error == null) msg.error = ErrorKind.Interrupted
            }
        }
    }
    return chat
}
