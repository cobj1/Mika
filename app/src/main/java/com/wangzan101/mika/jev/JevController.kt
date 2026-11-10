package com.wangzan101.mika.jev

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import com.wangzan101.mika.R
import com.wangzan101.mika.core.net.NetProxy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.wangzan101.mika.localmodel.LocalModelAccess
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

enum class JevPhase { Idle, Downloading, Verifying, Loading, Scoring }
data class JevState(
    val phase: JevPhase = JevPhase.Idle,
    val done: Long = 0,
    val total: Long = JevModelCatalog.totalBytes,
    val ready: Boolean = false,
    val score: Double? = null,
    val error: String? = null,
    val supported: Boolean = true,
    val cacheHit: Boolean = false,
    val loadMs: Long? = null,
    val scoreMs: Long? = null,
    val deviceNote: String? = null,
    val downloadAllowed: Boolean = true,
)

internal class JevAgentException(val code: String) : Exception(code)

/** 页面主动发起，离开就取消；绝不自动上传图片，也不拿校验分数授权后续动作。 */
class JevController private constructor(context: Context) {
    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val executable = File(app.applicationInfo.nativeLibraryDir, "libjevserver.so")
    private val files = JevModelFiles(File(app.filesDir, "models/jev/${JevModelCatalog.revision}"),
        NetProxy.shared.client(okhttp3.OkHttpClient.Builder().readTimeout(60, TimeUnit.SECONDS)))
    private val supported get() = "arm64-v8a" in Build.SUPPORTED_ABIS && executable.isFile && executable.canExecute()
    private val mutableState = MutableStateFlow(JevState(supported = supported))
    val state = mutableState.asStateFlow()
    private var job: Job? = null
    private val refreshQueue = JevRefreshQueue(scope)
    private val scoreCache = JevScoreCache()
    private val operation = Mutex()

    fun refresh() = refreshQueue.request(job) { refreshNow() }

    private fun refreshNow() = run(JevPhase.Verifying) {
        verifyModels()
        updateDeviceCheck()
    }

    fun download() = run(JevPhase.Downloading) {
        scoreCache.clear()
        mutableState.update { it.copy(ready = false) }
        requireSupported()
        val deviceStatus = updateDeviceCheck()
        if (deviceStatus != JevDeviceCheck.Status.Ready) throw Failure(deviceMessage(deviceStatus))
        files.download { done, total -> mutableState.update {
            it.copy(done = done, total = total, phase = if (done == total) JevPhase.Verifying else JevPhase.Downloading)
        } }
        check(verifyModels())
        updateDeviceCheck()
    }

    fun remove() = run(JevPhase.Verifying) {
        scoreCache.clear()
        mutableState.update { it.copy(ready = false) }
        withContext(Dispatchers.IO) { files.delete() }
        mutableState.update { it.copy(ready = false, score = null, done = 0) }
        updateDeviceCheck()
    }

    fun cancel() {
        refreshQueue.cancel()
        job?.cancel()
        // 离页与显式停止都结束这次隐私边界，不保留上次分数或请求摘要。
        scoreCache.clear()
        mutableState.update { it.copy(score = null, cacheHit = false, loadMs = null, scoreMs = null) }
    }

    internal fun score(source: JevImages.Source, text: String, crop: JevCrop = JevCrop.Full) = run(JevPhase.Verifying) {
        val result = evaluate(source, text, crop, scoreCache) { phase, loadMs ->
            mutableState.update { it.copy(phase = phase, loadMs = loadMs) }
        }
        coroutineContext.ensureActive()
        mutableState.update { it.copy(score = result.score, cacheHit = result.cacheHit, loadMs = result.loadMs, scoreMs = result.scoreMs) }
    }

    /** 与页面共用互斥入口；任务取消只终止其自己的推理，页面离开不取消任务。 */
    internal suspend fun agentAvailabilityError(): String? = when {
        !supported -> "unavailable"
        operation.isLocked -> "jev_busy"
        !withContext(Dispatchers.IO) { files.present() } -> "model_missing"
        // 任务评分不复用缓存，已知内存不足时无需先截图、OCR、编码或扫描大权重。
        !memoryReady() -> "memory_low"
        else -> null
    }

