package com.wangzan101.mika.jev

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class JevScoreCacheTest {
    private fun key(text: String = "文字", original: String = "原图", rendered: String = "渲染图", revision: String = "revision", version: String = "crop-v1") =
        JevScoreCache.key(revision, version, text, original, rendered)
    private fun score(value: Double = 0.8) = JevScoreResult(value, 200, 75)

    @Test fun exactSuccessfulRequestReusesOnlyScoreWithoutHistoricalTiming() = runBlocking {
        val cache = JevScoreCache()
        val first = cache.getOrCompute(key()) { score() }
        assertFalse(first.cacheHit)
        assertEquals(200L, first.loadMs)
        assertEquals(75L, first.scoreMs)
        val second = cache.getOrCompute(key()) { error("相同请求不应重载模型") }
        assertTrue(second.cacheHit)
        assertEquals(first.score, second.score, 0.0)
        assertNull(second.loadMs)
        assertNull(second.scoreMs)
    }

    @Test fun keyIncludesActualBothImagesTextModelAndPreprocessingVersion() {
        val base = key()
        for (other in listOf(key(text = "别的字"), key(original = "同URI的新图片内容"), key(rendered = "不同字体渲染"),
            key(revision = "other-revision"), key(version = "crop-v2"))) assertNotEquals(base, other)
        assertEquals(base, key())
        assertTrue(base.digest.matches(Regex("[0-9a-f]{64}")))
        assertFalse(base.toString().contains("文字"))
        assertFalse(base.toString().contains("原图"))
    }

    @Test fun hashingPreservesFieldBoundariesAndExactUnicodeUnits() {
        assertNotEquals(key(text = "ab", original = "c"), key(text = "a", original = "bc"))
        assertNotEquals(key(text = "\uD800"), key(text = "\uD801"))
        assertNotEquals(key(original = "图", rendered = "字"), key(original = "字", rendered = "图"))
        assertNotEquals(key(text = "字"), key(text = "字 "))
    }

    @Test fun cacheHasNoPersistenceAcrossInstances() = runBlocking {
        JevScoreCache().getOrCompute(key()) { score() }
        val result = JevScoreCache().getOrCompute(key()) { score(0.7) }
        assertFalse(result.cacheHit)
        assertEquals(0.7, result.score, 0.0)
    }

    @Test fun ttlStartsAtCompletionAndHitsDoNotExtendIt() = runBlocking {
        var now = 0L
        val cache = JevScoreCache(ttlMs = 100, clock = { now })
        cache.getOrCompute(key()) { now = 80; score() }
        now = 179
        assertTrue(cache.getOrCompute(key()) { error("未过期") }.cacheHit)
        now = 180
        assertFalse(cache.getOrCompute(key()) { score() }.cacheHit)
    }

    @Test fun leastRecentlyUsedEntryIsEvictedAndCapacityRemainsBounded() = runBlocking {
        val cache = JevScoreCache(capacity = 2)
        cache.getOrCompute(key("a")) { score() }
        cache.getOrCompute(key("b")) { score() }
        assertTrue(cache.getOrCompute(key("a")) { error("缓存 a") }.cacheHit)
        cache.getOrCompute(key("c")) { score() }
        assertTrue(cache.getOrCompute(key("a")) { error("缓存 a") }.cacheHit)
        assertTrue(cache.getOrCompute(key("c")) { error("缓存 c") }.cacheHit)
        assertFalse(cache.getOrCompute(key("b")) { score() }.cacheHit)
    }

    @Test fun exceptionAndInvalidScoresNeverEnterCache() = runBlocking {
        val cache = JevScoreCache()
        assertTrue(runCatching { cache.getOrCompute(key()) { error("model failed") } }.isFailure)
        for (invalid in listOf(Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, -0.01, 1.01)) {
            assertTrue(runCatching { cache.getOrCompute(key()) { score(invalid) } }.isFailure)
        }
        assertFalse(cache.getOrCompute(key()) { score() }.cacheHit)
        assertTrue(cache.getOrCompute(key()) { error("有效分数应缓存") }.cacheHit)
    }

    @Test fun exactBoundaryScoresAreSuccessfulNotFailureSentinels() = runBlocking {
        val cache = JevScoreCache()
        for (value in listOf(0.0, 1.0)) {
            cache.getOrCompute(key(value.toString())) { score(value) }
            assertEquals(value, cache.getOrCompute(key(value.toString())) { error("应缓存") }.score, 0.0)
        }
    }

    @Test fun cancelledComputationDoesNotCacheEvenIfItReturnsAValidScore() = runBlocking {
        val cache = JevScoreCache()
        val pending = async {
            cache.getOrCompute(key()) { currentCoroutineContext().cancel(); score() }
        }
        pending.join()
        assertTrue(pending.isCancelled)
        assertFalse(cache.getOrCompute(key()) { score() }.cacheHit)
    }

    @Test fun cancellationDuringInferenceDoesNotPopulateCache() = runBlocking {
        val cache = JevScoreCache()
        val started = CompletableDeferred<Unit>()
        val pending = async { cache.getOrCompute(key()) { started.complete(Unit); awaitCancellation() } }
        started.await()
        pending.cancelAndJoin()
        assertFalse(cache.getOrCompute(key()) { score() }.cacheHit)
    }

    @Test fun clearInvalidatesExistingEntriesAndInflightResults() = runBlocking {
        val cache = JevScoreCache()
        cache.getOrCompute(key("existing")) { score() }
        val started = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        val pending = async { cache.getOrCompute(key()) { started.complete(Unit); resume.await(); score() } }
        started.await()
        cache.clear()
        resume.complete(Unit)
        assertFalse(pending.await().cacheHit)
        assertFalse(cache.getOrCompute(key()) { score() }.cacheHit)
        assertFalse(cache.getOrCompute(key("existing")) { score() }.cacheHit)
    }

    @Test fun clockRollbackDoesNotReuseEntries() = runBlocking {
        var now = 100L
        val cache = JevScoreCache(clock = { now })
        cache.getOrCompute(key()) { score() }
        now = 99
        assertFalse(cache.getOrCompute(key()) { score() }.cacheHit)
    }

    @Test fun stableSourceKeySkipsBothPreparationAndEngineUntilAnyInputChanges() = runBlocking {
        val cache = JevScoreCache(capacity = 16)
        val bytes = byteArrayOf(1, 2, 3, 4)
        val source = JevImages.Source(bytes)
        var preparations = 0
        var engineRuns = 0
        suspend fun evaluate(
            selected: JevImages.Source = source,
            crop: JevCrop = JevCrop.Full,
            text: String = "recognized text",
            configuration: String = "en-US-font-v1",
            revision: String = "fixed-revision",
            preprocessing: String = "roi-v2",
        ): JevCachedScore {
            val request = JevScoreCache.key(revision, preprocessing, text, selected.contentDigest,
                listOf(crop.left.toBits(), crop.top.toBits(), crop.right.toBits(), crop.bottom.toBits()).joinToString(",") + configuration)
            return cache.getOrCompute(request) {
                // 两项昂贵工作都必须在 miss 的 lambda 内；摘要命中不生成图片或加载模型。
                preparations++
                engineRuns++
                score()
            }
        }
        assertFalse(evaluate().cacheHit)
        assertTrue(evaluate(JevImages.Source(bytes.copyOf())).cacheHit)
        bytes.fill(9)
        assertTrue(evaluate().cacheHit)
        assertEquals(1, preparations)
        assertEquals(1, engineRuns)
        assertFalse(evaluate(JevImages.Source(bytes)).cacheHit)
        assertFalse(evaluate(crop = JevCrop(0f, 0f, 0.5f, 1f)).cacheHit)
        assertFalse(evaluate(text = "different text").cacheHit)
        assertFalse(evaluate(configuration = "zh-CN-font-v2").cacheHit)
        assertFalse(evaluate(revision = "new-revision").cacheHit)
        assertFalse(evaluate(preprocessing = "roi-v3").cacheHit)
        assertEquals(7, preparations)
        assertEquals(preparations, engineRuns)
    }
}
