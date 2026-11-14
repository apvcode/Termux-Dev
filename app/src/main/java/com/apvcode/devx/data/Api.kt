package com.apvcode.devx.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

class ApiException(val kind: ErrorKind, val code: Int? = null, val detail: String? = null) : Exception(detail)

class ModelLists(
    val models: List<String>,
    val anthropicOnly: List<String>,
    val info: Map<String, ReasoningData> = emptyMap(),
)

class ModelEntry(val id: String, val reasoning: ReasoningData?)

/** A piece of an answer as it arrives: words of the answer, or words of the model thinking. */
sealed interface Piece {
    data class Text(val text: String) : Piece
    data class Reasoning(val text: String) : Piece

    /** The model asks for a tool to be run. [arguments] is the JSON the model wrote for it. */
    data class ToolCall(val id: String, val name: String, val arguments: String) : Piece

    /**
     * A thinking block of an Anthropic model with its signature. When a tool round follows, the next
     * request must carry these blocks back unchanged, or the API refuses it.
     */
    data class ThinkingBlock(val text: String, val signature: String, val redacted: String? = null) : Piece

    /**
     * What the provider says this request used: [input] is everything it read (the whole conversation, the tools and
     * the system prompt), [output] what it wrote. It may come more than once; the last one is the count.
     */
    data class Usage(val input: Int, val output: Int) : Piece
}

class WireImage(val mime: String, val base64: String)

class WireDocument(val name: String, val mime: String, val base64: String)

/** A tool the model may call: its name, what it does, and the JSON schema of its arguments. */
class WireTool(val name: String, val description: String, val parameters: JSONObject)

class WireToolCall(val id: String, val name: String, val arguments: String)

/** What a tool answered; [images] are pictures it returned (a rendered page of a PDF, for example). */
class WireToolResult(val callId: String, val name: String, val text: String, val images: List<WireImage> = emptyList())

class WireMessage(
    val role: String,
    val text: String,
    val images: List<WireImage> = emptyList(),
    val documents: List<WireDocument> = emptyList(),
    /** An assistant turn that asked for tools. */
    val toolCalls: List<WireToolCall> = emptyList(),
    /** The answers to those calls (role "tool"). */
    val toolResults: List<WireToolResult> = emptyList(),
    val thinking: List<Piece.ThinkingBlock> = emptyList(),
    /** Thinking text some OpenAI-style providers (DeepSeek and the like) want back within a tool round. */
    val reasoningContent: String = "",
)

/** Talks to the providers directly from the phone: nothing goes through any server of ours. */
class Api {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(240, TimeUnit.SECONDS)
        // A connection that went dead without a word (a train, a lift) is noticed in seconds, not after minutes.
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    /** Opens (and keeps in the pool) a connection to the provider, so that the first real request does not wait for the handshake. */
    suspend fun warm(provider: Provider) = withContext(Dispatchers.IO) {
        client.newCall(Request.Builder().url(provider.baseUrl.trimEnd('/')).head().build()).execute().close()
    }

    /** The ids of the models the provider offers. */
    suspend fun listModels(provider: Provider, key: String): List<String> = listModelEntries(provider, key).map { it.id }

