package com.wangzan101.mika.cap.phone.display

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 可见预览持有租约；多个窗口共享，任意一个关闭都不会停掉其他窗口的预览。 */
internal class PreviewDemand {
    private var count = 0
    private val _active = MutableStateFlow(false)
    val active = _active.asStateFlow()

    @Synchronized fun acquire(): AutoCloseable {
        count++
        _active.value = true
        var closed = false
        return AutoCloseable {
            synchronized(this) {
                if (!closed) {
                    closed = true
                    count--
                    _active.value = count > 0
                }
            }
        }
    }
}
