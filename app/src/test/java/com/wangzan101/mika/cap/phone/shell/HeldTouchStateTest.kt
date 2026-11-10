package com.wangzan101.mika.cap.phone.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HeldTouchStateTest {
    private val bounds = HeldTouchState.Bounds(720, 1600, 0)
    private val events = mutableListOf<Pair<Int, HeldTouchState.Event>>()
    private val state = HeldTouchState { display, event -> events.add(display to event); true }
    private var time = 100L

    private fun pointer(op: Int, id: Int = 0, x: Int = 100, y: Int = 200, display: Int = 4,
        size: HeldTouchState.Bounds? = bounds): Int = state.pointer(display, op, id, x, y, size, time++)

    @Test fun twoFingersKeepDownTimeAndCorrectIndexAfterFirstFingerLifts() {
        assertEquals(1, pointer(HeldTouchState.DOWN, 7))
        assertEquals(2, pointer(HeldTouchState.DOWN, 2, 300, 400))
        assertEquals(2, pointer(HeldTouchState.MOVE, 7, 150, 250))
        assertEquals(1, pointer(HeldTouchState.UP, 7, 160, 260))
        assertEquals(1, pointer(HeldTouchState.MOVE, 2, 330, 440))
        assertEquals(0, pointer(HeldTouchState.UP, 2, 340, 450))
        val sent = events.map { it.second }
        assertEquals(listOf(HeldTouchState.Action.Down, HeldTouchState.Action.PointerDown, HeldTouchState.Action.Move,
            HeldTouchState.Action.PointerUp, HeldTouchState.Action.Move, HeldTouchState.Action.Up), sent.map { it.action })
        assertEquals(1, sent[1].index)
        assertEquals(0, sent[3].index)
        assertEquals(listOf(7, 2), sent[3].fingers.map { it.id })
        assertEquals(listOf(2), sent[4].fingers.map { it.id })
        assertEquals(HeldTouchState.Finger(2, 300, 400), sent[2].fingers[1])
        assertTrue(sent.all { it.downTime == 100L })
        assertEquals((100L..105L).toList(), sent.map { it.eventTime })
        assertFalse(state.active)
    }

    @Test fun pointerUpOfNonFirstFingerUsesItsListIndexNotItsId() {
        pointer(HeldTouchState.DOWN, 8)
        pointer(HeldTouchState.DOWN, 3)
        pointer(HeldTouchState.DOWN, 9)
        assertEquals(2, pointer(HeldTouchState.UP, 3))
        assertEquals(1, events.last().second.index)
        assertEquals(listOf(8, 3, 9), events.last().second.fingers.map { it.id })
        pointer(HeldTouchState.UP, 9)
        assertEquals(1, events.last().second.index)
        assertEquals(listOf(8, 9), events.last().second.fingers.map { it.id })
    }

    @Test fun rejectsDuplicateOrUnknownFingerWithoutChangingGesture() {
        assertEquals(-1, pointer(HeldTouchState.MOVE))
        assertEquals(-1, pointer(HeldTouchState.UP))
        pointer(HeldTouchState.DOWN)
        assertEquals(-1, pointer(HeldTouchState.DOWN))
        assertEquals(-1, pointer(HeldTouchState.UP, 3))
        assertEquals(-1, pointer(4))
        assertEquals(1, events.size)
        assertEquals(0, pointer(HeldTouchState.UP))
    }

    @Test fun rejectsInvalidDisplayCoordinatesAndPointerIds() {
        assertEquals(-1, pointer(HeldTouchState.DOWN, display = 0))
        assertEquals(-1, pointer(HeldTouchState.DOWN, display = -1))
        assertEquals(-1, pointer(HeldTouchState.DOWN, id = -1))
        assertEquals(-1, pointer(HeldTouchState.DOWN, id = 10))
        assertEquals(-1, pointer(HeldTouchState.DOWN, x = 720))
        assertEquals(-1, pointer(HeldTouchState.DOWN, y = 1600))
        assertEquals(-1, pointer(HeldTouchState.DOWN, x = -1))
        assertEquals(-1, pointer(HeldTouchState.DOWN, size = null))
        assertTrue(events.isEmpty())
    }

    @Test fun acceptsTenFingersAndKeepsScreensIndependent() {
        repeat(10) { assertEquals(it + 1, pointer(HeldTouchState.DOWN, id = it)) }
        assertEquals(1, pointer(HeldTouchState.DOWN, display = 5))
        assertEquals(0, pointer(HeldTouchState.CANCEL))
        assertTrue(state.active)
        assertEquals(0, pointer(HeldTouchState.UP, display = 5))
        assertFalse(state.active)
    }

    @Test fun failureCancelsEveryPossiblyDeliveredFingerAndRejectsLaterMove() {
        val seen = mutableListOf<HeldTouchState.Event>()
        val failing = HeldTouchState { _, event -> seen += event; event.action != HeldTouchState.Action.PointerDown }
        assertEquals(1, failing.pointer(4, HeldTouchState.DOWN, 0, 1, 2, bounds, 100))
        assertEquals(-2, failing.pointer(4, HeldTouchState.DOWN, 1, 3, 4, bounds, 110))
        assertEquals(HeldTouchState.Action.Cancel, seen.last().action)
        assertEquals(listOf(0, 1), seen.last().fingers.map { it.id })
        assertEquals(-1, failing.pointer(4, HeldTouchState.MOVE, 0, 1, 2, bounds, 120))
        assertFalse(failing.active)
    }

    @Test fun thrownInjectionCancelsAndFailedCancelIsRetriedWithoutUp() {
        val seen = mutableListOf<HeldTouchState.Action>()
        val failing = HeldTouchState { _, event -> seen += event.action; throw IllegalStateException("inject failed") }
        assertEquals(-2, failing.pointer(4, HeldTouchState.DOWN, 0, 1, 2, bounds, 100))
        assertEquals(listOf(HeldTouchState.Action.Down, HeldTouchState.Action.Cancel, HeldTouchState.Action.Cancel), seen)
        assertFalse(failing.active)
    }

    @Test fun idleTimeoutUsesLastAcceptedCallAndNeverCommitsAReleaseClick() {
        pointer(HeldTouchState.DOWN)
        time = 500
        pointer(HeldTouchState.MOVE)
        state.expire(30_499) { bounds }
        assertEquals(2, events.size)
        state.expire(30_500) { bounds }
        assertEquals(HeldTouchState.Action.Cancel, events.last().second.action)
        assertFalse(state.active)
    }

    @Test fun lateCallCancelsRatherThanRevivingAnExpiredGesture() {
        pointer(HeldTouchState.DOWN)
        time = 30_100
        assertEquals(-1, pointer(HeldTouchState.MOVE))
        assertEquals(HeldTouchState.Action.Cancel, events.last().second.action)
        assertEquals(1, pointer(HeldTouchState.DOWN))
        assertEquals(30_101L, events.last().second.downTime)
    }

    @Test fun changedRotationOrMissingDisplayCancelsAtNextCallOrWatch() {
        pointer(HeldTouchState.DOWN)
        assertEquals(-1, pointer(HeldTouchState.MOVE, size = bounds.copy(rotation = 2)))
        assertEquals(HeldTouchState.Action.Cancel, events.last().second.action)
        pointer(HeldTouchState.DOWN)
        state.expire(time) { null }
        assertEquals(HeldTouchState.Action.Cancel, events.last().second.action)
        assertFalse(state.active)
    }

    @Test fun cancelIsIdempotentIgnoresCoordinatesAndCancelsAllScreens() {
        assertEquals(0, pointer(HeldTouchState.CANCEL, id = -1, x = -1, y = -1))
        pointer(HeldTouchState.DOWN)
        pointer(HeldTouchState.DOWN, display = 5)
        state.cancelAll(time)
        assertEquals(listOf(4, 5), events.takeLast(2).map { it.first })
        assertTrue(events.takeLast(2).all { it.second.action == HeldTouchState.Action.Cancel })
        assertFalse(state.active)
        assertEquals(0, pointer(HeldTouchState.CANCEL))
    }
}
