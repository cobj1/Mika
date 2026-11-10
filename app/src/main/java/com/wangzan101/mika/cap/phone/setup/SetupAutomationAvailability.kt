package com.wangzan101.mika.cap.phone.setup

/** 等待页面切换稳定；同一活动窗口持续不给节点时，交回明确的手动步骤。 */
internal class SetupAutomationAvailability(private val graceMs: Long = 4_000L) {
    private var missingWindow: Int? = null
    private var missingSince = 0L

    fun unreadable(windowId: Int?, nowMs: Long): Boolean {
        if (windowId == null) { reset(); return false }
        if (missingWindow != windowId) {
            missingWindow = windowId
            missingSince = nowMs
            return false
        }
        return nowMs - missingSince >= graceMs
    }

    fun reset() { missingWindow = null; missingSince = 0L }
}
