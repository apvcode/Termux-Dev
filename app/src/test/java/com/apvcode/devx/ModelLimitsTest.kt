package com.apvcode.devx

import com.apvcode.devx.data.LimitSource
import com.apvcode.devx.data.ModelLimits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class ModelLimitsTest {
    @Before fun load() {
        val assets = File("src/main/assets")
        ModelLimits.load(File(assets, "context-catalog.json").inputStream(), File(assets, "model-limits.json").inputStream())
    }

    @Test fun aModelOfTheCatalogueGetsTheMakersNumber() {
        val limit = ModelLimits.of("zai-org/glm-5.2")
        assertTrue(limit.tokens >= 100_000)
        assertFalse(limit.approximate)
    }

    @Test fun theNameIsNormalisedLikeInDevx() {
        // A route prefix, a date and dots make no difference.
        assertEquals(ModelLimits.of("glm-5.2").tokens, ModelLimits.of("openrouter/z-ai/glm-5.2").tokens)
    }

    @Test fun anUnknownModelGetsTheDefaultAndIsMarkedAsAGuess() {
        val limit = ModelLimits.of("totally-unknown-model-x")
        assertEquals(128_000, limit.tokens)
        assertEquals(LimitSource.Default, limit.source)
        assertTrue(limit.approximate)
    }

    @Test fun aRuleByNameIsUsedWhenNothingElseKnowsTheModel() {
        val limit = ModelLimits.of("claude-nonexistent-9")
        assertEquals(LimitSource.Rule, limit.source)
        assertEquals(200_000, limit.tokens)
    }
}

class TokenFormatTest {
    @org.junit.Test fun countsAreShortAndNeverHaveZeroDecimals() {
        org.junit.Assert.assertEquals("840", com.apvcode.devx.data.formatTokens(840))
        org.junit.Assert.assertEquals("12.4k", com.apvcode.devx.data.formatTokens(12_400))
        org.junit.Assert.assertEquals("1k", com.apvcode.devx.data.formatTokens(1_000))
        org.junit.Assert.assertEquals("393k", com.apvcode.devx.data.formatTokens(393_216))
        org.junit.Assert.assertEquals("1M", com.apvcode.devx.data.formatTokens(1_000_000))
        org.junit.Assert.assertEquals("1.05M", com.apvcode.devx.data.formatTokens(1_050_000))
        org.junit.Assert.assertEquals("1.05M", com.apvcode.devx.data.formatTokens(1_048_576))
    }
}
