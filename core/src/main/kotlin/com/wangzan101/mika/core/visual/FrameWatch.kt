package com.wangzan101.mika.core.visual

import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/** 同一次取帧的像素、尺寸和生产者时间；不能用消费时间冒充新画面。 */
data class ObservedFrame(val timestampNanos: Long, val width: Int, val height: Int, val grid: FrameGrid?, val png: ByteArray)

/** 编码前的轻量帧描述，与随后编码的像素必须来自同一份快照。 */
data class FramePreview(val timestampNanos: Long, val width: Int, val height: Int, val grid: FrameGrid?)

/** 有界、按需的连续观察。近似相同的新帧在 PNG 编码前丢弃，去重状态仅在本次调用内。 */
object FrameWatch {
    data class Entry(val sampledAtMs: Long, val frame: ObservedFrame)
    data class Result(val entries: List<Entry>, val elapsedMs: Long, val newFrames: Int, val reason: String)

    suspend fun collect(
        durationMs: Long, intervalMs: Long, maxFrames: Int,
        capture: suspend (afterNanos: Long, shouldEncode: (FramePreview) -> Boolean) -> ObservedFrame?,
        protected: suspend () -> Boolean = { false },
        clock: () -> Long = { System.nanoTime() / 1_000_000 },
        pause: suspend (Long) -> Unit = { delay(it) },
    ): Result {
        require(durationMs in 250..10_000 && intervalMs in 250..2_000 && maxFrames in 1..8)
        val started = clock()
        val entries = mutableListOf<Entry>()
        var lastTimestamp = 0L
        var newFrames = 0
        var bytes = 0
        var reason = "duration"
        var nextSampleAt = started
        val deadline = started + durationMs
        while (true) {
            coroutineContext.ensureActive()
            val now = clock()
            if (now >= deadline) break
            if (now < nextSampleAt) {
                pause(minOf(nextSampleAt, deadline) - now)
                continue
            }
            if (protected()) { reason = "protected_content"; break }
            // 访问检查也可能耗时；截止后不再启动一次截图/PNG 编码。
            val sampledAt = clock()
            if (sampledAt >= deadline) break
            if (bytes >= MAX_BYTES) { reason = "byte_limit"; break }
            var selected: FramePreview? = null
            val samplingContext = coroutineContext
            val frame = capture(lastTimestamp) select@{ candidate ->
                samplingContext.ensureActive()
                if (clock() >= deadline) return@select false
                if (candidate.timestampNanos <= lastTimestamp) return@select false
                // 未变化的生产者帧也前移游标，下次无需再拷贝、采样同一帧。
                lastTimestamp = candidate.timestampNanos
                newFrames++
                val previous = entries.lastOrNull()?.frame
                val changed = previous == null || previous.width != candidate.width || previous.height != candidate.height ||
                    FrameDiff.compare(previous.grid, candidate.grid)?.visible != false
                if (changed) selected = candidate
                changed
            }
            coroutineContext.ensureActive()
            if (frame != null) {
                // 两阶段必须用同一份像素，禁止筛选旧帧后重新取新帧来编码。
                check(selected?.let { it.timestampNanos == frame.timestampNanos && it.width == frame.width && it.height == frame.height && it.grid === frame.grid } == true)
                if (frame.png.size > MAX_BYTES - bytes) { reason = "byte_limit"; break }
                entries += Entry(sampledAt - started, frame)
                bytes += frame.png.size
                if (entries.size >= maxFrames) { reason = "frame_limit"; break }
                if (bytes >= MAX_BYTES) { reason = "byte_limit"; break }
            }
            // 以开始时刻为基准；慢截图跳过已错过的槽位，不补拍，也不把编码耗时累加到间隔。
            val elapsed = (clock() - started).coerceAtLeast(0)
            nextSampleAt = started + (elapsed / intervalMs + 1) * intervalMs
        }
        return Result(entries, clock() - started, newFrames, if (entries.isEmpty() && reason == "duration") "no_frames" else reason)
    }

    const val MAX_BYTES = 4 * 1024 * 1024
}
