package com.wangzan101.mika.cap.phone.a11y

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class OcrReadSessionTest {
    @Test fun failedStartupWaitsForTheAlreadyRunningRecognizer() {
        var released = 0
        var outcome: Result<List<String>>? = null
        val session = OcrReadSession<String>(2, { true }, { released++ }) { outcome = it }
        session.finish(1) { throw IllegalStateException("second recognizer did not start") }
        assertEquals(0, released)
        assertNull(outcome)
        session.finish(0) {
            assertEquals("input must stay readable until the first recognizer finishes", 0, released)
            listOf("中文")
        }
        assertEquals(listOf("中文"), outcome!!.getOrThrow())
        assertEquals(1, released)
    }

    @Test fun resultConversionExceptionCannotEscapeOrLeaveTheRequestPending() {
        var released = 0
        var outcome: Result<List<String>>? = null
        val session = OcrReadSession<String>(2, { true }, { released++ }) { outcome = it }
        session.finish(0) { throw IllegalArgumentException("invalid OCR result") }
        session.finish(1) { listOf("fallback") }
        assertEquals(listOf("fallback"), outcome!!.getOrThrow())
        assertEquals(1, released)
    }

    @Test fun twoFailuresStayFailuresAndAlwaysReleaseTheInput() {
        var released = false
        var outcome: Result<List<String>>? = null
        val first = IllegalStateException("first failed")
        val session = OcrReadSession<String>(2, { true }, { released = true }) {
            assertTrue(released)
            outcome = it
        }
        session.finish(0) { throw first }
        session.finish(1) { throw IllegalArgumentException("second failed") }
        assertSame(first, outcome!!.exceptionOrNull())
    }

    @Test fun cancellationSkipsResultAllocationButWaitsForBothTerminalCallbacks() {
        var active = true
        var released = 0
        val session = OcrReadSession<String>(2, { active }, { released++ }) { error("cancelled caller must not receive results") }
        active = false
        session.finish(1) { error("cancelled OCR must not process text") }
        assertEquals(0, released)
        session.finish(0) { error("cancelled OCR must not process text") }
        assertEquals(1, released)
    }

    @Test fun outOfMemoryIsReportedEvenWhenTheOtherRecognizerSucceeded() {
        var released = 0
        var outcome: Result<List<String>>? = null
        val oom = OutOfMemoryError("simulated conversion allocation failure")
        val session = OcrReadSession<String>(2, { true }, { released++ }) { outcome = it }
        session.finish(0) { listOf("partial") }
        session.finish(1) { throw oom }
        assertSame(oom, outcome!!.exceptionOrNull())
        assertEquals(1, released)
    }

    @Test fun repeatedCompletionDoesNotReadARecycledInputOrReleaseTwice() {
        var released = 0
        var completed = 0
        val session = OcrReadSession<String>(2, { true }, { released++ }) { completed++ }
        session.finish(0) { listOf("one") }
        session.finish(0) { error("duplicate result") }
        assertEquals(0, released)
        session.finish(1) { listOf("two") }
        session.finish(1) { error("input has already been released") }
        assertEquals(1, released)
        assertEquals(1, completed)
    }

    @Test fun concurrentCallbacksFinishReadingBeforeTheSingleRelease() {
        val started = CountDownLatch(2)
        val proceed = CountDownLatch(1)
        val readers = AtomicInteger()
        val released = AtomicInteger()
        val completed = AtomicInteger()
        val outcome = AtomicReference<List<String>>()
        val pool = Executors.newFixedThreadPool(2)
        val session = OcrReadSession<String>(2, { true }, {
            assertEquals(0, readers.get())
            released.incrementAndGet()
        }) { outcome.set(it.getOrThrow()); completed.incrementAndGet() }
        try {
            val work = (0..1).map { index -> pool.submit {
                session.finish(index) {
                    readers.incrementAndGet()
                    started.countDown()
                    try {
                        assertTrue(proceed.await(5, TimeUnit.SECONDS))
                        assertEquals(0, released.get())
                        listOf("result-$index")
                    } finally { readers.decrementAndGet() }
                }
            } }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            assertEquals(0, released.get())
            proceed.countDown()
            work.forEach { it.get(5, TimeUnit.SECONDS) }
            assertEquals(listOf("result-0", "result-1"), outcome.get())
            assertEquals(1, released.get())
            assertEquals(1, completed.get())
        } finally { proceed.countDown(); pool.shutdownNow() }
    }
}
