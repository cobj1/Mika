package com.wangzan101.mika.cap.phone.setup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SetupAutomationTreeTest {
    private fun usb(checked: Boolean = false) = listOf(
        SetupNode(0),
        SetupNode(1, 0, clickable = true),
        SetupNode(2, 1, text = "USB 调试"),
        SetupNode(3, 1, checkable = true, checked = checked, clickable = true),
    )

    @Test fun enablesOnlyTheSwitchInTheExactRow() {
        val nodes = usb() + listOf(
            SetupNode(4, 0, clickable = true),
            SetupNode(5, 4, text = "USB 调试（安全设置）"),
            SetupNode(6, 4, checkable = true, clickable = true),
        )
        assertEquals(3, SetupAutomationTree(nodes).turnOn(SetupTarget.Usb))
        assertNull(SetupAutomationTree(nodes.filter { it.id !in 1..3 }).turnOn(SetupTarget.Usb))
    }

    @Test fun neverTurnsAnEnabledSwitchOff() {
        assertNull(SetupAutomationTree(usb(checked = true)).turnOn(SetupTarget.Usb))
    }

    @Test fun duplicateLabelsOrSwitchesRequireManualSetup() {
        assertNull(SetupAutomationTree(usb() + SetupNode(4, 1, text = "USB 调试")).turnOn(SetupTarget.Usb))
        assertNull(SetupAutomationTree(usb() + SetupNode(4, 1, checkable = true, clickable = true)).turnOn(SetupTarget.Usb))
    }

    @Test fun dialogsPasswordsAndOtherAppsBlockEveryAction() {
        val blockers = listOf(
            SetupNode(4, 0, resource = "android:id/button1", clickable = true),
            SetupNode(4, 0, password = true),
            SetupNode(4, 0, editable = true),
            SetupNode(4, 0, dialog = true),
            SetupNode(4, 0, text = "允许", clickable = true),
            SetupNode(4, 0, contentDescription = "允许", clickable = true),
        )
        for (blocker in blockers) {
            val tree = SetupAutomationTree(usb() + blocker)
            assertTrue(tree.blocked)
            assertNull(tree.turnOn(SetupTarget.Usb))
            assertNull(tree.click(SetupTarget.Usb))
            assertNull(tree.scroll())
        }
        assertNull(SetupAutomationTree(usb(), trusted = false).turnOn(SetupTarget.Usb))
    }

    @Test fun unfocusedSettingsSearchDoesNotBlockKnownSettingRows() {
        val tree = SetupAutomationTree(usb() + SetupNode(
            4, 0, resource = "com.android.settings:id/search_src_text", editable = true,
        ))
        assertEquals(3, tree.turnOn(SetupTarget.Usb))
    }

    @Test fun searchExceptionNeverBypassesUserInputPasswordsOrConfirmations() {
        val search = SetupNode(4, 0, resource = "com.android.settings:id/search_src_text", editable = true)
        for (input in listOf(
            search.copy(focused = true),
            search.copy(password = true),
            search.copy(resource = "com.android.settings:id/unknown_input"),
        )) {
            assertTrue(SetupAutomationTree(usb() + input).blocked)
        }
        val confirmation = SetupNode(5, 0, resource = "android:id/button1", clickable = true)
        assertTrue(SetupAutomationTree(usb() + search + confirmation).blocked)
        assertTrue(SetupAutomationTree(usb() + search, trusted = false).blocked)
    }

    @Test fun searchHintCannotBecomeAnAutomationTarget() {
        val search = SetupNode(
            4, 0, contentDescription = "USB debugging", resource = "android:id/search_src_text",
            editable = true, clickable = true,
        )
        assertEquals(3, SetupAutomationTree(usb() + search).turnOn(SetupTarget.Usb))
        assertNull(SetupAutomationTree(listOf(SetupNode(0), search)).click(SetupTarget.Usb))
    }

    @Test fun contentDescriptionLocatesRowsWhoseTextIsEmpty() {
        val nodes = usb().map { if (it.id == 2) it.copy(text = "", contentDescription = "USB debugging") else it }
        assertEquals(3, SetupAutomationTree(nodes).turnOn(SetupTarget.Usb))
        val pairing = listOf(
            SetupNode(0), SetupNode(1, 0, clickable = true, contentDescription = "使用配对码配对设备"),
        )
        assertEquals(1, SetupAutomationTree(pairing).click(SetupTarget.Pair))
    }

    @Test fun contentDescriptionsStillRequireUniqueExactTargets() {
        val duplicate = SetupNode(4, 0, contentDescription = "USB debugging")
        assertNull(SetupAutomationTree(usb() + duplicate).turnOn(SetupTarget.Usb))
        val safetySetting = usb().map {
            if (it.id == 2) it.copy(text = "", contentDescription = "USB 调试（安全设置）") else it
        }
        assertNull(SetupAutomationTree(safetySetting).turnOn(SetupTarget.Usb))
    }

    @Test fun hiddenOrDisabledControlsAreNotClicked() {
        assertNull(SetupAutomationTree(usb().map { if (it.id == 2) it.copy(visible = false) else it }).turnOn(SetupTarget.Usb))
        assertNull(SetupAutomationTree(usb().map { if (it.id == 3) it.copy(enabled = false) else it }).turnOn(SetupTarget.Usb))
    }

    @Test fun doesNotBorrowSwitchFromAnotherRowOrClickWholePage() {
        val tree = SetupAutomationTree(listOf(
            SetupNode(0, clickable = true),
            SetupNode(1, 0, text = "USB 调试"),
            SetupNode(2, 0, checkable = true, clickable = true),
        ))
        assertNull(tree.turnOn(SetupTarget.Usb))
        assertNull(tree.click(SetupTarget.Usb))
    }

    @Test fun supportsEnglishAndChineseLabelsWithSpacing() {
        for (text in listOf("USB debugging", "USB 調試", "USB 偵錯", "USB调试")) {
            val tree = SetupAutomationTree(usb().map { if (it.id == 2) it.copy(text = text) else it })
            assertEquals(3, tree.turnOn(SetupTarget.Usb))
        }
    }

    @Test fun neverCrossesPreferenceRowToFindAnotherSwitch() {
        val nodes = listOf(
            SetupNode(0), SetupNode(1, 0),
            SetupNode(2, 1, clickable = true), SetupNode(3, 2, text = "USB debugging"),
            SetupNode(4, 1, clickable = true), SetupNode(5, 4, text = "OEM unlocking"),
            SetupNode(6, 4, checkable = true, clickable = true),
        )
        assertNull(SetupAutomationTree(nodes).turnOn(SetupTarget.Usb))
        // 即便系统把列表整体标为可点，也不能越过滚动容器寻找操作对象。
        assertNull(SetupAutomationTree(nodes.map {
            when (it.id) { 1 -> it.copy(scrollable = true, clickable = true); 2 -> it.copy(clickable = false); else -> it }
        }).turnOn(SetupTarget.Usb))
    }

    @Test fun opensOnlyThePairingRow() {
        val tree = SetupAutomationTree(listOf(
            SetupNode(0), SetupNode(1, 0, clickable = true),
            SetupNode(2, 1, text = "Pair device with pairing code"),
            SetupNode(3, 0, text = "Other setting", clickable = true),
        ))
        assertEquals(1, tree.click(SetupTarget.Pair))
        assertNull(tree.click(SetupTarget.BuildNumber))
    }

    @Test fun recognizesMumuBuildNumberWithoutMatchingVersionSummaries() {
        for (label in listOf("Build 号", "Build號", "Build number", "版本号")) {
            val tree = SetupAutomationTree(listOf(
                SetupNode(0), SetupNode(1, 0, clickable = true),
                SetupNode(2, 1, text = label), SetupNode(3, 1, text = "V417IR release-keys"),
                SetupNode(4, 0, text = "正常运行时间"), SetupNode(5, 0, text = "1:06:39"),
            ))
            assertEquals(1, tree.click(SetupTarget.BuildNumber))
        }
        assertNull(SetupAutomationTree(listOf(
            SetupNode(0), SetupNode(1, 0, text = "Build 号说明", clickable = true),
        )).click(SetupTarget.BuildNumber))
    }

    @Test fun wirelessDetailNeedsTitlePairingEntryAndUniqueSwitch() {
        val nodes = listOf(
            SetupNode(0), SetupNode(1, 0, text = "Wireless debugging"),
            SetupNode(2, 0, text = "Pair device with pairing code"),
            SetupNode(3, 0, checkable = true, clickable = true),
        )
        assertEquals(3, SetupAutomationTree(nodes).wirelessPageSwitch())
        val disabledPair = SetupAutomationTree(nodes.map { if (it.id == 2) it.copy(enabled = false) else it })
        assertEquals(3, disabledPair.wirelessPageSwitch())
        assertNull(disabledPair.click(SetupTarget.Pair))
        assertNull(SetupAutomationTree(nodes.filter { it.id != 2 }).wirelessPageSwitch())
        assertNull(SetupAutomationTree(nodes + SetupNode(4, 0, checkable = true)).wirelessPageSwitch())
    }

    // MuMu 关于本机实际包含两层滚动控件，中间经过多个普通布局节点。
    private fun mumuScrollContainers() = listOf(
        SetupNode(0),
        SetupNode(3, 0, resource = "com.android.settings:id/content_parent", scrollable = true),
        SetupNode(5, 3), SetupNode(7, 5), SetupNode(9, 7), SetupNode(14, 9), SetupNode(15, 14),
        SetupNode(16, 15, resource = "com.android.settings:id/recycler_view", scrollable = true),
    )

    @Test fun mumuNestedScrollContainersUseTheInnerList() {
        val nodes = mumuScrollContainers()
        assertEquals(16, SetupAutomationTree(nodes).scroll())
        // 遍历顺序不决定滚谁，只由祖先关系决定。
        assertEquals(16, SetupAutomationTree(nodes.reversed()).scroll())
    }

    @Test fun parallelScrollAreasRemainAmbiguousEvenWithACommonScrollableParent() {
        val nodes = mumuScrollContainers()
        assertNull(SetupAutomationTree(nodes + SetupNode(17, 15, scrollable = true)).scroll())
        assertNull(SetupAutomationTree(nodes + SetupNode(17, 0, scrollable = true)).scroll())
    }

    @Test fun hiddenOrDisabledInnerListDoesNotHideTheEligibleOuterScrollArea() {
        val nodes = mumuScrollContainers()
        for (inner in listOf(nodes.last().copy(visible = false), nodes.last().copy(enabled = false))) {
            assertEquals(3, SetupAutomationTree(nodes.dropLast(1) + inner).scroll())
            assertNull(SetupAutomationTree(nodes.dropLast(1).map {
                if (it.id == 3) it.copy(enabled = false) else it
            } + inner).scroll())
        }
    }
}
