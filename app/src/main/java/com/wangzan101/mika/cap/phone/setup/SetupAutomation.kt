package com.wangzan101.mika.cap.phone.setup

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import android.view.Display
import android.view.Gravity
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.wangzan101.mika.R
import com.wangzan101.mika.cap.phone.a11y.OverlayPlacement
import com.wangzan101.mika.cap.phone.a11y.OverlayWindow
import com.wangzan101.mika.cap.phone.a11y.ScreenAccessibility
import com.wangzan101.mika.cap.phone.a11y.SystemPickers
import com.wangzan101.mika.core.shell.BuiltinShellControl
import com.wangzan101.mika.core.shell.BuiltinShellStatus
import com.wangzan101.mika.ui.SetupAutomationOverlay
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

data class SetupAutomationState(val active: Boolean = false, val message: Int = R.string.setup_auto_intro)

/**
 * 用户点「自动设置」才开始的一次性会话。无障碍首次授权仍由用户完成，服务连上后继续。
 * 只在系统设置主屏的当前窗口执行已知动作；不读密码、不确认授权、不写受保护的设置。
 * 生命周期属于 PhoneServices：离开 Mika 去系统设置不会取消，停止/超时/进程结束后不自动重启。
 */
class SetupAutomation(
    context: Context,
    private val control: BuiltinShellControl,
    private val pairing: PairingCoordinator,
) {
    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutableState = MutableStateFlow(SetupAutomationState())
    val state: StateFlow<SetupAutomationState> = mutableState.asStateFlow()
    private var job: Job? = null
    private var overlay: OverlayWindow? = null
    private var overlayService: ScreenAccessibility? = null
    private var ownsPairing = false
    private var session = 0L
    private val settingsPackage by lazy { SystemPickers.settingsPackage(app.packageManager) }

    fun start() {
        if (job?.isActive == true) return
        val currentSession = ++session
        update(R.string.setup_auto_enable_accessibility)
        job = scope.launch {
            var completed = false
            try {
                completed = withTimeoutOrNull(SESSION_MS) { runSetup() } == true
                mutableState.value = SetupAutomationState(message = if (completed) R.string.setup_auto_done else R.string.setup_auto_manual)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // 设置树、系统页可能被厂商限制；停止并交回手动流程，不盲点或反复尝试。
                mutableState.value = SetupAutomationState(message = R.string.setup_auto_manual)
            } finally {
                // 停止后可以立即重试；旧协程的清理不能拆掉新会话的浮层或配对观察。
                if (currentSession == session) {
                    hideOverlay()
                    if (!completed) endWaitingForPairing()
                    ownsPairing = false
                }
            }
        }
    }

    fun stop() {
        if (job?.isActive != true) return
        session++
        job?.cancel()
        job = null
        hideOverlay()
        endWaitingForPairing()
        ownsPairing = false
        mutableState.value = SetupAutomationState(message = R.string.setup_auto_stopped)
    }

    private fun endWaitingForPairing() {
        // 已经开始的握手由原有 coordinator 完成；中途取消会让引擎停在 Connecting。
        // 停止意味着立即不再操作设置界面，不撤销刚刚确认的配对。
        if (ownsPairing && control.status.value != BuiltinShellStatus.Connecting) pairing.end()
    }

    private suspend fun runSetup(): Boolean {
        if (settingsPackage == null) return false
        if (ScreenAccessibility.instance.value == null) {
            if (!open(listOf(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)))) return false
            while (ScreenAccessibility.instance.value == null) delay(POLL_MS)
            // 等用户授权后的设置页稳定，不去操作刚出现的权限窗口。
            delay(1_000)
        }
        // 助手可能已由 USB/root 启动；普通解锁只确认可用，不额外开启调试或重复配对。
        if (control.status.value == BuiltinShellStatus.Running && !pairing.repairing.value) return true
        if (!DeveloperSettings.developerOptions(app.contentResolver) && !enableDeveloperOptions()) return false
        if (!DeveloperSettings.usbDebugging(app.contentResolver) && !enableDebugging(wireless = false)) return false
        if (!DeveloperSettings.wirelessDebugging(app.contentResolver) && !enableDebugging(wireless = true)) return false
        if (control.status.value == BuiltinShellStatus.Running && !pairing.repairing.value) return true
        return pair()
    }

    private suspend fun enableDeveloperOptions(): Boolean {
        update(R.string.setup_auto_developer)
        if (!open(DeveloperSettings.aboutPhone())) return false
        delay(PAGE_SETTLE_MS)
        var taps = 0
        var softwareOpened = false
        var scrolls = 0
        val deadline = SystemClock.elapsedRealtime() + STEP_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            val page = page(R.string.setup_auto_developer)
            page?.use {
                if (DeveloperSettings.developerOptions(app.contentResolver)) return true
                val build = it.tree.click(SetupTarget.BuildNumber)
                val software = if (!softwareOpened) it.tree.click(SetupTarget.SoftwareInfo) else null
                when {
                    build != null && taps < 7 -> { if (!it.click(build)) return false; taps++ }
                    software != null && taps == 0 -> { if (!it.click(software)) return false; softwareOpened = true; scrolls = 0 }
                    build == null && taps == 0 && scrolls < MAX_SCROLLS -> {
                        val scroll = it.tree.scroll() ?: return false
                        if (!it.scroll(scroll)) return false
                        scrolls++
                    }
                }
            }
            delay(POLL_MS)
        }
        return false
    }

    private suspend fun enableDebugging(wireless: Boolean): Boolean {
        val message = if (wireless) R.string.setup_auto_wireless else R.string.setup_auto_usb
        val target = if (wireless) SetupTarget.Wireless else SetupTarget.Usb
        fun enabled() = if (wireless) DeveloperSettings.wirelessDebugging(app.contentResolver) else DeveloperSettings.usbDebugging(app.contentResolver)
        update(message)
        if (!open(if (wireless) DeveloperSettings.wirelessDebuggingPage() else DeveloperSettings.usbDebuggingPage())) return false
        delay(PAGE_SETTLE_MS)
        var clicked = false
        var opened = false
        var scrolls = 0
        var stableOn = 0
        val deadline = SystemClock.elapsedRealtime() + STEP_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            val on = enabled()
            val page = page(message)
            // 某些系统先把无线调试写成 1，再弹网络确认框、拒绝后拨回 0。
            // 必须开关连续稳定且当前没有确认框，才跳到下一页。
            stableOn = if (on && page != null) stableOn + 1 else 0
            page?.use {
                if (stableOn >= 3) return true
                if (!on && !clicked) {
                    val toggle = it.tree.turnOn(target) ?: if (wireless) it.tree.wirelessPageSwitch() else null
                    val navigation = if (wireless && !opened) it.tree.click(target) else null
                    when {
                        toggle != null -> { if (!it.click(toggle)) return false; clicked = true }
                        navigation != null -> { if (!it.click(navigation)) return false; opened = true; scrolls = 0 }
                        it.tree.label(target) == null && scrolls < MAX_SCROLLS -> {
                            val scroll = it.tree.scroll() ?: return false
                            if (!it.scroll(scroll)) return false
                            scrolls++
                        }
                    }
                }
            }
            // 一次会话每个开关只点一次。确认框取消或设置读不到时交回手动，不重复切换。
            delay(POLL_MS)
        }
        return false
    }

    private suspend fun pair(): Boolean {
        update(R.string.setup_auto_pairing)
        ownsPairing = true
        pairing.begin()
        if (!open(DeveloperSettings.wirelessDebuggingPage())) return false
        delay(PAGE_SETTLE_MS)
        var opened = false
        var requested = false
        var scrolls = 0
        val deadline = SystemClock.elapsedRealtime() + STEP_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            if (control.status.value == BuiltinShellStatus.Running && !pairing.repairing.value) return true
            if (requested || control.status.value == BuiltinShellStatus.Connecting) {
                update(R.string.setup_auto_pairing)
                delay(POLL_MS)
                continue
            }
            val page = page(R.string.setup_auto_pairing)
            page?.use {
                if (!requested && control.status.value != BuiltinShellStatus.Connecting) {
                    val pair = it.tree.click(SetupTarget.Pair)
                    val wireless = if (!opened) it.tree.click(SetupTarget.Wireless) else null
                    when {
                        pair != null -> { if (!it.click(pair)) return false; requested = true }
                        wireless != null -> { if (!it.click(wireless)) return false; opened = true; scrolls = 0 }
                        scrolls < MAX_SCROLLS -> {
                            val scroll = it.tree.scroll() ?: return false
                            if (!it.scroll(scroll)) return false
                            scrolls++
                        }
                    }
                }
            }
            // 配对窗口交给已有 PairingCodeReader；不把配对码复制到状态、日志或浮层。
            delay(POLL_MS)
        }
        return false
    }

    private fun update(message: Int) { mutableState.value = SetupAutomationState(active = true, message = message) }

    private fun open(intents: List<Intent>): Boolean = intents.any { intent ->
        runCatching {
            val context = ScreenAccessibility.instance.value ?: app
            context.startActivity(Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        }.getOrDefault(false)
    }

    /** 只看当前活动的最上层窗口。通知栏/其他 App/锁屏出现时不碰下面的设置。 */
    private fun page(message: Int): SettingsPage? {
        val service = ScreenAccessibility.instance.value
        if (service == null || app.getSystemService(KeyguardManager::class.java).isKeyguardLocked) {
            hideOverlay()
            update(R.string.setup_auto_confirm)
            return null
        }
        val windows = service.windowsOn(Display.DEFAULT_DISPLAY)
        val top = windows.filter {
            it.type != AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY &&
                (it.type == AccessibilityWindowInfo.TYPE_APPLICATION || it.isActive || it.isFocused)
        }.maxByOrNull { it.layer }
        val root = service.rootInActiveWindow
        if (root == null || top == null || root.windowId != top.id || root.packageName?.toString() != settingsPackage) {
            @Suppress("DEPRECATION")
            root?.recycle()
            hideOverlay()
            update(R.string.setup_auto_confirm)
            return null
        }
        val page = SettingsPage(service, root)
        if (page.tree.blocked) {
            page.close()
            hideOverlay() // 让系统确认框不受浮层遮挡。
            update(R.string.setup_auto_confirm)
            return null
        }
        update(message)
        if (overlay == null || overlayService !== service) {
            hideOverlay()
            overlayService = service
            overlay = service.overlays().open(OverlayPlacement(Gravity.BOTTOM or Gravity.END, y = 120)) {
                val current by state.collectAsState()
                SetupAutomationOverlay(current, ::stop)
            }
            // 显示停止按钮失败时，不开始无人可停止的点击。
            if (overlay == null) { page.close(); throw IllegalStateException("setup overlay unavailable") }
            page.close()
            return null // 浮层加入会改变窗口代次；下一轮重新取节点再操作。
        }
        return page
    }

    private fun hideOverlay() {
        overlay?.close()
        overlay = null
        overlayService = null
    }

    private class SettingsPage(private val service: ScreenAccessibility, root: AccessibilityNodeInfo) : AutoCloseable {
        private val windowId = root.windowId
        private val generation = ScreenAccessibility.windowGeneration()
        private val raw = mutableListOf<AccessibilityNodeInfo>()
        val tree: SetupAutomationTree

        init {
            val nodes = mutableListOf<SetupNode>()
            val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int?>>()
            queue.add(root to null)
            while (queue.isNotEmpty()) {
                val (node, parent) = queue.removeFirst()
                val id = raw.size
                raw += node
                nodes += SetupNode(
                    id, parent, if (node.isPassword || node.isEditable) "" else node.text?.toString().orEmpty(), node.viewIdResourceName.orEmpty(),
                    node.isVisibleToUser, node.isEnabled, node.isClickable, node.isCheckable, node.isChecked,
                    node.isEditable, node.isPassword, node.isScrollable,
                    node.className?.toString()?.endsWith("Dialog") == true,
                )
                if (raw.size + queue.size + node.childCount <= MAX_NODES) {
                    for (i in 0 until node.childCount) node.getChild(i)?.let { queue.add(it to id) }
                } else {
                    // 部分树不能证明没有被漏掉的确认框或重复目标。
                    nodes += SetupNode(-1, dialog = true)
                }
            }
            tree = SetupAutomationTree(nodes)
        }

        fun click(id: Int) = action(id, AccessibilityNodeInfo.ACTION_CLICK)
        fun scroll(id: Int) = action(id, AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)

        private fun action(id: Int, action: Int): Boolean {
            if (generation != ScreenAccessibility.windowGeneration() || ScreenAccessibility.instance.value !== service) return false
            val active = service.rootInActiveWindow ?: return false
            val sameWindow = active.windowId == windowId
            // RecyclerView 可在同一窗口里复用节点；重新取整棵树比对后，用新节点下发，
            // 避免 refresh 把原来的开关变成另一行再误点。不会保留上一轮的节点定位。
            SettingsPage(service, active).use { fresh ->
                if (!sameWindow || fresh.tree.blocked || fresh.tree.nodes != tree.nodes) return false
                return fresh.raw.getOrNull(id)?.performAction(action) == true
            }
        }

        @Suppress("DEPRECATION")
        override fun close() { raw.forEach { it.recycle() } }
    }

    private companion object {
        const val POLL_MS = 700L
        const val PAGE_SETTLE_MS = 1_000L
        const val STEP_MS = 90_000L
        const val SESSION_MS = 5 * 60_000L
        const val MAX_SCROLLS = 12
        const val MAX_NODES = 600
    }
}