    /** The models with whatever their entry says about reasoning. */
    suspend fun listModelEntries(provider: Provider, key: String): List<ModelEntry> = withContext(Dispatchers.IO) {
        val call = client.newCall(request(provider, key, modelsUrl(provider)).get().build())
        val handle = coroutineContextJob()?.invokeOnCompletion { call.cancel() }
        try {
            call.execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) throw httpError(response.code, body)
                val data = try {
                    JSONObject(body).getJSONArray("data")
                } catch (e: JSONException) {
                    throw ApiException(ErrorKind.BadRequest, response.code, "Unexpected answer from the models list")
                }
                (0 until data.length()).mapNotNull { i ->
                    val item = data.optJSONObject(i) ?: return@mapNotNull null
                    val id = item.optString("id").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                    ModelEntry(id, reasoningFromModelEntry(item))
                }.sortedBy { it.id }
            }
        } catch (e: IOException) {
            throw mapIo(e)
        } finally {
            handle?.dispose()
        }
    }

    /**
     * The models of a provider. A plain gateway is asked twice, as an OpenAI client and as an
     * Anthropic client; models that only the second list has are remembered as Anthropic-format.
     * [probeAnyServer] is for tests only: servers on this phone are normally not probed.
     */
    suspend fun discoverModels(provider: Provider, key: String, probeAnyServer: Boolean = false): ModelLists {
        val probe = shouldProbeFormats(provider) ||
            (probeAnyServer && provider.kind == ProviderKind.OpenAI && gatewayRoot(provider.baseUrl) != null)
        if (!probe) {
            val entries = listModelEntries(provider, key)
            return ModelLists(entries.map { it.id }, emptyList(), infoOf(entries))
        }

        var primary: List<ModelEntry>? = null
        var failure: ApiException? = null
        try {
            primary = listModelEntries(provider, key)
        } catch (e: ApiException) {
            failure = e
        }
        val other = try {
            listModelEntries(provider.copy(kind = ProviderKind.Anthropic), key)
        } catch (e: ApiException) {
            null
        }
        if (primary == null && other == null) throw failure ?: ApiException(ErrorKind.Unknown)
        // A gateway that ignores the Anthropic headers answers with the same list: no second format.
        val known = primary.orEmpty().map { it.id.lowercase() }.toSet()
        val only = if (primary == null) other.orEmpty() else other.orEmpty().filter { it.id.lowercase() !in known }
        val all = primary.orEmpty() + only
        return ModelLists(all.map { it.id }, only.map { it.id }, infoOf(all))
    }

    private fun infoOf(entries: List<ModelEntry>): Map<String, ReasoningData> =
        entries.mapNotNull { e -> e.reasoning?.let { e.id to it.copy(source = "endpoint") } }.toMap()

    /** The answer as it arrives, piece by piece. Cancelling the collector cancels the request. */
    fun stream(
        provider: Provider,
        key: String,
        model: String,
        system: String,
        history: List<WireMessage>,
        extra: JSONObject? = null,
        tools: List<WireTool> = emptyList(),
    ): Flow<Piece> = flow {
        val json = "application/json".toMediaType()
        val body = buildBody(provider.kind, model, system, history, extra, tools)
        // Some servers send the count only when asked; those that do not know the option are not asked.
        if (provider.kind == ProviderKind.OpenAI && asksForUsage(provider.baseUrl) && !body.has("stream_options")) {
            body.put("stream_options", JSONObject().put("include_usage", true))
        }
        val request = request(provider, key, chatUrl(provider))
            .post(body.toString().toRequestBody(json))
            .build()
        val call = client.newCall(request)
        VoiceTrace.mark("request sent (${body.toString().length / 1024} KB, ${tools.size} tools)")
        var firstPiece = true
        @OptIn(kotlinx.coroutines.InternalCoroutinesApi::class)
        val handle = currentCoroutineContext()[Job]?.invokeOnCompletion(onCancelling = true) { call.cancel() }
        val splitter = ThinkSplitter()
        val state = StreamState()
        // Gemini behind some gateways puts its thought summaries at the head of the answer.
        val summaries = if (model.lowercase().contains("gemini")) ThoughtSummaryFilter() else null
        suspend fun pass(piece: Piece) {
            if (firstPiece) { firstPiece = false; VoiceTrace.mark("first piece of the answer") }
            if (summaries != null && piece is Piece.Text) summaries.feed(piece.text).forEach { emit(it) } else emit(piece)
        }
        try {
            call.execute().use { response ->
                VoiceTrace.mark("provider answered (headers)")
                if (!response.isSuccessful) throw httpError(response.code, response.body?.string().orEmpty())
                val source = response.body?.source() ?: throw ApiException(ErrorKind.ProviderDown, response.code, "Empty answer")
                while (true) {
                    val line = source.readUtf8Line() ?: break
                    if (!line.startsWith("data:")) continue
                    val data = line.removePrefix("data:").trim()
                    if (data.isEmpty()) continue
                    if (data == "[DONE]") break
                    for (piece in parseDelta(provider.kind, data, splitter, state)) pass(piece)
                }
                for (piece in splitter.flush()) pass(piece)
                summaries?.flush()?.forEach { emit(it) }
                // The calls are whole only now: their arguments arrive in fragments.
                for (pending in state.calls.values) {
                    if (pending.name.isNotBlank()) {
                        emit(Piece.ToolCall(pending.id.ifBlank { "call_" + java.util.UUID.randomUUID().toString().take(12) }, pending.name, pending.args.toString().ifBlank { "{}" }))
                    }
                }
            }
        } catch (e: IOException) {
            currentCoroutineContext().ensureActive() // a cancel from our side is not an error
            throw mapIo(e)
        } finally {
            handle?.dispose()
        }
    }.flowOn(Dispatchers.IO)

    /** The whole answer at once; used for short things such as naming a chat. */
    suspend fun complete(provider: Provider, key: String, model: String, system: String, history: List<WireMessage>, extra: JSONObject? = null): String =
        stream(provider, key, model, system, history, extra).toList().filterIsInstance<Piece.Text>().joinToString("") { it.text }

    private fun asksForUsage(baseUrl: String): Boolean {
        val host = baseUrl.lowercase()
        return "api.openai.com" in host || "openrouter.ai" in host
    }

    // ---------- requests ----------

    private suspend fun coroutineContextJob(): Job? = currentCoroutineContext()[Job]

    private fun root(p: Provider) = p.baseUrl.trim().trimEnd('/')

    private fun anthropicRoot(p: Provider) = root(p).removeSuffix("/v1")

    private fun modelsUrl(p: Provider) = when (p.kind) {
        ProviderKind.OpenAI -> root(p) + "/models"
        ProviderKind.Anthropic -> anthropicRoot(p) + "/v1/models"
    }

    private fun chatUrl(p: Provider) = when (p.kind) {
        ProviderKind.OpenAI -> root(p) + "/chat/completions"
        ProviderKind.Anthropic -> anthropicRoot(p) + "/v1/messages"
    }

    private fun request(p: Provider, key: String, url: String): Request.Builder {
        val builder = try {
            Request.Builder().url(url)
        } catch (e: IllegalArgumentException) {
            throw ApiException(ErrorKind.BadRequest, null, "Invalid address: $url")
        }
        builder.header("Accept", "application/json, text/event-stream")
        when (p.kind) {
            ProviderKind.OpenAI -> if (key.isNotBlank()) builder.header("Authorization", "Bearer $key")
            ProviderKind.Anthropic -> {
                builder.header("x-api-key", key)
                builder.header("anthropic-version", "2023-06-01")
            }
        }
        return builder
    }

    private fun buildBody(kind: ProviderKind, model: String, system: String, history: List<WireMessage>, extra: JSONObject?, tools: List<WireTool>): JSONObject {
        val body = buildBase(kind, model, system, history)
        if (tools.isNotEmpty()) {
            val list = JSONArray()
            tools.forEach { t ->
                list.put(
                    when (kind) {
                        ProviderKind.OpenAI -> JSONObject().put("type", "function")
                            .put("function", JSONObject().put("name", t.name).put("description", t.description).put("parameters", t.parameters))
                        ProviderKind.Anthropic -> JSONObject().put("name", t.name).put("description", t.description).put("input_schema", t.parameters)
                    },
                )
            }
            body.put("tools", list)
        }
        extra?.keys()?.forEach { key ->
            // `reasoning_effort` / `reasoning` are OpenAI-style: the Anthropic API refuses them as unknown fields.
            if (kind == ProviderKind.Anthropic && (key == "reasoning_effort" || key == "reasoning")) return@forEach
            body.put(key, extra.get(key))
        }
        return body
    }

    private fun buildBase(kind: ProviderKind, model: String, system: String, history: List<WireMessage>): JSONObject {
        val messages = JSONArray()
        when (kind) {
            ProviderKind.OpenAI -> {
                if (system.isNotBlank()) messages.put(JSONObject().put("role", "system").put("content", system))
                history.forEach { m ->
                if (m.toolResults.isNotEmpty()) {
                    // One answer per call; pictures a tool returned cannot ride in a tool message, so they follow as a user message.
                    m.toolResults.forEach { r ->
                        messages.put(JSONObject().put("role", "tool").put("tool_call_id", r.callId).put("content", r.text.ifBlank { "(empty)" }))
                    }
                    val pictures = m.toolResults.flatMap { it.images }
                    if (pictures.isNotEmpty()) {
                        val parts = JSONArray().put(JSONObject().put("type", "text").put("text", "(Pictures returned by the tools above.)"))
                        pictures.forEach { img ->
                            parts.put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", "data:${img.mime};base64,${img.base64}")))
                        }
                        messages.put(JSONObject().put("role", "user").put("content", parts))
                    }
                    return@forEach
                }
                if (m.toolCalls.isNotEmpty()) {
                    val calls = JSONArray()
                    m.toolCalls.forEach { c ->
                        calls.put(
                            JSONObject().put("id", c.id).put("type", "function")
                                .put("function", JSONObject().put("name", c.name).put("arguments", c.arguments)),
                        )
                    }
                    val assistant = JSONObject().put("role", "assistant").put("content", m.text).put("tool_calls", calls)
                    if (m.reasoningContent.isNotBlank()) assistant.put("reasoning_content", m.reasoningContent)
                    messages.put(assistant)
                    return@forEach
                }
                    val content: Any = if (m.images.isEmpty() && m.documents.isEmpty()) m.text else JSONArray().also { parts ->
                        parts.put(JSONObject().put("type", "text").put("text", m.text.ifBlank { " " }))
                        m.images.forEach { img ->
                            parts.put(
                                JSONObject().put("type", "image_url")
                                    .put("image_url", JSONObject().put("url", "data:${img.mime};base64,${img.base64}")),
                            )
                        }
                        m.documents.forEach { doc ->
                            parts.put(
                                JSONObject().put("type", "file")
                                    .put("file", JSONObject().put("filename", doc.name).put("file_data", "data:${doc.mime};base64,${doc.base64}")),
                            )
                        }
                    }
                    messages.put(JSONObject().put("role", m.role).put("content", content))
                }
                return JSONObject().put("model", model).put("stream", true).put("messages", messages)
            }
            ProviderKind.Anthropic -> {
                history.forEach { m ->
                if (m.toolResults.isNotEmpty()) {
                    val blocks = JSONArray()
                    m.toolResults.forEach { r ->
                        val inner = JSONArray().put(JSONObject().put("type", "text").put("text", r.text.ifBlank { "(empty)" }))
                        r.images.forEach { img ->
                            inner.put(
                                JSONObject().put("type", "image").put(
                                    "source",
                                    JSONObject().put("type", "base64").put("media_type", img.mime).put("data", img.base64),
                                ),
                            )
                        }
                        blocks.put(JSONObject().put("type", "tool_result").put("tool_use_id", r.callId).put("content", inner))
                    }
                    messages.put(JSONObject().put("role", "user").put("content", blocks))
                    return@forEach
                }
                if (m.toolCalls.isNotEmpty()) {
                    val blocks = JSONArray()
                    m.thinking.forEach { t ->
                        if (t.redacted != null) blocks.put(JSONObject().put("type", "redacted_thinking").put("data", t.redacted))
                        else blocks.put(JSONObject().put("type", "thinking").put("thinking", t.text).put("signature", t.signature))
                    }
                    if (m.text.isNotBlank()) blocks.put(JSONObject().put("type", "text").put("text", m.text))
                    m.toolCalls.forEach { c ->
                        val input = runCatching { JSONObject(c.arguments) }.getOrDefault(JSONObject())
                        blocks.put(JSONObject().put("type", "tool_use").put("id", c.id).put("name", c.name).put("input", input))
                    }
                    messages.put(JSONObject().put("role", "assistant").put("content", blocks))
                    return@forEach
                }
                    val content: Any = if (m.images.isEmpty() && m.documents.isEmpty()) m.text else JSONArray().also { parts ->
                        m.images.forEach { img ->
                            parts.put(
                                JSONObject().put("type", "image").put(
                                    "source",
                                    JSONObject().put("type", "base64").put("media_type", img.mime).put("data", img.base64),
                                ),
                            )
                        }
                        m.documents.forEach { doc ->
                            parts.put(
                                JSONObject().put("type", "document").put(
                                    "source",
                                    JSONObject().put("type", "base64").put("media_type", doc.mime).put("data", doc.base64),
                                ),
                            )
                        }
                        parts.put(JSONObject().put("type", "text").put("text", m.text.ifBlank { " " }))
                    }
                    messages.put(JSONObject().put("role", m.role).put("content", content))
                }
                return JSONObject().put("model", model).put("max_tokens", 8192).put("stream", true)
                    .put("system", system).put("messages", messages)
            }
        }
    }

    // ---------- answers ----------

    /** What is being assembled while a stream runs: the tool calls (their arguments come in fragments) and Anthropic thinking blocks. */
    private class PendingCall(var id: String = "", var name: String = "", val args: StringBuilder = StringBuilder())

    private class PendingThinking(val text: StringBuilder = StringBuilder(), var signature: String = "", var redacted: String? = null)

    private class StreamState {
        var usageIn = 0
        var usageOut = 0
        val calls = java.util.TreeMap<Int, PendingCall>()
        val thinking = HashMap<Int, PendingThinking>()
    }

    private fun parseDelta(kind: ProviderKind, data: String, splitter: ThinkSplitter, state: StreamState): List<Piece> {
        val o = try {
            JSONObject(data)
        } catch (e: JSONException) {
            return emptyList()
        }
        if (o.has("error") && !o.isNull("error")) {
            val err = o.opt("error")
            val message = (err as? JSONObject)?.optString("message") ?: err.toString()
            val type = (err as? JSONObject)?.optString("type").orEmpty()
            throw ApiException(kindFromType(type, message), null, message)
        }
        return when (kind) {
            ProviderKind.OpenAI -> {
                // The count comes in the last chunk (which may have no choices at all), or in the one with the last words.
                val usage = o.optJSONObject("usage")?.takeIf { it.has("prompt_tokens") && !it.isNull("prompt_tokens") }
                    ?.let { Piece.Usage(it.optInt("prompt_tokens"), it.optInt("completion_tokens")) }
                val choice = o.optJSONArray("choices")?.optJSONObject(0) ?: return listOfNotNull(usage)
                val delta = choice.optJSONObject("delta") ?: return listOfNotNull(usage)
                val out = ArrayList<Piece>()
                if (usage != null) out += usage
                // Providers send the model's thinking in different fields.
                for (field in listOf("reasoning_content", "reasoning", "thinking")) {
                    if (delta.has(field) && !delta.isNull(field)) {
                        val text = delta.optString(field)
                        if (text.isNotEmpty()) out += Piece.Reasoning(text)
                    }
                }
                if (delta.has("content") && !delta.isNull("content")) out += splitter.feed(delta.optString("content"))
                delta.optJSONArray("tool_calls")?.let { calls ->
                    for (n in 0 until calls.length()) {
                        val tc = calls.optJSONObject(n) ?: continue
                        val index = when {
                            tc.has("index") -> tc.optInt("index")
                            tc.optString("id").isNotEmpty() -> state.calls.size
                            else -> (state.calls.size - 1).coerceAtLeast(0)
                        }
                        val pending = state.calls.getOrPut(index) { PendingCall() }
                        tc.optString("id").takeIf { it.isNotEmpty() && it != "null" }?.let { pending.id = it }
                        tc.optJSONObject("function")?.let { fn ->
                            fn.optString("name").takeIf { it.isNotEmpty() && it != "null" }?.let { pending.name = it }
                            if (fn.has("arguments") && !fn.isNull("arguments")) pending.args.append(fn.optString("arguments"))
                        }
                    }
                }
                out
            }
            ProviderKind.Anthropic -> {
                val index = o.optInt("index", -1)
                when (o.optString("type")) {
                    "message_start" -> {
                        // Input is counted whole: what was read afresh, what came from the cache and what was written to it.
                        val u = o.optJSONObject("message")?.optJSONObject("usage") ?: return emptyList()
                        state.usageIn = u.optInt("input_tokens") + u.optInt("cache_read_input_tokens") + u.optInt("cache_creation_input_tokens")
                        state.usageOut = u.optInt("output_tokens")
                        listOf(Piece.Usage(state.usageIn, state.usageOut))
                    }
                    "message_delta" -> {
                        val u = o.optJSONObject("usage") ?: return emptyList()
                        if (u.has("output_tokens")) state.usageOut = u.optInt("output_tokens")
                        listOf(Piece.Usage(state.usageIn, state.usageOut))
                    }
                    "content_block_start" -> {
                        val block = o.optJSONObject("content_block") ?: return emptyList()
                        when (block.optString("type")) {
                            "tool_use" -> state.calls[index] = PendingCall(block.optString("id"), block.optString("name"))
                            "thinking" -> state.thinking[index] = PendingThinking()
                            "redacted_thinking" -> state.thinking[index] = PendingThinking(redacted = block.optString("data"))
                        }
                        emptyList()
                    }
                    "content_block_delta" -> {
                        val delta = o.optJSONObject("delta") ?: return emptyList()
                        when (delta.optString("type")) {
                            "text_delta" -> listOf(Piece.Text(delta.optString("text")))
                            "thinking_delta" -> {
                                state.thinking[index]?.text?.append(delta.optString("thinking"))
                                listOf(Piece.Reasoning(delta.optString("thinking")))
                            }
                            "signature_delta" -> {
                                state.thinking[index]?.signature = delta.optString("signature")
                                emptyList()
                            }
                            "input_json_delta" -> {
                                state.calls[index]?.args?.append(delta.optString("partial_json"))
                                emptyList()
                            }
                            else -> emptyList()
                        }
                    }
                    "content_block_stop" -> {
                        val done = state.thinking.remove(index)
                        if (done != null) listOf(Piece.ThinkingBlock(done.text.toString(), done.signature, done.redacted)) else emptyList()
                    }
                    else -> emptyList()
                }
            }
        }
    }

    private fun kindFromType(type: String, message: String): ErrorKind {
        val t = (type + " " + message).lowercase()
        return when {
            "overloaded" in t -> ErrorKind.ProviderDown
            "rate_limit" in t || "rate limit" in t -> ErrorKind.RateLimit
            "authentication" in t || "invalid api key" in t -> ErrorKind.InvalidKey
            "quota" in t || "billing" in t || "credit" in t || "insufficient" in t -> ErrorKind.Quota
            else -> ErrorKind.Unknown
        }
    }

    private fun httpError(code: Int, body: String): ApiException {
        val detail = runCatching {
            val o = JSONObject(body)
            val err = o.opt("error")
            when {
                err is JSONObject -> err.optString("message")
                err is String -> err
                o.has("message") -> o.optString("message")
                else -> ""
            }
        }.getOrDefault("").ifBlank { body.take(200) }
        val lower = (detail + " " + body).lowercase()
        val money = "quota" in lower || "billing" in lower || "insufficient" in lower || "credit" in lower || "balance" in lower
        val kind = when (code) {
            401, 403 -> ErrorKind.InvalidKey
            402 -> ErrorKind.Quota
            429 -> if (money) ErrorKind.Quota else ErrorKind.RateLimit
            408 -> ErrorKind.Timeout
            400 -> if (money) ErrorKind.Quota else ErrorKind.BadRequest
            404, 422 -> ErrorKind.BadRequest
            in 500..599 -> ErrorKind.ProviderDown
            else -> ErrorKind.Unknown
        }
        return ApiException(kind, code, detail.ifBlank { null })
    }

    private fun mapIo(e: IOException): ApiException = when (e) {
        is InterruptedIOException -> ApiException(ErrorKind.Timeout, null, e.message)
        is UnknownHostException, is ConnectException, is NoRouteToHostException -> ApiException(ErrorKind.NoInternet, null, e.message)
        // The line broke in the middle of an answer: that is the network, not the provider.
        is java.net.SocketException, is java.io.EOFException, is java.net.ProtocolException -> ApiException(ErrorKind.NoInternet, null, e.message)
        is SSLException -> ApiException(
            if (Regex("reset|abort|closed|read error|unexpected end|broken pipe|timed out", RegexOption.IGNORE_CASE).containsMatchIn(e.message.orEmpty())) ErrorKind.NoInternet else ErrorKind.ProviderDown,
            null, e.message,
        )
        else -> if (Regex("unexpected end of stream|stream was reset|connection (reset|closed|abort)|ping|timeout", RegexOption.IGNORE_CASE).containsMatchIn(e.message.orEmpty())) ApiException(ErrorKind.NoInternet, null, e.message)
        else ApiException(ErrorKind.Unknown, null, e.message)
    }
}

