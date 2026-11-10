package com.wangzan101.mika.cap.phone.display

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import com.wangzan101.mika.shell.IMikaShell
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.ByteArrayOutputStream

/**
 * Agent 专用的虚拟屏。画面输出到本进程的 ImageReader,截图不依赖无障碍截图接口。
 * 帧处理限速到 ≤10 帧/秒:来帧时若离上次处理不足 100ms 就延后到期再取最新一帧,
 * 期间旧帧由 acquireLatestImage 丢弃。
 */
class AgentDisplay(
    private val shell: IMikaShell,
    private val bufferWidth: Int,
    private val bufferHeight: Int,
    dpi: Int,
    private val displayManager: DisplayManager,
    private val sessionToken: String? = null,
    private val onReleased: () -> Unit = {},
) {
    private val thread = HandlerThread("agent-display").apply { start() }
    private val handler = Handler(thread.looper)
    private val reader = ImageReader.newInstance(bufferWidth, bufferHeight, PixelFormat.RGBA_8888, 2)

    val displayId: Int

    // ImageReader 始终是建屏时的物理尺寸;应用的逻辑坐标随虚拟屏旋转,与主屏的方向无关。
    private val geometry get() = DisplayFrameGeometry(bufferWidth, bufferHeight,
        displayManager.getDisplay(displayId)?.rotation ?: Surface.ROTATION_0)
    val width: Int get() = geometry.width
    val height: Int get() = geometry.height

    private val _frames = MutableStateFlow<Bitmap?>(null)
    /** 仅在有可见消费者时发布的 UI 预览，≤5 帧/秒；内部截图和分析不依赖这个流。 */
    val frames: StateFlow<Bitmap?> = _frames.asStateFlow()

    private val frameLock = Any()
    private var fullFrame: Bitmap? = null // 带行填充的整帧,宽 = rowStride / pixelStride
    private var hasFrame = false
    private var frameGeometry = DisplayFrameGeometry(bufferWidth, bufferHeight, Surface.ROTATION_0)
    private var frameAnalysis: DisplayFrameAnalysis? = null
    // 只由帧线程改写；关闭预览不影响原帧更新。
    private var previewVisible = false
    private var previewAt = Long.MIN_VALUE
    private var lastProcessedAt = 0L
    private var pending = false
    /** 虚拟屏生产者的单调时钟纳秒时间戳，与整帧和缩略帧一起在 frameLock 下发布。 */
    private var frameTimestampNanos = 0L
    @Volatile private var released = false

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)

    private val process = Runnable {
        pending = false
        lastProcessedAt = SystemClock.uptimeMillis()
        processLatest()
    }
    private val publishPreview = Runnable { synchronized(frameLock) { publishPreviewLocked() } }

    init {
        val id = try {
            if (sessionToken == null) shell.createDisplay(reader.surface, bufferWidth, bufferHeight, dpi)
            else shell.openDisplaySession(sessionToken, reader.surface, bufferWidth, bufferHeight, dpi)
        } catch (t: Throwable) {
            closeLocal()
            throw t
        }
        if (id < 0) {
            closeLocal()
            throw IllegalStateException("createDisplay failed")
        }
        displayId = id
        reader.setOnImageAvailableListener({ schedule() }, handler)
        // 建屏期间可能已经有帧排进来;先有正确的屏号,再处理这些帧。
        handler.post { schedule() }
    }

    private fun schedule() {
        if (pending || released) return
        pending = true
        val wait = lastProcessedAt + FRAME_INTERVAL_MS - SystemClock.uptimeMillis()
        if (wait > 0) handler.postDelayed(process, wait) else handler.post(process)
    }

    private fun processLatest() {
        if (released) return
        val image = try {
            reader.acquireLatestImage()
        } catch (t: Throwable) {
            // 没拿到新帧时沿用现有null/新鲜度保护;留下取帧失败证据,不把旧帧当新帧。
            Log.w(TAG, "acquireLatestImage failed: ${t.javaClass.simpleName}", t)
            null
        } ?: return
        try {
            val plane = image.planes[0]
            val strideWidth = plane.rowStride / plane.pixelStride
            val capturedGeometry = geometry
            synchronized(frameLock) {
                var bmp = fullFrame
                if (bmp == null || bmp.width != strideWidth) {
                    bmp?.recycle()
                    bmp = Bitmap.createBitmap(strideWidth, bufferHeight, Bitmap.Config.ARGB_8888)
                    fullFrame = bmp
                }
                bmp!!.copyPixelsFromBuffer(plane.buffer.rewind())
                hasFrame = true
                // 限速或线程调度会延后消费，不能把复制位图的时刻记成帧时刻。
                frameTimestampNanos = image.timestamp
                frameGeometry = capturedGeometry
                frameAnalysis = DisplayFrameAnalysis(capturedGeometry, bmp::getPixel)
                publishPreviewLocked()
            }
        } finally {
            image.close()
        }
    }

    /** UI 的生命周期租约合并后调用；重新展开时即使画面静止也立即发布最新内部帧。 */
    fun setPreviewVisible(visible: Boolean) {
        if (released) return
        handler.post {
            if (released || previewVisible == visible) return@post
            previewVisible = visible
            synchronized(frameLock) {
                if (visible) publishPreviewLocked(force = true) else {
                    handler.removeCallbacks(publishPreview)
                    _frames.value = null
                }
            }
        }
    }

    private fun publishPreviewLocked(force: Boolean = false) {
        val bmp = fullFrame ?: return
        if (!previewVisible || !hasFrame || released) return
        val now = SystemClock.uptimeMillis()
        if (!force && previewAt != Long.MIN_VALUE && now - previewAt < PREVIEW_INTERVAL_MS) {
            // 最后一帧可能落在限速区间内；到期发布它，不能等下一次应用重绘才更新。
            handler.removeCallbacks(publishPreview)
            handler.postDelayed(publishPreview, PREVIEW_INTERVAL_MS - (now - previewAt))
            return
        }
        handler.removeCallbacks(publishPreview)
        previewAt = now
        _frames.value = uprightDisplayFrame(bmp, frameGeometry, frameGeometry.previewWidth(PREVIEW_SHORT_SIDE), paint)
    }

    fun latestFrameSignature(): IntArray? = synchronized(frameLock) {
        if (released) null else frameAnalysis?.signature?.copyOf()
    }

    fun latestFrameBlack(): Boolean? = synchronized(frameLock) {
        if (released) null else frameAnalysis?.black
    }

    fun latestFrameGrid(): com.wangzan101.mika.core.visual.FrameGrid? = synchronized(frameLock) {
        if (released) null else frameAnalysis?.grid?.let {
            com.wangzan101.mika.core.visual.FrameGrid(it.cols, it.rows, it.luma.copyOf())
        }
    }

    /** 最新一帧的整幅 PNG;还没收到画面时返回 null。 */
    fun latestFramePng(): ByteArray? {
        val crop = latestFrameBitmap() ?: return null
        return try {
            ByteArrayOutputStream().also { crop.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        } finally {
            crop.recycle()
        }
    }

    /** 按需给模型的连续观察帧，长边至多 640；先筛选再编码，帧号与像素在同一个锁内取得。 */
    fun latestObservation(afterNanos: Long,
        shouldEncode: (com.wangzan101.mika.core.visual.FramePreview) -> Boolean = { true },
        ensureActive: () -> Unit = {},
    ): com.wangzan101.mika.core.visual.ObservedFrame? {
        ensureActive()
        val captured = synchronized(frameLock) {
            val bmp = fullFrame
            if (released || !hasFrame || bmp == null || frameTimestampNanos <= afterNanos) return null
            val width = (frameGeometry.width * minOf(1.0, 640.0 / maxOf(frameGeometry.width, frameGeometry.height))).toInt().coerceAtLeast(1)
            frameTimestampNanos to uprightDisplayFrame(bmp, frameGeometry, width, paint)
        }
        return encodeObservationFrame(captured.second, captured.first, shouldEncode, ensureActive)
    }

    /** 最新已发布帧的单调时钟纳秒时间戳。 */
    fun lastFrameAt(): Long? = synchronized(frameLock) {
        frameTimestampNanos.takeIf { !released && it > 0 }
    }

    /** OCR 使用原始尺寸的画面副本，避免 PNG 编码和解码；调用方负责 recycle。 */
    fun latestFrameBitmap(): Bitmap? = latestFrameSnapshot()?.bitmap

    /** 像素与生产者时间戳来自同一帧；调用方负责回收 bitmap。 */
    data class DisplaySnapshot(val timestampNanos: Long, val bitmap: Bitmap)

    fun latestFrameSnapshot(): DisplaySnapshot? = synchronized(frameLock) {
        val bmp = fullFrame
        if (released || !hasFrame || bmp == null) null else
            DisplaySnapshot(frameTimestampNanos, uprightDisplayFrame(bmp, frameGeometry, paint = paint))
    }

    internal data class RegionSnapshot(val timestampNanos: Long, val bitmap: Bitmap, val plan: DisplayRegionPlan)

    /** 帧号、几何和选区像素在同一锁内获取，无整屏中间副本；调用方负责回收。 */
    internal fun latestRegionSnapshot(region: List<Int>): RegionSnapshot? = synchronized(frameLock) {
        val bmp = fullFrame
        if (released || !hasFrame || bmp == null) null else {
            val plan = DisplayRegionPlan.of(frameGeometry.width, frameGeometry.height, region)
            RegionSnapshot(frameTimestampNanos, drawDisplayRegion(bmp, frameGeometry, plan, paint), plan)
        }
    }

    fun release() {
        if (released) return
        released = true
        runCatching { shell.releaseDisplay(displayId) }
        closeLocal()
        _frames.value = null
        runCatching { onReleased() }
    }

    private fun closeLocal() {
        released = true
        handler.removeCallbacksAndMessages(null)
        // 在帧线程上收尾,避免与正在处理的帧并发
        handler.post {
            reader.close()
            synchronized(frameLock) {
                fullFrame?.recycle()
                fullFrame = null
                frameAnalysis = null
                hasFrame = false
            }
        }
        thread.quitSafely()
    }

    private companion object {
        const val TAG = "AgentDisplay"
        const val PREVIEW_SHORT_SIDE = 360
        const val FRAME_INTERVAL_MS = 100L
        const val PREVIEW_INTERVAL_MS = 200L
    }
}
