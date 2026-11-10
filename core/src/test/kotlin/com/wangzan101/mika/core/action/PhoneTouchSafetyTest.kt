package com.wangzan101.mika.core.action

import com.wangzan101.mika.core.access.AppAccessPolicy
import com.wangzan101.mika.core.access.AppAccessState
import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.gate.ConfirmationGate
import com.wangzan101.mika.core.gate.IrreversibleDetector
import com.wangzan101.mika.core.guard.OperationGuards
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.record.RunEvent
import com.wangzan101.mika.core.record.RunSink
import com.wangzan101.mika.core.risk.GateTarget
import com.wangzan101.mika.core.risk.IrrevAction
import com.wangzan101.mika.core.tool.FakeScreen
import com.wangzan101.mika.core.tool.Verb
import com.wangzan101.mika.core.uistate.ActionResult
import com.wangzan101.mika.core.uistate.AppAccessScreenPort
import com.wangzan101.mika.core.uistate.ScreenPort
import com.wangzan101.mika.core.uistate.ScreenSnapshot
import com.wangzan101.mika.core.uistate.UiElement
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class PhoneTouchSafetyTest {
    private class TouchScreen(var label: String? = "普通按钮") : ScreenPort by FakeScreen() {
        val operations = mutableListOf<String>()
        var reads = 0
        var failReads = false
        var result: ActionResult = ActionResult.Ok()
        var onPointer: suspend (String) -> Unit = {}
        override suspend fun snapshot(): ScreenSnapshot {
            reads++
            check(!failReads) { "释放不应读取页面" }
            val elements = label?.let { listOf(UiElement("@e1", "button", it, 500, 500,
                listOf(400, 400, 600, 600), null, true, false, false)) }.orEmpty()
            return ScreenSnapshot("s1", "com.example", elements, 1000, 1000, hasWindow = true)
        }
        override suspend fun peek() = snapshot()
        override suspend fun viewport() = com.wangzan101.mika.core.uistate.ScreenViewport(1000, 1000, true)
        override suspend fun pointer(op: String, pointerId: Int, x: Int, y: Int): ActionResult {
            currentCoroutineContext().ensureActive()
            operations += op
            onPointer(op)
            return if (op == "cancel") ActionResult.Ok() else result
        }
    }

    private fun call(op: String, target: String? = null) = Part.ToolCall("touch", "phone_touch",
        if (op == "cancel") """{"op":"cancel"}"""
        else """{"op":"$op","pointerId":0,"x":500,"y":500${target?.let { ",\"target\":${JsonPrimitive(it)}" }.orEmpty()}}""")

    private fun executor(screen: ScreenPort, events: MutableList<RunEvent> = mutableListOf(), gate: suspend () -> Unit = {}) = ActionExecutor(
        toolDispatcher(screen), OperationGuards(), ConfirmationGate(), gate,
        ActionRecorder(object : RunSink { override fun event(e: RunEvent) { events += e } }),
    )

    @Test fun downMoveAndUpOnPaymentAreRefusedForEveryOriginAndReleaseHeldPointers() = runTest {
        val origins = listOf(Origin.Model(1), Origin.Batch(1), Origin.Script(1), Origin.Replay, Origin.McpTool(1))
        for (origin in origins) for (op in listOf("down", "move", "up")) {
            val screen = TouchScreen("确认支付")
            val outcome = executor(screen).run(call(op), origin)
            assertTrue("$op $origin $outcome", outcome is ActionOutcome.Refused)
            assertEquals(IrrevAction.pay, (outcome as ActionOutcome.Refused).hit?.action)
            assertEquals(listOf("cancel"), screen.operations)
        }
    }

    @Test fun paymentUsesFreshScreenInsteadOfPreviousOrdinaryButton() = runTest {
        val screen = TouchScreen()
        val tools = toolDispatcher(screen)
        tools.executeRead(Part.ToolCall("observe", "phone_observe", "{}"))
        screen.label = "确认支付"
        val executor = ActionExecutor(tools, OperationGuards(), ConfirmationGate(), {}, ActionRecorder(RunSink.NONE))
        assertTrue(executor.run(call("down"), Origin.Model(1)) is ActionOutcome.Refused)
        assertEquals(listOf("cancel"), screen.operations)
    }

    @Test fun lightweightViewportDoesNotReuseRiskDecisionForMoveOrUp() = runTest {
        for (op in listOf("move", "up")) {
            val screen = TouchScreen()
            val executor = executor(screen)
            assertTrue(executor.run(call("down"), Origin.Model(1)) is ActionOutcome.Ran)
            val readsAfterDown = screen.reads
            screen.label = "确认支付"
            assertTrue(executor.run(call(op), Origin.Model(2)) is ActionOutcome.Refused)
            assertTrue("每次续指/抬指必须重新读取风险内容", screen.reads > readsAfterDown)
            assertEquals(listOf("down", "cancel"), screen.operations)
        }
    }

    @Test fun namedPaymentOnTextlessCanvasStillRequiresConfirmation() = runTest {
        val screen = TouchScreen(null)
        val outcome = executor(screen).run(call("up", "确认支付"), Origin.Model(1))
        assertTrue(outcome is ActionOutcome.Refused)
        assertEquals(listOf("cancel"), screen.operations)
    }

    @Test fun ordinaryCanvasRemainsUsableForModelAndSuccessfulCallsKeepFingersHeld() = runTest {
        val screen = TouchScreen(null)
        val executor = executor(screen)
        for (op in listOf("down", "move", "up")) {
            val outcome = executor.run(call(op, "游戏摇杆"), Origin.Model(1))
            assertTrue("$outcome", outcome is ActionOutcome.Ran)
            assertEquals(JsonPrimitive(true), (outcome as ActionOutcome.Ran).exec.content["ok"])
        }
        assertEquals(listOf("down", "move", "up"), screen.operations)
    }

    @Test fun explicitCancelBypassesBlockedAppAndGateWithoutReadingButStillRecords() = runTest {
        val inner = TouchScreen().apply { failReads = true }
        val blocked = AppAccessScreenPort(inner, AppAccessPolicy { AppAccessState(blocked = setOf("bank")) },
            packages = { error("cancel 不应检查当前窗口") })
        val events = mutableListOf<RunEvent>()
        val outcome = executor(blocked, events, gate = { error("cancel 不应等待关卡") }).run(call("cancel"), Origin.Model(1))
        assertTrue(outcome is ActionOutcome.Ran)
        assertEquals(listOf("cancel"), inner.operations)
        assertEquals(0, inner.reads)
        assertEquals(1, events.filterIsInstance<RunEvent.ToolResult>().count { it.tool == "phone_touch" })
    }

    @Test fun accessRefusalBeforeToolDispatchStillReleasesPreviousGesture() = runTest {
        val inner = TouchScreen().apply { failReads = true }
        val blocked = AppAccessScreenPort(inner, AppAccessPolicy { AppAccessState(blocked = setOf("bank")) }, packages = { listOf("bank") })
        val outcome = executor(blocked).run(call("move"), Origin.Model(1))
        assertEquals("app_blocked", (outcome as ActionOutcome.Refused).code)
        assertEquals(listOf("cancel"), inner.operations)
        assertEquals(0, inner.reads)
    }

    @Test fun failedPointerResultReleasesPreviousGesture() = runTest {
        val screen = TouchScreen().apply { result = ActionResult.Failed("invalid_pointer") }
        val outcome = executor(screen).run(call("move"), Origin.Model(1))
        assertTrue(outcome is ActionOutcome.Ran)
        assertEquals(JsonPrimitive(false), (outcome as ActionOutcome.Ran).exec.content["ok"])
        assertEquals(listOf("move", "cancel"), screen.operations)
    }

    @Test fun pointerExceptionOrCancellationReleasesWithoutAddingAnUpEvent() = runTest {
        for (cancelled in listOf(false, true)) {
            val screen = TouchScreen().apply { onPointer = { op -> if (op != "cancel") {
                if (cancelled) throw CancellationException("cancel") else error("socket disconnected")
            } } }
            try {
                executor(screen).run(call("down"), Origin.Model(1))
                assertFalse("取消应继续抛出", cancelled)
            } catch (_: CancellationException) { assertTrue(cancelled) }
            assertEquals(listOf("down", "cancel"), screen.operations)
        }
    }

    @Test fun cancelledJobStillRunsReleaseInActiveCleanupContext() = runTest {
        val screen = TouchScreen().apply { onPointer = { op -> if (op == "down") {
            currentCoroutineContext().cancel()
            awaitCancellation()
        } } }
        val job = launch { executor(screen).run(call("down"), Origin.Model(1)) }
        job.join()
        assertTrue(job.isCancelled)
        assertEquals(listOf("down", "cancel"), screen.operations)
    }

    @Test fun cancelSpellingCannotReleaseIntoANormalActionOrSkipAccessRules() = runTest {
        val inner = TouchScreen().apply { failReads = true }
        val blocked = AppAccessScreenPort(inner, AppAccessPolicy { AppAccessState(blocked = setOf("bank")) }, packages = { listOf("bank") })
        val outcome = executor(blocked).run(call("CANCEL"), Origin.Model(1))
        assertEquals("app_blocked", (outcome as ActionOutcome.Refused).code)
        assertEquals(listOf("cancel"), inner.operations)
        // 给普通点击塞入 op=cancel 也不能进入释放专用通道。
        val ordinary = executor(blocked).run(Part.ToolCall("tap", "phone_tap", """{"op":"cancel","x":500,"y":500}"""), Origin.Model(2))
        assertEquals("app_blocked", (ordinary as ActionOutcome.Refused).code)
        assertEquals(listOf("cancel"), inner.operations)
    }

    @Test fun pointerGateChecksAllActiveOperationsAndNeverTreatsCancelAsTap() {
        assertTrue(ClassifiedRisk.changesState(Verb.PhoneTouch))
        for (op in listOf("down", "move", "up", "cancel")) {
            val args = Json.parseToJsonElement(call(op).argsJson) as kotlinx.serialization.json.JsonObject
            assertEquals(op != "cancel", IrreversibleDetector.needsCheck(GateTarget.Pointer, args))
            if (op == "cancel") assertNull(IrreversibleDetector.classify(GateTarget.Pointer, args, null))
        }
    }
}
