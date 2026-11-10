package com.wangzan101.mika.cap.phone.a11y

import android.graphics.Bitmap
import android.util.Log
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.wangzan101.mika.core.uistate.VisibleTextLine
import com.wangzan101.mika.core.uistate.normalize
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.coroutineContext

/** 两套内置模型均在本机运行；同一帧并行识别，完成后才释放位图。两套都出错时抛异常(不当成没字)。 */
internal object PhoneOcr {
    private const val TAG = "PhoneOcr"
    // 完成处理不必排到主线程；同一请求的并发回调由 OcrReadSession 汇合。
    private val callbacks = Executor { it.run() }

    private val chinese: TextRecognizer by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }
    private val latin: TextRecognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    suspend fun read(bitmap: Bitmap): List<VisibleTextLine> {
        val recognizers = try { coroutineContext.ensureActive(); listOf(chinese, latin) } catch (e: Throwable) {
            bitmap.recycle()
            throw e
        }
        val image = try { InputImage.fromBitmap(bitmap, 0) } catch (e: Throwable) {
            bitmap.recycle()
            throw e
        }
        return suspendCancellableCoroutine { continuation ->
            val session = try {
                OcrReadSession<VisibleTextLine>(recognizers.size, { continuation.isActive }, bitmap::recycle) { result ->
                    result.fold(continuation::resume, continuation::resumeWithException)
                }
            } catch (error: Throwable) { bitmap.recycle(); throw error }
            var startupError: Error? = null
            recognizers.forEachIndexed { index, recognizer ->
                val stopped = startupError
                if (!continuation.isActive || stopped != null) {
                    session.finish(index) { if (stopped != null) throw stopped else emptyList() }
                    return@forEachIndexed
                }
                val task: Task<Text> = try {
                    recognizer.process(image)
                } catch (error: Throwable) {
                    if (error is Error) startupError = error
                    session.finish(index) { throw error }
                    return@forEachIndexed
                }
                // 提交成功后不能把注册回调失败当作“识别已结束”并回收输入。
                // 若注册本身抛错，请求向外失败，图片留给仍持有 InputImage 的任务和 GC 管理。
                task.addOnCompleteListener(callbacks) { done ->
                    session.finish(index) {
                        if (!done.isSuccessful) {
                            Log.w(TAG, "recognizer failed", done.exception)
                            throw done.exception ?: IllegalStateException("text recognition failed")
                        }
                        readLines(done.result, bitmap.width, bitmap.height) { continuation.isActive }
                    }
                }
            }
        }
    }

    private fun readLines(result: Text, width: Int, height: Int, active: () -> Boolean): List<VisibleTextLine> {
        val lines = mutableListOf<VisibleTextLine>()
        for (block in result.textBlocks) for (line in block.lines) {
            if (!active()) return emptyList()
            val box = line.boundingBox ?: continue
            val value = line.text.trim()
            // 有的识别模型不给置信度(为 0),当作没有。
            if (value.isNotEmpty()) lines += VisibleTextLine(value, listOf(
                normalize(box.left, width), normalize(box.top, height),
                normalize(box.right, width), normalize(box.bottom, height),
            ), line.confidence.takeIf { it > 0f })
        }
        return lines
    }
}
