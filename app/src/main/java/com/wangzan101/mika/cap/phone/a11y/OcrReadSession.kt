package com.wangzan101.mika.cap.phone.a11y

/**
 * 两套识别器共用输入，任何已启动的识别器都结束后才能释放。
 * 回调转换也属于读取过程：异常或取消不能跳过结算，也不能提前回收另一套仍在使用的图片。
 */
internal class OcrReadSession<T>(
    count: Int,
    private val active: () -> Boolean,
    private val release: () -> Unit,
    private val complete: (Result<List<T>>) -> Unit,
) {
    private val lock = Any()
    private val claimed = BooleanArray(count)
    private val outcomes = MutableList<Result<List<T>>?>(count) { null }
    private var remaining = count

    init { require(count > 0) }

    fun finish(index: Int, read: () -> List<T>) {
        synchronized(lock) {
            if (claimed[index]) return
            claimed[index] = true
        }
        // 已取消时只等终结通知，不再遍历 OCR 内容和分配候选。
        val result = if (active()) runCatching(read) else null
        val last = synchronized(lock) {
            outcomes[index] = result
            --remaining == 0
        }
        if (!last) return
        var resultToDeliver: Result<List<T>>? = null
        try {
            if (active()) resultToDeliver = runCatching {
                // 内存不足等 Error 不能被另一套成功结果掩盖；普通识别失败仍允许单套降级。
                outcomes.firstNotNullOfOrNull { it?.exceptionOrNull()?.takeIf { error -> error is Error } }?.let { throw it }
                val successes = outcomes.mapNotNull { it?.getOrNull() }
                if (successes.isEmpty()) throw outcomes.firstNotNullOfOrNull { it?.exceptionOrNull() }
                    ?: IllegalStateException("text recognition failed")
                successes.flatten()
            }
        } finally {
            try { release() }
            catch (error: Throwable) { resultToDeliver = Result.failure(error) }
            outcomes.clear()
        }
        if (active()) resultToDeliver?.let(complete)
    }
}
