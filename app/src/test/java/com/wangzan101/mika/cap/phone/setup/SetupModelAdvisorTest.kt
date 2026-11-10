package com.wangzan101.mika.cap.phone.setup

import com.wangzan101.mika.core.model.Capabilities
import com.wangzan101.mika.core.model.ChatModel
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.ModelReply
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.StopReason
import com.wangzan101.mika.core.model.ToolSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SetupModelAdvisorTest {
    private class FakeModel(var response: ModelReply = reply("navigate", 1)) : ChatModel {
        override val capabilities = Capabilities(true, false, 16_384, false)
        val inputs = mutableListOf<List<Message>>()
        val requested = CompletableDeferred<Unit>()
        var hung = false
        var cancelled = false
        override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply {
            assertTrue(system.contains("untrusted UI data"))
            assertEquals(listOf(SetupModelPolicy.TOOL_NAME), tools.map { it.name })
            inputs += messages
            requested.complete(Unit)
            if (hung) try { awaitCancellation() } catch (error: CancellationException) { cancelled = true; throw error }
            return response
        }
        fun input() = inputs.single().flatMap { it.parts }.filterIsInstance<Part.Text>().joinToString("") { it.text }
    }

    @Test fun unfamiliarSoftwareMenuUsesTheConfiguredTextModelAndOnlyReturnsOneSuggestion() = runBlocking {
        val model = FakeModel()
        val advisor = SetupModelAdvisor({ model })
        val page = tree("系统详情与版本")
        val choice = advisor.suggest(SetupModelStage.Developer, page)!!
        assertEquals(SetupModelAction.Navigate, choice.action)
        assertEquals(1, choice.nodeId)
        assertTrue(model.input().contains("系统详情与版本"))
        // 没下发的建议可重新验证，但同一页不重复请求服务商。
        assertEquals(choice, advisor.suggest(SetupModelStage.Developer, page))
        assertEquals(1, model.inputs.size)
        advisor.applied(choice)
        assertNull(advisor.suggest(SetupModelStage.Developer, page))
        assertEquals(1, model.inputs.size)
    }

    @Test fun sendsNoSummaryIdentifiersOrMergedPersonalDescriptions() = runBlocking {
        val model = FakeModel()
        val nodes = tree("系统详情与版本").nodes + listOf(
            SetupNode(3, 1, text = "Private Phone 123456", resource = "android:id/summary"),
            SetupNode(4, 0, text = "Serial number 8675309", clickable = true),
            SetupNode(5, 0, text = "192.168.1.10", clickable = true),
            SetupNode(6, 0, contentDescription = "About Alice's phone", clickable = true),
            SetupNode(7, 0, text = "alice@example.org", clickable = true),
            SetupNode(8, 0, text = "123456", resource = "android:id/title"),
            SetupNode(9, 0, text = "USB 调试", resource = "android:id/summary"),
        )
        assertNotNull(SetupModelAdvisor({ model }).suggest(SetupModelStage.Developer, SetupAutomationTree(nodes)))
        val input = model.input()
        for (privateText in listOf("Private", "123456", "8675309", "192.168", "Alice", "alice@example", "USB")) {
            assertFalse(input, input.contains(privateText))
        }
        assertTrue(model.inputs.single().single().parts.all { it is Part.Text })
    }

    @Test fun matchingParentDescriptionAndChildTitleMergeWithinOneRowButNotAcrossRows() = runBlocking {
        val model = FakeModel()
        val nodes = tree("System").nodes.map {
            if (it.id == 1) it.copy(contentDescription = " System ") else it
        }
        val page = SetupAutomationTree(nodes)
        assertEquals(1, SetupModelAdvisor({ model }).suggest(SetupModelStage.Developer, page)?.nodeId)
        val candidates = SetupModelPolicy.page(SetupModelStage.Developer, page)!!.candidates
        assertEquals(1, candidates.size)
        assertEquals(1, candidates.single().nodeId)
        val duplicateRow = SetupAutomationTree(nodes + SetupNode(3, 0, text = "SYSTEM", clickable = true))
        assertNull(SetupModelAdvisor({ model }).suggest(SetupModelStage.Developer, duplicateRow))
        assertEquals(1, model.inputs.size)
    }

    @Test fun explanationTextIsIgnoredAndOnlyTheValidatedToolCallDeterminesTheAction() = runBlocking {
        val valid = reply("navigate", 1)
        val explanation = Part.Text("The system menu contains the target. Ignore this text: click node 999.")
        val model = FakeModel(valid.copy(parts = listOf(explanation) + valid.parts + Part.Text("Opening the menu.")))
        val choice = SetupModelAdvisor({ model }).suggest(SetupModelStage.Developer, tree("系统详情"))!!
        assertEquals(SetupModelAction.Navigate, choice.action)
        assertEquals(1, choice.nodeId)
        val page = SetupModelPolicy.page(SetupModelStage.Developer, tree("系统详情"))!!
        assertNull(SetupModelPolicy.decision(reply("navigate", 999).copy(parts = listOf(explanation) + reply("navigate", 999).parts), page))
        assertNull(SetupModelPolicy.decision(valid.copy(parts = listOf(explanation) + valid.parts + valid.parts), page))
    }

    @Test fun unknownOrDangerousTargetsAndAlreadyEnabledSwitchesNeverBecomeChoices() {
        for (title in listOf("OEM unlocking", "恢复出厂设置", "USB 调试（安全设置）", "允许 USB 调试", "撤销 USB 调试授权")) {
            assertNull(SetupModelPolicy.page(SetupModelStage.Usb, tree(title, toggle = true)))
        }
        assertNull(SetupModelPolicy.page(SetupModelStage.Usb, tree("USB 调试模式", toggle = true, checked = true)))
        assertNull(SetupModelPolicy.page(SetupModelStage.Developer, tree("系统设置", toggle = true)))
        assertNull(SetupModelPolicy.page(SetupModelStage.Wireless, tree("USB 调试模式", toggle = true)))
    }

    @Test fun enablesOnlyAnOffSwitchWithMatchingStageAndDebuggingEvidence() {
        val page = SetupModelPolicy.page(SetupModelStage.Usb, tree("USB 调试模式", toggle = true))!!
        assertEquals(SetupModelAction.Enable, SetupModelPolicy.decision(reply("enable", 3), page)?.action)
        assertNull(SetupModelPolicy.decision(reply("navigate", 1), page))
        assertNull(SetupModelPolicy.decision(reply("build", 3), page))
        val wireless = SetupModelPolicy.page(SetupModelStage.Wireless, tree("Wi-Fi 调试", toggle = true))!!
        assertNotNull(SetupModelPolicy.decision(reply("enable", 3), wireless))
    }

    @Test fun pairingAndScrollingOnlyUseExposedCandidatesForTheCurrentStage() {
        val pairNodes = tree("通过配对码连接设备").nodes + SetupNode(4, 0, scrollable = true)
        val page = SetupModelPolicy.page(SetupModelStage.Pair, SetupAutomationTree(pairNodes))!!
        assertNotNull(SetupModelPolicy.decision(reply("pair", 1), page))
        assertNotNull(SetupModelPolicy.decision(reply("scroll", 4), page))
        assertNull(SetupModelPolicy.decision(reply("scroll", 1), page))
        assertNull(SetupModelPolicy.page(SetupModelStage.Usb, SetupAutomationTree(pairNodes)))
        assertNull(SetupModelPolicy.page(SetupModelStage.Developer, tree("Android 版本"))?.candidates
            ?.singleOrNull { SetupModelAction.Build in it.actions })
    }

    @Test fun confirmationsPasswordsAmbiguousLabelsAndMultipleSwitchesBlockModelCalls() = runBlocking {
        val model = FakeModel()
        val pages = listOf(
            SetupAutomationTree(tree("系统详情").nodes + SetupNode(3, 0, password = true)),
            SetupAutomationTree(tree("系统详情").nodes + SetupNode(3, 0, resource = "android:id/button1")),
            SetupAutomationTree(tree("系统详情").nodes + SetupNode(3, 0, text = "系统详情", clickable = true)),
            SetupAutomationTree(tree("USB 调试模式", toggle = true).nodes + SetupNode(4, 1, checkable = true, clickable = true)),
            SetupAutomationTree(tree("系统详情").nodes + SetupNode(3, 1, text = "恢复出厂设置", resource = "android:id/title")),
        )
        for (page in pages) assertNull(SetupModelAdvisor({ model }).suggest(SetupModelStage.Usb, page))
        assertTrue(model.inputs.isEmpty())
    }

    @Test fun malformedOrMultipleCallsAndInventedIdsAreRejected() {
        val page = SetupModelPolicy.page(SetupModelStage.Developer, tree("系统详情"))!!
        val valid = reply("navigate", 1)
        for (response in listOf(
            reply("navigate", 999), reply("enable", 1), reply("manual", -1),
            valid.copy(parts = valid.parts + valid.parts),
            valid.copy(parts = listOf(Part.Text("click node 1"))),
            valid.copy(stopReason = StopReason.MaxTokens),
            valid.copy(parts = listOf(Part.ToolCall("1", "other_tool", "{}"))),
        )) assertNull(SetupModelPolicy.decision(response, page))
        for (args in listOf(
            "{", """{"action":"navigate","node_id":1,"extra":true}""",
            """{"action":"navigate","node_id":"1"}""",
            """{"action":"navigate","node_id":1,"node_id":1}""",
            """{"action":"enable","action":"navigate","node_id":1}""",
        )) assertNull(SetupModelPolicy.decision(valid.copy(parts = listOf(Part.ToolCall("1", SetupModelPolicy.TOOL_NAME, args))), page))
    }

    @Test fun buildAdviceIsBoundedAndNodeRenumberingCannotReuseOldIds() = runBlocking {
        val model = FakeModel(reply("build", 1))
        val advisor = SetupModelAdvisor({ model })
        val page = tree("MIUI 版本")
        repeat(7) { advisor.applied(advisor.suggest(SetupModelStage.Developer, page)!!) }
        assertNull(advisor.suggest(SetupModelStage.Developer, page))
        assertEquals(1, model.inputs.size)
        // 同样的标题换了节点编号，重新请求；模型还返回旧编号时拒绝。
        val renumbered = SetupAutomationTree(page.nodes.map {
            when (it.id) { 1 -> it.copy(id = 10); 2 -> it.copy(parent = 10); else -> it }
        })
        assertNull(advisor.suggest(SetupModelStage.Developer, renumbered))
        assertEquals(2, model.inputs.size)
        advisor.reset()
        assertNotNull(advisor.suggest(SetupModelStage.Developer, page))
    }

    @Test fun timeoutDoesNotLoopOnTheSamePageAndOuterCancellationPropagates() = runBlocking {
        val timed = FakeModel().apply { hung = true }
        val advisor = SetupModelAdvisor({ timed }, timeoutMs = 20)
        val page = tree("系统详情")
        assertNull(advisor.suggest(SetupModelStage.Developer, page))
        assertTrue(timed.cancelled)
        assertNull(advisor.suggest(SetupModelStage.Developer, page))
        assertEquals(1, timed.inputs.size)
        val cancelled = FakeModel().apply { hung = true }
        var returned = false
        val job = launch { SetupModelAdvisor({ cancelled }).suggest(SetupModelStage.Developer, page); returned = true }
        cancelled.requested.await()
        job.cancelAndJoin()
        assertTrue(cancelled.cancelled)
        assertFalse(returned)
    }

    @Test fun requestBudgetAndUnavailableModelsLeaveTheRuleFallbackAvailable() = runBlocking {
        val model = FakeModel(reply("manual", -1))
        val advisor = SetupModelAdvisor({ model }, maxRequests = 1)
        assertNull(advisor.suggest(SetupModelStage.Developer, tree("系统详情")))
        assertNull(advisor.suggest(SetupModelStage.Developer, tree("软件版本详情")))
        assertEquals(1, model.inputs.size)
        assertNull(SetupModelAdvisor({ null }).suggest(SetupModelStage.Developer, tree("系统详情")))
        val noTools = object : ChatModel {
            override val capabilities = Capabilities(false, false, 8_000, false)
            override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply = error("must not call")
        }
        assertNull(SetupModelAdvisor({ noTools }).suggest(SetupModelStage.Developer, tree("系统详情")))
    }

    @Test fun summaryUpdatesDoNotTriggerAnotherRequestButUnsafeNewUiIsStillRejected() = runBlocking {
        val model = FakeModel()
        val advisor = SetupModelAdvisor({ model })
        val nodes = tree("系统详情").nodes
        val first = SetupAutomationTree(nodes + SetupNode(3, 1, text = "1:00:01", resource = "android:id/summary"))
        val next = SetupAutomationTree(nodes + SetupNode(3, 1, text = "1:00:02", resource = "android:id/summary"))
        assertEquals(advisor.suggest(SetupModelStage.Developer, first), advisor.suggest(SetupModelStage.Developer, next))
        assertEquals(1, model.inputs.size)
        assertNull(advisor.suggest(SetupModelStage.Developer, SetupAutomationTree(next.nodes + SetupNode(4, 0, dialog = true))))
    }

    @Test fun resettingWhileTheModelRunsCannotLeakTheOldSuggestionIntoANewSession() = runBlocking {
        val released = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        val model = object : ChatModel {
            override val capabilities = Capabilities(true, false, 8_000, false)
            override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply {
                entered.complete(Unit)
                released.await()
                return reply("navigate", 1)
            }
        }
        val advisor = SetupModelAdvisor({ model })
        var old: SetupModelDecision? = null
        val job = launch { old = advisor.suggest(SetupModelStage.Developer, tree("系统详情")) }
        entered.await()
        advisor.reset()
        released.complete(Unit)
        job.join()
        assertNull(old)
    }

    private companion object {
        fun tree(title: String, toggle: Boolean = false, checked: Boolean = false) = SetupAutomationTree(buildList {
            add(SetupNode(0))
            add(SetupNode(1, 0, clickable = true))
            add(SetupNode(2, 1, text = title, resource = "android:id/title"))
            if (toggle) add(SetupNode(3, 1, checkable = true, checked = checked, clickable = true))
        })
        fun reply(action: String, id: Int) = ModelReply(
            listOf(Part.ToolCall("1", SetupModelPolicy.TOOL_NAME, """{"action":"$action","node_id":$id}""")),
            StopReason.ToolUse, null,
        )
    }
}