/**
 * Some models put their thinking inside the text, between `<think>` and `</think>` (also
 * `<thinking>`, `<thought>`). This splits a stream of text chunks into answer and thinking, even
 * when a tag is cut in two by the chunk boundary.
 */
class ThinkSplitter {
    private var inside = false
    private val buffer = StringBuilder()

    private val opening = listOf("<think>", "<thinking>", "<thought>")
    private val closing = listOf("</think>", "</thinking>", "</thought>")

    fun feed(chunk: String): List<Piece> {
        buffer.append(chunk)
        val out = ArrayList<Piece>()
        while (true) {
            val tags = if (inside) closing else opening
            var at = -1
            var length = 0
            for (tag in tags) {
                val i = buffer.indexOf(tag)
                if (i >= 0 && (at < 0 || i < at)) {
                    at = i
                    length = tag.length
                }
            }
            if (at >= 0) {
                emit(out, buffer.substring(0, at))
                buffer.delete(0, at + length)
                inside = !inside
                continue
            }
            // No whole tag: keep the end back if it could be the start of one.
            var keep = 0
            for (n in minOf(buffer.length, 10) downTo 1) {
                val tail = buffer.substring(buffer.length - n)
                if (tags.any { it.startsWith(tail) }) {
                    keep = n
                    break
                }
            }
            emit(out, buffer.substring(0, buffer.length - keep))
            buffer.delete(0, buffer.length - keep)
            return out
        }
    }

