package com.wangzan101.mika.localmodel

import com.wangzan101.mika.jev.JevDownloadException
import com.wangzan101.mika.jev.JevModelArtifact
import com.wangzan101.mika.jev.JevModelFiles
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** 本地主模型只有一个 GGUF；直接走生产下载器的单文件路径，避免意外要求视觉投影文件。 */
class LocalModelFilesTest {
    @get:Rule val tmp = TemporaryFolder()
    private val body = "0123456789abcdefghij"
    private val name = "local-main.gguf"
    private val partial get() = File(tmp.root, "$name.part")
    private val pending get() = File(tmp.root, "$name.unverified")

    private fun files(server: MockWebServer): JevModelFiles {
        val sha = MessageDigest.getInstance("SHA-256").digest(body.toByteArray())
            .joinToString("") { "%02x".format(it) }
        val artifact = JevModelArtifact(name, body.toByteArray().size.toLong(), sha, server.url("/$name"))
        return JevModelFiles(tmp.root, OkHttpClient.Builder().readTimeout(5, TimeUnit.SECONDS).build(),
            listOf(artifact), { it.host == server.url("/").host && it.port == server.port }, { Long.MAX_VALUE / 2 })
    }

    @Test fun oneFileDownloadPublishesVerifiedModelAndReportsOnlyItsBytes() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(body))
            val files = files(server)
            val progress = mutableListOf<Pair<Long, Long>>()
            assertFalse(files.present())
            assertEquals(0L, files.downloadedBytes())
            files.download { done, total -> progress += done to total }
            assertTrue(files.present())
            assertTrue(files.verify())
            assertEquals(body, files.model.readText())
            assertEquals(body.length.toLong(), files.downloadedBytes())
            assertEquals(body.length.toLong() to body.length.toLong(), progress.last())
            assertTrue(progress.all { (done, total) -> total == body.length.toLong() && done in 0..total })
            assertEquals(setOf(name), tmp.root.listFiles()!!.map { it.name }.toSet())
            val request = server.takeRequest(5, TimeUnit.SECONDS)!!
            assertEquals("/$name", request.path)
            assertEquals("identity", request.getHeader("Accept-Encoding"))
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun interruptedSingleFileTransferResumesAfterRecreatingDownloader() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(body).setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
            val first = files(server)
            assertTrue(runCatching { first.download { _, _ -> } }.exceptionOrNull() is IOException)
            assertFalse(first.present())
            assertFalse(first.model.exists())
            assertTrue(partial.length() in 1 until body.length.toLong())
            server.takeRequest(5, TimeUnit.SECONDS)!!

            val offset = partial.length().toInt()
            server.enqueue(MockResponse().setResponseCode(206)
                .setHeader("Content-Range", "bytes $offset-${body.lastIndex}/${body.length}")
                .setBody(body.drop(offset)))
            val resumed = files(server)
            assertEquals(offset.toLong(), resumed.downloadedBytes())
            resumed.download { _, _ -> }
            assertEquals("bytes=$offset-", server.takeRequest(5, TimeUnit.SECONDS)!!.getHeader("Range"))
            assertTrue(resumed.verify())
            assertEquals(body, resumed.model.readText())
            assertFalse(partial.exists())
            assertFalse(pending.exists())
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun ignoredRangeRestartsOneFileWithoutAppendingOldBytes() = runBlocking {
        MockWebServer().use { server ->
            partial.writeText(body.take(8))
            server.enqueue(MockResponse().setBody(body))
            val files = files(server)
            files.download { _, _ -> }
            assertEquals("bytes=8-", server.takeRequest(5, TimeUnit.SECONDS)!!.getHeader("Range"))
            assertEquals(body, files.model.readText())
            assertTrue(files.verify())
            assertFalse(partial.exists())
        }
    }

    @Test fun mismatchedRangeNeverPublishesAndPreservesExistingPartialBytes() = runBlocking {
        MockWebServer().use { server ->
            partial.writeText(body.take(8))
            server.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 9-19/20")
                .setBody(body.drop(9)))
            val files = files(server)
            val failure = runCatching { files.download { _, _ -> } }.exceptionOrNull()
            assertEquals(JevDownloadException.Reason.InvalidResponse, (failure as JevDownloadException).reason)
            assertEquals(body.take(8), partial.readText())
            assertFalse(files.present())
            assertFalse(files.model.exists())
        }
    }

    @Test fun sameSizedCorruptDownloadCannotBecomeReadyAndCanBeRetried() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("x".repeat(body.length)))
            val files = files(server)
            val failure = runCatching { files.download { _, _ -> } }.exceptionOrNull()
            assertEquals(JevDownloadException.Reason.Integrity, (failure as JevDownloadException).reason)
            assertFalse(files.present())
            assertFalse(files.verify())
            assertFalse(files.model.exists())
            assertFalse(partial.exists())
            server.enqueue(MockResponse().setBody(body))
            files.download { _, _ -> }
            assertTrue(files.verify())
            assertEquals(body, files.model.readText())
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun completePartialCanBeVerifiedWithoutAnyNetworkAndDeletionNeedsNoProjector() = runBlocking {
        MockWebServer().use { server ->
            partial.writeText(body)
            val files = files(server)
            assertFalse(files.present())
            files.download { _, _ -> }
            assertTrue(files.verify())
            assertEquals(0, server.requestCount)
            files.delete()
            assertFalse(files.present())
            assertFalse(files.verify())
            assertEquals(0L, files.downloadedBytes())
            assertTrue(tmp.root.listFiles()!!.isEmpty())
        }
    }
}
