package com.wangzan101.mika.jev

import kotlinx.coroutines.ensureActive
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

/** 一次真实推理的分数与单调时钟耗时；不包含预处理、权重校验和引擎退出时间。 */
internal data class JevScoreResult(val score: Double, val loadMs: Long, val scoreMs: Long)

/** 命中缓存时没有本次模型耗时，用 null 表示，不能把历史计时或 0 毫秒冒充本次测量。 */
internal data class JevCachedScore(val score: Double, val cacheHit: Boolean, val loadMs: Long?, val scoreMs: Long?)

/**
 * 只保存请求摘要和有效分数的进程内 LRU 缓存。过期时间从推理完成起算，命中不会延长。
 * clear 会推进代数，已在途的计算不能把刚清理的内容重新填回；这里不持有图片、文本或 URI。
 */
internal class JevScoreCache(
    private val capacity: Int = 4,
    private val ttlMs: Long = 5 * 60_000L,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    internal data class Key(val digest: String)
    private data class Entry(val score: Double, val at: Long)
    private val lock = Any()
    private val entries = LinkedHashMap<Key, Entry>(capacity, 0.75f, true)
    private var generation = 0L

    init { require(capacity in 1..16 && ttlMs > 0) }

    fun clear() = synchronized(lock) {
        entries.clear()
        generation++
    }

    suspend fun getOrCompute(key: Key, compute: suspend () -> JevScoreResult): JevCachedScore {
        val context = coroutineContext
        context.ensureActive()
        val (cached, version) = synchronized(lock) {
            evictExpired(clock())
            entries[key]?.score to generation
        }
        if (cached != null) return JevCachedScore(cached, true, null, null)
        val fresh = compute()
        context.ensureActive()
        require(fresh.score.isFinite() && fresh.score in 0.0..1.0 && fresh.loadMs >= 0 && fresh.scoreMs >= 0)
        synchronized(lock) {
            context.ensureActive()
            if (generation == version) {
                val now = clock()
                evictExpired(now)
                entries[key] = Entry(fresh.score, now)
                while (entries.size > capacity) entries.remove(entries.keys.first())
            }
        }
        return JevCachedScore(fresh.score, false, fresh.loadMs, fresh.scoreMs)
    }

    private fun evictExpired(now: Long) {
        // 单调时钟倒退的替换时钟/异常环境也不沿用旧值。
        entries.entries.removeAll { (_, value) -> now < value.at || now - value.at >= ttlMs }
    }

    companion object {
        /** 每段显式编码长度和 UTF-16 代码单元，避免拼接边界/代理字符产生摘要歧义。 */
        fun key(revision: String, preprocessingVersion: String, text: String, original: String, rendered: String): Key {
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(8 * 1024)
            for (value in listOf("mika-jev-score-v1", revision, preprocessingVersion, text, original, rendered)) {
                val length = value.length
                for (shift in intArrayOf(24, 16, 8, 0)) digest.update((length ushr shift).toByte())
                var index = 0
                while (index < length) {
                    val count = minOf(buffer.size / 2, length - index)
                    repeat(count) { i ->
                        val unit = value[index + i].code
                        buffer[i * 2] = (unit ushr 8).toByte()
                        buffer[i * 2 + 1] = unit.toByte()
                    }
                    digest.update(buffer, 0, count * 2)
                    index += count
                }
            }
            buffer.fill(0)
            return Key(digest.digest().joinToString("") { "%02x".format(it) })
        }
    }
}
