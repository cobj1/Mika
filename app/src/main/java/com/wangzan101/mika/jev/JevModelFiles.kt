package com.wangzan101.mika.jev

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.FileTime
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

class JevDownloadException(val reason: Reason) : IOException(reason.name) {
    enum class Reason { NoSpace, Integrity, InvalidResponse, UnsafeRedirect, Busy, FileOperation }
}

/** ctime 记录内容/元数据改写，inode 记录替换；不能只比较长度或可还原的 mtime。 */
internal data class JevFileSnapshot(
    val device: Long,
    val inode: Long,
    val size: Long,
    val modified: FileTime,
    val changed: FileTime,
)

/** Android/Linux 私有文件使用完整 stat 身份；不支持 ctime 的文件系统不启用校验缓存。 */
internal fun jevFileSnapshot(file: File): JevFileSnapshot? = try {
    val attributes = Files.readAttributes(file.toPath(), "unix:dev,ino,size,lastModifiedTime,ctime,isRegularFile", LinkOption.NOFOLLOW_LINKS)
    if (attributes["isRegularFile"] != true) null
    else JevFileSnapshot(
        (attributes["dev"] as Number).toLong(),
        (attributes["ino"] as Number).toLong(),
        (attributes["size"] as Number).toLong(),
        attributes["lastModifiedTime"] as FileTime,
        attributes["ctime"] as FileTime,
    )
} catch (_: UnsupportedOperationException) { null }
catch (_: IllegalArgumentException) { null }
catch (_: IOException) { null }
catch (_: SecurityException) { null }

/**
 * 应用私有目录中的两份 Jev 权重。只接受固定目录清单；不会下载脚本或可执行代码。
 * 断点数据保存在 .part；完整下载先原子改名，再在最终路径做一次 SHA-256。
 * .unverified 只表示尚未校验、阻止就绪，绝不是可信标记；取消保留断点或待校验文件。
 * [present] 只用于快速显示；推理前必须调用 [verify]，不能把“文件大小相同”当成校验成功。
 */
