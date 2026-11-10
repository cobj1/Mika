package com.wangzan101.mika.localmodel

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import com.wangzan101.mika.core.model.Capabilities
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.ModelError
import com.wangzan101.mika.core.model.ModelReply
import com.wangzan101.mika.core.model.PromptCaching
import com.wangzan101.mika.core.model.StreamingChatModel
import com.wangzan101.mika.core.model.ToolSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.withLock
import java.io.File
import kotlin.coroutines.coroutineContext

/** 手机本机主模型。只对固定目录的已校验权重启动 APK 内运行时，无外网推理和自动云端回退。 */
class LocalChatModel(context: Context, private val modelId: String) : StreamingChatModel {
    private val app = context.applicationContext
    private val spec get() = LocalModelCatalog.find(modelId)
    override val name: String get() = spec?.name ?: modelId
    override val capabilities: Capabilities get() = capabilities(spec?.contextTokens ?: 16_384)

    override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply =
        request(system, messages, tools, null)

    override suspend fun completeStreaming(system: String, messages: List<Message>, tools: List<ToolSpec>, onText: (String) -> Unit): ModelReply =
        request(system, messages, tools, onText)

    private suspend fun request(system: String, messages: List<Message>, tools: List<ToolSpec>, onText: ((String) -> Unit)?): ModelReply = try {
        // 下载/删除与所有本地主模型共享入口；等待过程可取消，文件不会在原生加载途中被移走。
        LocalModelAccess.mutex.withLock {
            coroutineContext.ensureActive()
            val selected = spec ?: throw ModelError.LocalUnavailable("所选本地模型不存在，请重新选择。")
            val executable = File(app.applicationInfo.nativeLibraryDir, "libjevserver.so")
            if (Build.VERSION.SDK_INT < 30 || "arm64-v8a" !in Build.SUPPORTED_ABIS || !executable.isFile || !executable.canExecute())
                throw ModelError.LocalUnavailable("此设备无法运行内置的本地推理引擎。")
            requireMemory(selected.minAvailableBytes)
            val files = LocalModelFiles.get(app)
            if (!files.verify(selected)) throw ModelError.LocalUnavailable("模型尚未下载完整或校验失败，请到设置重新下载。")
            requireMemory(selected.minAvailableBytes)
            LocalModelEngine(executable, files.modelFile(selected), selected.id, capabilities).complete(system, messages, tools, onText)
        }
    } catch (cancelled: TimeoutCancellationException) {
        // 外部任务超时仍传播取消，只有引擎自己的预算耗尽转成可理解的本机错误。
        coroutineContext.ensureActive()
        throw ModelError.LocalUnavailable("本地模型运行超时，请缩短任务或选择更小的模型。")
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: OutOfMemoryError) { throw ModelError.LocalUnavailable("当前可用内存不足，请关闭其他应用或选择更小的模型。") }
    catch (error: ModelError) {
        if (error is ModelError.Network || error is ModelError.Server || error is ModelError.StreamCut)
            throw ModelError.LocalUnavailable("本地推理进程已停止或连接中断，请重试。")
        throw error
    }
    catch (_: Exception) { throw ModelError.LocalUnavailable("本地推理未能完成，请检查模型文件和设备可用内存。") }

    private fun requireMemory(minimum: Long) {
        val memory = ActivityManager.MemoryInfo()
        app.getSystemService(ActivityManager::class.java).getMemoryInfo(memory)
        if (memory.lowMemory || memory.availMem < minimum)
            throw ModelError.LocalUnavailable("当前可用内存不足，请关闭其他应用或选择更小的模型。")
    }

    companion object {
        fun capabilities(contextTokens: Int = 16_384) = Capabilities(
            toolCalls = true, vision = false, contextTokens = contextTokens, reasoning = false,
            promptCaching = PromptCaching.None, maxImages = 0,
        )
    }
}
