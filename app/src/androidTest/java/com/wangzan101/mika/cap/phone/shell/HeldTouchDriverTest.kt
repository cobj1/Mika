package com.wangzan101.mika.cap.phone.shell

import android.view.InputDevice
import android.view.MotionEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 不注入系统事件；在 Android 上确认 MotionEvent 编码和回收前的原生字段。 */
@RunWith(AndroidJUnit4::class)
class HeldTouchDriverTest {
    @Test fun nativeEventCarriesBothFingersWithStableDownTimeAndActionIndex() {
        val event = HeldTouchState.Event(100, 120, HeldTouchState.Action.PointerUp, 1,
            listOf(HeldTouchState.Finger(8, 10, 20), HeldTouchState.Finger(3, 30, 40)))
        val native = HeldTouchDriver.motionEvent(event)
        try {
            assertEquals(MotionEvent.ACTION_POINTER_UP, native.actionMasked)
            assertEquals(1, native.actionIndex)
            assertEquals(2, native.pointerCount)
            assertEquals(8, native.getPointerId(0))
            assertEquals(3, native.getPointerId(1))
            assertEquals(30f, native.getX(1), 0f)
            assertEquals(40f, native.getY(1), 0f)
            assertEquals(0f, native.getPressure(1), 0f)
            assertEquals(1f, native.getPressure(0), 0f)
            assertEquals(100L, native.downTime)
            assertEquals(120L, native.eventTime)
            assertEquals(InputDevice.SOURCE_TOUCHSCREEN, native.source)
        } finally { native.recycle() }
    }

    @Test fun refusesUnregisteredScreensAndCancelsBeforeUnregisterAndSingleStroke() {
        val actions = mutableListOf<Int>()
        val driver = HeldTouchDriver({ HeldTouchState.Bounds(720, 1600, 0) }) { _, event -> actions += event.actionMasked; true }
        driver.register(4)
        try {
            assertEquals(-1, driver.pointer(0, 0, 0, 10, 20))
            assertEquals(-1, driver.pointer(5, 0, 0, 10, 20))
            assertEquals(1, driver.pointer(4, 0, 0, 10, 20))
            assertTrue(driver.single(4) { actions += -99; true })
            assertEquals(listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_CANCEL, -99), actions)
            assertEquals(1, driver.pointer(4, 0, 0, 10, 20))
            driver.unregister(4)
            assertEquals(MotionEvent.ACTION_CANCEL, actions.last())
            assertEquals(-1, driver.pointer(4, 0, 0, 10, 20))
        } finally { driver.unregisterAll() }
    }
}
