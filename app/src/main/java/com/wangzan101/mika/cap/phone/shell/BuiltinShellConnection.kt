package com.wangzan101.mika.cap.phone.shell

import com.wangzan101.mika.core.shell.HelperLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import com.wangzan101.mika.shell.IMikaShell

/**
 * 内置助手的连接。解绑撤掉活动屏和系统改动；异常退出后已停放的屏保留至原定的恢复超时。
 * 再绑定时直接用手里的 Binder。没有助手时请看门狗起一个。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BuiltinShellConnection(hub: HelperHub, need: () -> Unit) : ShellBinding {
    // 单线程:撤销和下一次发布按先后执行;撤销是同步 binder 调用,不放主线程
    private val link = HelperLink(
        hub.shell,
        CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1)),
        release = { it.releaseSessionResources() },
        need = need,
    )

    override val shell: StateFlow<IMikaShell?> get() = link.shell
    override val dropped: StateFlow<Boolean> get() = link.dropped
    override fun bind() = link.bind()
    override fun unbind() = link.unbind()
    override fun reconnect() = link.reconnect()
}
