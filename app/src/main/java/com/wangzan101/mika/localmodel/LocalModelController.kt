package com.wangzan101.mika.localmodel

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import com.wangzan101.mika.R
import com.wangzan101.mika.core.model.Author
import com.wangzan101.mika.core.model.ListedModel
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.ModelError
import com.wangzan101.mika.core.model.ModelRole
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.Role
import com.wangzan101.mika.core.model.ToolSpec
import com.wangzan101.mika.jev.JevDownloadException
import com.wangzan101.mika.jev.JevRefreshQueue
import com.wangzan101.mika.settings.ProviderConfig
import com.wangzan101.mika.settings.ProviderStore
import com.wangzan101.mika.settings.RoleChoice
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.File
import kotlin.coroutines.coroutineContext

enum class LocalModelPhase { Idle, Downloading, Verifying, Activating }
data class LocalModelItem(val id: String, val name: String, val bytes: Long, val ready: Boolean = false,
    val downloadedBytes: Long = 0, val downloadAllowed: Boolean = false, val reason: String? = null, val recommended: Boolean = false)
data class LocalModelState(val rows: List<LocalModelItem> = emptyList(), val phase: LocalModelPhase = LocalModelPhase.Idle,
    val activeId: String? = null, val done: Long = 0, val total: Long = 0, val supported: Boolean = false,
    val error: String? = null, val enabledId: String? = null)

/** 本地下载和启用均由用户发起；测试成功才修改主模型，不自动回退在线服务。 */
class LocalModelController private constructor(context: Context) {
    private val app = context.applicationContext
    private val files = LocalModelFiles.get(app)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutable = MutableStateFlow(LocalModelState())
    val state = mutable.asStateFlow()
    private var job: Job? = null
    private val refreshQueue = JevRefreshQueue(scope)
    private val verified = mutableSetOf<String>()
    private var store: ProviderStore? = null
    private val supported get() = Build.VERSION.SDK_INT >= 30 && "arm64-v8a" in Build.SUPPORTED_ABIS &&
        File(app.applicationInfo.nativeLibraryDir, "libjevserver.so").let { it.isFile && it.canExecute() }

    fun refresh(store: ProviderStore) {
        this.store = store
        refreshQueue.request(job) {
            run(LocalModelPhase.Verifying) {
                LocalModelAccess.mutex.withLock {
                    for (spec in LocalModelCatalog.models) {
                        if (files.verify(spec)) verified.add(spec.id) else verified.remove(spec.id)
                    }
                }
            }
        }
    }

    fun download(id: String) = run(LocalModelPhase.Downloading, id) {
        val spec = selected(id)
        LocalModelAccess.mutex.withLock {
            checkResources(spec, download = true)
            verified.remove(id)
            files.download(spec) { done, total -> mutable.update {
                it.copy(done = done, total = total, phase = if (done == total) LocalModelPhase.Verifying else LocalModelPhase.Downloading)
            } }
            if (!files.verify(spec)) throw Failure(R.string.local_error_integrity)
            verified.add(id)
        }
    }

    fun remove(id: String, store: ProviderStore) = run(LocalModelPhase.Verifying, id) {
        this.store = store
        LocalModelAccess.mutex.withLock {
            // 任何正在使用的本地用途都不能在管理页删除，不制造失效配置。
            if (ModelRole.entries.any { role -> store.configFor(role)?.let { it.baseUrl == LocalModelCatalog.BASE_URL && it.model == id } == true })
                throw Failure(R.string.local_error_active_delete)
            withContext(Dispatchers.IO) { files.delete(selected(id)) }
            verified.remove(id)
            store.providers.value.firstOrNull { it.baseUrl == LocalModelCatalog.BASE_URL }?.let { provider ->
                withContext(Dispatchers.IO) { store.rememberModels(provider.id, provider.models.orEmpty().filterNot { it.id == id }) }
            }
        }
    }

    fun activate(id: String, store: ProviderStore, onSuccess: () -> Unit) = run(LocalModelPhase.Activating, id) {
        this.store = store
        val spec = selected(id)
        checkResources(spec, download = false)
        if (!withContext(Dispatchers.IO) { files.present(spec) }) throw Failure(R.string.local_error_model_missing)
        // 不在持文件锁时调用模型：LocalChatModel 自己持同一锁，避免自锁。
        val reply = withTimeout(5 * 60_000L) { LocalChatModel(app, id).complete(
            "Call the get_time tool now. Do not answer with text.",
            listOf(Message(Role.User, listOf(Part.Text("What time is it? Use get_time.")), Author.Mika)),
            listOf(ToolSpec("get_time", "Returns the current time.", """{"type":"object","properties":{},"additionalProperties":false}""")),
        ) }
        if (!LocalModelActivation.validProbe(reply.parts)) throw Failure(R.string.local_error_runtime)
        LocalModelAccess.mutex.withLock {
            if (!files.verify(spec)) throw Failure(R.string.local_error_integrity)
            val installed = withContext(Dispatchers.IO) { LocalModelCatalog.models.filter { files.present(it) }.map { it.listed() } }
            coroutineContext.ensureActive()
            // 最后一次可取消检查之后，仅将小型配置作为一个提交单元落盘并同步成功状态。
            // 仍在主线程的同一个事件内；取消/返回不能插进三个角色更新之间。
            withContext(NonCancellable + Dispatchers.Main.immediate) {
                LocalModelActivation.select(store, spec, installed)
                verified.add(id)
                mutable.update { it.copy(enabledId = id) }
                onSuccess()
            }
        }
    }

