package com.wangzan101.mika.cap.phone.setup

import com.wangzan101.mika.core.uistate.VisibleTextLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class SetupAutomationUsbOcrTest {
    private fun line(text: String, left: Int, top: Int, right: Int, bottom: Int) =
        VisibleTextLine(text, listOf(left, top, right, bottom))

    private val header = line("开发者选项", 80, 40, 320, 70)
    private val master = line("启用开发者选项", 50, 145, 400, 175)
    private val memory = line("内存", 50, 240, 150, 265)
    private val bugReport = line("错误报告", 50, 360, 250, 385)
    private val stayAwake = line("保持唤醒状态", 50, 480, 350, 505)
    private val usb = line("USB 调试", 50, 930, 223, 953)
    private val listing get() = listOf(header, master, memory, bugReport, stayAwake)
    private val page get() = listing + usb

    @Test fun findsCompleteUsbTitleNearTheBottomWithoutRequiringItsSummary() {
        assertEquals(SetupAutomationUsbOcr.Action.Tap(136, 941), SetupAutomationUsbOcr.choose(page))
    }

    @Test fun android15LandscapeUsbRowUsesTheVisibleTilesAndGrammarSettingsAsContext() {
        val landscape = listOf(line("开发者选项", 88, 87, 206, 128),
            line("启用开发者选项", 52, 263, 215, 304),
            line("快捷设置开发者功能块", 29, 466, 260, 505),
            line("语法性别", 29, 594, 120, 633), line("调试", 29, 779, 60, 807),
            line("USB 调试", 29, 878, 124, 916))
        assertEquals(SetupAutomationUsbOcr.Action.Tap(76, 897), SetupAutomationUsbOcr.stable(landscape, landscape))
        val debugSectionOnly = landscape.filterNot { it.text in setOf("快捷设置开发者功能块", "语法性别") }
        assertEquals(SetupAutomationUsbOcr.Action.Tap(76, 897), SetupAutomationUsbOcr.stable(debugSectionOnly, debugSectionOnly))
        assertNull(SetupAutomationUsbOcr.choose(debugSectionOnly.filterNot { it.text == "调试" }))
    }

    @Test fun supportsEnglishAndTraditionalTitlesWithTheSameNormalizedCoordinates() {
        val english = listOf("Developer options", "Use developer options", "Memory", "Bug report", "Stay awake", "USB debugging")
        val traditional = listOf("開發人員選項", "使用開發人員選項", "記憶體", "錯誤報告", "保持清醒", "USB 偵錯")
        for (words in listOf(english, traditional)) {
            assertEquals(SetupAutomationUsbOcr.Action.Tap(136, 941),
                SetupAutomationUsbOcr.choose(page.mapIndexed { i, line -> line.copy(text = words[i]) }))
        }
    }

    @Test fun toleratesObservedHeaderGlyphErrorsWithoutFuzzingTheUsbTarget() {
        for (title in listOf("开发老选项", "开发考选项")) {
            val recognized = page - header + header.copy(text = title)
            assertEquals(SetupAutomationUsbOcr.Action.Tap(136, 941), SetupAutomationUsbOcr.stable(recognized, recognized))
            assertNull(SetupAutomationUsbOcr.choose(recognized - master))
            assertFalse(SetupAutomationUsbOcr.choose(recognized - usb + usb.copy(text = "USB 调式")) is SetupAutomationUsbOcr.Action.Tap)
            assertNull(SetupAutomationUsbOcr.choose(recognized + line("取消", 500, 600, 600, 630)))
        }
        assertNull(SetupAutomationUsbOcr.choose(page - header + header.copy(text = "开发设置")))
    }

    @Test fun requiresDeveloperHeaderMasterAndListContext() {
        assertNull(SetupAutomationUsbOcr.choose(page - header))
        assertNull(SetupAutomationUsbOcr.choose(page - master))
        assertNull(SetupAutomationUsbOcr.choose(listOf(header, master, usb)))
        assertNull(SetupAutomationUsbOcr.choose(page - header + header.copy(bounds = listOf(80, 500, 320, 530))))
        assertNull(SetupAutomationUsbOcr.choose(page - master + master.copy(bounds = listOf(50, 10, 400, 35))))
    }

    @Test fun deduplicatesOnlyTheSameTextInOverlappingOcrBoxes() {
        val duplicate = line("USB调试", 52, 931, 222, 953)
        assertEquals(SetupAutomationUsbOcr.Action.Tap(136, 941), SetupAutomationUsbOcr.choose(page + duplicate))
        assertNull(SetupAutomationUsbOcr.choose(page + line("USB 调试", 50, 700, 223, 723)))
        assertNull(SetupAutomationUsbOcr.choose(page + line("USB 调试", 250, 930, 423, 953)))
    }

    @Test fun rejectsSecuritySettingLookalikesAndSplitTargetLines() {
        for (text in listOf("USB 调试（安全设置）", "USB debugging (Security settings)", "USB 调试设置")) {
            assertNull(SetupAutomationUsbOcr.choose(listing + usb.copy(text = text)))
        }
        assertNull(SetupAutomationUsbOcr.choose(listing +
            usb.copy(text = "USB") + line("调试", 50, 955, 150, 975)))
        assertNull(SetupAutomationUsbOcr.choose(page + line("（安全设置）", 230, 930, 430, 953)))
        assertNull(SetupAutomationUsbOcr.choose(page + line("Security settings", 50, 955, 430, 975)))
    }

    @Test fun refusesCroppedOrMalformedBoundsInsteadOfGuessing() {
        for (box in listOf(listOf(0, 930, 223, 953), listOf(50, 930, 999, 953), listOf(50, 960, 223, 990),
            listOf(50, 930, 223, 932), listOf(50, 930, 223), listOf(50, 953, 223, 930), listOf(-1, 930, 223, 953))) {
            assertNull(SetupAutomationUsbOcr.choose(listing + usb.copy(bounds = box)))
        }
        assertNull(SetupAutomationUsbOcr.choose(page + line("", 0, 0, 0, 0).copy(text = "确定")))
    }

    @Test fun rejectsConfirmationsAndAuthenticationIncludingNewlyAppearingButtons() {
        for (text in listOf("允许", "取消", "确定", "Allow", "Cancel", "OK", "请输入密码", "Enter PIN", "是否允许 USB 调试？", "RSA key fingerprint")) {
            val modal = page + line(text, 300, 500, 700, 530)
            assertNull(SetupAutomationUsbOcr.choose(modal))
            assertNull(SetupAutomationUsbOcr.stable(page, modal))
        }
        // 正常列表的「桌面备份密码」是设置入口，不等于正在输入密码。
        assertEquals(SetupAutomationUsbOcr.Action.Tap(136, 941), SetupAutomationUsbOcr.choose(page +
            line("桌面备份密码", 50, 580, 350, 605)))
    }

    @Test fun scrollRequiresSeveralKnownAlignedSettingsAndNoTarget() {
        assertEquals(SetupAutomationUsbOcr.Action.ScrollForward, SetupAutomationUsbOcr.choose(listing))
        assertNull(SetupAutomationUsbOcr.choose(listing - stayAwake))
        assertNull(SetupAutomationUsbOcr.choose(listing - stayAwake + stayAwake.copy(bounds = listOf(450, 480, 750, 505))))
        assertNull(SetupAutomationUsbOcr.choose(listing - stayAwake + stayAwake.copy(text = "未知菜单")))
    }

    @Test fun stableFramesTolerateSmallOcrJitterButRejectMovementAndAddedContent() {
        val smallShift = page.map { it.copy(bounds = it.bounds.map { n -> n + 3 }) }
        assertEquals(SetupAutomationUsbOcr.Action.Tap(139, 944), SetupAutomationUsbOcr.stable(page, smallShift))
        val moved = page.map { it.copy(bounds = it.bounds.map { n -> n + 20 }) }
        assertNull(SetupAutomationUsbOcr.stable(page, moved))
        assertNull(SetupAutomationUsbOcr.stable(page, page + line("新增内容", 300, 600, 500, 620)))
        assertNull(SetupAutomationUsbOcr.stable(page, listing))
        assertEquals(SetupAutomationUsbOcr.Action.ScrollForward, SetupAutomationUsbOcr.stable(listing, listing.reversed()))
    }

    @Test fun statusBarClockChangesDoNotInvalidateAnOtherwiseStablePage() {
        val first = page + line("12:30", 20, 12, 100, 28)
        val second = page + line("12:31", 20, 12, 100, 28)
        assertEquals(SetupAutomationUsbOcr.Action.Tap(136, 941), SetupAutomationUsbOcr.stable(first, second))
    }
}
