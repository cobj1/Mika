package com.wangzan101.mika.jev

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class JevRefreshQueueTest {
    @Test fun waitsForCancelledCleanupAndCoalescesRepeatedRefreshRequests() = runTest {
        val cleanup = CompletableDeferred<Unit>()
        val previous = launch(start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { withContext(NonCancellable) { cleanup.await() } }
        }
        previous.cancel()
        runCurrent()
        assertTrue(previous.isCancelled)
        assertFalse(previous.isCompleted)
        val queue = JevRefreshQueue(this)
        var refreshed = 0
        repeat(3) { queue.request(previous) { refreshed++ } }
        runCurrent()
        assertEquals(0, refreshed)
        cleanup.complete(Unit)
        runCurrent()
        assertEquals(1, refreshed)
    }

    @Test fun leavingPageCancelsQueuedRefreshEvenWhenCleanupFinishesLater() = runTest {
        val cleanup = CompletableDeferred<Unit>()
        val previous = launch(start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { withContext(NonCancellable) { cleanup.await() } }
        }
        previous.cancel()
        runCurrent()
        val queue = JevRefreshQueue(this)
        var refreshed = 0
        queue.request(previous) { refreshed++ }
        runCurrent()
        queue.cancel()
        cleanup.complete(Unit)
        runCurrent()
        assertEquals(0, refreshed)
        // 之后真实重新进页仍可刷新，旧取消请求不能阻止它。
        queue.request(previous) { refreshed++ }
        assertEquals(1, refreshed)
    }

    @Test fun refreshDoesNotQueueBehindOrCancelAnOrdinaryRunningTask() = runTest {
        val active = launch { awaitCancellation() }
        runCurrent()
        val queue = JevRefreshQueue(this)
        var refreshed = 0
        queue.request(active) { refreshed++ }
        runCurrent()
        assertTrue(active.isActive)
        assertEquals(0, refreshed)
        active.cancel()
        runCurrent()
        assertEquals(0, refreshed)
        queue.request(active) { refreshed++ }
        assertEquals(1, refreshed)
    }

    @Test fun cancelledQueueCannotClearTheNextPagesPendingRefresh() = runTest {
        val cleanup = CompletableDeferred<Unit>()
        val previous = launch(start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { withContext(NonCancellable) { cleanup.await() } }
        }
        previous.cancel()
        runCurrent()
        val queue = JevRefreshQueue(this)
        var refreshed = 0
        queue.request(previous) { error("已离开的页面不得刷新") }
        runCurrent()
        queue.cancel()
        queue.request(previous) { refreshed++ }
        // 旧排队协程的 finally 此时才执行，不应清除新页面的 pending。
        runCurrent()
        queue.request(previous) { refreshed++ }
        cleanup.complete(Unit)
        runCurrent()
        assertEquals(1, refreshed)
    }
}
