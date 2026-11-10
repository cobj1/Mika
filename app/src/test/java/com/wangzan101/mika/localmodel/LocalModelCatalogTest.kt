package com.wangzan101.mika.localmodel

import com.wangzan101.mika.core.model.Part
import org.junit.Assert.*
import org.junit.Test

class LocalModelCatalogTest {
    @Test fun catalogPinsOfficialArtifactsAndHasNoVisionCapabilities() {
        assertEquals(2, LocalModelCatalog.models.size)
        LocalModelCatalog.models.forEach { spec ->
            assertEquals("huggingface.co", spec.artifact.url.host)
            assertTrue(spec.artifact.url.encodedPath.contains("/resolve/${spec.revision}/"))
            assertEquals(64, spec.artifact.sha256.length)
            assertTrue(spec.capabilities.toolCalls)
            assertFalse(spec.capabilities.vision)
            assertEquals(16_384, spec.capabilities.contextTokens)
        }
    }
    @Test fun resourceRecommendationDoesNotSuggestModelsThatCannotFit() {
        val gib = 1024L * 1024 * 1024
        val models = LocalModelCatalog.models
        assertNull(LocalModelCatalog.recommended(2*gib, 8*gib) { it.artifact.bytes })
        assertEquals(models[0].id, LocalModelCatalog.recommended(3*gib, 8*gib) { it.artifact.bytes })
        assertEquals(models[1].id, LocalModelCatalog.recommended(4*gib, 8*gib) { it.artifact.bytes })
        assertEquals(models[0].id, LocalModelCatalog.recommended(4*gib, gib) { it.artifact.bytes })
        assertNull(LocalModelCatalog.recommended(4*gib, 0) { 0 })
    }
    @Test fun activationRequiresAnExactStructuredToolCall() {
        val good = Part.ToolCall("1", "get_time", "{}")
        assertTrue(LocalModelActivation.validProbe(listOf(good)))
        assertFalse(LocalModelActivation.validProbe(listOf(Part.Text("get_time()"))))
        assertFalse(LocalModelActivation.validProbe(listOf(good, good)))
        assertFalse(LocalModelActivation.validProbe(listOf(good.copy(name = "other"))))
        assertFalse(LocalModelActivation.validProbe(listOf(good.copy(argsJson = "{"))))
        assertFalse(LocalModelActivation.validProbe(listOf(good.copy(argsJson = "{\"unexpected\":true}"))))
    }
}
