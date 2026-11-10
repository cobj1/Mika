package com.wangzan101.mika.jev

/** 节制网络小块读取引起的状态更新；开始、重新从零下载和明确完成始终立即发布。 */
internal class JevDownloadProgress(
    private val total: Long,
    private val emit: (Long, Long) -> Unit,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000L },
    private val intervalMs: Long = 200,
) {
    private var lastDone: Long? = null
    private var lastTime = 0L

    init { require(total >= 0 && intervalMs > 0) }

    fun update(done: Long, force: Boolean = false) {
        require(done in 0..total)
        val now = clock()
        val previous = lastDone
        if (!force && previous != null && done >= previous && now >= lastTime && now - lastTime < intervalMs) return
        if (previous == done && !force) return
        lastDone = done
        lastTime = now
        emit(done, total)
    }
}
