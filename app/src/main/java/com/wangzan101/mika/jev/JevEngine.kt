package com.wangzan101.mika.jev

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.net.Proxy
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** 每次校验独立启动/关闭一个本机 CPU 引擎，不常驻。未使用外部服务，API key 每次随机生成。 */
internal class JevEngine(
    private val executable: File,
    private val files: JevModelFiles,
    private val nanoTime: () -> Long = System::nanoTime,
    private val timeouts: Timeouts = Timeouts(),
    private val startProcess: (List<String>) -> Process = { ProcessBuilder(it).redirectErrorStream(true).start() },
) {
    internal data class Timeouts(val loadingMs: Long = 180_000, val controlRequestMs: Long = 5_000, val scoringMs: Long = 180_000) {
        init { require(loadingMs > 0 && controlRequestMs > 0 && scoringMs > 0) }
    }

    private val http = OkHttpClient.Builder().proxy(Proxy.NO_PROXY)
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .connectTimeout(1, TimeUnit.SECONDS).readTimeout(180, TimeUnit.SECONDS).build()

    suspend fun score(images: JevImages.Pair, text: String, onLoaded: (Long) -> Unit): JevScoreResult {
        // Linux的parent-death signal还关联创建子进程的线程；专用线程保持到子进程退出，
        // 避免Dispatchers.IO空闲线程回收时提前杀死正在推理的原生程序。
        val owner = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "Jev-owner").apply { isDaemon = true } }.asCoroutineDispatcher()
        try { return withContext(owner) { evaluate(images, text, onLoaded) } }
        finally {
            owner.close()
            // 每次评分独立创建客户端；包括原生启动失败的路径也立即释放闲置 HTTP 线程和连接。
            http.dispatcher.cancelAll()
            http.connectionPool.evictAll()
            http.dispatcher.executorService.shutdown()
        }
    }

    private suspend fun evaluate(images: JevImages.Pair, text: String, onLoaded: (Long) -> Unit): JevScoreResult {
        coroutineContext.ensureActive()
        require(executable.isFile && executable.canExecute())
        // 原生进程直接绑定系统分配的空闲端口，拿到自身输出才连接，不存在“预留后释放”的抢占窗口。
        val listening = CompletableDeferred<Int>()
        val key = UUID.randomUUID().toString() + UUID.randomUUID().toString()
        val command = listOf(
            executable.absolutePath, "-m", files.model.absolutePath, "--mmproj", files.projector.absolutePath,
            "--host", "127.0.0.1", "--port", "0", "--api-key", key,
            "-c", "4096", "-np", "1", "-b", "512", "-ub", "128", "-ngl", "0",
            "--threads", Runtime.getRuntime().availableProcessors().coerceIn(1, 4).toString(),
            "--image-max-tokens", "512", "--no-webui", "--no-warmup",
        )
        val loadingStarted = nanoTime()
        var process: Process? = null
        var drain: Thread? = null
        try {
            // 启动、监听、健康检查和模型身份检查共享一个加载预算，不能逐阶段重新计时。
            val base = withTimeout(timeouts.loadingMs) {
                val running = startProcess(command).also { process = it }
                coroutineContext.ensureActive()
                // 原生stdout可能包含请求细节，不传给Logcat；持续排空避免管道满后死锁。
                drain = Thread({
                    try {
                        running.inputStream.bufferedReader().useLines { lines -> lines.forEach { line ->
                            if (!listening.isCompleted) JevProtocol.listeningPort(line)?.let { listening.complete(it) }
                        } }
                    } catch (_: IOException) {
                        // 取消/进程退出时管道关闭。
                    } finally { if (!listening.isCompleted) listening.completeExceptionally(IOException("Jev did not start")) }
                }, "Jev-output").apply { isDaemon = true; start() }
                val address = "http://127.0.0.1:${listening.await()}"
                while (true) {
                    coroutineContext.ensureActive()
                    check(running.isAlive) { "Jev engine exited" }
                    val request = Request.Builder().url("$address/health").header("Authorization", "Bearer $key").build()
                    val ready = try { controlCall(request).text(successOnly = false).first == 200 }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: IOException) { false }
                    if (ready) break
                    delay(500)
                }
                // /health 可能不鉴权；/props 需密钥，校验当前服务确实加载了指定模型。
                val props = controlCall(Request.Builder().url("$address/props").header("Authorization", "Bearer $key").build()).text().second
                check(props.contains(files.model.name)) { "unexpected Jev engine" }
                address
            }
            val loadMs = elapsedMs(loadingStarted)
            coroutineContext.ensureActive()
            onLoaded(loadMs)
            val request = Request.Builder().url("$base/v1/systemone").header("Authorization", "Bearer $key")
                .post(JevProtocol.request(text, images.original, images.rendered).toRequestBody("application/json".toMediaType())).build()
            val scoringStarted = nanoTime()
            val score = withTimeout(timeouts.scoringMs) { JevProtocol.score(http.newCall(request).text().second) }
            return JevScoreResult(score, loadMs, elapsedMs(scoringStarted))
        } finally {
            process?.let { running ->
                runCatching { running.destroy() }
                runCatching { if (running.isAlive) running.destroyForcibly() }
                // 单个清理步骤失败不应跳过其余管道和线程的释放。
                runCatching { running.waitFor(3, TimeUnit.SECONDS) }
                runCatching { running.inputStream.close() }
                runCatching { running.outputStream.close() }
                runCatching { running.errorStream.close() }
            }
            runCatching { drain?.join(1_000) }
        }
    }

    private fun controlCall(request: Request): Call = http.newCall(request).apply {
        // 整次请求限制同时覆盖响应头和响应体，不能靠持续滴流延长 readTimeout。
        timeout().timeout(timeouts.controlRequestMs, TimeUnit.MILLISECONDS)
    }

    private fun elapsedMs(started: Long): Long = ((nanoTime() - started) / 1_000_000L).coerceAtLeast(0L)

    private suspend fun Call.text(successOnly: Boolean = true): kotlin.Pair<Int, String> = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }
            override fun onResponse(call: Call, response: Response) {
                val result = runCatching {
                    response.use {
                        if (successOnly && !it.isSuccessful) throw IOException("Jev HTTP ${it.code}")
                        val body = it.peekBody(1024 * 1024 + 1).string()
                        require(body.length <= 1024 * 1024)
                        it.code to body
                    }
                }
                if (continuation.isActive) result.fold(continuation::resume, continuation::resumeWithException)
            }
        })
    }
}
