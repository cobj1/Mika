package com.wangzan101.mika.jev

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext

/**
 * 只由页面主线程调用。快速离页再进入时，等待旧页面已取消的任务完成清理后刷新一次。
 * 普通运行中的任务不排队、不取消；离页同时撤销待刷新请求，防止页面消失后继续扫模型。
 */
internal class JevRefreshQueue(private val scope: CoroutineScope) {
    private var pending: Job? = null

    fun request(previous: Job?, refresh: () -> Unit) {
        if (pending?.isActive == true) return
        if (previous == null || previous.isCompleted) {
            refresh()
            return
        }
        if (!previous.isCancelled) return
        val queued = scope.launch(start = CoroutineStart.LAZY) {
            try {
                previous.join()
                coroutineContext.ensureActive()
                refresh()
            } finally {
                if (pending === coroutineContext[Job]) pending = null
            }
        }
        // 先记录再启动，兼容 Main.immediate 同步完成的情形。
        pending = queued
        queued.start()
    }

    fun cancel() {
        pending?.cancel()
        pending = null
    }
}