    fun cancel() { refreshQueue.cancel(); job?.cancel() }

    private fun selected(id: String) = LocalModelCatalog.find(id) ?: throw Failure(R.string.local_error_model_missing)
    private fun memory() = ActivityManager.MemoryInfo().also { app.getSystemService(ActivityManager::class.java).getMemoryInfo(it) }
    private suspend fun checkResources(spec: LocalModelSpec, download: Boolean) {
        if (!supported) throw Failure(R.string.local_error_unsupported)
        val memory = memory()
        if (memory.lowMemory || memory.availMem < spec.minAvailableBytes) throw Failure(R.string.local_error_memory)
        if (download && withContext(Dispatchers.IO) { app.filesDir.usableSpace <
                (spec.artifact.bytes - files.downloadedBytes(spec)).coerceAtLeast(0) + LocalModelCatalog.STORAGE_RESERVE })
            throw Failure(R.string.local_error_storage)
    }

    private suspend fun publishRows() {
        val memory = memory()
        val supported = supported
        val rows = withContext(Dispatchers.IO) {
            val free = app.filesDir.usableSpace
            val recommended = if (supported && !memory.lowMemory) LocalModelCatalog.recommended(memory.availMem, free) {
                it.artifact.bytes - files.downloadedBytes(it)
            } else null
            LocalModelCatalog.models.map { spec ->
                val done = files.downloadedBytes(spec)
                val ready = spec.id in verified && files.present(spec)
                val reason = when {
                    !supported -> R.string.local_error_unsupported
                    memory.lowMemory || memory.availMem < spec.minAvailableBytes -> R.string.local_error_memory
                    !ready && free < (spec.artifact.bytes - done).coerceAtLeast(0) + LocalModelCatalog.STORAGE_RESERVE -> R.string.local_error_storage
                    else -> null
                }
                LocalModelItem(spec.id, spec.name, spec.artifact.bytes, ready, done, reason == null,
                    reason?.let(app::getString), spec.id == recommended)
            }
        }
        val enabled = store?.configFor(ModelRole.Main)?.takeIf { it.baseUrl == LocalModelCatalog.BASE_URL }?.model
        mutable.update { it.copy(rows = rows, supported = supported, enabledId = enabled) }
    }

    private fun run(phase: LocalModelPhase, id: String? = null, block: suspend () -> Unit) {
        if (job?.isCompleted == false) return
        mutable.update { it.copy(phase = phase, activeId = id, done = 0, total = 0, error = null) }
        job = scope.launch {
            try { publishRows(); block() }
            catch (_: TimeoutCancellationException) { fail(R.string.local_error_timeout) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Failure) { fail(failure.messageId) }
            catch (failure: JevDownloadException) { fail(when (failure.reason) {
                JevDownloadException.Reason.NoSpace -> R.string.local_error_storage
                JevDownloadException.Reason.Integrity -> R.string.local_error_integrity
                else -> R.string.local_error_download
            }) }
            catch (_: OutOfMemoryError) { fail(R.string.local_error_memory) }
            catch (failure: ModelError.LocalUnavailable) { mutable.update { it.copy(error = failure.detail) } }
            catch (_: Exception) { fail(if (phase == LocalModelPhase.Downloading) R.string.local_error_download else R.string.local_error_runtime) }
            finally {
                try { withContext(NonCancellable) { publishRows() } }
                finally { mutable.update { it.copy(phase = LocalModelPhase.Idle, activeId = null) } }
            }
        }
    }
    private fun fail(message: Int) { mutable.update { it.copy(error = app.getString(message)) } }
    private class Failure(val messageId: Int) : Exception()
    companion object {
        @Volatile private var instance: LocalModelController? = null
        fun get(context: Context): LocalModelController = instance ?: synchronized(this) {
            instance ?: LocalModelController(context).also { instance = it }
        }
    }
}

internal object LocalModelActivation {
    /** 已通过文件和推理校验后一起切换用途，避免本地模式继续使用以前的在线辅助模型。 */
    fun select(store: ProviderStore, spec: LocalModelSpec, installed: List<ListedModel>) {
        store.activateLocalModel(spec, installed)
    }

    /** 必须完整返回唯一预期工具和空参数；普通文字或半截/错误工具不能通过启用测试。 */
    fun validProbe(parts: List<Part>): Boolean {
        val calls = parts.filterIsInstance<Part.ToolCall>()
        return calls.size == 1 && calls.single().name == "get_time" &&
            runCatching { (Json.parseToJsonElement(calls.single().argsJson) as? JsonObject)?.isEmpty() == true }.getOrDefault(false)
    }
}
