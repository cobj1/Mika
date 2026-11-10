package com.wangzan101.mika.cap.phone.shell

/** 跨 Binder 调用的触摸状态；调用方串行调用，事件只有明确指定的自有副屏。 */
internal class HeldTouchState(private val send: (Int, Event) -> Boolean) {
    data class Bounds(val width: Int, val height: Int, val rotation: Int)
    data class Finger(val id: Int, val x: Int, val y: Int)
    enum class Action { Down, PointerDown, Move, PointerUp, Up, Cancel }
    data class Event(val downTime: Long, val eventTime: Long, val action: Action, val index: Int, val fingers: List<Finger>)
    private data class Gesture(val downTime: Long, val lastAt: Long, val bounds: Bounds, val fingers: List<Finger>)
    private val gestures = mutableMapOf<Int, Gesture>()
    val active: Boolean get() = gestures.isNotEmpty()

    /** >=0 为当前按下数；-1 表示拒绝；-2 表示投递不确定，已补发取消并清空状态。 */
    fun pointer(displayId: Int, op: Int, pointerId: Int, x: Int, y: Int, bounds: Bounds?, now: Long): Int {
        if (displayId <= 0 || op !in DOWN..CANCEL) return REJECTED
        val previous = gestures[displayId]
        if (op == CANCEL) return cancel(displayId, now)
        if (bounds == null || bounds.width <= 0 || bounds.height <= 0) {
            cancel(displayId, now)
            return REJECTED
        }
        if (previous != null && (previous.bounds != bounds || now - previous.lastAt >= IDLE_TIMEOUT_MS)) {
            cancel(displayId, now)
            return REJECTED
        }
        if (pointerId !in 0 until MAX_FINGERS || x !in 0 until bounds.width || y !in 0 until bounds.height) return REJECTED
        val old = previous?.fingers.orEmpty()
        val index = old.indexOfFirst { it.id == pointerId }
        if (op == DOWN && (index >= 0 || old.size >= MAX_FINGERS)) return REJECTED
        if (op != DOWN && index < 0) return REJECTED
        val finger = Finger(pointerId, x, y)
        val points = if (op == DOWN) old + finger else old.map { if (it.id == pointerId) finger else it }
        val action = when (op) {
            DOWN -> if (old.isEmpty()) Action.Down else Action.PointerDown
            MOVE -> Action.Move
            else -> if (old.size == 1) Action.Up else Action.PointerUp
        }
        val eventTime = maxOf(now, previous?.lastAt ?: now)
        val next = Gesture(previous?.downTime ?: eventTime, eventTime, bounds, points)
        // 注入失败也可能已部分派发，先记录这一份，使取消包含可能已按下的所有手指。
        gestures[displayId] = next
        val event = Event(next.downTime, eventTime, action, if (op == DOWN) points.lastIndex else index, points)
        if (!deliver(displayId, event)) {
            cancel(displayId, eventTime)
            return INJECTION_FAILED
        }
        val remaining = if (op == UP) points.filterNot { it.id == pointerId } else points
        if (remaining.isEmpty()) gestures.remove(displayId) else gestures[displayId] = next.copy(fingers = remaining)
        return remaining.size
    }

    fun cancel(displayId: Int, now: Long): Int {
        val old = gestures.remove(displayId) ?: return 0
        val event = Event(old.downTime, maxOf(now, old.lastAt), Action.Cancel, 0, old.fingers)
        // 取消不改成 UP，避免把一次失败/超时的长按当作点击提交。
        return if (deliver(displayId, event) || deliver(displayId, event)) 0 else INJECTION_FAILED
    }

    fun cancelAll(now: Long) { gestures.keys.toList().forEach { cancel(it, now) } }

    fun expire(now: Long, currentBounds: (Int) -> Bounds?) {
        gestures.filter { (id, g) -> now - g.lastAt >= IDLE_TIMEOUT_MS || currentBounds(id) != g.bounds }
            .keys.toList().forEach { cancel(it, now) }
    }

    private fun deliver(displayId: Int, event: Event): Boolean = try { send(displayId, event) } catch (_: Exception) { false }

    companion object {
        const val DOWN = 0
        const val MOVE = 1
        const val UP = 2
        const val CANCEL = 3
        const val MAX_FINGERS = 10
        const val IDLE_TIMEOUT_MS = 30_000L
        const val REJECTED = -1
        const val INJECTION_FAILED = -2
    }
}
