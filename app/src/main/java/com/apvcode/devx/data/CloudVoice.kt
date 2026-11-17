package com.apvcode.devx.data

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * Speech that sounds like a person, made by a provider's neural voice instead of the phone's own engine. Two official ways, both with the
 * key the person already gave for that provider: the OpenAI "audio/speech" call (OpenAI, and servers that copy it), and Gemini's own
 * text-to-speech. The text goes to that provider and nowhere else; what comes back is a small audio file for the player.
 */
object CloudVoice {
    enum class Backend { OpenAI, Gemini }

    /** An audio file as the player wants it: its bytes and the extension that tells the player the format. */
    class Audio(val bytes: ByteArray, val extension: String)

    class Failure(message: String, val code: Int? = null) : Exception(message)

    private const val GEMINI_HOST = "generativelanguage.googleapis.com"
    const val GEMINI_NATIVE = "https://$GEMINI_HOST/v1beta"

    val openAiVoices = listOf("nova", "shimmer", "coral", "sage", "alloy", "echo", "fable", "onyx", "ash", "ballad", "verse")
    val geminiVoices = listOf("Kore", "Puck", "Zephyr", "Aoede", "Charon", "Fenrir", "Leda", "Orus", "Callirrhoe", "Autonoe", "Sulafat", "Achernar")

    /** Which way a provider can speak, or null when it cannot (an Anthropic-format provider has no speech). */
    fun backendOf(p: Provider): Backend? = when {
        GEMINI_HOST in p.baseUrl -> Backend.Gemini
        p.kind == ProviderKind.OpenAI -> Backend.OpenAI
        else -> null
    }

    /**
     * The speech models the provider itself lists (their ids say "tts"), the newest first. Nothing is assumed: a model that the provider
     * does not list is never asked for, so an old one that was closed is not used, and a new one is used as soon as it is listed.
     */
    fun speechModels(p: Provider): List<String> =
        p.models.distinct().filter { it.contains("tts", ignoreCase = true) && !it.contains("realtime", ignoreCase = true) }.sortedWith(NEWEST_FIRST)

    /** The newest speech model of a provider, or null when it lists none. */
    fun newestModel(p: Provider): String? = speechModels(p).firstOrNull()

    /** Ids with the biggest numbers first (gemini-3.6-… before gemini-3.1-…, "-preview" after the final one of the same number). */
    internal val NEWEST_FIRST = Comparator<String> { a, b ->
        val na = numbers(a); val nb = numbers(b)
        for (i in 0 until maxOf(na.size, nb.size)) {
            val x = na.getOrElse(i) { -1 }; val y = nb.getOrElse(i) { -1 }
            if (x != y) return@Comparator y.compareTo(x)
        }
        val pa = a.contains("preview", true); val pb = b.contains("preview", true)
        if (pa != pb) (if (pa) 1 else -1) else a.compareTo(b)
    }

    private fun numbers(id: String): List<Int> = Regex("\\d+").findAll(id).map { it.value.toIntOrNull() ?: 0 }.toList()

    fun voicesOf(backend: Backend): List<String> = if (backend == Backend.Gemini) geminiVoices else openAiVoices

    fun defaultVoice(backend: Backend): String = voicesOf(backend).first()

    private val JSON = "application/json".toMediaType()

    /** One piece of text to audio. [speed]: 1.0 is normal. Throws [Failure] with the provider's words when it refuses. */
    fun synthesize(
        client: OkHttpClient, provider: Provider, key: String, model: String, text: String, voice: String, speed: Float = 1f,
        geminiBase: String = GEMINI_NATIVE,
    ): Audio {
        val backend = backendOf(provider) ?: throw Failure("This provider has no speech.")
        if (model.isBlank()) throw Failure("This provider lists no speech model. Update its model list in Providers.")
        val clean = text.trim().take(if (backend == Backend.OpenAI) 4000 else 3000)
        if (clean.isEmpty()) throw Failure("Nothing to say.")
        return when (backend) {
            Backend.OpenAI -> openAi(client, provider, key, model, clean, voice.ifBlank { defaultVoice(backend) }, speed)
            Backend.Gemini -> gemini(client, key, model, clean, voice.ifBlank { defaultVoice(backend) }, geminiBase)
        }
    }

