package com.wangzan101.mika.cap.phone.setup

import com.wangzan101.mika.core.uistate.VisibleTextLine
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * USB 调试的本地 OCR 定位规则。坐标沿用 VisibleTextLine 的 0..999 范围。
 * 只给已确认身份的开发者列表提供一次标题点击或向前滚动建议；窗口身份、锁屏、
 * 系统开关、截图时序和实际动作前的再次核验由 Android 执行器负责。
 */
internal object SetupAutomationUsbOcr {
    sealed interface Action {
        /** 点击标题自身，不根据文字位置猜测开关的位置。 */
        data class Tap(val x: Int, val y: Int) : Action
        data object ScrollForward : Action
    }

    private data class Line(val text: String, val box: List<Int>) {
        val left get() = box[0]
        val top get() = box[1]
        val right get() = box[2]
        val bottom get() = box[3]
        val x get() = (left + right) / 2
        val y get() = (top + bottom) / 2
        val area get() = (right - left) * (bottom - top)
    }

    private data class Page(val lines: List<Line>, val action: Action)

    fun choose(lines: List<VisibleTextLine>): Action? = page(lines)?.action

    /** 两次都必须完整通过规则；确认按钮、新出现文字或列表移动都会使旧位置作废。 */
    fun stable(first: List<VisibleTextLine>, second: List<VisibleTextLine>): Action? {
        val before = page(first) ?: return null
        val after = page(second) ?: return null
        if (before.lines.size != after.lines.size) return null
        val remaining = after.lines.toMutableList()
        for (line in before.lines) {
            val matches = remaining.filter { it.text == line.text && close(it.box, line.box, STABLE_TOLERANCE) }
            val match = matches.singleOrNull() ?: return null
            remaining.remove(match)
        }
        return when (val action = after.action) {
            is Action.Tap -> (before.action as? Action.Tap)?.takeIf {
                abs(it.x - action.x) <= STABLE_TOLERANCE && abs(it.y - action.y) <= STABLE_TOLERANCE
            }?.let { action }
            Action.ScrollForward -> action.takeIf { before.action == Action.ScrollForward }
        }
    }

    private fun page(raw: List<VisibleTextLine>): Page? {
        if (raw.isEmpty() || raw.size > MAX_LINES) return null
        val lines = mutableListOf<Line>()
        for (item in raw) {
            val text = normalized(item.text)
            if (text.isEmpty()) continue
            // 坏框不能证明不包含确认内容；不要悄悄丢掉后继续点击。
            if (item.bounds.size != 4 || item.bounds.any { it !in 0..999 } ||
                item.bounds[0] >= item.bounds[2] || item.bounds[1] >= item.bounds[3]) return null
            if (blocked(text)) return null
            val line = Line(text, item.bounds)
            // 中英两套识别器可能返回同一个标题，只合并高度重叠的同文字框。
            val duplicates = lines.filter { it.text == text && overlap(it, line) }
            if (duplicates.size > 1) return null
            val duplicate = duplicates.singleOrNull()
            if (duplicate == null) lines += line else {
                lines.remove(duplicate)
                lines += Line(text, listOf(min(line.left, duplicate.left), min(line.top, duplicate.top),
                    max(line.right, duplicate.right), max(line.bottom, duplicate.bottom)))
            }
        }
        val header = lines.filter { it.text in HEADERS || it.text in HEADER_OCR_VARIANTS }.singleOrNull() ?: return null
        if (header.top < 10 || header.bottom > 240 || !complete(header)) return null
        val master = lines.filter { it.text in MASTER_TITLES }.singleOrNull() ?: return null
        if (!complete(master) || master.top <= header.bottom || master.bottom > 390) return null
        val body = lines.filter { it.top > master.bottom + 8 }
        val anchors = body.filter { it.text in LIST_TITLES && complete(it) }
        if (anchors.isEmpty()) return null
        // 多列、明显不对齐的内容不能证明是同一开发者设置列表。
        if (anchors.maxOf { it.left } - anchors.minOf { it.left } > 100) return null
        val targets = lines.filter { it.text in USB_TITLES }
        val action = if (targets.isNotEmpty()) {
            val target = targets.singleOrNull() ?: return null
            // Android 15 横屏可能只显示「调试」分组和 USB 行；分组标题紧邻其上同样是明确上下文。
            val inDebugSection = anchors.any { it.text in DEBUG_SECTIONS && it.bottom < target.top && target.top - it.bottom <= 200 }
            if (anchors.map { it.text }.distinct().size < 2 && !inDebugSection) return null
            if (!complete(target) || target.top <= master.bottom + 8 ||
                abs(target.left - anchors.map { it.left }.sorted()[anchors.size / 2]) > 100) return null
            Action.Tap(target.x, target.y)
        } else {
            // 类似「USB 调试（安全设置）」的标题不作为目标，也不能借其存在盲滚。
            // 独立 USB 可能是被 OCR 拆成两行的目标，无法证明整行位置时也交回手动。
            if (body.any { line -> line.text == "usb" || USB_TITLES.any { line.text.startsWith(it) } }) return null
            // 没找到目标时，仅在仍看见三个明确的设置条目时允许执行器有限滚动。
            if (anchors.map { it.text }.distinct().size < 3) return null
            Action.ScrollForward
        }
        // 状态栏的时钟、电量变化不影响页面稳定性；标题以下任何变化都重新识别。
        return Page(lines.filter { it.bottom >= header.top }, action)
    }

