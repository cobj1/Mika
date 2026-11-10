package com.wangzan101.mika.core.visual

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

class FrameWatchTest {
    private fun frame(at: Long, level: Int = 0, width: Int = 320) =
        FramePreview(at, width, 640, FrameGrid(2, 2, IntArray(4) { level }))

    private fun encode(frame: FramePreview, select: (FramePreview) -> Boolean, size: Int = 1,
        encoded: () -> Unit = {},
    ): ObservedFrame? {
        if (!select(frame)) return null
        encoded()
        return ObservedFrame(frame.timestampNanos, frame.width, frame.height, frame.grid, ByteArray(size))
    }

    @Test fun ignoresRepeatedProducerFramesAndVisuallyUnchangedFramesBeforeEncoding() = runTest {
        var now = 0L
        val samples = listOf(frame(10), frame(10), frame(20), frame(30, 255))
        val afters = mutableListOf<Long>()
        var index = 0
        var encodes = 0
        val result = FrameWatch.collect(1000, 250, 8, capture = { after, select ->
            afters += after
            encode(samples[index++], select) { encodes++ }
        }, clock = { now }, pause = { now += it })
        assertEquals(listOf(10L, 30L), result.entries.map { it.frame.timestampNanos })
        assertEquals(listOf(0L, 750L), result.entries.map { it.sampledAtMs })
        assertEquals(listOf(0L, 10L, 10L, 20L), afters)
        assertEquals(3, result.newFrames)
        assertEquals(2, encodes)
    }

    @Test fun stopsAtFrameBudgetWithoutEncodingMoreOrWaitingRemainingDuration() = runTest {
        var now = 0L
        var encodes = 0
        val result = FrameWatch.collect(10000, 250, 2, capture = { _, select ->
            encode(frame(now + 1, if (now == 0L) 0 else 255), select) { encodes++ }
        }, clock = { now }, pause = { now += it })
        assertEquals("frame_limit", result.reason)
        assertEquals(250, result.elapsedMs)
        assertEquals(2, encodes)
    }

    @Test fun fullImageBudgetStopsBeforeAnotherCaptureOrEncoding() = runTest {
        var now = 0L
        var encodes = 0
        val result = FrameWatch.collect(1000, 250, 8, capture = { _, select ->
            encode(frame(now + 1, if (now == 0L) 0 else 255), select, FrameWatch.MAX_BYTES) { encodes++ }
        }, clock = { now }, pause = { now += it })
        assertEquals("byte_limit", result.reason)
        assertEquals(1, result.entries.size)
        assertEquals(1, encodes)
    }

    @Test fun imageExceedingRemainingBudgetIsNotIncluded() = runTest {
        var now = 0L
        val result = FrameWatch.collect(1000, 250, 8, capture = { _, select ->
            encode(frame(now + 1, if (now == 0L) 0 else 255), select, FrameWatch.MAX_BYTES / 2 + 1)
        }, clock = { now }, pause = { now += it })
        assertEquals("byte_limit", result.reason)
        assertEquals(1, result.entries.size)
        assertTrue(result.entries.sumOf { it.frame.png.size } <= FrameWatch.MAX_BYTES)
    }

    @Test fun checksProtectionBeforeEveryCapture() = runTest {
        var now = 0L
        var captures = 0
        val result = FrameWatch.collect(1000, 250, 8, capture = { _, select -> captures++; encode(frame(1), select) },
            protected = { now > 0 }, clock = { now }, pause = { now += it })
        assertEquals("protected_content", result.reason)
        assertEquals(1, captures)
    }

    @Test fun cancellationStopsSampling() = runTest {
        var captures = 0
        try {
            FrameWatch.collect(1000, 250, 8, capture = { _, select -> captures++; encode(frame(1), select) },
                pause = { throw CancellationException() })
            fail("must propagate cancellation")
        } catch (_: CancellationException) { assertEquals(1, captures) }
    }

    @Test fun cancellationBeforeSelectionPreventsEncoding() = runTest {
        var encodes = 0
        try {
            withContext(Job()) {
                FrameWatch.collect(1000, 250, 8, capture = { _, select ->
                    currentCoroutineContext().cancel()
                    encode(frame(1), select) { encodes++ }
                })
            }
            fail("must propagate cancellation")
        } catch (_: CancellationException) { assertEquals(0, encodes) }
    }

    @Test fun missingFramesNeverClaimStableOrSuccessful() = runTest {
        var now = 0L
        val result = FrameWatch.collect(1000, 250, 8, capture = { _, _ -> null }, clock = { now }, pause = { now += it })
        assertEquals("no_frames", result.reason)
        assertTrue(result.entries.isEmpty())
        assertEquals(1000, result.elapsedMs)
    }