class JevModelFiles internal constructor(
    private val directory: File,
    http: OkHttpClient,
    private val artifacts: List<JevModelArtifact>,
    private val allowedUrl: (HttpUrl) -> Boolean,
    private val freeSpace: () -> Long,
    private val snapshot: (File) -> JevFileSnapshot? = ::jevFileSnapshot,
    private val openInput: (File) -> InputStream = { it.inputStream() },
) {
    constructor(directory: File, http: OkHttpClient) : this(
        directory, http, JevModelCatalog.files, JevModelCatalog::allows, { directory.usableSpace },
    )

    private val client = http.newBuilder().followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).build()
    private val mutex = Mutex()
    // 只存在于当前实例/进程，且所有访问受 mutex 保护；永不持久化“已校验”标记。
    private val verifiedFiles = mutableMapOf<String, JevFileSnapshot>()
    val model: File = File(directory, artifacts[0].name)
    val projector: File get() = File(directory, artifacts[1].name)
    private val total = artifacts.sumOf { it.bytes }

    init {
        require(artifacts.isNotEmpty() && artifacts.map { it.name }.distinct().size == artifacts.size)
        require(artifacts.all { allowedUrl(it.url) })
    }

    fun present(): Boolean = artifacts.all { !pending(it).exists() && target(it).let { f -> f.isFile && f.length() == it.bytes } }

    /** 仅用于进度和清理入口的快速统计；字节数不代表已通过完整性校验。 */
    fun downloadedBytes(): Long = artifacts.sumOf { artifact ->
        val file = target(artifact).takeIf { it.isFile } ?: part(artifact)
        file.length().coerceIn(0L, artifact.bytes)
    }

    suspend fun verify(): Boolean = mutex.withLock {
        withContext(Dispatchers.IO) { artifacts.all { verifiedTarget(it) } }
    }

    /** 有操作运行时拒绝删除；调用方应先取消下载/校验并等待它结束。 */
    fun delete() {
        if (!mutex.tryLock()) throw JevDownloadException(JevDownloadException.Reason.Busy)
        try {
            verifiedFiles.clear()
            artifacts.forEach { artifact -> listOf(target(artifact), part(artifact), pending(artifact)).forEach(::remove) }
        } finally { mutex.unlock() }
    }

    suspend fun download(onProgress: (done: Long, total: Long) -> Unit) = mutex.withLock {
        withContext(Dispatchers.IO) {
            verifiedFiles.clear()
            if (!directory.mkdirs() && !directory.isDirectory) fail(JevDownloadException.Reason.FileOperation)
            // 两份模型是一套；开始前先为本次仍需下载的总量留足空间。
            val missingBytes = artifacts.sumOf { artifact ->
                val finalBytes = target(artifact).takeIf { it.isFile }?.length() ?: 0L
                if (finalBytes == artifact.bytes) 0L
                else artifact.bytes - part(artifact).length().coerceIn(0, artifact.bytes)
            }
            if (missingBytes > 0) requireSpace(missingBytes)
            val progress = JevDownloadProgress(total, onProgress)
            var completed = 0L
            for (artifact in artifacts) {
                coroutineContext.ensureActive()
                val file = target(artifact)
                if (!verifiedTarget(artifact)) {
                    remove(file)
                    remove(pending(artifact))
                    fetch(artifact) { n -> progress.update(completed + n) }
                }
                completed += artifact.bytes
                progress.update(completed, force = true)
            }
        }
    }

    private suspend fun fetch(artifact: JevModelArtifact, progress: (Long) -> Unit) {
        val partial = part(artifact)
        if (partial.length() > artifact.bytes) remove(partial)
        if (partial.length() == artifact.bytes) {
            if (commitVerified(partial, artifact)) return
        }
        val offset = partial.length()
        requireSpace(artifact.bytes - offset)
        progress(offset)
        withResponse(artifact.url, offset) { response ->
            val body = response.body ?: fail(JevDownloadException.Reason.InvalidResponse)
            val append = when (response.code) {
                200 -> {
                    if (response.header("Content-Range") != null) fail(JevDownloadException.Reason.InvalidResponse)
                    // 服务端忽略 Range 时重新写；旧断点释放出的空间可供本次完整写入。
                    requireSpace(artifact.bytes, reclaimed = offset)
                    false
                }
                206 -> {
                    if (!validRange(response.header("Content-Range"), offset, artifact.bytes)) fail(JevDownloadException.Reason.InvalidResponse)
                    true
                }
                else -> throw IOException("HTTP ${response.code}")
            }
            val start = if (append) offset else 0L
            val remaining = artifact.bytes - start
            if (body.contentLength() >= 0 && body.contentLength() != remaining) fail(JevDownloadException.Reason.InvalidResponse)
            if (response.header("Content-Encoding")?.let { !it.equals("identity", ignoreCase = true) } == true) {
                fail(JevDownloadException.Reason.InvalidResponse)
            }
            var done = start
            progress(done)
            body.byteStream().use { input ->
                FileOutputStream(partial, append).use { out ->
                    val buffer = ByteArray(128 * 1024)
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (done + read > artifact.bytes) {
                            // 多出的字节不落盘；即便长度恰好够了，下次仍须完整 SHA-256 校验。
                            fail(JevDownloadException.Reason.InvalidResponse)
                        }
                        out.write(buffer, 0, read)
                        done += read
                        progress(done)
                    }
                    out.fd.sync()
                }
            }
            if (done != artifact.bytes) fail(JevDownloadException.Reason.InvalidResponse)
        }
        coroutineContext.ensureActive()
        if (!commitVerified(partial, artifact)) fail(JevDownloadException.Reason.Integrity)
    }

    /** 手动处理重定向：每一跳都校验主机，不把凭据或 Range 条件交给任意 HTTP 地址。 */
    private suspend fun <T> withResponse(initial: HttpUrl, offset: Long, consume: suspend (Response) -> T): T = coroutineScope {
        var url = initial
        repeat(MAX_REDIRECTS + 1) { hop ->
            if (!allowedUrl(url)) fail(JevDownloadException.Reason.UnsafeRedirect)
            val request = Request.Builder().url(url).header("Accept-Encoding", "identity")
                .apply { if (offset > 0) header("Range", "bytes=$offset-") }.build()
            val call = client.newCall(request)
            // 监听覆盖响应头和完整响应体；取消时立即中断 socket，不等待 readTimeout。
            val cancellation = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
                try { awaitCancellation() } finally { call.cancel() }
            }
            try {
                call.execute().use { response ->
                    coroutineContext.ensureActive()
                    if (response.code !in REDIRECTS) return@coroutineScope consume(response)
                    if (hop == MAX_REDIRECTS) fail(JevDownloadException.Reason.InvalidResponse)
                    url = response.header("Location")?.let { url.resolve(it) }
                        ?: fail(JevDownloadException.Reason.InvalidResponse)
                }
            } catch (e: IOException) {
                coroutineContext.ensureActive()
                throw e
            } finally {
                cancellation.cancelAndJoin()
            }
        }
        fail(JevDownloadException.Reason.InvalidResponse)
    }

    private fun requireSpace(needed: Long, reclaimed: Long = 0) {
        if (freeSpace() + reclaimed < needed + RESERVE_BYTES) fail(JevDownloadException.Reason.NoSpace)
    }

    private suspend fun verified(file: File, artifact: JevModelArtifact): Boolean {
        coroutineContext.ensureActive()
        val key = file.absolutePath
        val before = snapshot(file)
        if (!file.isFile || file.length() != artifact.bytes || (before != null && before.size != artifact.bytes)) {
            verifiedFiles.remove(key)
            return false
        }
        if (before != null && verifiedFiles[key] == before) return true
        verifiedFiles.remove(key)
        val digest = MessageDigest.getInstance("SHA-256")
        openInput(file).buffered().use { input ->
            val buffer = ByteArray(128 * 1024)
            while (true) {
                coroutineContext.ensureActive()
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        coroutineContext.ensureActive()
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        val after = snapshot(file)
        // 校验途中被写入、替换或删除，不能发布本次结果。缺少可信 stat 时只做全量校验，不缓存。
        if (actual != artifact.sha256 || !file.isFile || file.length() != artifact.bytes || (before != null && before != after)) return false
        if (before != null) verifiedFiles[key] = before
        return true
    }

    private suspend fun verifiedTarget(artifact: JevModelArtifact): Boolean {
        if (!verified(target(artifact), artifact)) return false
        coroutineContext.ensureActive()
        remove(pending(artifact))
        return true
    }

    private suspend fun commitVerified(partial: File, artifact: JevModelArtifact): Boolean {
        coroutineContext.ensureActive()
        val file = target(artifact)
        verifiedFiles.remove(partial.absolutePath)
        verifiedFiles.remove(file.absolutePath)
        // 标记先于 rename 建立；即使取消/退出发生在移动后，也不会由 present 发布就绪。
        // marker 缺失永远不代表已校验，跨实例和 verify 仍需 SHA/可信进程内 stat 缓存。
        FileOutputStream(pending(artifact)).use { it.fd.sync() }
        try {
            Files.move(partial.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            // 同目录的 rename 不会暴露半个文件；不回退为 copy。
            if (!partial.renameTo(file)) fail(JevDownloadException.Reason.FileOperation)
        }
        // 在 rename 之后做唯一一次 SHA，缓存完整的新 ctime；绝不忽略 rename 前后间的改写。
        if (verifiedTarget(artifact)) return true
        remove(file)
        remove(pending(artifact))
        return false
    }

    private fun target(artifact: JevModelArtifact) = File(directory, artifact.name)
    private fun part(artifact: JevModelArtifact) = File(directory, artifact.name + ".part")
    private fun pending(artifact: JevModelArtifact) = File(directory, artifact.name + ".unverified")
    private fun remove(file: File) {
        verifiedFiles.remove(file.absolutePath)
        if (file.exists() && !file.delete()) fail(JevDownloadException.Reason.FileOperation)
    }

    private fun fail(reason: JevDownloadException.Reason): Nothing = throw JevDownloadException(reason)

    internal companion object {
        private const val RESERVE_BYTES = 128L * 1024 * 1024
        private const val MAX_REDIRECTS = 5
        private val REDIRECTS = setOf(301, 302, 303, 307, 308)
        private val RANGE = Regex("bytes (\\d+)-(\\d+)/(\\d+)")

        fun validRange(header: String?, offset: Long, size: Long): Boolean {
            val parts = header?.let(RANGE::matchEntire)?.groupValues ?: return false
            return parts[1].toLongOrNull() == offset && parts[2].toLongOrNull() == size - 1 && parts[3].toLongOrNull() == size
        }
    }
}
