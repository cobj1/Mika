package com.wangzan101.mika.localmodel

import com.wangzan101.mika.core.model.Capabilities
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.ModelReply
import com.wangzan101.mika.core.model.OpenAiCompatModel
import com.wangzan101.mika.core.model.ToolSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.net.Proxy
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** APK 中的 llama-server 同时支持文字生成；每次请求独立加载、完成或取消后卸载，不后台常驻。 */
internal class LocalModelEngine(
    private val executable: File,
    private val modelFile: File,
    private val modelId: String,
    private val capabilities: Capabilities,
    private val timeouts: Timeouts = Timeouts(),
    private val startProcess: (List<String>) -> Process = { ProcessBuilder(it).redirectErrorStream(true).start() },
) {
    data class Timeouts(val loadingMs: Long = 180_000, val controlMs: Long = 5_000, val generationMs: Long = 600_000) {
        init { require(loadingMs > 0 && controlMs > 0 && generationMs > 0) }
    }

    private val http = OkHttpClient.Builder().proxy(Proxy.NO_PROXY)
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        // 协程总预算负责超时归因，socket 读超时稍晚，避免同一时刻竞争而误报成网络故障。
        .connectTimeout(1, TimeUnit.SECONDS).readTimeout(timeouts.generationMs + 5_000, TimeUnit.MILLISECONDS).build()

    suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>, onText: ((String) -> Unit)?): ModelReply {
        // Android parent-death signal 绑定创建子进程的线程，整个子进程生命周期使用同一专用线程。
        val owner = Executors.newSingleThreadExecutor { r -> Thread(r, "Mika-local-model").apply { isDaemon = true } }.asCoroutineDispatcher()
        try { return withContext(owner) { generate(system, messages, tools, onText) } }
        finally {
            owner.close()
            http.dispatcher.cancelAll()
            http.connectionPool.evictAll()
            http.dispatcher.executorService.shutdown()
        }
    }

    private suspend fun generate(system: String, messages: List<Message>, tools: List<ToolSpec>, onText: ((String) -> Unit)?): ModelReply {
        coroutineContext.ensureActive()
        require(executable.isFile && executable.canExecute() && modelFile.isFile)
        val key = UUID.randomUUID().toString() + UUID.randomUUID().toString()
        val listening = CompletableDeferred<Int>()
        var process: Process? = null
        var drain: Thread? = null
        try {
            val base = withTimeout(timeouts.loadingMs) {
                val running = startProcess(command(key)).also { process = it }
                coroutineContext.ensureActive()
                drain = Thread({
                    try {
                        // 原生输出可能包含模板/请求内容；仅识别端口，其他内容全部丢弃，不进 Logcat。
                        running.inputStream.bufferedReader().useLines { lines -> lines.forEach { line ->
                            if (!listening.isCompleted) listeningPort(line)?.let { listening.complete(it) }
                        } }
                    } catch (_: IOException) {
                        // 取消/进程退出会关闭管道。
                    } finally { if (!listening.isCompleted) listening.completeExceptionally(IOException("Local engine did not start")) }
                }, "Mika-local-output").apply { isDaemon = true; start() }
                val address = "http://127.0.0.1:${listening.await()}"
                while (true) {
                    coroutineContext.ensureActive()
                    if (!running.isAlive) throw IOException("Local engine exited")
                    val ready = try { controlCall("$address/health", key).body(false).first == 200 }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: IOException) { false }
                    if (ready) break
                    delay(500)
                }
                // /props 受密钥保护，精确比对模型路径，禁止只凭健康状态向其他本机进程发送对话。
                val props = controlCall("$address/props", key).body().second
                if (!matchesModel(props, modelFile)) throw IOException("Unexpected local model identity")
                address
            }
            coroutineContext.ensureActive()
            val adapter = OpenAiCompatModel("$base/v1", key, modelId, capabilities, http)
            return withTimeout(timeouts.generationMs) {
                if (onText == null) adapter.complete(system, messages, tools)
                else adapter.completeStreaming(system, messages, tools, onText)
            }
        } finally {
            process?.let { running ->
                runCatching { running.destroy() }
                runCatching { if (running.isAlive) running.destroyForcibly() }
                runCatching { running.waitFor(3, TimeUnit.SECONDS) }
                runCatching { running.inputStream.close() }
                runCatching { running.outputStream.close() }
                runCatching { running.errorStream.close() }
            }
            runCatching { drain?.join(1_000) }
        }
    }

    internal fun command(key: String): List<String> = listOf(
        executable.absolutePath, "-m", modelFile.absolutePath, "--alias", modelId,
        "--host", "127.0.0.1", "--port", "0", "--api-key", key,
        "-c", capabilities.contextTokens.toString(), "-np", "1", "-n", "2048", "-b", "512", "-ub", "128", "-ngl", "0",
        "--threads", Runtime.getRuntime().availableProcessors().coerceIn(1, 4).toString(),
        "--jinja", "--reasoning", "off", "--reasoning-format", "deepseek", "--no-context-shift", "--no-webui", "--no-warmup",
    )

    private fun controlCall(url: String, key: String): Call = http.newCall(
        Request.Builder().url(url).header("Authorization", "Bearer $key").build(),
    ).apply { timeout().timeout(timeouts.controlMs, TimeUnit.MILLISECONDS) }

    private suspend fun Call.body(successOnly: Boolean = true): Pair<Int, String> = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(e) }
            override fun onResponse(call: Call, response: Response) {
                val result = runCatching {
                    response.use {
                        if (successOnly && !it.isSuccessful) throw IOException("Local engine HTTP ${it.code}")
                        val text = it.peekBody(1024 * 1024 + 1).string()
                        require(text.length <= 1024 * 1024)
                        it.code to text
                    }
                }
                if (continuation.isActive) result.fold(continuation::resume, continuation::resumeWithException)
            }
        })
    }

    companion object {
        private val portPattern = Regex("listening on http://127\\.0\\.0\\.1:(\\d+)(?:\\s|$)")
        internal fun listeningPort(line: String): Int? = portPattern.find(line)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 1..65535 }
        internal fun matchesModel(props: String, expected: File): Boolean {
            val root = runCatching { Json.parseToJsonElement(props) as? JsonObject }.getOrNull() ?: return false
            val actual = (root["model_path"] as? JsonPrimitive)?.contentOrNull ?: return false
            return actual == expected.absolutePath
        }
    }
}
