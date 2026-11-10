package com.wangzan101.mika.cap.phone.setup

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Toast
import com.wangzan101.mika.R
import com.wangzan101.mika.cap.phone.a11y.OverlayPlacement
import com.wangzan101.mika.cap.phone.a11y.OverlayWindow
import com.wangzan101.mika.cap.phone.a11y.ScreenAccessibility
import com.wangzan101.mika.cap.phone.a11y.SystemPickers
import com.wangzan101.mika.core.shell.BuiltinShellControl
import com.wangzan101.mika.core.shell.BuiltinShellStatus
import com.wangzan101.mika.core.model.ModelRole
import com.wangzan101.mika.settings.ProviderStore
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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull

data class SetupAutomationState(val active: Boolean = false, val message: Int = R.string.setup_auto_intro)

/**
 * 用户点「自动设置」才开始的一次性会话。无障碍首次授权仍由用户完成，服务连上后继续。
 * 先按规则操作，用户启用模型辅助时才让当前主模型选择候选；不读密码、不确认授权。
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
    private val pageAvailability = SetupAutomationAvailability()
    private val settingsPackage by lazy { SystemPickers.settingsPackage(app.packageManager) }

    fun start(useModel: Boolean = false) {
        if (job?.isActive == true) return
        val currentSession = ++session
        pageAvailability.reset()
        // 每次开始都独立建建议器，取消后旧请求不能污染新会话的次数/缓存。
        // 在用户按开始时固定主模型；会话中不会因为后台配置变化切换到另一服务商。
        val sessionModel = if (useModel) runCatching { ProviderStore.get(app).modelFor(ModelRole.Main) }.getOrNull() else null
        val advisor = if (useModel) SetupModelAdvisor(
            model = { sessionModel },
        ) else null
        Log.i(TAG, "session started")
        update(R.string.setup_auto_enable_accessibility)
        job = scope.launch {
            var completed = false
            try {
                completed = withTimeoutOrNull(SESSION_MS) { runSetup(advisor) } == true
                Log.i(TAG, "session result=${if (completed) "completed" else "manual"}")
                mutableState.value = SetupAutomationState(message = if (completed) R.string.setup_auto_done else R.string.setup_auto_manual)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: SettingsPageUnavailable) {
                Log.i(TAG, "session result=manual reason=PageUnavailable")
                mutableState.value = SetupAutomationState(message = R.string.setup_auto_page_unavailable)
                Toast.makeText(app, R.string.setup_auto_page_unavailable, Toast.LENGTH_LONG).show()
            } catch (error: Exception) {
                // 设置树、系统页可能被厂商限制；停止并交回手动流程，不盲点或反复尝试。
                // 异常只记录类型，不记录可能包含页面文字或授权信息的 message/stack trace。
                Log.w(TAG, "session failed category=${error.javaClass.simpleName}")
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
        Log.i(TAG, "session stopped")
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

    private suspend fun runSetup(advisor: SetupModelAdvisor?): Boolean {
        if (settingsPackage == null) return false
        if (ScreenAccessibility.instance.value == null) {
            if (!open(listOf(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)))) return false
            while (ScreenAccessibility.instance.value == null) delay(POLL_MS)
            // 等用户授权后的设置页稳定，不去操作刚出现的权限窗口。
            delay(1_000)
        }
        // 助手可能已由 USB/root 启动；普通解锁只确认可用，不额外开启调试或重复配对。
        if (control.status.value == BuiltinShellStatus.Running && !pairing.repairing.value) return true
        if (!DeveloperSettings.developerOptions(app.contentResolver) && !enableDeveloperOptions(advisor)) return false
        if (!DeveloperSettings.usbDebugging(app.contentResolver) && !enableDebugging(wireless = false, advisor = advisor)) return false
        if (!DeveloperSettings.wirelessDebugging(app.contentResolver) && !enableDebugging(wireless = true, advisor = advisor)) return false
        if (control.status.value == BuiltinShellStatus.Running && !pairing.repairing.value) return true
        return pair(advisor)
    }

    private suspend fun enableDeveloperOptions(advisor: SetupModelAdvisor?): Boolean {
        update(R.string.setup_auto_developer)
        if (!open(DeveloperSettings.aboutPhone())) return false
        delay(PAGE_SETTLE_MS)
        var taps = 0
        var softwareOpened = false
        var scrolls = 0
        var navigations = 0
        val deadline = SystemClock.elapsedRealtime() + STEP_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            val page = page(R.string.setup_auto_developer)
            page?.use {
                if (DeveloperSettings.developerOptions(app.contentResolver)) return true
                val build = it.tree.click(SetupTarget.BuildNumber)
                val software = if (!softwareOpened) it.tree.click(SetupTarget.SoftwareInfo) else null
                when {
                    build != null && taps < 7 -> when (it.click(build)) {
                        SetupActionResult.Applied -> taps++
                        SetupActionResult.Retry -> Unit
                        SetupActionResult.Failed -> return false
                    }
                    software != null && taps == 0 -> when (it.click(software)) {
                        SetupActionResult.Applied -> { softwareOpened = true; scrolls = 0 }
                        SetupActionResult.Retry -> Unit
                        SetupActionResult.Failed -> return false
                    }
                    build == null && taps < 7 -> {
                        val suggested = modelAction(advisor, SetupModelStage.Developer, it, R.string.setup_auto_developer,
                            canScroll = scrolls < MAX_SCROLLS, canNavigate = taps == 0 && navigations < MAX_NAVIGATIONS, deadline = deadline)
                        if (suggested != null) {
                            if (suggested.result == SetupActionResult.Failed) return false
                            if (suggested.result == SetupActionResult.Applied) when (suggested.action) {
                                SetupModelAction.Build -> taps++
                                SetupModelAction.Navigate -> { navigations++; scrolls = 0 }
                                SetupModelAction.Scroll -> scrolls++
                                else -> return false
                            }
                        } else if (taps == 0 && scrolls < MAX_SCROLLS) {
                            val scroll = it.tree.scroll() ?: return false
                            when (it.scroll(scroll)) {
                                SetupActionResult.Applied -> scrolls++
                                SetupActionResult.Retry -> Unit
                                SetupActionResult.Failed -> return false
                            }
                        } else return false
                    }
                    taps >= 7 && !DeveloperSettings.developerOptions(app.contentResolver) -> {
                        // 七次已下发；只等待系统生效/用户确认，不再次借模型重复点击。
                        Unit
                    }
                }
            }
            delay(POLL_MS)
        }
        return false
    }

    private data class ModelActionResult(val action: SetupModelAction, val result: SetupActionResult)

    private suspend fun modelAction(
        advisor: SetupModelAdvisor?, stage: SetupModelStage, page: SettingsPage, message: Int,
        canScroll: Boolean, canNavigate: Boolean, deadline: Long,
    ): ModelActionResult? {
        if (advisor == null) return null
        val remaining = deadline - SystemClock.elapsedRealtime()
        if (remaining <= 0) return null
        val decision = withTimeoutOrNull(remaining) {
            advisor.suggest(stage, page.tree) { update(R.string.setup_auto_model_thinking) }
        }
        currentCoroutineContext().ensureActive()
        update(message)
        if (decision == null) return null
        val allowed = when (stage) {
            SetupModelStage.Developer -> setOf(SetupModelAction.Navigate, SetupModelAction.Build, SetupModelAction.Scroll)
            SetupModelStage.Usb, SetupModelStage.Wireless -> setOf(SetupModelAction.Navigate, SetupModelAction.Enable, SetupModelAction.Scroll)
            SetupModelStage.Pair -> setOf(SetupModelAction.Navigate, SetupModelAction.Pair, SetupModelAction.Scroll)
        }
        if (decision.action !in allowed || SystemClock.elapsedRealtime() >= deadline) return null
        if (decision.action == SetupModelAction.Scroll && !canScroll ||
            decision.action == SetupModelAction.Navigate && !canNavigate) return null
        val result = if (decision.action == SetupModelAction.Scroll) page.scroll(decision.nodeId) else page.click(decision.nodeId)
        if (result == SetupActionResult.Applied) advisor.applied(decision)
        Log.d(TAG, "model action=${decision.action} result=$result")
        return ModelActionResult(decision.action, result)
    }

    private suspend fun enableDebugging(wireless: Boolean, advisor: SetupModelAdvisor?): Boolean {
        val message = if (wireless) R.string.setup_auto_wireless else R.string.setup_auto_usb
        val target = if (wireless) SetupTarget.Wireless else SetupTarget.Usb
        fun enabled() = if (wireless) DeveloperSettings.wirelessDebugging(app.contentResolver) else DeveloperSettings.usbDebugging(app.contentResolver)
        update(message)
        val debuggingIntents = if (wireless) DeveloperSettings.wirelessDebuggingPage() else
            DeveloperSettings.usbDebuggingPage().map { Intent(it).setPackage(settingsPackage ?: return false) }
        if (!open(debuggingIntents)) return false
        val usbLease = if (!wireless) settingsPackage?.let { SetupAutomationWindowLease(it, SystemClock.uptimeMillis()) } else null
        delay(PAGE_SETTLE_MS)
        var clicked = false
        var opened = false
        var scrolls = 0
        var navigations = 0
        var stableOn = 0
        val deadline = SystemClock.elapsedRealtime() + STEP_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            val on = enabled()
            val page = try { page(message) } catch (unavailable: SettingsPageUnavailable) {
                if (wireless) throw unavailable
                val service = ScreenAccessibility.instance.value ?: throw unavailable
                val lease = usbLease ?: throw unavailable
                if (!on) stableOn = 0
                if (on) {
                    val captureOverlay = overlay
                    stableOn = if (SetupUsbScreenshot.developerPageVisible(service, lease, captureOverlay) && enabled()) stableOn + 1 else 0
                    if (stableOn >= 3) return true
                } else if (!clicked) {
                    update(R.string.setup_auto_usb_visual)
                    if (!showOverlay(service)) throw unavailable
                    delay(POLL_MS) // 等停止浮层加入窗口列表，随后才记录截图的窗口代次。
                    val captureOverlay = overlay
                    when (SetupUsbScreenshot.act(service, lease, scrolls < MAX_SCROLLS, captureOverlay)) {
                        SetupUsbScreenshot.Result.Tapped -> { clicked = true; update(R.string.setup_auto_confirm) }
                        SetupUsbScreenshot.Result.Scrolled -> scrolls++
                        SetupUsbScreenshot.Result.Retry -> Unit
                        SetupUsbScreenshot.Result.Unavailable -> throw unavailable
                        SetupUsbScreenshot.Result.ActionUncertain -> return false
                    }.also { Log.d(TAG, "usb screenshot action finished clicked=$clicked scrolls=$scrolls") }
                    hideOverlay()
                } else {
                    // 已点一次，仅等待用户完成系统确认；不得把同一个开关再次关掉。
                    update(R.string.setup_auto_confirm)
                }
                pageAvailability.reset()
                delay(POLL_MS)
                continue
            }
            // 某些系统先把无线调试写成 1，再弹网络确认框、拒绝后拨回 0。
            // 必须开关连续稳定且当前没有确认框，才跳到下一页。
            stableOn = when {
                !on -> 0
                page != null -> stableOn + 1
                !wireless -> stableOn // USB 截图回退会在核实正常列表后计数，等待根节点时保留该证据。
                else -> 0
            }
            page?.use {
                if (stableOn >= 3) return true
                if (!on && !clicked) {
                    val toggle = it.tree.turnOn(target) ?: if (wireless) it.tree.wirelessPageSwitch() else null
                    val navigation = if (wireless && !opened) it.tree.click(target) else null
                    when {
                        toggle != null -> when (it.click(toggle)) {
                            SetupActionResult.Applied -> clicked = true
                            SetupActionResult.Retry -> Unit
                            SetupActionResult.Failed -> return false
                        }
                        navigation != null -> when (it.click(navigation)) {
                            SetupActionResult.Applied -> { opened = true; scrolls = 0 }
                            SetupActionResult.Retry -> Unit
                            SetupActionResult.Failed -> return false
                        }
                        else -> {
                            // 部分 ROM 只隐藏敏感的 USB 行，根节点和其余列表仍可读。
                            // 在继续滚动前看一眼画面，避免把已经可见的 USB 行反复滚过去。
                            if (!wireless) {
                                val service = ScreenAccessibility.instance.value
                                val lease = usbLease
                                if (service != null && lease != null) {
                                    update(R.string.setup_auto_usb_visual)
                                    val captureOverlay = overlay
                                    val visual = SetupUsbScreenshot.act(service, lease, scrolls < MAX_SCROLLS, captureOverlay)
                                    Log.d(TAG, "usb screenshot result=$visual")
                                    update(message)
                                    when (visual) {
                                        SetupUsbScreenshot.Result.Tapped -> { clicked = true; update(R.string.setup_auto_confirm); hideOverlay(); return@use }
                                        SetupUsbScreenshot.Result.Scrolled -> { scrolls++; return@use }
                                        SetupUsbScreenshot.Result.Retry -> return@use
                                        SetupUsbScreenshot.Result.Unavailable -> Unit
                                        SetupUsbScreenshot.Result.ActionUncertain -> return false
                                    }
                                }
                            }
                            val suggested = modelAction(advisor, if (wireless) SetupModelStage.Wireless else SetupModelStage.Usb,
                                it, message, canScroll = scrolls < MAX_SCROLLS, canNavigate = navigations < MAX_NAVIGATIONS, deadline = deadline)
                            if (suggested != null) {
                                if (suggested.result == SetupActionResult.Failed) return false
                                if (suggested.result == SetupActionResult.Applied) when (suggested.action) {
                                    SetupModelAction.Enable -> clicked = true
                                    SetupModelAction.Navigate -> { navigations++; opened = true; scrolls = 0 }
                                    SetupModelAction.Scroll -> scrolls++
                                    else -> return false
                                }
                            } else if (it.tree.label(target) == null && scrolls < MAX_SCROLLS) {
                                val scroll = it.tree.scroll() ?: return false
                                when (it.scroll(scroll)) {
                                    SetupActionResult.Applied -> scrolls++
                                    SetupActionResult.Retry -> Unit
                                    SetupActionResult.Failed -> return false
                                }
                            }
                        }
                    }
                }
            }
            // 一次会话每个开关只点一次。确认框取消或设置读不到时交回手动，不重复切换。
            delay(POLL_MS)
        }
        return false
    }

    private suspend fun pair(advisor: SetupModelAdvisor?): Boolean {
        update(R.string.setup_auto_pairing)
        ownsPairing = true
        pairing.begin()
        if (!open(DeveloperSettings.wirelessDebuggingPage())) return false
        delay(PAGE_SETTLE_MS)
        var opened = false
        var requested = false
        var scrolls = 0
        var navigations = 0
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
                        pair != null -> when (it.click(pair)) {
                            SetupActionResult.Applied -> requested = true
                            SetupActionResult.Retry -> Unit
                            SetupActionResult.Failed -> return false
                        }
                        wireless != null -> when (it.click(wireless)) {
                            SetupActionResult.Applied -> { opened = true; scrolls = 0 }
                            SetupActionResult.Retry -> Unit
                            SetupActionResult.Failed -> return false
                        }
                        else -> {
                            val suggested = modelAction(advisor, SetupModelStage.Pair, it, R.string.setup_auto_pairing,
                                canScroll = scrolls < MAX_SCROLLS, canNavigate = navigations < MAX_NAVIGATIONS, deadline = deadline)
                            if (suggested != null) {
                                if (suggested.result == SetupActionResult.Failed) return false
                                if (suggested.result == SetupActionResult.Applied) when (suggested.action) {
                                    SetupModelAction.Pair -> requested = true
                                    SetupModelAction.Navigate -> { navigations++; opened = true; scrolls = 0 }
                                    SetupModelAction.Scroll -> scrolls++
                                    else -> return false
                                }
                            } else if (scrolls < MAX_SCROLLS) {
                                val scroll = it.tree.scroll() ?: return false
                                when (it.scroll(scroll)) {
                                    SetupActionResult.Applied -> scrolls++
                                    SetupActionResult.Retry -> Unit
                                    SetupActionResult.Failed -> return false
                                }
                            } else return false
                        }
                    }
                }
            }
            // 配对窗口交给已有 PairingCodeReader；不把配对码复制到状态、日志或浮层。
            delay(POLL_MS)
        }
        return false
    }

    private fun update(message: Int) {
        if (mutableState.value.message != message) Log.i(TAG, "stage=${app.resources.getResourceEntryName(message)}")
        mutableState.value = SetupAutomationState(active = true, message = message)
    }

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
            pageAvailability.reset()
            hideOverlay()
            update(R.string.setup_auto_confirm)
            return null
        }
        val windows = service.windowsOn(Display.DEFAULT_DISPLAY)
        val top = windows.filter {
            it.type != AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY &&
                (it.type == AccessibilityWindowInfo.TYPE_APPLICATION || it.isActive || it.isFocused)
        }.maxByOrNull { it.layer }
        // 从刚确认的主屏最上层窗口取根，保持窗口选择与节点来源一致。
        val root = top?.takeIf { it.isActive || it.isFocused }?.root
        if (root == null || top == null || root.windowId != top.id || root.packageName?.toString() != settingsPackage) {
            if (mutableState.value.message != R.string.setup_auto_confirm) {
                Log.d(TAG, "page paused reason=WindowUnavailable root=${root?.windowId} top=${top?.id}")
            }
            @Suppress("DEPRECATION")
            root?.recycle()
            hideOverlay()
            val unreadableAppWindow = top?.takeIf {
                root == null && it.type == AccessibilityWindowInfo.TYPE_APPLICATION && (it.isActive || it.isFocused)
            }?.id
            if (pageAvailability.unreadable(unreadableAppWindow, SystemClock.elapsedRealtime())) {
                throw SettingsPageUnavailable()
            }
            update(R.string.setup_auto_confirm)
            return null
        }
        pageAvailability.reset()
        val page = SettingsPage(service, root)
        if (page.tree.blocked) {
            if (mutableState.value.message != R.string.setup_auto_confirm) {
                Log.d(TAG, "page paused reason=Confirmation nodes=${page.tree.nodes.size}")
            }
            page.close()
            hideOverlay() // 让系统确认框不受浮层遮挡。
            update(R.string.setup_auto_confirm)
            return null
        }
        update(message)
        if (overlay == null || overlayService !== service) {
            // 显示停止按钮失败时，不开始无人可停止的点击。
            if (!showOverlay(service)) { page.close(); throw IllegalStateException("setup overlay unavailable") }
            page.close()
            return null // 浮层加入会改变窗口代次；下一轮重新取节点再操作。
        }
        return page
    }

    private fun showOverlay(service: ScreenAccessibility): Boolean {
        if (overlay != null && overlayService === service) return true
        hideOverlay()
        overlayService = service
        overlay = service.overlays().open(OverlayPlacement(Gravity.BOTTOM or Gravity.END, y = 120)) {
            val current by state.collectAsState()
            SetupAutomationOverlay(current, ::stop)
        }
        return overlay != null
    }

    private fun hideOverlay() {
        overlay?.close()
        overlay = null
        overlayService = null
    }

    private class SettingsPageUnavailable : Exception()

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
                    contentDescription = if (node.isPassword || node.isEditable) "" else node.contentDescription?.toString().orEmpty(),
                    focused = node.isFocused,
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

        private fun action(id: Int, action: Int): SetupActionResult {
            val kind = if (action == AccessibilityNodeInfo.ACTION_CLICK) "click" else "scroll"
            if (generation != ScreenAccessibility.windowGeneration() || ScreenAccessibility.instance.value !== service) {
                Log.d(TAG, "action=$kind result=Retry reason=WindowChanged")
                return SetupActionResult.Retry
            }
            if (service.getSystemService(KeyguardManager::class.java).isKeyguardLocked) return SetupActionResult.Retry
            val top = service.windowsOn(Display.DEFAULT_DISPLAY).filter {
                it.type != AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY &&
                    (it.type == AccessibilityWindowInfo.TYPE_APPLICATION || it.isActive || it.isFocused)
            }.maxByOrNull { it.layer }
            val active = top?.takeIf { it.id == windowId && (it.isActive || it.isFocused) }?.root ?: run {
                Log.d(TAG, "action=$kind result=Retry reason=WindowUnavailable")
                return SetupActionResult.Retry
            }
            val sameWindow = active.windowId == windowId
            // RecyclerView 可在同一窗口里复用节点；重新取整棵树比对后，用新节点下发，
            // 避免 refresh 把原来的开关变成另一行再误点。不会保留上一轮的节点定位。
            SettingsPage(service, active).use { fresh ->
                val stableWindow = sameWindow && generation == ScreenAccessibility.windowGeneration() &&
                    ScreenAccessibility.instance.value === service
                val result = SetupAutomationAction.apply(tree, fresh.tree, stableWindow) {
                    fresh.raw.getOrNull(id)?.performAction(action) == true
                }
                val reason = SetupAutomationAction.retryReason(tree, fresh.tree, stableWindow)?.name
                    ?: if (result == SetupActionResult.Failed) "ActionRejected" else "Accepted"
                Log.d(TAG, "action=$kind result=$result reason=$reason")
                return result
            }
        }

        @Suppress("DEPRECATION")
        override fun close() { raw.forEach { it.recycle() } }
    }

    private companion object {
        const val TAG = "MikaSetup"
        const val POLL_MS = 700L
        const val PAGE_SETTLE_MS = 1_000L
        const val STEP_MS = 90_000L
        const val SESSION_MS = 5 * 60_000L
        const val MAX_SCROLLS = 12
        const val MAX_NAVIGATIONS = 5
        const val MAX_NODES = 600
    }
}
