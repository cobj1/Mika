package com.wangzan101.mika.core.cap.phone

import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.tool.Args
import com.wangzan101.mika.core.tool.FakeScreen
import com.wangzan101.mika.core.tool.Risk
import com.wangzan101.mika.core.uistate.ScreenPort
import com.wangzan101.mika.core.uistate.TextVerification
import com.wangzan101.mika.core.uistate.TextVerificationCandidate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class PhoneVerifyTextTest {
    private val region = listOf(0, 100, 999, 300)
    private fun call(args: String = """{"region":[0,100,999,300],"text":"余额 12.34"}""") =
        Part.ToolCall("verify", "phone_verify_text", args)

    @Test fun readEntryPassesExactCandidateAndRegionAndReturnsOnlyUncalibratedScore() = runTest {
        var calls = 0
        val screen = object : ScreenPort by FakeScreen() {
            override suspend fun verifyText(text: String?, region: List<Int>): TextVerification {
                calls++
                assertEquals("余额 12.34", text)
                assertEquals(this@PhoneVerifyTextTest.region, region)
                return TextVerification(region, candidate = TextVerificationCandidate(text!!, region, "provided"),
                    score = 0.99, producerTimestampNanos = 42, capturedAgoMs = 6000, newerFrameAvailable = true)
            }
        }
        val result = toolDispatcher(screen).executeRead(call()).content
        assertEquals(1, calls)
        assertEquals("true", result["ok"]!!.jsonPrimitive.content)
        assertEquals("0.99", result["uncalibratedScore"]!!.jsonPrimitive.content)
        assertEquals("false", result["calibrated"]!!.jsonPrimitive.content)
        assertEquals("true", result["freshness"]!!.jsonObject["newerFrameAvailable"]!!.jsonPrimitive.content)
        assertFalse(result.containsKey("verified"))
        assertFalse(result.containsKey("success"))
        assertTrue(result["candidate"]!!.jsonObject["text"]!!.jsonPrimitive.content.startsWith("<untrusted>"))
        assertEquals(Risk.Read, PhoneVerifyText.risk)
        assertTrue(PhoneVerifyText.yieldsToSteer)
    }

    @Test fun omittedTextRequestsSameFrameOcrAndMissingModelDoesNotBecomeLowScore() = runTest {
        val screen = object : ScreenPort by FakeScreen() {
            override suspend fun verifyText(text: String?, region: List<Int>): TextVerification {
                assertNull(text)
                return TextVerification(region, error = "model_missing", message = "尚未下载")
            }
        }
        val result = toolDispatcher(screen).executeRead(call("""{"region":[0,100,999,300]}""")).content
        assertEquals("model_missing", result["error"]!!.jsonPrimitive.content)
        assertFalse(result.containsKey("uncalibratedScore"))
        assertEquals("false", result["ok"]!!.jsonPrimitive.content)
    }

    @Test fun malformedRegionsAndOversizedTextNeverReachScreen() = runTest {
        var calls = 0
        val screen = object : ScreenPort by FakeScreen() {
            override suspend fun verifyText(text: String?, region: List<Int>): TextVerification { calls++; error("must not execute") }
        }
        val dispatcher = toolDispatcher(screen)
        for (bad in listOf("[]", "[0,0,999]", "[0,0,0,999]", "[999,0,0,999]", "[-1,0,999,999]", "[0,0,1000,999]", "[0.5,0,999,999]", "[\"0\",0,999,999]")) {
            assertEquals("false", dispatcher.executeRead(call("""{"region":$bad}""")).content["ok"]!!.jsonPrimitive.content)
        }
        dispatcher.executeRead(call("""{"region":[0,0,999,999],"text":"${"x".repeat(1001)}"}"""))
        assertEquals(0, calls)
    }

    @Test fun cancellationPropagatesToCaller() = runTest {
        val screen = object : ScreenPort by FakeScreen() {
            override suspend fun verifyText(text: String?, region: List<Int>): TextVerification = throw CancellationException("task stopped")
        }
        val error = runCatching {
            PhoneVerifyText.execute(Args(PhoneVerifyText.def, Json.parseToJsonElement(call().argsJson).jsonObject), toolDispatcher(screen).ctx)
        }.exceptionOrNull()
        assertTrue(error is CancellationException)
    }

    @Test fun coldStartupHasItsOwnBoundedBudgetAndTimeoutCancelsWork() = runTest {
        var completedCleanup = false
        val screen = object : ScreenPort by FakeScreen() {
            override suspend fun verifyText(text: String?, region: List<Int>): TextVerification = try {
                delay(8 * 60_000L)
                TextVerification(region, score = 0.99)
            } finally { completedCleanup = true }
        }
        val result = PhoneVerifyText.execute(Args(PhoneVerifyText.def, Json.parseToJsonElement(call().argsJson).jsonObject), toolDispatcher(screen).ctx)
        assertEquals("timeout", result.content["error"]!!.jsonPrimitive.content)
        assertTrue(completedCleanup)
        assertEquals(7 * 60_000L, testScheduler.currentTime)
    }
}
