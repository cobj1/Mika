package com.wangzan101.mika.cap.phone.shell

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** 维护连续触摸的注册屏、超时与原生事件；没有 shell 命令或主屏回退。 */
internal class HeldTouchDriver(
    private val bounds: (Int) -> HeldTouchState.Bounds?,
    private val inject: (Int, MotionEvent) -> Boolean,
) {
    private val lock = Any()
    private val registered = mutableSetOf<Int>()
    private val state = HeldTouchState { id, event ->
        val motion = motionEvent(event)
        try { inject(id, motion) } finally { motion.recycle() }
    }
    private val timer by lazy {
        Executors.newSingleThreadScheduledExecutor { Thread(it, "mika-held-touch").apply { isDaemon = true } }
    }
    private var watch: ScheduledFuture<*>? = null

    fun register(displayId: Int) { synchronized(lock) { if (displayId > 0) registered.add(displayId) } }

    /** 先撤掉接收资格再释放屏，阻止并发的 down 在清理之后重新留下手指。 */
    fun unregister(displayId: Int) = synchronized(lock) {
        registered.remove(displayId)
        state.cancel(displayId, SystemClock.uptimeMillis())
        stopIdleWatch()
    }

    fun unregisterAll() = synchronized(lock) {
        registered.clear()
        state.cancelAll(SystemClock.uptimeMillis())
        stopIdleWatch()
    }

    /** 断线后仍保留副屏时只取消手指；重新接回后可继续操作。 */
    fun cancelAll() = synchronized(lock) {
        state.cancelAll(SystemClock.uptimeMillis())
        stopIdleWatch()
    }

    fun pointer(displayId: Int, op: Int, pointerId: Int, x: Int, y: Int): Int = synchronized(lock) {
        if (displayId !in registered) return@synchronized HeldTouchState.REJECTED
        val result = state.pointer(displayId, op, pointerId, x, y, currentBounds(displayId), SystemClock.uptimeMillis())
        if (state.active && watch == null) watch = timer.scheduleWithFixedDelay({
            synchronized(lock) {
                state.expire(SystemClock.uptimeMillis(), ::currentBounds)
                stopIdleWatch()
            }
        }, 1, 1, TimeUnit.SECONDS)
        stopIdleWatch()
        result
    }

    /** 单次 tap/stroke 与跨调用的手指不能混用；先取消旧手势，整笔注入期间保持互斥。 */
    fun single(displayId: Int, block: () -> Boolean): Boolean = synchronized(lock) {
        if (displayId !in registered || currentBounds(displayId) == null) return@synchronized false
        val cancelled = state.cancel(displayId, SystemClock.uptimeMillis())
        stopIdleWatch()
        if (cancelled < 0) false else block()
    }

    private fun currentBounds(displayId: Int) = if (displayId in registered) runCatching { bounds(displayId) }.getOrNull() else null

    private fun stopIdleWatch() {
        if (!state.active) { watch?.cancel(false); watch = null }
    }

    companion object {
        internal fun motionEvent(event: HeldTouchState.Event): MotionEvent {
            val action = when (event.action) {
                HeldTouchState.Action.Down -> MotionEvent.ACTION_DOWN
                HeldTouchState.Action.PointerDown -> MotionEvent.ACTION_POINTER_DOWN or (event.index shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
                HeldTouchState.Action.Move -> MotionEvent.ACTION_MOVE
                HeldTouchState.Action.PointerUp -> MotionEvent.ACTION_POINTER_UP or (event.index shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
                HeldTouchState.Action.Up -> MotionEvent.ACTION_UP
                HeldTouchState.Action.Cancel -> MotionEvent.ACTION_CANCEL
            }
            val properties = event.fingers.map { finger -> MotionEvent.PointerProperties().apply {
                id = finger.id; toolType = MotionEvent.TOOL_TYPE_FINGER
            } }.toTypedArray()
            val coordinates = event.fingers.mapIndexed { index, finger -> MotionEvent.PointerCoords().apply {
                x = finger.x.toFloat(); y = finger.y.toFloat(); size = 1f
                pressure = if (event.action == HeldTouchState.Action.Cancel ||
                    (event.action in listOf(HeldTouchState.Action.Up, HeldTouchState.Action.PointerUp) && index == event.index)) 0f else 1f
            } }.toTypedArray()
            return MotionEvent.obtain(event.downTime, event.eventTime, action, properties.size, properties, coordinates,
                0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
        }
    }
}
