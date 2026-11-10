package com.wangzan101.mika.cap.phone.a11y

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import com.wangzan101.mika.cap.phone.display.AgentDisplay
import com.wangzan101.mika.cap.phone.display.DisplayFrameGeometry
import com.wangzan101.mika.cap.phone.display.DisplayRegionPlan
import com.wangzan101.mika.cap.phone.display.drawDisplayRegion
import com.wangzan101.mika.core.uistate.TextVerification
import com.wangzan101.mika.core.uistate.TextVerificationCandidate
import com.wangzan101.mika.core.uistate.TextVerificationCandidates
import com.wangzan101.mika.jev.JevAgentException
import com.wangzan101.mika.jev.JevController
import com.wangzan101.mika.jev.JevImages
import com.wangzan101.mika.jev.JevProtocol
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.coroutines.coroutineContext

/** 任务专用入口：截图/OCR/评分共用一个区域快照，绝不触发下载或修改手动校验页状态。 */
internal object AgentTextVerification {
    suspend fun verify(context: Context, display: AgentDisplay, text: String?, region: List<Int>): TextVerification =
        withContext(Dispatchers.Default) {
            require(TextVerificationCandidates.validRegion(region))
            require(text == null || text.isNotBlank() && text.length <= TextVerificationCandidates.MAX_TEXT)
            val controller = JevController.get(context)
            controller.agentAvailabilityError()?.let { return@withContext failure(region, it) }
            coroutineContext.ensureActive()
            val snapshot = try { display.latestRegionSnapshot(region) }
            catch (_: OutOfMemoryError) { coroutineContext.ensureActive(); return@withContext failure(region, "memory_low") }
                ?: return@withContext failure(region, "frame_unavailable")
            val capturedAt = SystemClock.elapsedRealtime()
            val width = snapshot.plan.sourceWidth
            val height = snapshot.plan.sourceHeight
            var result = TextVerification(region = region, producerTimestampNanos = snapshot.timestampNanos,
                sourceWidth = width, sourceHeight = height)
            try {
                // 只为目标区域分配至多 1024² 像素；不先把整图缩小，避免小字在裁剪前丢失。
                val cropped = snapshot.bitmap
                val source = try {
                    coroutineContext.ensureActive()
                    val candidates = if (text == null) {
                        // PhoneOcr 接管副本，直到两套异步识别回调结束才回收；它不持有后续编码用的图。
                        val copy = cropped.copy(Bitmap.Config.ARGB_8888, false) ?: error("bitmap copy failed")
                        val lines = try { PhoneOcr.read(copy) }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { return@withContext finish(result.copy(error = "ocr_failed", message = message("ocr_failed")), display, capturedAt) }
                        val mapped = lines.filter { TextVerificationCandidates.validRegion(it.bounds) }
                            .map { it.copy(bounds = snapshot.plan.screenBounds(it.bounds)) }
                        TextVerificationCandidates.fromOcr(mapped, listOf(0, 0, 999, 999))
                    } else listOf(TextVerificationCandidate(text, region, "provided"))
                    val candidate = if (text == null) TextVerificationCandidates.unique(candidates) else candidates.single()
                    result = result.copy(candidate = candidate, candidates = candidates.take(TextVerificationCandidates.MAX_CANDIDATES))
                    if (candidate == null) {
                        val code = if (candidates.isEmpty()) "ocr_empty" else "candidate_ambiguous"
                        return@withContext finish(result.copy(error = code, message = message(code)), display, capturedAt)
                    }
                    coroutineContext.ensureActive()
                    JevImages.Source(encode(cropped))
                } finally { cropped.recycle() }
                coroutineContext.ensureActive()
                val scored = controller.evaluateAgent(source, requireNotNull(result.candidate).text)
                coroutineContext.ensureActive()
                finish(result.copy(score = scored.score, cacheHit = scored.cacheHit, loadMs = scored.loadMs, scoreMs = scored.scoreMs), display, capturedAt)
            } catch (cancelled: CancellationException) {
                // 让任务取消一直传播到 evaluateAgent/原生子进程的 finally，不伪装成低分或普通失败。
                throw cancelled
            } catch (failure: JevAgentException) {
                finish(result.copy(error = failure.code, message = message(failure.code)), display, capturedAt)
            } catch (_: OutOfMemoryError) {
                coroutineContext.ensureActive()
                finish(result.copy(error = "memory_low", message = message("memory_low")), display, capturedAt)
            } catch (_: Exception) {
                finish(result.copy(error = "image_error", message = message("image_error")), display, capturedAt)
            } finally {
                if (!snapshot.bitmap.isRecycled) snapshot.bitmap.recycle()
            }
        }

    private fun finish(result: TextVerification, display: AgentDisplay, capturedAt: Long): TextVerification {
        val current = display.lastFrameAt()
        if (current == null) return result.copy(score = null, error = "frame_unavailable", message = message("frame_unavailable"),
            capturedAgoMs = (SystemClock.elapsedRealtime() - capturedAt).coerceAtLeast(0))
        return result.copy(capturedAgoMs = (SystemClock.elapsedRealtime() - capturedAt).coerceAtLeast(0),
            newerFrameAvailable = current != result.producerTimestampNanos)
    }

    /** 保留指定区域的比例与细节；传给 OCR 和 Jev 的像素完全一致。 */
    internal fun crop(source: Bitmap, region: List<Int>): Bitmap = drawDisplayRegion(source,
        DisplayFrameGeometry(source.width, source.height, 0), DisplayRegionPlan.of(source.width, source.height, region))

    private fun encode(bitmap: Bitmap): ByteArray = object : ByteArrayOutputStream() {
        override fun write(value: Int) {
            require(count < JevProtocol.MAX_IMAGE_BYTES)
            super.write(value)
        }
        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            require(length >= 0 && count <= JevProtocol.MAX_IMAGE_BYTES - length)
            super.write(bytes, offset, length)
        }
    }.use { output ->
        check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
        output.toByteArray()
    }

    private fun failure(region: List<Int>, code: String) = TextVerification(region = region, error = code, message = message(code))

    private fun message(code: String): String = when (code) {
        "model_missing" -> "尚未安装可用的 Jev 模型；请继续使用普通读屏，用户可自行到设置下载。"
        "unavailable", "unsupported" -> "此设备不支持本地 Jev 校验，请使用普通读屏。"
        "jev_busy" -> "本地 Jev 正在处理其他请求；本次没有排队，也没有启动新的推理。"
        "integrity_error" -> "Jev 模型完整性校验失败，请用户在设置中检查模型。"
        "memory_low" -> "当前可用内存不足，已停止本次校验；请使用普通读屏。"
        "text_layout" -> "候选文字过长或版式超出支持范围，请缩小到单个普通文字字段。"
        "timeout" -> "本地校验已超时，不能据此判断文字是否正确。"
        "runtime_error" -> "本地校验运行失败，请使用普通读屏。"
        "ocr_empty" -> "指定区域没有可用的 OCR 候选；请调整区域或明确提供待校验文字。"
        "ocr_failed" -> "指定区域的 OCR 识别失败，没有启动 Jev。"
        "candidate_ambiguous" -> "同一帧的区域存在多个 OCR 候选；请缩小区域或明确提供其中一条文字。"
        "frame_unavailable" -> "当前没有可用画面，请重新读屏后再试。"
        else -> "无法准备这次校验图片，请重新读屏并选择文字区域。"
    }
}
