package com.wangzan101.mika.cap.phone.a11y

/** 仅缓存系统窗口事件的归属，不保留界面文字；身份未知时撤销已有记录。 */
internal class ObservedWindowOwners {
    private data class Window(val displayId: Int, val windowId: Int)
    private val owners = LinkedHashMap<Window, String>()

    @Synchronized fun observe(displayId: Int, windowId: Int, packageName: String?) {
        if (displayId < 0 || windowId < 0) { remove(displayId, windowId); return }
        val key = Window(displayId, windowId)
        owners.remove(key)
        if (!packageName.isNullOrBlank()) {
            owners[key] = packageName
            // 异常系统若漏发移除事件，也不无限积累已失效的身份。
            while (owners.size > MAX_WINDOWS) owners.remove(owners.keys.first())
        }
    }

    @Synchronized fun remove(displayId: Int, windowId: Int) {
        // 无法确定屏号时清除同号窗口；无法确定窗口号时保守清空受影响屏幕。
        owners.keys.removeAll {
            (displayId < 0 || it.displayId == displayId) && (windowId < 0 || it.windowId == windowId)
        }
    }

    @Synchronized fun packageOf(displayId: Int, windowId: Int): String? = owners[Window(displayId, windowId)]

    @Synchronized fun clear() { owners.clear() }

    private companion object { const val MAX_WINDOWS = 128 }
}