    private fun complete(line: Line): Boolean = line.left >= 10 && line.right <= 989 &&
        line.top >= 10 && line.bottom <= 975 && line.right - line.left >= 20 &&
        line.bottom - line.top in 6..90

    private fun close(a: List<Int>, b: List<Int>, tolerance: Int) = a.indices.all { abs(a[it] - b[it]) <= tolerance }

    private fun overlap(a: Line, b: Line): Boolean {
        val area = max(0, min(a.right, b.right) - max(a.left, b.left)) *
            max(0, min(a.bottom, b.bottom) - max(a.top, b.top))
        return area * 100L >= min(a.area, b.area) * 80L && abs(a.x - b.x) <= 12 && abs(a.y - b.y) <= 12
    }

    private fun normalized(text: String) = text.lowercase(Locale.ROOT).filterNot(Char::isWhitespace)

    private fun blocked(text: String): Boolean = text in CONFIRMATION_WORDS ||
        CONFIRMATION_PHRASES.any(text::contains) ||
        listOf("安全设置", "安全設置", "安全設定", "securitysettings").any(text::contains) ||
        ((text.contains("usb") && (text.contains("调试") || text.contains("調試") || text.contains("偵錯") || text.contains("debugging"))) &&
            (text.contains('?') || text.contains('？')))

    private const val MAX_LINES = 180
    private const val STABLE_TOLERANCE = 6
    private val HEADERS = setOf("开发者选项", "開發者選項", "開發人員選項", "developeroptions")
    // MuMu 的窗口截图中，内置 OCR 会将标题的「者」误识为「老/考」。仅容错非操作标题，
    // 仍须精确匹配总开关、列表上下文和 USB 目标，并由执行器独立核对系统设置窗口。
    private val HEADER_OCR_VARIANTS = setOf("开发老选项", "开发考选项")
    private val MASTER_TITLES = setOf("启用开发者选项", "开启开发者选项", "使用开发者选项", "啟用開發者選項", "開啟開發者選項",
        "使用開發者選項", "啟用開發人員選項", "開啟開發人員選項", "使用開發人員選項", "usedeveloperoptions")
    private val USB_TITLES = SetupTarget.Usb.labels.map(::normalized).toSet()
    private val DEBUG_SECTIONS = setOf("调试", "偵錯", "debugging")
    private val LIST_TITLES = setOf(
        "内存", "記憶體", "memory", "错误报告", "錯誤報告", "bugreport", "获取错误报告", "擷取錯誤報告", "takebugreport",
        "桌面备份密码", "桌面備份密碼", "desktopbackuppassword", "保持唤醒状态", "保持喚醒狀態", "保持清醒", "stayawake",
        "启用蓝牙hci信息收集日志", "啟用藍牙hci封包記錄", "enablebluetoothhcisnooplog", "oem解锁", "oem解鎖", "oemunlocking",
        "正在运行的服务", "執行中的服務", "runningservices", "图片颜色模式", "圖片色彩模式", "picturecolormode",
        "系统自动更新", "系統自動更新", "automaticsystemupdates", "演示模式", "示範模式", "demomode",
        "webview实现", "webview實作", "webview實現", "webviewimplementation", "dsuloader", "系统界面演示模式",
        "快捷设置开发者图块", "快速設定開發人員圖塊", "quicksettingsdevelopertiles", "调试应用", "偵錯應用程式", "selectdebugapp",
        "快捷设置开发者功能块", "语法性别", "文法性別", "grammaticalgender", "调试", "偵錯", "debugging",
        "等待调试器", "等待偵錯工具", "waitfordebugger", "验证通过usb安装的应用", "透過usb驗證應用程式", "verifyappsoverusb",
        "无线调试", "無線偵錯", "wirelessdebugging", "撤销usb调试授权", "撤銷usb偵錯授權", "revokeusbdebuggingauthorizations",
    )
    private val CONFIRMATION_WORDS = setOf("允许", "允許", "确定", "確定", "取消", "同意", "继续", "繼續", "接受", "拒绝", "拒絕",
        "allow", "ok", "cancel", "agree", "continue", "accept", "deny", "password", "密码", "密碼", "pin")
    private val CONFIRMATION_PHRASES = setOf("是否允许", "是否允許", "允许usb调试", "允許usb偵錯", "允許usb調試", "allowusbdebugging",
        "是否启用", "是否啟用", "useusbdebugging", "输入密码", "輸入密碼", "请输入", "請輸入", "enterpassword", "enterpin",
        "确认密码", "確認密碼", "confirmpassword", "验证身份", "驗證身分", "verifyyouridentity", "rsa", "密钥指纹", "金鑰指紋", "fingerprint")
}