    @Test fun sizeChangeIsEncodedEvenWhenLumaMatches() = runTest {
        var now = 0L
        var encodes = 0
        val result = FrameWatch.collect(500, 250, 8, capture = { _, select ->
            encode(frame(now + 1, width = if (now == 0L) 320 else 640), select) { encodes++ }
        }, clock = { now }, pause = { now += it })
        assertEquals(2, result.entries.size)
        assertEquals(2, encodes)
    }

    @Test fun slowAccumulatedChangeComparesAgainstLastKeptFrame() = runTest {
        var now = 0L
        var encodes = 0
        val result = FrameWatch.collect(750, 250, 8, capture = { _, select ->
            encode(frame(now + 1, (now / 25).toInt()), select) { encodes++ }
        }, clock = { now }, pause = { now += it })
        assertEquals(listOf(1L, 501L), result.entries.map { it.frame.timestampNanos })
        assertEquals(3, result.newFrames)
        assertEquals(2, encodes)
    }

    @Test fun eachWatchStartsFreshWithoutSharingItsDeduplicationState() = runTest {
        var encodes = 0
        repeat(2) {
            val result = FrameWatch.collect(250, 250, 1, capture = { after, select ->
                assertEquals(0L, after)
                encode(frame(10), select) { encodes++ }
            })
            assertEquals(1, result.entries.size)
        }
        assertEquals(2, encodes)
    }

    @Test fun unknownGridIsEncodedRatherThanAssumedUnchanged() = runTest {
        var now = 0L
        var encodes = 0
        val result = FrameWatch.collect(500, 250, 8, capture = { _, select ->
            encode(frame(now + 1).copy(grid = null), select) { encodes++ }
        }, clock = { now }, pause = { now += it })
        assertEquals(2, result.entries.size)
        assertEquals(2, encodes)
    }

    @Test fun processingTimeDoesNotDriftSamplingAndLabelsUseCaptureStart() = runTest {
        var now = 0L
        val starts = mutableListOf<Long>()
        val result = FrameWatch.collect(1000, 250, 8, capture = { _, select ->
            starts += now
            val candidate = frame(now + 1).copy(grid = null)
            now += 40 // 复制、采样
            encode(candidate, select) { now += 40 } // PNG 编码
        }, clock = { now }, pause = { now += it })
        assertEquals(listOf(0L, 250L, 500L, 750L), starts)
        assertEquals(starts, result.entries.map { it.sampledAtMs })
        assertEquals(1000L, result.elapsedMs)
    }

    @Test fun slowCaptureSkipsMissedSlotsWithoutBurstOrAnExtraCaptureAtDeadline() = runTest {
        var now = 0L
        val starts = mutableListOf<Long>()
        val result = FrameWatch.collect(1000, 250, 8, capture = { _, select ->
            starts += now
            encode(frame(now + 1).copy(grid = null), select) { now += if (starts.size == 1) 620 else 100 }
        }, clock = { now }, pause = { now += it })
        assertEquals(listOf(0L, 750L), starts)
        assertEquals(starts, result.entries.map { it.sampledAtMs })
        assertEquals(1000L, result.elapsedMs)
    }

    @Test fun deadlineReachedDuringAccessCheckDoesNotCapture() = runTest {
        var now = 0L
        val result = FrameWatch.collect(250, 250, 8, capture = { _, _ -> error("截止后不能截图") },
            protected = { now = 250; false }, clock = { now }, pause = { now += it })
        assertEquals("no_frames", result.reason)
        assertEquals(250L, result.elapsedMs)
    }

    @Test fun lateWakeDoesNotEvenProbeOrCaptureAfterDeadline() = runTest {
        var now = 0L
        var probes = 0
        var captures = 0
        FrameWatch.collect(250, 250, 8, capture = { _, select -> captures++; encode(frame(1), select) },
            protected = { probes++; false }, clock = { now }, pause = { now += it + 100 })
        assertEquals(1, probes)
        assertEquals(1, captures)
    }

    @Test fun deadlineCrossedWhileCopyingPreventsStartingPngEncoding() = runTest {
        var now = 0L
        var encodes = 0
        val result = FrameWatch.collect(250, 250, 8, capture = { _, select ->
            now = 300
            encode(frame(1), select) { encodes++ }
        }, clock = { now }, pause = { now += it })
        assertEquals(0, encodes)
        assertTrue(result.entries.isEmpty())
        // 同步取帧不能被中途抢占；报告实际耗时，不把 300ms 伪装成 250ms。
        assertEquals(300L, result.elapsedMs)
    }
}
