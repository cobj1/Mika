package com.wangzan101.mika.cap.phone.setup

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.util.Log
import android.provider.Settings
import android.view.Display
import android.view.Gravity
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.R
import com.wangzan101.mika.cap.phone.a11y.OverlayPlacement
import com.wangzan101.mika.cap.phone.a11y.PhoneOcr
import com.wangzan101.mika.cap.phone.a11y.ScreenAccessibility
import com.wangzan101.mika.core.uistate.VisibleTextLine
import com.wangzan101.mika.ui.SetupAutomationOverlay
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
import java.io.File
import kotlin.coroutines.resume

/**
 * 显式诊断普通无障碍服务的截图和 USB 行点击；点击测试须单独传参，永不确认授权。
 * 截图和 OCR 均调用应用自己的服务；不使用 UiAutomation、不输出完整识别文字。
 * 仅额外开启 verifySetupUsbCaptureComparisons 的 USB 点击诊断保存对照截图；此代码不进入主 APK。
 */
@RunWith(AndroidJUnit4::class)
class SetupUsbCaptureDeviceTest {
    @Test fun ordinaryAccessibilityCanCaptureUsbSettings() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("verifySetupUsbCapture") == "true")
        withContext(Dispatchers.Main.immediate) {
            val service = withTimeout(20_000) { ScreenAccessibility.instance.filterNotNull().first() }
            val events = mutableListOf<EventOwner>()
            var eventCount = 0
            val observer = launch(start = CoroutineStart.UNDISPATCHED) {
                service.eventsFor(Display.DEFAULT_DISPLAY).collect { event ->
                    eventCount++
                    if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && events.size < 24) {
                        events += EventOwner(event.windowId, event.packageName?.toString(), event.className?.toString())
                    }
                }
            }
            try {
                openUsbSettings(service)
                delay(2_000)
                val top = service.windowsOn(Display.DEFAULT_DISPLAY).filter {
                    it.type != AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY &&
                        (it.type == AccessibilityWindowInfo.TYPE_APPLICATION || it.isActive || it.isFocused)
                }.maxByOrNull { it.layer }
                val root = top?.root
                report("window=${top?.id} rootNull=${root == null} rootPackage=${root?.packageName}")
                report("observedPackage=${top?.let { service.observedWindowPackage(Display.DEFAULT_DISPLAY, it.id) }}")
                @Suppress("DEPRECATION")
                root?.recycle()
                val shot = withTimeout(10_000) { service.takeMainScreenshot() }
                var usbFound = false
                when (shot) {
                    is ScreenAccessibility.MainShot.Ok -> {
                        report("screenshot=Ok width=${shot.bitmap.width} height=${shot.bitmap.height}")
                        // PhoneOcr 接管原图，超时或取消时也由其识别回调负责回收。
                        val lines = withTimeout(15_000) { PhoneOcr.read(shot.bitmap) }
                        reportOcr(lines)
                        val usb = lines.filter { normalize(it.text) in USB_TITLES }
                        usbFound = usb.isNotEmpty()
                        val developerFound = lines.any { normalize(it.text) in DEVELOPER_TITLES }
                        report("usbFound=$usbFound usbBounds=${usb.map { it.bounds }.distinct()} developerTitleFound=$developerFound")
                        assertTrue("Developer page title must be recognized", developerFound)
                    }
                    ScreenAccessibility.MainShot.Secure -> report("screenshot=Secure")
                    is ScreenAccessibility.MainShot.Failed -> report("screenshot=Failed code=${shot.code}")
                }
                report("eventCount=$eventCount windowStateEvents=${events.size} matchingTopEvents=${events.count { it.windowId == top?.id }}")
                events.distinct().forEach { report("event window=${it.windowId} package=${it.packageName} class=${it.className}") }
                assertTrue("The ordinary service must capture the settings screen", shot is ScreenAccessibility.MainShot.Ok)
            } finally {
                observer.cancelAndJoin()
            }
        }
    }

    /** 显式点击验收：最多点击一次 USB 行并留下系统确认框，不代用户确认。 */
    @Test fun ordinaryAccessibilityTapsUsbOnceAndLeavesConfirmation() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("verifySetupUsbTap") == "true")
        withContext(Dispatchers.Main.immediate) {
            val service = withTimeout(20_000) { ScreenAccessibility.instance.filterNotNull().first() }
            assertFalse("UsbAlreadyEnabled", DeveloperSettings.usbDebugging(service.contentResolver))
            val settingsPackage = service.packageManager.resolveActivity(Intent(Settings.ACTION_SETTINGS), 0)
                ?.activityInfo?.packageName ?: error("SettingsPackageUnavailable")
            openUsbSettings(service)
            var lease = SetupAutomationWindowLease(settingsPackage, android.os.SystemClock.uptimeMillis())
            delay(2_000)
            // 真实自动设置会显示状态条；无浮层的截图测试不能覆盖提示文字和遮挡造成的失败。
            val testJob = currentCoroutineContext()[Job] ?: error("TestJobUnavailable")
            val overlay = service.overlays().open(OverlayPlacement(Gravity.BOTTOM or Gravity.END, y = 120)) {
                SetupAutomationOverlay(SetupAutomationState(active = true, message = R.string.setup_auto_usb_visual)) {
                    testJob.cancel()
                }
            } ?: error("SetupOverlayUnavailable")
            try {
                delay(700)
                assertTrue("SetupOverlayNotLaidOut", overlay.width > 0 && overlay.height > 0)
                if (InstrumentationRegistry.getArguments().getString("verifySetupUsbCaptureComparisons") == "true") {
                    // 默认点击验收不存图；只有额外显式开启截图对照才运行这些诊断。
                    diagnoseScreenshot("visible", "usb-ocr-visible.png") { service.takeMainScreenshot() }
                    delay(1_100)
                    diagnoseScreenshot("hidden", "usb-ocr-probe.png") {
                        overlay.hiddenWhile { service.takeMainScreenshot() }
                    }
                    delay(1_100)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        val top = service.windowsOn(Display.DEFAULT_DISPLAY).filter {
                            it.type != AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY &&
                                (it.type == AccessibilityWindowInfo.TYPE_APPLICATION || it.isActive || it.isFocused)
                        }.maxByOrNull { it.layer }
                        // 没有切换页面或调用 UiAutomation，目标仍是本测试主动打开的 Settings 应用窗口。
                        if (top != null && top.type == AccessibilityWindowInfo.TYPE_APPLICATION && (top.isActive || top.isFocused)) {
                            val bounds = Rect().also(top::getBoundsInScreen)
                            report("windowCaptureTarget=${top.id} screenBounds=$bounds width=${bounds.width()} height=${bounds.height()}")
                            diagnoseScreenshot("window", "usb-ocr-window.png", upscale = true) { captureWindow(service, top.id) }
                        } else report("windowScreenshot=UnavailableWindow")
                    } else report("windowScreenshot=UnsupportedApi")

                    // 对照截图可能耗时；重新显式打开 Settings 后再建立动作租约，不伪造启动时间。
                    openUsbSettings(service)
                    lease = SetupAutomationWindowLease(settingsPackage, android.os.SystemClock.uptimeMillis())
                    delay(2_000)
                }
                var result = SetupUsbScreenshot.Result.Retry
                var retries = 0
                var scrolls = 0
                do {
                    result = SetupUsbScreenshot.act(service, lease, canScroll = scrolls < 6, overlay = overlay)
                    assertTrue("CaptureMustPreserveSetupOverlay", overlay.isOpen)
                    val top = service.windowsOn(Display.DEFAULT_DISPLAY).filter {
                        it.type != AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY &&
                            (it.type == AccessibilityWindowInfo.TYPE_APPLICATION || it.isActive || it.isFocused)
                    }.maxByOrNull { it.layer }
                    report("tapResult=$result retries=$retries withSetupOverlay=true observedPackage=${top?.let { service.observedWindowPackage(Display.DEFAULT_DISPLAY, it.id) }}")
                    if (result == SetupUsbScreenshot.Result.Scrolled) { scrolls++; retries = 0 }
                    else if (result == SetupUsbScreenshot.Result.Retry && retries < 3) retries++
                    else break
                    delay(700)
                } while (true)
                assertEquals("UsbTapNotAppliedWithSetupOverlay", SetupUsbScreenshot.Result.Tapped, result)
                // 与产品一致：下发一次点击后立即撤下自己的状态条，系统确认只留给用户。
                overlay.close()
                delay(1_500)
                assertFalse("UsbEnabledWithoutConfirmation", DeveloperSettings.usbDebugging(service.contentResolver))
                val pageVisible = SetupUsbScreenshot.developerPageVisible(service, lease)
                report("afterTapPageVisible=$pageVisible")
                assertFalse("ConfirmationMisidentifiedAsList", pageVisible)
                val repeated = SetupUsbScreenshot.act(service, lease, canScroll = false)
                report("confirmationResult=$repeated usbEnabled=${DeveloperSettings.usbDebugging(service.contentResolver)}")
                assertEquals("ConfirmationAcceptedByExecutor", SetupUsbScreenshot.Result.Unavailable, repeated)
                assertFalse("UsbEnabledWithoutConfirmation", DeveloperSettings.usbDebugging(service.contentResolver))
            } finally {
                overlay.close()
            }
        }
    }

    private suspend fun openUsbSettings(service: ScreenAccessibility) {
        // 测试重启 App 后不能复用未观察到身份的旧前台窗口，模拟用户从首页开始。
        service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
        delay(500)
        val settingsPackage = service.packageManager.resolveActivity(Intent(Settings.ACTION_SETTINGS), 0)?.activityInfo?.packageName
            ?: error("SettingsPackageUnavailable")
        service.startActivity(Intent(DeveloperSettings.usbDebuggingPage().first()).setPackage(settingsPackage).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
    private data class EventOwner(val windowId: Int, val packageName: String?, val className: String?)

    private fun normalize(text: String) = text.lowercase().filterNot(Char::isWhitespace)

    /** 只在显式调试入口调用：三种捕获共用存图和 OCR 分类，不打印完整页面文本。 */
    private suspend fun diagnoseScreenshot(
        label: String, fileName: String, upscale: Boolean = false, capture: suspend () -> ScreenAccessibility.MainShot,
    ) {
        withTimeout(if (upscale) 20_000 else 12_000) {
            val generationBefore = ScreenAccessibility.windowGeneration()
            val shot = capture()
            report("${label}CaptureGenerationBefore=$generationBefore after=${ScreenAccessibility.windowGeneration()}")
            when (shot) {
                is ScreenAccessibility.MainShot.Ok -> {
                    val file = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, fileName)
                    var enlarged: Bitmap? = null
                    try {
                        file.outputStream().use { output ->
                            check(shot.bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) { "ProbeImageWriteFailed" }
                        }
                        // 仅对窗口截图做 2 倍放大识别对照；原图先保存，坐标仍由 OCR 归一化到 0..999。
                        if (upscale) enlarged = Bitmap.createScaledBitmap(shot.bitmap,
                            shot.bitmap.width * 2, shot.bitmap.height * 2, true)
                    } catch (error: Throwable) {
                        enlarged?.recycle()
                        shot.bitmap.recycle()
                        throw error
                    }
                    report("${label}Screenshot=Ok width=${shot.bitmap.width} height=${shot.bitmap.height} saved=${file.absolutePath}")
                    try {
                        // 保存完成后 PhoneOcr 接管位图，异步识别完成后统一回收。
                        reportOcr(PhoneOcr.read(shot.bitmap), "$label ", diagnoseTitles = true)
                        enlarged?.let { bitmap ->
                            report("${label}2xBitmap width=${bitmap.width} height=${bitmap.height}")
                            enlarged = null // 从调用 read 起交给 OCR；取消时也不能提前回收仍在使用的图片。
                            reportOcr(PhoneOcr.read(bitmap), "${label}2x ", diagnoseTitles = true)
                        }
                    } finally {
                        enlarged?.recycle()
                    }
                }
                ScreenAccessibility.MainShot.Secure -> report("${label}Screenshot=Secure code=${AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW}")
                is ScreenAccessibility.MainShot.Failed -> report("${label}Screenshot=Failed code=${shot.code}")
            }
        }
    }

    /** Android 14 的公开普通无障碍窗口截图 API；不借用 UiAutomation 身份。 */
    private suspend fun captureWindow(service: ScreenAccessibility, windowId: Int): ScreenAccessibility.MainShot {
        check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
        return suspendCancellableCoroutine { continuation ->
            service.takeScreenshotOfWindow(windowId, service.mainExecutor, object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                    val bitmap = runCatching {
                        result.hardwareBuffer.use { buffer ->
                            if (!continuation.isActive) return@use null
                            val hardware = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace) ?: return@use null
                            try { hardware.copy(Bitmap.Config.ARGB_8888, false) } finally { hardware.recycle() }
                        }
                    }.getOrNull()
                    if (continuation.isActive) {
                        val shot = bitmap?.let(ScreenAccessibility.MainShot::Ok)
                            ?: ScreenAccessibility.MainShot.Failed(AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR)
                        continuation.resume(shot) { _, value, _ ->
                            (value as? ScreenAccessibility.MainShot.Ok)?.bitmap?.recycle()
                        }
                    } else bitmap?.recycle()
                }

                override fun onFailure(errorCode: Int) {
                    if (continuation.isActive) continuation.resume(
                        if (errorCode == AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW) ScreenAccessibility.MainShot.Secure
                        else ScreenAccessibility.MainShot.Failed(errorCode),
                    )
                }
            })
        }
    }

    /** 只输出分类与边界框，用相同诊断比较正常截图和隐藏自有浮层后的实际截图。 */
    private fun reportOcr(lines: List<VisibleTextLine>, prefix: String = "", diagnoseTitles: Boolean = false) {
        val policyClass = SetupAutomationUsbOcr::class.java
        fun titles(fieldName: String): Set<*> = policyClass.getDeclaredField(fieldName)
            .apply { isAccessible = true }.get(SetupAutomationUsbOcr) as Set<*>
        val headers = titles("HEADERS")
        val masters = titles("MASTER_TITLES")
        val anchors = titles("LIST_TITLES")
        val usbTitles = titles("USB_TITLES")
        val blocked = policyClass.getDeclaredMethod("blocked", String::class.java).apply { isAccessible = true }
        lines.forEachIndexed { index, line ->
            val value = line.text.lowercase(java.util.Locale.ROOT).filterNot(Char::isWhitespace)
            report("${prefix}ocrLine=$index bounds=${line.bounds} header=${value in headers} master=${value in masters} " +
                "anchor=${value in anchors} usb=${value in usbTitles} blocked=${blocked.invoke(SetupAutomationUsbOcr, value) == true}")
            // 只对本测试主动打开的 Settings 页固定标题区域打印短文字，排除密码/确认内容。
            // 分类日志不含任意页面正文；本诊断不会进入生产 APK，普通 capture 测试也不启用。
            if (diagnoseTitles && InstrumentationRegistry.getArguments().getString("verifySetupUsbTap") == "true" &&
                InstrumentationRegistry.getArguments().getString("verifySetupUsbCaptureComparisons") == "true" &&
                line.bounds.size == 4 && line.bounds[1] < 390 && line.bounds[0] > 40 && line.bounds[2] < 450 &&
                line.text.length in 1..40 && line.text.none { it.isDigit() || it in "@:/\\" } &&
                TITLE_DIAGNOSTIC_DENIED.none(value::contains) && blocked.invoke(SetupAutomationUsbOcr, value) != true) {
                report("${prefix}titleDiagnostic=$index bounds=${line.bounds} text=${JSONObject.quote(line.text)}")
            }
        }
        report("${prefix}ocrAction=${SetupAutomationUsbOcr.choose(lines)?.javaClass?.simpleName}")
    }

    private fun report(message: String) {
        Log.i("MikaSetupProbe", message)
        println("MikaSetupProbe: $message")
    }

    private companion object {
        val USB_TITLES = setOf("usb调试", "usb偵錯", "usb偵錯模式", "usb调试模式", "usbdebugging")
        val DEVELOPER_TITLES = setOf("开发者选项", "開發人員選項", "開發者選項", "developeroptions")
        val TITLE_DIAGNOSTIC_DENIED = setOf("密码", "密碼", "password", "pin", "确认", "確認", "confirm", "允许", "允許", "allow",
            "取消", "cancel", "授权", "授權", "同意", "agree", "密钥", "金鑰", "fingerprint", "rsa")
    }
}
