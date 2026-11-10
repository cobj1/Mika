package com.wangzan101.mika.jev

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** 使用本地 HTTP 与可控子进程替身验证超时/释放，不下载权重也不依赖原生二进制。 */
class JevEngineTest {
    @get:Rule val tmp = TemporaryFolder()

    private class Child(port: Int, private val announceAfterMs: Long = 0) : Process() {
        @Volatile var alive = true
        @Volatile var inputClosed = false
        @Volatile var outputClosed = false
        @Volatile var errorClosed = false
        private val ended = CountDownLatch(1)
        private val stdout = object : FilterInputStream(ByteArrayInputStream("listening on http://127.0.0.1:$port\n".toByteArray())) {
            private var announced = false
            override fun read(bytes: ByteArray, offset: Int, count: Int): Int {
                if (!announced) {
                    announced = true
                    ended.await(announceAfterMs, TimeUnit.MILLISECONDS)
                }
                return super.read(bytes, offset, count)
            }
            override fun close() { inputClosed = true; super.close() }
        }
        private val stdin = object : ByteArrayOutputStream() {
            override fun close() { outputClosed = true; super.close() }
        }
        private val stderr = object : ByteArrayInputStream(byteArrayOf()) {
            override fun close() { errorClosed = true; super.close() }
        }
        override fun getInputStream(): InputStream = stdout
        override fun getOutputStream() = stdin
        override fun getErrorStream(): InputStream = stderr
        override fun waitFor(): Int { ended.await(); return 0 }
        override fun waitFor(timeout: Long, unit: TimeUnit) = ended.await(timeout, unit)
        override fun exitValue(): Int { check(!alive); return 0 }
        override fun destroy() { alive = false; ended.countDown() }
        override fun destroyForcibly(): Process { destroy(); return this }
        override fun isAlive() = alive
        fun assertReleased() {
            assertFalse(alive)
            assertTrue(inputClosed)
            assertTrue(outputClosed)
            assertTrue(errorClosed)
        }
    }

    private fun engine(child: Child, timeouts: JevEngine.Timeouts): JevEngine {
        val executable = tmp.newFile("engine-${System.nanoTime()}")
        executable.setExecutable(true)
        assertTrue(executable.canExecute())
        return JevEngine(executable, JevModelFiles(tmp.root, OkHttpClient()), timeouts = timeouts, startProcess = { child })
    }

    @Test fun listeningAndPropsShareOneLoadingBudget() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("ok"))
            server.enqueue(MockResponse().setBody(JevModelCatalog.files.first().name).setBodyDelay(800, TimeUnit.MILLISECONDS))
            val child = Child(server.port, announceAfterMs = 800)
            var loaded = false
            val error = runCatching {
                engine(child, JevEngine.Timeouts(1_300, 5_000, 5_000)).score(JevImages.Pair("a", "b"), "x") { loaded = true }
            }.exceptionOrNull()
            assertTrue(error is TimeoutCancellationException)
            assertFalse(loaded)
            assertEquals(2, server.requestCount)
            child.assertReleased()
        }
    }

    @Test fun healthCallsHaveShortDeadlinesAndRetryWithinOverallBudget() = runBlocking {
        MockWebServer().use { server ->
            repeat(4) { server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)) }
            val child = Child(server.port)
            val error = runCatching {
                engine(child, JevEngine.Timeouts(1_300, 100, 5_000)).score(JevImages.Pair("a", "b"), "x") {}
            }.exceptionOrNull()
            assertTrue(error is TimeoutCancellationException)
            assertTrue(server.requestCount >= 2)
            child.assertReleased()
        }
    }

    @Test fun propsDeadlineIncludesTricklingResponseBody() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("ok"))
            server.enqueue(MockResponse().setBody(JevModelCatalog.files.first().name).throttleBody(1, 100, TimeUnit.MILLISECONDS))
            val child = Child(server.port)
            val error = runCatching {
                engine(child, JevEngine.Timeouts(5_000, 250, 5_000)).score(JevImages.Pair("a", "b"), "x") {}
            }.exceptionOrNull()
            assertTrue("a control request must expire before the full loading budget", error is IOException)
            assertEquals(2, server.requestCount)
            child.assertReleased()
        }
    }

    @Test fun scoringGetsItsOwnBudgetAndCancellationReleasesTheChild() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("ok"))
            server.enqueue(MockResponse().setBody(JevModelCatalog.files.first().name))
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val child = Child(server.port)
            var loaded = false
            val error = runCatching {
                engine(child, JevEngine.Timeouts(5_000, 1_000, 250)).score(JevImages.Pair("a", "b"), "x") { loaded = true }
            }.exceptionOrNull()
            assertTrue(error is TimeoutCancellationException)
            assertTrue(loaded)
            assertEquals(3, server.requestCount)
            child.assertReleased()
        }
    }

    @Test fun externalCancellationInterruptsLoadingWithoutWaitingForItsDeadline() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val child = Child(server.port)
            val job = launch(Dispatchers.IO) {
                engine(child, JevEngine.Timeouts()).score(JevImages.Pair("a", "b"), "x") {}
            }
            assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            withTimeout(2_000) { job.cancelAndJoin() }
            child.assertReleased()
        }
    }
}
