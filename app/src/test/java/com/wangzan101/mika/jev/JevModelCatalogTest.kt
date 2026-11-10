package com.wangzan101.mika.jev

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JevModelCatalogTest {
    @Test fun allArtifactsArePinnedAndHaveIndependentIntegrityMetadata() {
        assertEquals(1_945_777_664L, JevModelCatalog.totalBytes)
        assertEquals(2, JevModelCatalog.files.size)
        assertTrue(JevModelCatalog.revision.matches(Regex("[0-9a-f]{40}")))
        for (file in JevModelCatalog.files) {
            assertTrue(file.url.encodedPath.contains("/resolve/${JevModelCatalog.revision}/"))
            assertTrue(file.sha256.matches(Regex("[0-9a-f]{64}")))
            assertTrue(JevModelCatalog.allows(file.url))
        }
        assertEquals(2, JevModelCatalog.files.map { it.sha256 }.distinct().size)
    }

    @Test fun redirectPolicyAcceptsOnlyTlsHuggingFaceHosts() {
        assertTrue(JevModelCatalog.allows("https://cas-bridge.xethub.hf.co/a?signature=test".toHttpUrl()))
        assertTrue(JevModelCatalog.allows("https://cdn-lfs.huggingface.co/a".toHttpUrl()))
        for (url in listOf("http://huggingface.co/a", "https://evil.example/a", "https://huggingface.co.evil.example/a",
            "https://huggingface.co:8443/a", "https://user:pass@huggingface.co/a")) {
            assertFalse(url, JevModelCatalog.allows(url.toHttpUrl()))
        }
    }
}
