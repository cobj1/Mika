package com.wangzan101.mika.cap.phone.setup

/** 仅在本次明确启动系统设置后创建；敏感页面不提供包名时，用窗口标题和后续整页 OCR 交叉核对。 */
internal class SetupAutomationWindowLease(private val settingsPackage: String, private val openedAt: Long) {
    private var windowId: Int? = null

    fun accepts(id: Int, title: String?, owner: String?, now: Long): Boolean {
        if (id < 0 || now < openedAt || now - openedAt > 90_000) return false
        if (owner != null && owner != settingsPackage) return false
        val developer = title?.lowercase()?.filterNot(Char::isWhitespace) in TITLES
        if (owner == settingsPackage) {
            if (developer && windowId == null) windowId = id
            return true
        }
        if (!developer) return false
        // 包名不可用时只接纳刚刚启动的一个窗口，不能随新的未知窗口重新绑定。
        if (windowId == null && now - openedAt <= 15_000) windowId = id
        return windowId == id
    }

    private companion object {
        val TITLES = setOf("开发者选项", "開發者選項", "開發人員選項", "developeroptions")
    }
}
