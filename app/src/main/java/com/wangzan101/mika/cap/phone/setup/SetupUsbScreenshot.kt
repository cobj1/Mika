package com.wangzan101.mika.cap.phone.setup

import android.app.KeyguardManager
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityWindowInfo
import com.wangzan101.mika.cap.phone.a11y.PhoneOcr
import com.wangzan101.mika.cap.phone.a11y.InjectedInputGate
import com.wangzan101.mika.cap.phone.a11y.OverlayWindow
import com.wangzan101.mika.cap.phone.a11y.ScreenAccessibility
import com.wangzan101.mika.core.uistate.VisibleTextLine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.ByteBuffer
import java.security.MessageDigest

/** 控件树不可读时，使用普通无障碍截图和内置 OCR 定位 USB 行；图片不落盘、不发给模型。 */
internal object SetupUsbScreenshot {
    enum class Result { Tapped, Scrolled, Retry, Unavailable, ActionUncertain }

    private data class Window(val id: Int, val generation: Long, val bounds: Rect)
    private data class Frame(val width: Int, val height: Int, val hash: List<Byte>, val lines: List<VisibleTextLine>)
    /** 标记留在超时作用域外，实际注入阶段的失败不能再变成允许其它路径重试。 */
    private class Attempt { var submitted = false }

    /** 开关为开也要看见正常开发者列表，不能把尚未结束的确认窗口当作完成。 */
    suspend fun developerPageVisible(
        service: ScreenAccessibility, lease: SetupAutomationWindowLease, overlay: OverlayWindow? = null,
    ): Boolean = try {
        withTimeoutOrNull(8_000) {
            val before = window(service, lease) ?: return@withTimeoutOrNull false
            val frame = read(service, before, overlay) ?: return@withTimeoutOrNull false
            SetupAutomationUsbOcr.choose(frame.lines) != null && window(service, lease) == before
        } == true
    } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { false }

    suspend fun act(
        service: ScreenAccessibility, lease: SetupAutomationWindowLease, canScroll: Boolean, overlay: OverlayWindow? = null,
    ): Result {
        val attempt = Attempt()
        return try {
            withTimeoutOrNull(20_000) { perform(service, lease, canScroll, overlay, attempt) }
                ?: if (attempt.submitted) Result.ActionUncertain else Result.Unavailable
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: InjectedInputGate.InputCompletionUnknown) {
            // 可能已注入的点击不能再交给另一条路径重试。
            Result.ActionUncertain
        } catch (error: Exception) {
            Log.i("MikaSetup", "usb screenshot unavailable category=${error.javaClass.simpleName}")
            if (attempt.submitted) Result.ActionUncertain else Result.Unavailable
        }
    }

