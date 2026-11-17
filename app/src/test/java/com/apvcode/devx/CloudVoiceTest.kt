package com.apvcode.devx

import com.apvcode.devx.data.CloudVoice
import com.apvcode.devx.data.Provider
import com.apvcode.devx.data.ProviderKind
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class CloudVoiceTest {
    private fun provider(url: String, kind: ProviderKind = ProviderKind.OpenAI) = Provider("p", "P", kind, url)

    @Test fun whichProviderCanSpeak() {
        assertEquals(CloudVoice.Backend.OpenAI, CloudVoice.backendOf(provider("https://api.openai.com/v1")))
        assertEquals(CloudVoice.Backend.Gemini, CloudVoice.backendOf(provider("https://generativelanguage.googleapis.com/v1beta/openai")))
        assertNull(CloudVoice.backendOf(provider("https://api.anthropic.com/v1", ProviderKind.Anthropic)))
    }

    @Test fun openAiSpeechAsksForAnMp3WithTheKeyAndTheVoice() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(Buffer().write(byteArrayOf(1, 2, 3, 4))))
            server.start()
            val audio = CloudVoice.synthesize(CloudVoice.newClient(), provider(server.url("/v1").toString()), "sk-test", "tts-x", "Привет", "nova", 1.1f)
            assertEquals("mp3", audio.extension); assertEquals(4, audio.bytes.size)
            val req = server.takeRequest()
            assertEquals("/v1/audio/speech", req.path)
            assertEquals("Bearer sk-test", req.getHeader("Authorization"))
            val body = JSONObject(req.body.readUtf8())
            assertEquals("tts-x", body.getString("model")); assertEquals("nova", body.getString("voice")); assertEquals("Привет", body.getString("input"))
            assertEquals("mp3", body.getString("response_format")); assertEquals(1.1, body.getDouble("speed"), 0.001)
        }
    }

    @Test fun theNewestSpeechModelTheProviderListsIsChosenAndNothingElseIsAsked() {
        val p = Provider("p", "P", ProviderKind.OpenAI, "https://x/v1", models = listOf("gpt-5.5", "gpt-x-tts-2", "gpt-x-tts-10", "old-tts-1", "gpt-realtime-tts-9", "gemini-3.1-flash-tts-preview", "gemini-3.1-flash-tts"))
        assertEquals(listOf("gpt-x-tts-10", "gemini-3.1-flash-tts", "gemini-3.1-flash-tts-preview", "gpt-x-tts-2", "old-tts-1"), CloudVoice.speechModels(p))
        assertEquals("gpt-x-tts-10", CloudVoice.newestModel(p))
        assertNull(CloudVoice.newestModel(Provider("q", "Q", ProviderKind.OpenAI, "https://x/v1", models = listOf("gpt-5.5"))))
        val e = runCatching { CloudVoice.synthesize(CloudVoice.newClient(), p, "k", "", "hi", "nova") }.exceptionOrNull() as CloudVoice.Failure
        assertTrue(e.message!!.contains("no speech model"))
    }

    @Test fun aRefusalCarriesTheProvidersReason() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(401).setBody("{\"error\":{\"message\":\"Incorrect API key\"}}"))
            server.start()
            val e = runCatching { CloudVoice.synthesize(CloudVoice.newClient(), provider(server.url("/v1").toString()), "bad", "tts-x", "hi", "nova") }.exceptionOrNull() as CloudVoice.Failure
            assertEquals(401, e.code); assertTrue(e.message!!, e.message!!.contains("Incorrect API key"))
        }
    }

    @Test fun geminiSpeechIsWrappedAsAWavFile() {
        val pcm = ByteArray(480) { (it % 7).toByte() }
        val answer = JSONObject().put("candidates", org.json.JSONArray().put(JSONObject().put("content", JSONObject().put("parts", org.json.JSONArray().put(JSONObject().put("inlineData", JSONObject().put("mimeType", "audio/L16;rate=24000").put("data", Base64.getEncoder().encodeToString(pcm))))))))
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(answer.toString()))
            server.start()
            val audio = CloudVoice.synthesize(CloudVoice.newClient(), provider("https://generativelanguage.googleapis.com/v1beta/openai"), "gkey", "some-tts-model", "Привет", "Kore", geminiBase = server.url("/v1beta").toString())
            assertEquals("wav", audio.extension)
            assertEquals("RIFF", String(audio.bytes, 0, 4)); assertEquals("WAVE", String(audio.bytes, 8, 4)); assertEquals(44 + 480, audio.bytes.size)
            val req = server.takeRequest()
            assertEquals("/v1beta/models/some-tts-model:generateContent", req.path)
            assertEquals("gkey", req.getHeader("x-goog-api-key"))
            val body = JSONObject(req.body.readUtf8())
            assertEquals("Kore", body.getJSONObject("generationConfig").getJSONObject("speechConfig").getJSONObject("voiceConfig").getJSONObject("prebuiltVoiceConfig").getString("voiceName"))
            assertEquals("AUDIO", body.getJSONObject("generationConfig").getJSONArray("responseModalities").getString(0))
        }
    }

    @Test fun geminiAnswerWithoutAudioIsNull() {
        assertNull(CloudVoice.geminiPcm("{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"no\"}]}}]}"))
        assertNull(CloudVoice.geminiPcm("not json"))
    }
}
