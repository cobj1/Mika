package com.wangzan101.mika.cap.phone.a11y

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicReference

/** dispatchGesture 会取消已有触摸；有任何任务仍按着时，不让全局无障碍手势插入。 */
internal class InjectedInputGate(private val now: () -> Long) {
    private val mutex = Mutex()
    private data class Held(val checkAt: Long, val cancel: () -> Boolean)
    private val heldUntil = mutableMapOf<Any, Held>()
    private class Pending(val targetGone: () -> Boolean)
    private val pendingGesture = AtomicReference<Pending?>(null)
    val completionPending: Boolean get() {
        val pending = pendingGesture.get() ?: return false
        // 副屏已确认销毁也是输入不可能继续的证据，不能把无回调故障拖到下一项任务。
        if (runCatching(pending.targetGone).getOrDefault(false)) pendingGesture.compareAndSet(pending, null)
        return pendingGesture.get() != null
    }

    suspend fun <T> pointer(owner: Any, held: () -> Boolean, cancel: () -> Boolean,
        cancelOnly: Boolean = false, block: () -> T): T = mutex.withLock {
        if (!cancelOnly && completionPending) throw InputCompletionUnknown()
        try { block() } finally {
            if (held()) heldUntil[owner] = Held(now() + RECHECK_MS, cancel) else heldUntil.remove(owner)
        }
    }

    suspend fun accessibility(timeoutMs: Long = MAX_CALLBACK_WAIT_MS,
        targetGone: () -> Boolean = { false },
        block: suspend (completed: () -> Unit) -> Boolean): Boolean = mutex.withLock {
        if (completionPending) throw InputCompletionUnknown()
        val at = now()
        // helper的超时线程可能被另一笔长手势阻塞，时间到了只发取消核对，不把计时当成已经抬手。
        val expired = heldUntil.filterValues { it.checkAt <= at }
        for ((owner, entry) in expired) {
            if (runCatching(entry.cancel).getOrDefault(false)) heldUntil.remove(owner)
        }
        if (heldUntil.isNotEmpty()) return@withLock false
        val token = Pending(targetGone)
        pendingGesture.set(token)
        val completed = { pendingGesture.compareAndSet(token, null); Unit }
        // 超时仅释放mutex，不假定系统输入已结束；迟到回调仍可解除未知状态。
        val result = withContext(NonCancellable) {
            withTimeoutOrNull(timeoutMs.coerceIn(1, MAX_CALLBACK_WAIT_MS)) { block(completed) }
        }
        if (pendingGesture.get() === token) throw InputCompletionUnknown()
        result ?: false
    }

    class InputCompletionUnknown : IllegalStateException("Previous accessibility gesture completion is unknown; wait for its callback before sending more input.")

    companion object {
        const val RECHECK_MS = 30_000L
        const val MAX_CALLBACK_WAIT_MS = 65_000L
        val shared by lazy { InjectedInputGate(android.os.SystemClock::uptimeMillis) }
    }
}
