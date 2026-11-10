package com.wangzan101.mika.localmodel

import com.wangzan101.mika.core.model.Author
import com.wangzan101.mika.core.model.Capabilities
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.Role
import com.wangzan101.mika.core.model.StopReason
import com.wangzan101.mika.core.model.ToolSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** 用真实 HTTP/SSE 适配器与可控子进程替身检查工具往返、鉴权与取消，不下载权重。 */
class LocalModelEngineTest {
    @get:Rule val tmp = TemporaryFolder()
    private val caps = Capabilities(toolCalls = true, vision = false, contextTokens = 16_384, reasoning = false)
    private val messages = listOf(Message(Role.User, listOf(Part.Text("打开设置")), Author.User))
    private val tool = ToolSpec("phone_launch_app", "打开应用", """{"type":"object","properties":{"package":{"type":"string"}},"required":["package"]}""")

    private class Child(port: Int) : Process() {
        @Volatile private var alive = true
        private val ended = CountDownLatch(1)
        private var stdoutClosed = false
        private var stdinClosed = false
        private var stderrClosed = false
        private val stdout = object : ByteArrayInputStream("listening on http://127.0.0.1:$port\n".toByteArray()) {
            override fun close() { stdoutClosed = true; super.close() }
        }
        private val stdin = object : ByteArrayOutputStream() { override fun close() { stdinClosed = true; super.close() } }
        private val stderr = object : ByteArrayInputStream(byteArrayOf()) { override fun close() { stderrClosed = true; super.close() } }
        override fun getInputStream() = stdout
        override fun getOutputStream() = stdin
        override fun getErrorStream() = stderr
        override fun waitFor(): Int { ended.await(); return 0 }
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = ended.await(timeout, unit)
        override fun exitValue(): Int { check(!alive); return 0 }
        override fun destroy() { alive = false; ended.countDown() }
        override fun destroyForcibly(): Process { destroy(); return this }
        override fun isAlive(): Boolean = alive
        fun assertReleased() { assertFalse(alive); assertTrue(stdoutClosed); assertTrue(stdinClosed); assertTrue(stderrClosed) }
    }

    private fun model() = tmp.newFile("model-${System.nanoTime()}.gguf")
    private fun engine(file: File, child: Child, timeouts: LocalModelEngine.Timeouts = LocalModelEngine.Timeouts(),
        command: (List<String>) -> Unit = {},
    ): LocalModelEngine {
        val exe = tmp.newFile("engine-${System.nanoTime()}")
        exe.setExecutable(true)
        assertTrue(exe.canExecute())
        return LocalModelEngine(exe, file, "local-qwen", caps, timeouts) { args -> command(args); child }
    }

    private fun ready(server: MockWebServer, file: File) {
        server.enqueue(MockResponse().setBody("ok"))
        server.enqueue(MockResponse().setBody(buildJsonObject { put("model_path", file.absolutePath) }.toString()))
    }