    fun flush(): List<Piece> {
        val out = ArrayList<Piece>()
        emit(out, buffer.toString())
        buffer.clear()
        return out
    }

    private fun emit(out: MutableList<Piece>, text: String) {
        if (text.isEmpty()) return
        out += if (inside) Piece.Reasoning(text) else Piece.Text(text)
    }
}

/**
 * Gemini writes short summaries of its thinking, each a bold title with a paragraph under it. Some gateways
 * pass them on as ordinary text at the head of the answer, where they do not belong. This watches the start
 * of the answer of a Gemini model: while the answer opens with paragraphs that are "**Title** words" they are
 * thinking, and are sent on as thinking (with the title set apart from the words); the first paragraph that is
 * not one begins the answer, and from there on nothing is touched. An answer that does not open with bold text
 * passes through at once.
 */
class ThoughtSummaryFilter {
    private var looking = true
    private val buffer = StringBuilder()

    fun feed(chunk: String): List<Piece> {
        if (!looking) return listOf(Piece.Text(chunk))
        buffer.append(chunk)
        val out = ArrayList<Piece>()
        while (looking) {
            val start = buffer.indexOfFirst { !it.isWhitespace() }
            if (start < 0) return out
            if (!buffer.startsWith("**", start)) return release(out)
            val end = buffer.indexOf("\n\n", start)
            if (end < 0) return out // the paragraph is not finished: wait, it may still turn out to be the answer
            val summary = summary(buffer.substring(start, end)) ?: return release(out)
            out += Piece.Reasoning(summary)
            buffer.delete(0, end + 2)
        }
        return out
    }

