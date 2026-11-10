package com.wangzan101.mika.jev

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.MockResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.nio.file.attribute.FileTime
import java.security.MessageDigest

class JevVerificationCacheTest {
    @get:Rule val tmp = TemporaryFolder()
    private val content = linkedMapOf("model.gguf" to "model-0123456789", "projector.gguf" to "projector-012345")

    /** 模拟 Unix 的 inode/ctime，让 Windows 上也能验证缓存对文件替换和原地改写的处理。 */
    private class FileIo {
        var reads = 0
        var closed = 0
        var reliable = true
        var onOpen: ((File) -> Unit)? = null
        val inodes = mutableMapOf<String, Long>()
        val changes = mutableMapOf<String, Long>()

        fun snapshot(file: File): JevFileSnapshot? = if (!reliable || !file.isFile) null else JevFileSnapshot(
            1, inodes[file.name] ?: 1, file.length(), FileTime.fromMillis(file.lastModified()),
            FileTime.fromMillis(changes[file.name] ?: 1),
        )

        fun open(file: File): InputStream {
            reads++
            val stream = object : FilterInputStream(file.inputStream()) {
                override fun close() {
                    try { super.close() } finally { closed++ }
                }
            }
            try { onOpen?.invoke(file) }
            catch (e: Exception) { stream.close(); throw e }
            return stream
        }
    }

    private fun files(server: MockWebServer, io: FileIo): JevModelFiles = JevModelFiles(
        tmp.root, OkHttpClient(), content.map { (name, body) ->
            val digest = MessageDigest.getInstance("SHA-256").digest(body.toByteArray()).joinToString("") { "%02x".format(it) }
            JevModelArtifact(name, body.toByteArray().size.toLong(), digest, server.url("/$name"))
        }, { it.host == server.url("/").host && it.port == server.port }, { Long.MAX_VALUE / 2 }, io::snapshot, io::open,
    )

    private fun seed() = content.forEach { (name, body) -> File(tmp.root, name).writeText(body) }

    @Test fun unchangedFilesAreReadOncePerInstanceAndNeverTrustedAcrossInstances() = runBlocking {
        MockWebServer().use { server ->
            seed()
            val io = FileIo()
            val files = files(server, io)
            assertTrue(files.verify())
            assertEquals(2, io.reads)
            repeat(3) { assertTrue(files.verify()) }
            assertEquals(2, io.reads)
            assertTrue(files(server, io).verify())
            assertEquals(4, io.reads)
        }
    }

    @Test fun sameLengthRewriteWithRestoredMtimeStillInvalidatesByCtime() = runBlocking {
        MockWebServer().use { server ->
            seed()
            val io = FileIo()
            val files = files(server, io)
            assertTrue(files.verify())
            val mtime = files.model.lastModified()
            files.model.writeText("x".repeat(content.getValue(files.model.name).length))
            assertTrue(files.model.setLastModified(mtime))
            io.changes[files.model.name] = 2
            assertFalse(files.verify())
            assertEquals(3, io.reads)
            assertFalse(files.verify())
            assertEquals(4, io.reads)
        }
    }

    @Test fun replacementWithMatchingSizeAndTimestampsStillInvalidatesByInode() = runBlocking {
        MockWebServer().use { server ->
            seed()
            val io = FileIo()
            val files = files(server, io)
            assertTrue(files.verify())
            val mtime = files.model.lastModified()
            assertTrue(files.model.delete())
            files.model.writeText("x".repeat(content.getValue(files.model.name).length))
            assertTrue(files.model.setLastModified(mtime))
            io.inodes[files.model.name] = 2
            assertFalse(files.verify())
            assertEquals(3, io.reads)
        }
    }

    @Test fun fileMissingThenRecreatedDoesNotKeepItsOldVerification() = runBlocking {
        MockWebServer().use { server ->
            seed()
            val io = FileIo()
            val files = files(server, io)
            assertTrue(files.verify())
            val mtime = files.model.lastModified()
            assertTrue(files.model.delete())
            assertFalse(files.verify())
            files.model.writeText(content.getValue(files.model.name))
            assertTrue(files.model.setLastModified(mtime))
            assertTrue(files.verify())
            assertEquals(3, io.reads)
        }
    }

    @Test fun deletingClearsCacheEvenIfRecreatedFilesAppearToHaveTheSameMetadata() = runBlocking {
        MockWebServer().use { server ->
            seed()
            val io = FileIo()
            val files = files(server, io)
            val times = content.keys.associateWith { File(tmp.root, it).lastModified() }
            assertTrue(files.verify())
            files.delete()
            seed()
            times.forEach { (name, time) -> assertTrue(File(tmp.root, name).setLastModified(time)) }
            assertTrue(files.verify())
            assertEquals(4, io.reads)
        }
    }

    @Test fun downloadExplicitlyRevalidatesPreviouslyCachedFiles() = runBlocking {
        MockWebServer().use { server ->
            seed()
            val io = FileIo()
            val files = files(server, io)
            assertTrue(files.verify())
            files.download { _, _ -> }
            assertEquals(4, io.reads)
            assertEquals(0, server.requestCount)
            assertTrue(files.verify())
            assertEquals(4, io.reads)
        }
    }

