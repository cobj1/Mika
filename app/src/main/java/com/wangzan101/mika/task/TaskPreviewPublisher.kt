package com.wangzan101.mika.task

/** 只管理可见预览。内部截图、OCR 与动作观察不经过这里，也不随预览关闭。 */
internal class TaskPreviewPublisher<T : Any>(
    private val target: () -> Target,
    private val phoneVisible: (Boolean) -> Unit,
    private val phoneAllowed: suspend () -> Boolean,
    private val phoneFrame: () -> T?,
    private val webFrame: suspend () -> T?,
    private val publish: (T?) -> Unit,
) {
    enum class Mode { Hidden, Phone, Web }
    data class Target(val mode: Mode, val source: Any? = null)

    private val lock = Any()
    private var selected: Target? = null
    private var epoch = 0L
    private var webRequest = 0L
    private var phoneRequest = 0L

    /** 手机帧事件只刷新手机；网页复制保持由原来的有界轮询和可见需求触发。 */
    suspend fun refresh(captureWeb: Boolean = false) {
        val request = target()
        val version = synchronized(lock) {
            if (selected != request) {
                selected = request
                epoch++
                publish(null)
            }
            phoneVisible(request.mode == Mode.Phone)
            epoch
        }
        when (request.mode) {
            Mode.Hidden -> synchronized(lock) { if (current(request, version)) publish(null) }
            Mode.Phone -> {
                val serial = synchronized(lock) { ++phoneRequest }
                val allowed = phoneAllowed()
                synchronized(lock) {
                    if (serial == phoneRequest && current(request, version)) publish(if (allowed) phoneFrame() else null)
                }
            }
            Mode.Web -> if (captureWeb) {
                val serial = synchronized(lock) { ++webRequest }
                val frame = webFrame()
                synchronized(lock) {
                    // 截图期间可能切屏、关闭预览或换任务；迟到结果不能复活已撤销的画面。
                    if (serial == webRequest && current(request, version)) publish(frame)
                }
            }
        }
    }

    private fun current(request: Target, version: Long) = epoch == version && selected == request && target() == request
}
