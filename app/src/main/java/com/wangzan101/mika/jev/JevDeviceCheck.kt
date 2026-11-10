package com.wangzan101.mika.jev

/** 下载前的保守资源预检，不将总内存或一次可用内存检查当成实测兼容保证。 */
internal object JevDeviceCheck {
    const val MIN_AVAILABLE = 3L * 1024 * 1024 * 1024
    const val STORAGE_RESERVE = 128L * 1024 * 1024
    enum class Status { Unsupported, LowMemory, LowStorage, Ready }
    fun memoryReady(lowMemory: Boolean, available: Long): Boolean = !lowMemory && available >= MIN_AVAILABLE
    fun assess(supported: Boolean, lowMemory: Boolean, available: Long, freeStorage: Long, remainingDownload: Long): Status = when {
        !supported -> Status.Unsupported
        !memoryReady(lowMemory, available) -> Status.LowMemory
        freeStorage < remainingDownload.coerceAtLeast(0) + STORAGE_RESERVE -> Status.LowStorage
        else -> Status.Ready
    }
}