    @Test fun missingReliableMetadataAlwaysFallsBackToCompleteHashing() = runBlocking {
        MockWebServer().use { server ->
            seed()
            val io = FileIo()
            val files = files(server, io)
            assertTrue(files.verify())
            io.reliable = false
            assertTrue(files.verify())
            assertTrue(files.verify())
            assertEquals(6, io.reads)
        }
    }

    @Test fun fileChangedDuringHashingCannotPublishOrCacheVerification() = runBlocking {
        MockWebServer().use { server ->
            seed()
            val io = FileIo()
            val files = files(server, io)
            io.onOpen = { file -> io.changes[file.name] = 2 }
            assertFalse(files.verify())
            assertEquals(1, io.reads)
            io.onOpen = null
            assertTrue(files.verify())
            assertEquals(3, io.reads)
        }
    }

    @Test fun cancelledVerificationClosesItsInputAndCannotPopulateCache() = runBlocking {
        MockWebServer().use { server ->
            seed()
            val io = FileIo()
            val files = files(server, io)
            val job = launch(Dispatchers.IO, start = CoroutineStart.LAZY) { files.verify() }
            io.onOpen = { job.cancel() }
            job.start()
            job.join()
            assertTrue(job.isCancelled)
            assertEquals(1, io.reads)
            assertEquals(1, io.closed)
            io.onOpen = null
            assertTrue(files.verify())
            assertEquals(3, io.reads)
            files.delete()
            assertFalse(files.present())
        }
    }

    @Test fun freshDownloadHashesOnlyFinalPathsOnceAndKeepsTheirFullStatCache() = runBlocking {
        MockWebServer().use { server ->
            content.values.forEach { server.enqueue(MockResponse().setBody(it)) }
            val io = FileIo()
            val files = files(server, io)
            val hashed = mutableListOf<String>()
            io.onOpen = { file ->
                hashed += file.name
                assertTrue(File(tmp.root, file.name + ".unverified").exists())
                assertFalse(files.present())
            }
            files.download { _, _ -> }
            assertEquals(content.keys.toList(), hashed)
            assertEquals(2, io.reads)
            assertTrue(files.present())
            assertTrue(files.verify())
            assertEquals(2, io.reads)
            assertTrue(content.keys.none { File(tmp.root, "$it.unverified").exists() })
        }
    }

    @Test fun cancellationAfterRenameRetainsPendingFileAndResumesVerificationWithoutNetwork() = runBlocking {
        MockWebServer().use { server ->
            content.forEach { (name, body) -> File(tmp.root, "$name.part").writeText(body) }
            val io = FileIo()
            val files = files(server, io)
            val job = launch(Dispatchers.IO, start = CoroutineStart.LAZY) { files.download { _, _ -> } }
            io.onOpen = { file ->
                assertEquals(files.model, file)
                assertFalse(files.present())
                job.cancel()
            }
            job.start()
            job.join()
            assertTrue(job.isCancelled)
            assertEquals(content.getValue(files.model.name), files.model.readText())
            assertTrue(File(tmp.root, files.model.name + ".unverified").exists())
            assertFalse(files.present())
            io.onOpen = null
            // 模拟离页后重建文件管理器；拒绝标记不能充当跨实例的校验凭证。
            val resumed = files(server, io)
            resumed.download { _, _ -> }
            assertEquals(3, io.reads)
            assertEquals(0, server.requestCount)
            assertTrue(resumed.present())
            assertTrue(resumed.verify())
            assertEquals(3, io.reads)
        }
    }

    @Test fun rewriteDuringFinalVerificationCannotPublishReadyOrPreserveBadFile() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(content.values.first()))
            val io = FileIo()
            val files = files(server, io)
            io.onOpen = { file ->
                val mtime = file.lastModified()
                file.writeText("x".repeat(content.getValue(file.name).length))
                assertTrue(file.setLastModified(mtime))
                io.changes[file.name] = 2
            }
            val error = runCatching { files.download { _, _ -> } }.exceptionOrNull()
            assertEquals(JevDownloadException.Reason.Integrity, (error as JevDownloadException).reason)
            assertFalse(files.model.exists())
            assertFalse(File(tmp.root, files.model.name + ".unverified").exists())
            assertFalse(files.present())
        }
    }

    @Test fun stalePendingMarkerForCorruptFileNeverGrantsTrustAndDeleteRemovesIt() = runBlocking {
        MockWebServer().use { server ->
            seed()
            val io = FileIo()
            val files = files(server, io)
            files.model.writeText("x".repeat(content.getValue(files.model.name).length))
            val marker = File(tmp.root, files.model.name + ".unverified").apply { writeText("") }
            assertFalse(files.present())
            assertFalse(files.verify())
            assertEquals(1, io.reads)
            files.delete()
            assertFalse(marker.exists())
        }
    }
}
