package com.wangzan101.mika.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.R
import com.wangzan101.mika.jev.JevCrop
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType
import kotlin.math.roundToInt

/** 滑杆既可触摸也可由无障碍调整，预览和评分共用原图的归一化坐标。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun JevCropEditor(bitmap: Bitmap, crop: JevCrop, onCrop: (JevCrop) -> Unit, enabled: Boolean) {
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    val colors = MikaTheme.colors
    Canvas(Modifier.fillMaxWidth().height(260.dp).testTag("jev-crop-preview")) {
        val scale = minOf(size.width / bitmap.width, size.height / bitmap.height)
        val width = (bitmap.width * scale).roundToInt().coerceAtLeast(1)
        val height = (bitmap.height * scale).roundToInt().coerceAtLeast(1)
        val x = ((size.width - width) / 2).roundToInt()
        val y = ((size.height - height) / 2).roundToInt()
        drawImage(image, dstOffset = IntOffset(x, y), dstSize = IntSize(width, height))
        val left = x + width * crop.left
        val top = y + height * crop.top
        val right = x + width * crop.right
        val bottom = y + height * crop.bottom
        val shade = Color.Black.copy(alpha = 0.55f)
        drawRect(shade, Offset(x.toFloat(), y.toFloat()), Size(width.toFloat(), top - y))
        drawRect(shade, Offset(x.toFloat(), bottom), Size(width.toFloat(), y + height - bottom))
        drawRect(shade, Offset(x.toFloat(), top), Size(left - x, bottom - top))
        drawRect(shade, Offset(right, top), Size(x + width - right, bottom - top))
        drawRect(Color.White, Offset(left, top), Size(right - left, bottom - top), style = Stroke(2.dp.toPx()))
    }
    if (crop != JevCrop.Full) {
        Text(stringResource(R.string.jev_crop_detail), style = MikaType.Caption, color = colors.ink2)
        Canvas(Modifier.fillMaxWidth().height(140.dp).testTag("jev-crop-detail")) {
            val left = (bitmap.width * crop.left).toInt().coerceIn(0, bitmap.width - 1)
            val top = (bitmap.height * crop.top).toInt().coerceIn(0, bitmap.height - 1)
            val width = ((bitmap.width * crop.right).toInt() - left).coerceIn(1, bitmap.width - left)
            val height = ((bitmap.height * crop.bottom).toInt() - top).coerceIn(1, bitmap.height - top)
            val scale = minOf(size.width / width, size.height / height)
            val dst = IntSize((width * scale).roundToInt().coerceAtLeast(1), (height * scale).roundToInt().coerceAtLeast(1))
            drawImage(image, srcOffset = IntOffset(left, top), srcSize = IntSize(width, height),
                dstOffset = IntOffset(((size.width - dst.width) / 2).toInt(), ((size.height - dst.height) / 2).toInt()), dstSize = dst)
        }
    }
    Text(stringResource(R.string.jev_crop_horizontal), style = MikaType.Caption, color = colors.ink2)
    RangeSlider(value = crop.left..crop.right, onValueChange = {
        if (it.endInclusive - it.start >= 0.005f) onCrop(crop.copy(left = it.start, right = it.endInclusive))
    }, enabled = enabled, modifier = Modifier.fillMaxWidth().testTag("jev-crop-horizontal"))
    Text(stringResource(R.string.jev_crop_vertical), style = MikaType.Caption, color = colors.ink2)
    RangeSlider(value = crop.top..crop.bottom, onValueChange = {
        if (it.endInclusive - it.start >= 0.005f) onCrop(crop.copy(top = it.start, bottom = it.endInclusive))
    }, enabled = enabled, modifier = Modifier.fillMaxWidth().testTag("jev-crop-vertical"))
    TextButton(onClick = { onCrop(JevCrop.Full) }, enabled = enabled && crop != JevCrop.Full,
        modifier = Modifier.testTag("jev-crop-reset")) { Text(stringResource(R.string.jev_crop_reset)) }
}
