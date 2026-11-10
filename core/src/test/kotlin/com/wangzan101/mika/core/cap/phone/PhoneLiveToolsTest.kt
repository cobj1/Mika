package com.wangzan101.mika.core.cap.phone

import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.tool.Args
import com.wangzan101.mika.core.tool.FakeScreen
import com.wangzan101.mika.core.uistate.ActionResult
import com.wangzan101.mika.core.uistate.ScreenPort
import com.wangzan101.mika.core.uistate.ScreenViewport
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class PhoneLiveToolsTest {
    @Test fun activeTouchOnlyUsesFreshViewportForCoordinateConversion() = runTest {
        var viewport = ScreenViewport(101, 201, true)
        val received = mutableListOf<Pair<Int, Int>>()
        val screen = object : ScreenPort by FakeScreen() {
            override suspend fun peek() = error("工具执行体不应重复风险门的完整读屏")
            override suspend fun viewport() = viewport
            override suspend fun pointer(op: String, pointerId: Int, x: Int, y: Int): ActionResult {
                received += x to y
                return ActionResult.Ok()
            }
        }
        val ctx = toolDispatcher(screen).ctx
        for (op in listOf("down", "move", "up")) {
            PhoneTouch.execute(Args(PhoneTouch.def,
                Json.parseToJsonElement("""{"op":"$op","pointerId":0,"x":999,"y":999}""").jsonObject), ctx)
            viewport = viewport.copy(width = viewport.width + 100, height = viewport.height + 100)
        }
        assertEquals(listOf(100 to 200, 200 to 300, 300 to 400), received)
    }

    @Test fun emptyViewportDoesNotSendInput() = runTest {
        val screen = object : ScreenPort by FakeScreen() {
            override suspend fun viewport() = ScreenViewport(100, 100, false)
            override suspend fun pointer(op: String, pointerId: Int, x: Int, y: Int): ActionResult = error("空屏不能落指")
        }
        val result = PhoneTouch.execute(Args(PhoneTouch.def,
            Json.parseToJsonElement("""{"op":"down","pointerId":0,"x":0,"y":0}""").jsonObject), toolDispatcher(screen).ctx)
        assertEquals("false", result.content["ok"]!!.jsonPrimitive.content)
    }

    @Test fun touchIsRejectedByReadOnlyEntry() = runTest {
        var called = false
        val screen = object : ScreenPort by FakeScreen() {
            override suspend fun pointer(op: String, pointerId: Int, x: Int, y: Int): ActionResult { called = true; return ActionResult.Ok() }
        }
        val result = toolDispatcher(screen).executeRead(Part.ToolCall("t", "phone_touch", """{"op":"cancel"}"""))
        assertFalse(called)
        assertEquals("false", result.content["ok"]!!.jsonPrimitive.content)
    }

    @Test fun cancelDoesNotReadScreenOrRequireCoordinates() = runTest {
        var operation = ""
        val screen = object : ScreenPort by FakeScreen() {
            override suspend fun peek() = error("cancellation must not read screen")
            override suspend fun pointer(op: String, pointerId: Int, x: Int, y: Int): ActionResult { operation = op; return ActionResult.Ok() }
        }
        val context = toolDispatcher(screen).ctx
        val result = PhoneTouch.execute(Args(PhoneTouch.def, Json.parseToJsonElement("""{"op":"cancel"}""").jsonObject), context)
        assertEquals("cancel", operation)
        assertEquals("true", result.content["ok"]!!.jsonPrimitive.content)
    }
}