    private fun openAi(client: OkHttpClient, provider: Provider, key: String, model: String, text: String, voice: String, speed: Float): Audio {
        val body = JSONObject().put("model", model).put("input", text).put("voice", voice).put("response_format", "mp3").put("speed", speed.coerceIn(0.5f, 2f).toDouble())
        // Only the newer "gpt-" speech models take a style instruction.
        if (model.startsWith("gpt-", ignoreCase = true)) body.put("instructions", "Speak naturally and warmly, like a person talking to a friend. Keep a calm, lively pace.")
        client.newCall(
            Request.Builder().url(provider.baseUrl.trimEnd('/') + "/audio/speech").header("Authorization", "Bearer $key").post(body.toString().toRequestBody(JSON)).build(),
        ).execute().use { r ->
            val bytes = r.body?.bytes() ?: ByteArray(0)
            if (!r.isSuccessful) throw Failure(errorText(bytes, r.code), r.code)
            if (bytes.isEmpty()) throw Failure("The provider sent no audio.")
            return Audio(bytes, "mp3")
        }
    }

    private fun gemini(client: OkHttpClient, key: String, model: String, text: String, voice: String, base: String): Audio {
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", text)))))
            .put("generationConfig", JSONObject()
                .put("responseModalities", JSONArray().put("AUDIO"))
                .put("speechConfig", JSONObject().put("voiceConfig", JSONObject().put("prebuiltVoiceConfig", JSONObject().put("voiceName", voice)))))
        val request = Request.Builder().url("${base.trimEnd('/')}/models/$model:generateContent").header("x-goog-api-key", key).post(body.toString().toRequestBody(JSON)).build()
        client.newCall(request).execute().use { r ->
            val bytes = r.body?.bytes() ?: ByteArray(0)
            if (!r.isSuccessful) throw Failure(errorText(bytes, r.code), r.code)
            val pcm = geminiPcm(bytes.decodeToString()) ?: throw Failure("The provider sent no audio.")
            return Audio(wav(pcm), "wav")
        }
    }

    /** The raw audio of a Gemini answer (base64 inside the JSON), or null if there is none. */
    fun geminiPcm(json: String): ByteArray? = runCatching {
        val parts = JSONObject(json).getJSONArray("candidates").getJSONObject(0).getJSONObject("content").getJSONArray("parts")
        for (i in 0 until parts.length()) parts.getJSONObject(i).optJSONObject("inlineData")?.optString("data")?.takeIf { it.isNotEmpty() }?.let { return@runCatching Base64.getDecoder().decode(it) }
        null
    }.getOrNull()

    /** Raw 16-bit mono audio as a WAV file (Gemini speaks at 24 kHz), which any player opens. */
    fun wav(pcm: ByteArray, sampleRate: Int = 24000): ByteArray {
        val out = ByteArrayOutputStream(pcm.size + 44)
        fun int(v: Int) { out.write(v and 255); out.write((v shr 8) and 255); out.write((v shr 16) and 255); out.write((v shr 24) and 255) }
        fun short(v: Int) { out.write(v and 255); out.write((v shr 8) and 255) }
        out.write("RIFF".toByteArray()); int(36 + pcm.size); out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray()); int(16); short(1); short(1); int(sampleRate); int(sampleRate * 2); short(2); short(16)
        out.write("data".toByteArray()); int(pcm.size); out.write(pcm)
        return out.toByteArray()
    }

    /** The reason a provider gave (its JSON "error", else the start of the body), with the status. */
    internal fun errorText(body: ByteArray, code: Int): String {
        val text = body.decodeToString()
        val reason = runCatching { JSONObject(text).let { o -> o.optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() } ?: o.optString("error").takeIf { it.isNotBlank() && !it.startsWith("{") } } }.getOrNull()
        return "HTTP $code" + (reason ?: text.take(160).takeIf { it.isNotBlank() })?.let { ": $it" }.orEmpty()
    }

    fun newClient(): OkHttpClient = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
}