    fun flush(): List<Piece> = if (looking) release(ArrayList()) else emptyList()

    private fun release(out: MutableList<Piece>): List<Piece> {
        looking = false
        if (buffer.isNotEmpty()) out += Piece.Text(buffer.toString())
        buffer.clear()
        return out
    }

    /** "**Title** words" as a paragraph; null if it is not one. */
    private fun summary(paragraph: String): String? {
        val m = Regex("^\\*\\*([^*\\n]{2,80}?)\\*\\*[ \\t]*\\n?([\\s\\S]*)$").find(paragraph.trim()) ?: return null
        val title = m.groupValues[1].trim()
        val body = m.groupValues[2].trim()
        if (body.isEmpty() || title.endsWith(":") || title.split(' ').size > 8) return null
        return "**$title**\n\n$body\n\n"
    }
}


/**
 * Some gateways pass the short titles of a model's thinking summaries on as words of the answer, glued to
 * what comes before and after ("...done.**Reading the file**Now I..."). A piece that is nothing but one bold
 * title, and arrives glued to the text before it (or right after another title), is such a summary.
 */
object TitleChunk {
    private val TITLE = Regex("^\\*\\*[^*\\n]{2,100}\\*\\*$")

    fun looksLikeSummary(chunk: String, glued: Boolean): Boolean = glued && TITLE.matches(chunk.trim())
}
