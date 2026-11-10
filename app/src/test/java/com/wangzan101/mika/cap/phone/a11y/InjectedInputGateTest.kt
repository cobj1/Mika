package com.wangzan101.mika.cap.phone.a11y

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.awaitCancellation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class InjectedInputGateTest {
    @Test fun anotherScreensAccessibilityCannotCancelHeldTouch() = runTest {
        val gate = InjectedInputGate { testScheduler.currentTime }
        val one = Any()
        val two = Any()
        gate.pointer(one, { true }, { false }) { 1 }
        gate.pointer(two, { true }, { false }) { 1 }
        var sent = false
        assertFalse(gate.accessibility { sent = true; true })
        gate.pointer(one, { false }, { true }) { 0 }
        assertFalse(gate.accessibility { sent = true; true })
        assertFalse(sent)
        gate.pointer(two, { false }, { true }) { 0 }
        assertTrue(gate.accessibility { completed -> sent = true; completed(); true })
        assertTrue(sent)
    }

    @Test fun pointerWaitsForNativeGestureCallbackEvenWhenCallerIsCancelled() = runTest {
        val gate = InjectedInputGate { testScheduler.currentTime }
        val done = CompletableDeferred<Unit>()
        val gesture = async { gate.accessibility { completed -> done.await(); completed(); true } }
        runCurrent()
        gesture.cancel()
        var sent = false
        val pointer = async { gate.pointer(Any(), { true }, { false }) { sent = true; 1 } }
        runCurrent()
        assertFalse(sent)
        done.complete(Unit)
        runCurrent()
        assertEquals(1, pointer.await())
        assertTrue(sent)
    }

    @Test fun elapsedTimeNeverReleasesHeldTouchWithoutConfirmedCancellation() = runTest {
        var time = 0L
        val gate = InjectedInputGate { time }
        val owner = Any()
        var cancels = 0
        gate.pointer(owner, { true }, { cancels++; false }) { 1 }
        time = InjectedInputGate.RECHECK_MS - 1
        assertFalse(gate.accessibility { true })
        assertEquals(0, cancels)
        time++
        assertFalse(gate.accessibility { true })
        time += 100_000L // helper可能被另一笔长stroke挡住，不能按时间猜抬手。
        assertFalse(gate.accessibility { true })
        assertEquals(2, cancels)
        gate.pointer(owner, { false }, { true }, cancelOnly = true) { 0 }
        assertTrue(gate.accessibility { completed -> completed(); true })
    }

    @Test fun expiredHeldTouchCanRecoverWhenExplicitOwnerCancellationSucceeds() = runTest {
        var time = 0L
        var cancelled = false
        val gate = InjectedInputGate { time }
        gate.pointer(Any(), { true }, { cancelled = true; true }) { 1 }
        time = InjectedInputGate.RECHECK_MS
        assertTrue(gate.accessibility { completed -> assertTrue(cancelled); completed(); true })
    }

    @Test fun callbackTimeoutReleasesMutexButBlocksNewInputUntilRealCompletion() = runTest {
        val gate = InjectedInputGate { testScheduler.currentTime }
        lateinit var complete: () -> Unit
        val gesture = async { runCatching { gate.accessibility(100) { completed ->
            complete = completed
            awaitCancellation()
        } } }
        runCurrent()
        advanceTimeBy(100)
        runCurrent()
        assertTrue(gesture.await().exceptionOrNull() is InjectedInputGate.InputCompletionUnknown)
        assertTrue(gate.completionPending)
        var injected = false
        assertTrue(runCatching { gate.pointer(Any(), { true }, { false }) { injected = true; 1 } }
            .exceptionOrNull() is InjectedInputGate.InputCompletionUnknown)
        assertFalse(injected)
        assertTrue(runCatching { gate.accessibility { true } }.exceptionOrNull() is InjectedInputGate.InputCompletionUnknown)
        // 清理仍能进入门，不会因为未知的原生回调而永久卡住mutex。
        assertEquals(0, gate.pointer(Any(), { false }, { true }, cancelOnly = true) { 0 })
        complete()
        assertFalse(gate.completionPending)
        assertEquals(1, gate.pointer(Any(), { true }, { false }) { 1 })
    }

    @Test fun oldCompletionCannotUnlockANewerGesture() = runTest {
        val gate = InjectedInputGate { testScheduler.currentTime }
        lateinit var oldCompletion: () -> Unit
        assertTrue(gate.accessibility { completed -> oldCompletion = completed; completed(); true })
        val done = CompletableDeferred<Unit>()
        val next = async { gate.accessibility { completed -> done.await(); completed(); true } }
        runCurrent()
        oldCompletion()
        assertTrue(gate.completionPending)
        done.complete(Unit)
        assertTrue(next.await())
        assertFalse(gate.completionPending)
    }

    @Test fun confirmedDisplayDestructionRecoversMissingCallbackWithoutTimeAssumptions() = runTest {
        val gate = InjectedInputGate { testScheduler.currentTime }
        var gone = false
        val gesture = async { runCatching {
            gate.accessibility(100, targetGone = { gone }) { awaitCancellation() }
        } }
        runCurrent()
        advanceTimeBy(100)
        runCurrent()
        assertTrue(gesture.await().isFailure)
        assertTrue(gate.completionPending)
        gone = true
        assertFalse(gate.completionPending)
        assertEquals(1, gate.pointer(Any(), { true }, { false }) { 1 })
    }
}