    @Test fun streamedToolCallUsesLoopbackAuthenticationAndStructuredAdapter() = runBlocking {
        MockWebServer().use { server ->
            val file = model()
            ready(server, file)
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody("""
                data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"phone_launch_app","arguments":"{\"package\":"}}]},"finish_reason":null}]}

                data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"\"com.android.settings\"}"}}]},"finish_reason":"tool_calls"}]}

                data: {"choices":[],"usage":{"prompt_tokens":80,"completion_tokens":12}}

                data: [DONE]

            """.trimIndent()))
            val child = Child(server.port)
            var args = emptyList<String>()
            val visible = mutableListOf<String>()
            val reply = engine(file, child, command = { args = it }).complete("system", messages, listOf(tool), visible::add)
            val call = reply.parts.filterIsInstance<Part.ToolCall>().single()
            assertEquals("phone_launch_app", call.name)
            assertEquals("com.android.settings", Json.parseToJsonElement(call.argsJson).jsonObject["package"]!!.jsonPrimitive.content)
            assertEquals(StopReason.ToolUse, reply.stopReason)
            assertEquals(80, reply.usage!!.inputTokens)
            assertTrue(visible.isEmpty())
            assertEquals("127.0.0.1", args[args.indexOf("--host") + 1])
            assertEquals("0", args[args.indexOf("--port") + 1])
            assertEquals("off", args[args.indexOf("--reasoning") + 1])
            assertEquals("2048", args[args.indexOf("-n") + 1])
            val key = args[args.indexOf("--api-key") + 1]
            val health = server.takeRequest()
            val props = server.takeRequest()
            val inference = server.takeRequest()
            assertEquals("/health", health.path)
            assertEquals("/props", props.path)
            assertEquals("/v1/chat/completions", inference.path)
            listOf(health, props, inference).forEach { assertEquals("Bearer $key", it.getHeader("Authorization")) }
            val payload = Json.parseToJsonElement(inference.body.readUtf8()).jsonObject
            assertEquals("phone_launch_app", payload["tools"]!!.jsonArray.single().jsonObject["function"]!!.jsonObject["name"]!!.jsonPrimitive.content)
            child.assertReleased()
        }
    }

    @Test fun toolResultIsReplayedAndPlainTextCanFinishTheTask() = runBlocking {
        MockWebServer().use { server ->
            val file = model()
            ready(server, file)
            server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"content":"已打开设置"},"finish_reason":"stop"}]}"""))
            val child = Child(server.port)
            val history = messages + listOf(
                Message(Role.Assistant, listOf(Part.ToolCall("c1", "phone_launch_app", "{}")), Author.Mika),
                Message(Role.Tool, listOf(Part.ToolResult("c1", "{\"ok\":true}", false)), Author.Mika),
            )
            val reply = engine(file, child).complete("system", history, listOf(tool), null)
            assertEquals("已打开设置", (reply.parts.single() as Part.Text).text)
            server.takeRequest(); server.takeRequest()
            val wire = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject["messages"]!!.jsonArray
            assertEquals("tool", wire.last().jsonObject["role"]!!.jsonPrimitive.content)
            assertEquals("c1", wire.last().jsonObject["tool_call_id"]!!.jsonPrimitive.content)
            child.assertReleased()
        }
    }

    @Test fun wrongModelIdentityNeverReceivesConversation() = runBlocking {
        MockWebServer().use { server ->
            val file = model()
            server.enqueue(MockResponse().setBody("ok"))
            // 文件名出现在其他字段仍不能通过精确身份检查。
            server.enqueue(MockResponse().setBody(buildJsonObject { put("model_path", "wrong"); put("note", file.absolutePath) }.toString()))
            val child = Child(server.port)
            val failure = runCatching { engine(file, child).complete("private", messages, listOf(tool), null) }.exceptionOrNull()
            assertTrue(failure is IOException)
            assertEquals(2, server.requestCount)
            child.assertReleased()
        }
    }

    @Test fun loadingDeadlineAndExternalCancellationReleaseTheProcess() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val child = Child(server.port)
            val job = launch(Dispatchers.IO) { engine(model(), child).complete("s", messages, emptyList(), null) }
            assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            withTimeout(2_000) { job.cancelAndJoin() }
            child.assertReleased()
        }
        MockWebServer().use { server ->
            repeat(3) { server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)) }
            val child = Child(server.port)
            val failure = runCatching { engine(model(), child, LocalModelEngine.Timeouts(800, 100, 5000)).complete("s", messages, emptyList(), null) }.exceptionOrNull()
            assertTrue(failure is TimeoutCancellationException)
            child.assertReleased()
        }
    }

    @Test fun generationDeadlineReleasesAProcessThatStoppedResponding() = runBlocking {
        MockWebServer().use { server ->
            val file = model()
            ready(server, file)
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val child = Child(server.port)
            val failure = runCatching {
                engine(file, child, LocalModelEngine.Timeouts(5000, 1000, 250)).complete("s", messages, emptyList(), {})
            }.exceptionOrNull()
            assertTrue(failure is TimeoutCancellationException)
            child.assertReleased()
        }
    }
}