    internal suspend fun evaluateAgent(source: JevImages.Source, text: String, crop: JevCrop = JevCrop.Full): JevCachedScore {
        if (!operation.tryLock()) throw JevAgentException("jev_busy")
        try {
            if (!withContext(Dispatchers.IO) { files.present() }) {
                mutableState.update { it.copy(ready = false) }
                throw JevAgentException("model_missing")
            }
            // OCR 期间其他应用可能消耗内存；此处再检查一次，页面缓存命中不经过此分支。
            if (!memoryReady()) throw JevAgentException("memory_low")
            return evaluate(source, text, crop, JevScoreCache()) { _, _ -> }
        } catch (cancelled: TimeoutCancellationException) { coroutineContext.ensureActive(); throw JevAgentException("timeout") }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: JevAgentException) { throw failure }
        catch (failure: Failure) { throw JevAgentException(when (failure.messageId) {
            R.string.jev_unavailable -> "unavailable"
            R.string.jev_integrity_error -> "integrity_error"
            R.string.jev_memory_low -> "memory_low"
            R.string.jev_text_layout_error, R.string.jev_input_error -> "text_layout"
            R.string.jev_image_error -> "image_error"
            else -> "runtime_error"
        }) }
        catch (_: OutOfMemoryError) { throw JevAgentException("memory_low") }
        catch (_: Exception) { throw JevAgentException("runtime_error") }
        finally { operation.unlock() }
    }

    private suspend fun evaluate(source: JevImages.Source, text: String, crop: JevCrop, cache: JevScoreCache,
        progress: (JevPhase, Long?) -> Unit): JevCachedScore {
        requireSupported()
        if (text.isBlank() || text.length > JevProtocol.MAX_TEXT) throw Failure(R.string.jev_input_error)
        if (!verifyModels()) { cache.clear(); throw Failure(R.string.jev_integrity_error) }
        // Source 是不可变快照；内容摘要 + 框 + 文字 + 渲染配置唯一确定预处理输入，命中前无需解码。
        val key = withContext(Dispatchers.IO) {
            JevScoreCache.key(JevModelCatalog.revision, JevImages.PREPROCESSING_VERSION, text,
                source.contentDigest, listOf(crop.left.toBits(), crop.top.toBits(), crop.right.toBits(), crop.bottom.toBits()).joinToString(",") + app.resources.configuration.toString())
        }
        return cache.getOrCompute(key) { LocalModelAccess.mutex.withLock {
            if (!memoryReady()) throw Failure(R.string.jev_memory_low)
            val images = try { withContext(Dispatchers.IO) { JevImages.prepare(source, text, crop) } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: JevTextLayoutException) { throw Failure(R.string.jev_text_layout_error) }
            catch (_: Exception) { throw Failure(R.string.jev_image_error) }
            progress(JevPhase.Loading, null)
            JevEngine(executable, files).score(images, text) { progress(JevPhase.Scoring, it) }
        } }
    }

    /** 模型可用性是页面和任务共用的状态，任务校验成功也应让已打开的设置页看到模型。 */
    private suspend fun verifyModels(): Boolean {
        val ready = try { files.verify() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Throwable) {
            scoreCache.clear()
            mutableState.update { it.copy(ready = false) }
            throw failure
        }
        if (!ready) scoreCache.clear()
        // 完整校验已经确认两份长度，无需再 stat 一遍；失败先撤销就绪再统计断点。
        mutableState.update { it.copy(ready = ready, supported = supported, done = if (ready) JevModelCatalog.totalBytes else it.done) }
        if (!ready) {
            val done = withContext(Dispatchers.IO) { files.downloadedBytes() }
            mutableState.update { it.copy(done = done) }
        }
        return ready
    }

    private fun memoryInfo() = ActivityManager.MemoryInfo().also { app.getSystemService(ActivityManager::class.java).getMemoryInfo(it) }
    private fun memoryReady() = memoryInfo().let { JevDeviceCheck.memoryReady(it.lowMemory, it.availMem) }
    private fun deviceMessage(status: JevDeviceCheck.Status): Int = when (status) {
        JevDeviceCheck.Status.Ready -> R.string.jev_device_ready
        JevDeviceCheck.Status.LowMemory -> R.string.jev_memory_low
        JevDeviceCheck.Status.LowStorage -> R.string.jev_no_space
        JevDeviceCheck.Status.Unsupported -> R.string.jev_unavailable
    }

    private suspend fun updateDeviceCheck(): JevDeviceCheck.Status {
        val memory = memoryInfo()
        val status = withContext(Dispatchers.IO) { JevDeviceCheck.assess(supported, memory.lowMemory, memory.availMem,
            app.filesDir.usableSpace, JevModelCatalog.totalBytes - files.downloadedBytes()) }
        val allowed = status == JevDeviceCheck.Status.Ready
        val message = deviceMessage(status)
        mutableState.update { it.copy(deviceNote = app.getString(message), downloadAllowed = allowed) }
        return status
    }

    private fun requireSupported() { if (!supported) throw Failure(R.string.jev_unavailable) }

    private fun run(phase: JevPhase, block: suspend () -> Unit) {
        // 取消时旧引擎仍在finally里退出；等清理完成才允许开始下一次，避免双模型同时驻留。
        if (job?.isCompleted == false) return
        mutableState.update { it.copy(phase = phase, score = null, error = null, cacheHit = false, loadMs = null, scoreMs = null) }
        job = scope.launch {
            if (!operation.tryLock()) { fail(R.string.jev_busy); mutableState.update { it.copy(phase = JevPhase.Idle) }; return@launch }
            try { block() }
            catch (_: TimeoutCancellationException) { fail(R.string.jev_timeout) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Failure) { fail(failure.messageId) }
            catch (failure: JevDownloadException) {
                fail(when (failure.reason) {
                    JevDownloadException.Reason.NoSpace -> R.string.jev_no_space
                    JevDownloadException.Reason.Integrity -> R.string.jev_integrity_error
                    else -> R.string.jev_download_error
                })
            }
            catch (_: OutOfMemoryError) { fail(R.string.jev_memory_low) }
            catch (_: Exception) { fail(if (phase == JevPhase.Downloading) R.string.jev_download_error else R.string.jev_runtime_error) }
            finally {
                try {
                    withContext(NonCancellable + Dispatchers.IO) {
                        val present = files.present()
                        val done = files.downloadedBytes()
                        mutableState.update { it.copy(ready = it.ready && present, done = done) }
                    }
                } finally { operation.unlock(); mutableState.update { it.copy(phase = JevPhase.Idle) } }
            }
        }
    }

    private fun fail(id: Int) { mutableState.update { it.copy(error = app.getString(id)) } }
    private class Failure(val messageId: Int) : Exception()

    companion object {
        @Volatile private var instance: JevController? = null
        fun get(context: Context): JevController = instance ?: synchronized(this) {
            instance ?: JevController(context).also { instance = it }
        }
    }
}
