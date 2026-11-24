package com.apvcode.devx

import com.apvcode.devx.data.CustomModel
import com.apvcode.devx.data.ModelLimits
import com.apvcode.devx.data.Provider
import com.apvcode.devx.data.ProviderKind
import com.apvcode.devx.data.LimitSource
import com.apvcode.devx.data.displayName
import com.apvcode.devx.data.formatOf
import com.apvcode.devx.data.providerFromJson
import com.apvcode.devx.data.toJson
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class CustomModelTest {
    @Before fun loadLimits() {
        val assets = File("src/main/assets")
        ModelLimits.load(File(assets, "context-catalog.json").inputStream(), File(assets, "model-limits.json").inputStream())
    }

    private val provider = Provider(
        id = "p1", name = "DeepSeek", kind = ProviderKind.OpenAI, baseUrl = "https://api.deepseek.com/v1",
        models = listOf("deepseek-flash", "deepseek-v4-pro", "my-model"),
        listedModels = listOf("deepseek-flash", "deepseek-v4-pro"),
        customModels = listOf(CustomModel(name = "My Model", id = "my-model", context = 256_000, kind = ProviderKind.Anthropic)),
    )

    @Test fun aCustomModelSurvivesSavingAndReading() {
        val back = providerFromJson(provider.toJson())
        assertEquals(provider.customModels, back.customModels)
        assertEquals(provider.listedModels, back.listedModels)
        assertEquals(provider.models, back.models)
        assertEquals("My Model", back.displayName("my-model"))
        assertEquals("deepseek-flash", back.displayName("deepseek-flash"))
    }

    @Test fun aFileWithoutTheSeparateListsStillReads() {
        // How the file looked before custom models existed: everything came from the server.
        val old = JSONObject()
            .put("id", "p1").put("name", "OpenAI").put("kind", "OpenAI").put("baseUrl", "https://api.openai.com/v1")
            .put("models", org.json.JSONArray(listOf("gpt-a", "gpt-b")))
        val back = providerFromJson(old)
        assertTrue(back.customModels.isEmpty())
        assertEquals(listOf("gpt-a", "gpt-b"), back.listedModels)
        assertEquals(listOf("gpt-a", "gpt-b"), back.models)
    }

    @Test fun anEmptyIdIsNotAModel() {
        val json = provider.toJson()
        json.put("customModels", org.json.JSONArray().put(JSONObject().put("name", "broken").put("id", "  ")).put(JSONObject().put("name", "ok").put("id", "ok-id")))
        val back = providerFromJson(json)
        assertEquals(listOf("ok-id"), back.customModels.map { it.id })
    }

    @Test fun optionalFieldsStayOptional() {
        val json = provider.toJson()
        json.put("customModels", org.json.JSONArray().put(JSONObject().put("name", "x").put("id", "x")))
        val back = providerFromJson(json).customModels.single()
        assertNull(back.context)
        assertNull(back.kind)
    }

    @Test fun theNumberOfTokensTypedInWinsOverTheCatalogue() {
        // "deepseek-flash" is in the catalogue with 1M; a custom model of the same id says 256k.
        val typed = provider.copy(customModels = listOf(CustomModel(name = "flash", id = "deepseek-flash", context = 256_000)))
        assertEquals(256_000, ModelLimits.of(typed, "deepseek-flash").tokens)
        assertEquals(LimitSource.Custom, ModelLimits.of(typed, "deepseek-flash").source)
        assertTrue(!ModelLimits.of(typed, "deepseek-flash").approximate)
        // Without a number the rules are used as before.
        assertTrue(ModelLimits.of(provider, "deepseek-flash").tokens >= 1_000_000)
    }

    @Test fun theFormatTypedInIsUsedUnlessTheProviderNamesOne() {
        assertEquals(ProviderKind.Anthropic, formatOf(provider, "my-model"))
        assertEquals(ProviderKind.OpenAI, formatOf(provider, "deepseek-flash"))
        val anthropic = provider.copy(kind = ProviderKind.Anthropic)
        assertEquals(ProviderKind.Anthropic, formatOf(anthropic, "deepseek-flash"))
    }
}
