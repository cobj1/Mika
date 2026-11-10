package com.wangzan101.mika.jev

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class JevModelFilesTest {
    @get:Rule val tmp = TemporaryFolder()
    private val modelBody = "0123456789abcdefghij"
    private val projectorBody = "projector-123"

    private fun files(server: MockWebServer, freeSpace: () -> Long = { Long.MAX_VALUE / 2 }): JevModelFiles {
        val artifacts = listOf("model.gguf" to modelBody, "mmproj.gguf" to projectorBody).map { (name, body) ->
            JevModelArtifact(name, body.toByteArray().size.toLong(), sha(body), server.url("/$name"))
        }
        return JevModelFiles(tmp.root, OkHttpClient.Builder().readTimeout(20, TimeUnit.SECONDS).build(), artifacts,
            { it.host == server.url("/").host && it.port == server.port }, freeSpace)
    }

    private fun sha(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun part(name: String = "model.gguf") = File(tmp.root, "$name.part")
    private fun completeProjector() = File(tmp.root, "mmproj.gguf").writeText(projectorBody)

    @Test fun downloadsVerifiesAndAtomicallyPublishesBothFiles() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(modelBody))
            server.enqueue(MockResponse().setBody(projectorBody))
            val files = files(server)
            val progress = mutableListOf<Pair<Long, Long>>()
            files.download { done, total -> progress += done to total }
            assertTrue(files.present())
            assertTrue(files.verify())
            assertFalse(part().exists())
            assertFalse(part("mmproj.gguf").exists())
            assertEquals(modelBody, files.model.readText())
            assertEquals(33L to 33L, progress.last())
            assertEquals("identity", server.takeRequest().getHeader("Accept-Encoding"))
        }
    }

    @Test fun resumesOnlyExactMatchingContentRange() = runBlocking {
        MockWebServer().use { server ->
            part().writeText(modelBody.take(8))
            completeProjector()
            server.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 8-19/20").setBody(modelBody.drop(8)))
            val files = files(server)
            files.download { _, _ -> }
            assertEquals("bytes=8-", server.takeRequest().getHeader("Range"))
            assertTrue(files.verify())
        }
    }

    @Test fun ignoresRangeResponseByRestartingFromZero() = runBlocking {
        MockWebServer().use { server ->
            part().writeText(modelBody.take(8))
            completeProjector()
            server.enqueue(MockResponse().setBody(modelBody))
            val files = files(server)
            files.download { _, _ -> }
            assertEquals(modelBody, files.model.readText())
            assertTrue(files.verify())
        }
    }

    @Test fun wrongRangeCannotAppendOrBecomeReady() = runBlocking {
        MockWebServer().use { server ->
            part().writeText(modelBody.take(8))
            server.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 9-19/20").setBody(modelBody.drop(9)))
            val files = files(server)
            val error = runCatching { files.download { _, _ -> } }.exceptionOrNull()
            assertEquals(JevDownloadException.Reason.InvalidResponse, (error as JevDownloadException).reason)
            assertEquals(modelBody.take(8), part().readText())
            assertFalse(files.present())
        }
    }

    @Test fun hashMismatchRemovesCorruptPartAndNeverPublishes() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("x".repeat(modelBody.length)))
            val files = files(server)
            val error = runCatching { files.download { _, _ -> } }.exceptionOrNull()
            assertEquals(JevDownloadException.Reason.Integrity, (error as JevDownloadException).reason)
            assertFalse(files.model.exists())
            assertFalse(part().exists())
        }
    }

    @Test fun truncatedTransferKeepsOnlyPartialDataForNextAttempt() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(modelBody).setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
            val files = files(server)
            assertTrue(runCatching { files.download { _, _ -> } }.exceptionOrNull() is IOException)
            assertFalse(files.model.exists())
            assertTrue(part().length() in 1 until modelBody.length.toLong())
            val offset = part().length().toInt()
            server.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes $offset-19/20").setBody(modelBody.drop(offset)))
            server.enqueue(MockResponse().setBody(projectorBody))
            files.download { _, _ -> }
            assertTrue(files.verify())
        }
    }

    @Test fun cancellationInterruptsSocketAndRetainsAlreadyWrittenBytes() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(modelBody).throttleBody(4, 1, TimeUnit.SECONDS))
            val files = files(server)
            val wrote = CountDownLatch(1)
            val job = launch(Dispatchers.IO) { files.download { done, _ -> if (done > 0) wrote.countDown() } }
            assertTrue(wrote.await(5, TimeUnit.SECONDS))
            withTimeout(2_000) { job.cancelAndJoin() }
            assertFalse(files.model.exists())
            assertTrue(part().length() in 1 until modelBody.length.toLong())
            // 取消结束后互斥锁已释放，可由用户删除残留。
            files.delete()
            assertFalse(part().exists())
        }
    }

    @Test fun cancellationBeforeResponseHeadersAlsoInterruptsTheCall() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val files = files(server)
            val job = launch(Dispatchers.IO) { files.download { _, _ -> } }
            assertTrue(server.takeRequest(5, TimeUnit.SECONDS) != null)
            withTimeout(2_000) { job.cancelAndJoin() }
            assertFalse(files.model.exists())
            assertFalse(part().exists())
        }
    }

    @Test fun verifiesSameSizedExistingFileBeforeSkippingDownload() = runBlocking {
        MockWebServer().use { server ->
            val files = files(server)
            files.model.writeText("x".repeat(modelBody.length))
            completeProjector()
            assertTrue(files.present())
            assertFalse(files.verify())
            server.enqueue(MockResponse().setBody(modelBody))
            files.download { _, _ -> }
            assertTrue(files.verify())
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun completeVerifiedPartCanBeCommittedWithoutNetwork() = runBlocking {
        MockWebServer().use { server ->
            part().writeText(modelBody)
            completeProjector()
            val files = files(server)
            files.download { _, _ -> }
            assertTrue(files.verify())
            assertEquals(0, server.requestCount)
        }
    }

    @Test fun storageCheckFailsBeforeNetworkOrWriting() = runBlocking {
        MockWebServer().use { server ->
            val files = files(server) { 0 }
            val error = runCatching { files.download { _, _ -> } }.exceptionOrNull()
            assertEquals(JevDownloadException.Reason.NoSpace, (error as JevDownloadException).reason)
            assertEquals(0, server.requestCount)
            assertFalse(part().exists())
        }
    }

    @Test fun rejectsRedirectToUnapprovedHost() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "https://untrusted.example/weights"))
            val files = files(server)
            val error = runCatching { files.download { _, _ -> } }.exceptionOrNull()
            assertEquals(JevDownloadException.Reason.UnsafeRedirect, (error as JevDownloadException).reason)
            assertFalse(files.model.exists())
        }
    }

    @Test fun preservesRangeAcrossAllowedRedirects() = runBlocking {
        MockWebServer().use { server ->
            part().writeText(modelBody.take(8))
            completeProjector()
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/cdn/model.gguf"))
            server.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 8-19/20").setBody(modelBody.drop(8)))
            val files = files(server)
            files.download { _, _ -> }
            assertEquals("bytes=8-", server.takeRequest().getHeader("Range"))
            val redirected = server.takeRequest()
            assertEquals("/cdn/model.gguf", redirected.path)
            assertEquals("bytes=8-", redirected.getHeader("Range"))
            assertTrue(files.verify())
        }
    }

    @Test fun rangeRequiresNumericExactStartEndAndLength() {
        assertTrue(JevModelFiles.validRange("bytes 8-19/20", 8, 20))
        for (header in listOf(null, "bytes 8-18/20", "bytes 9-19/20", "bytes 8-19/*", "bytes 8-19/21", "bytes */20")) {
            assertFalse(JevModelFiles.validRange(header, 8, 20))
        }
    }

    @Test fun reportsBoundedBytesFromFinalOrPartialWithoutClaimingIntegrity() {
        MockWebServer().use { server ->
            val files = files(server)
            assertEquals(0L, files.downloadedBytes())
            part().writeText("partial")
            assertEquals(7L, files.downloadedBytes())
            files.model.writeText("abc")
            // 有最终文件时优先最终文件；两者均存在不重复计数。
            assertEquals(3L, files.downloadedBytes())
            files.projector.writeText("x".repeat(100))
            assertEquals(16L, files.downloadedBytes())
            assertFalse(files.present())
        }
    }
}