    private suspend fun perform(
        service: ScreenAccessibility, lease: SetupAutomationWindowLease, canScroll: Boolean, overlay: OverlayWindow?,
        attempt: Attempt,
    ): Result {
        val before = window(service, lease) ?: return unavailable("WindowIdentity")
        if (DeveloperSettings.usbDebugging(service.contentResolver)) return Result.Retry
        val first = read(service, before, overlay) ?: return unavailable("Capture")
        if (window(service, lease) != before) return Result.Retry
        if (SetupAutomationUsbOcr.choose(first.lines) == null) return unavailable("PageRecognition")
        delay(350)
        val second = read(service, before, overlay) ?: return Result.Unavailable
        if (window(service, lease) != before || first.width != second.width || first.height != second.height) return Result.Retry
        val action = SetupAutomationUsbOcr.stable(first.lines, second.lines) ?: return Result.Retry
        if (action == SetupAutomationUsbOcr.Action.ScrollForward && !canScroll) return unavailable("ScrollLimit")

        // OCR 可能耗时；完成后再截一帧，正文像素改变就丢弃坐标。状态栏时钟不参与比对。
        val shot = capture(service, before, overlay) as? ScreenAccessibility.MainShot.Ok ?: return Result.Unavailable
        val unchanged = try {
            shot.bitmap.width == second.width && shot.bitmap.height == second.height && fingerprint(shot.bitmap) == second.hash
        } finally { shot.bitmap.recycle() }
        if (!unchanged || window(service, lease) != before) return Result.Retry
        val checkedAt = SystemClock.uptimeMillis()
        // 窗口截图的 OCR 坐标相对于窗口左上角；尺寸已在 capture 校验，不能按整屏猜比例。
        val offsetX = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) before.bounds.left else 0
        val offsetY = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) before.bounds.top else 0
        val x: Float
        val startY: Float
        val endY: Float
        when (action) {
            is SetupAutomationUsbOcr.Action.Tap -> {
                x = offsetX + action.x * (second.width - 1) / 999f
                startY = offsetY + action.y * (second.height - 1) / 999f
                endY = startY
            }
            SetupAutomationUsbOcr.Action.ScrollForward -> {
                x = offsetX + second.width * .14f
                startY = offsetY + second.height * .82f
                endY = offsetY + second.height * .42f
            }
        }
        val gestureBounds = Rect((x - 12).toInt(), (minOf(startY, endY) - 12).toInt(),
            (x + 12).toInt(), (maxOf(startY, endY) + 12).toInt())
        if (!before.bounds.contains(gestureBounds)) return Result.Unavailable
        val path = Path().apply { moveTo(x, startY); if (startY != endY) lineTo(x, endY) }
        currentCoroutineContext().ensureActive()
        val applied = service.guardedGesture(Display.DEFAULT_DISPLAY, path, if (startY == endY) 60 else 400) {
            // 校验在输入锁排队结束之后、真正 dispatchGesture 之前执行。
            (SystemClock.uptimeMillis() - checkedAt <= 500 && window(service, lease) == before &&
                DeveloperSettings.developerOptions(service.contentResolver) && !DeveloperSettings.usbDebugging(service.contentResolver) &&
                service.windowsOn(Display.DEFAULT_DISPLAY).none { other ->
                    other.id != before.id && Rect().let { bounds ->
                        other.getBoundsInScreen(bounds); Rect.intersects(bounds, gestureBounds)
                    }
                }).also { if (it) attempt.submitted = true }
        }
        currentCoroutineContext().ensureActive()
        if (!applied) return if (attempt.submitted) Result.ActionUncertain else Result.Retry
        return if (action is SetupAutomationUsbOcr.Action.Tap) Result.Tapped else Result.Scrolled
    }

    /** 优先核对系统包名；包名也被隐藏时，必须仍是本次主动打开的开发者窗口并通过整页 OCR。 */
    private fun window(service: ScreenAccessibility, lease: SetupAutomationWindowLease): Window? {
        if (ScreenAccessibility.instance.value !== service || service.getSystemService(KeyguardManager::class.java).isKeyguardLocked) return null
        val windows = service.windowsOn(Display.DEFAULT_DISPLAY)
        if (windows.count { it.type == AccessibilityWindowInfo.TYPE_APPLICATION } != 1) return null
        val top = windows.filter {
            it.type != AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY &&
                (it.type == AccessibilityWindowInfo.TYPE_APPLICATION || it.isActive || it.isFocused)
        }.maxByOrNull { it.layer } ?: return null
        if (top.type != AccessibilityWindowInfo.TYPE_APPLICATION || (!top.isActive && !top.isFocused)) return null
        val root = top.root
        val rootPackage = root?.packageName?.toString()
        @Suppress("DEPRECATION")
        root?.recycle()
        val eventPackage = service.observedWindowPackage(Display.DEFAULT_DISPLAY, top.id)
        if (rootPackage != null && eventPackage != null && rootPackage != eventPackage) return null
        if (!lease.accepts(top.id, top.title?.toString(), rootPackage ?: eventPackage, SystemClock.uptimeMillis())) return null
        return Window(top.id, ScreenAccessibility.windowGeneration(), Rect().also(top::getBoundsInScreen))
    }

    private suspend fun read(service: ScreenAccessibility, window: Window, overlay: OverlayWindow?): Frame? {
        val shot = capture(service, window, overlay) as? ScreenAccessibility.MainShot.Ok ?: return null
        val bitmap = shot.bitmap
        val width = bitmap.width
        val height = bitmap.height
        val hash = try { fingerprint(bitmap) } catch (error: Throwable) { bitmap.recycle(); throw error }
        // PhoneOcr 接管位图，异步识别结束后才回收；取消/超时同样由它清理。
        val lines = PhoneOcr.read(bitmap)
        return Frame(width, height, hash, lines)
    }

    /**
     * Android 14 起使用已校验的设置窗口截图，浮层持续显示，系统拒绝安全窗口时直接停止。
     * 旧系统仅采集时隐藏本次状态条；持有具体旧窗口，不回读新会话的 overlay。
     * 三次采集和完成状态检查共用此入口，窗口坐标必须与截图像素一一对应。
     */
    private suspend fun capture(service: ScreenAccessibility, window: Window, overlay: OverlayWindow?): ScreenAccessibility.MainShot {
        currentCoroutineContext().ensureActive()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val shot = service.takeWindowScreenshot(window.id)
            if (shot is ScreenAccessibility.MainShot.Ok &&
                (shot.bitmap.width != window.bounds.width() || shot.bitmap.height != window.bounds.height())) {
                shot.bitmap.recycle()
                Log.d("MikaSetup", "usb screenshot unavailable reason=WindowImageGeometry")
                return ScreenAccessibility.MainShot.Failed(android.accessibilityservice.AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR)
            }
            return shot
        }
        return if (overlay == null) service.takeMainScreenshot() else overlay.hiddenWhile {
            currentCoroutineContext().ensureActive()
            service.takeMainScreenshot()
        }
    }

    private fun unavailable(reason: String): Result {
        Log.d("MikaSetup", "usb screenshot unavailable reason=$reason")
        return Result.Unavailable
    }

    private fun fingerprint(bitmap: Bitmap): List<Byte> {
        val top = (bitmap.height / 30).coerceAtLeast(1)
        val pixels = IntArray(bitmap.width * (bitmap.height - top))
        bitmap.getPixels(pixels, 0, bitmap.width, 0, top, bitmap.width, bitmap.height - top)
        val buffer = ByteBuffer.allocate(pixels.size * 4)
        buffer.asIntBuffer().put(pixels)
        return MessageDigest.getInstance("SHA-256").digest(buffer.array()).toList()
    }
}
