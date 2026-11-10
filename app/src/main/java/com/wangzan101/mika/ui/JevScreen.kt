package com.wangzan101.mika.ui

import android.net.Uri
import android.graphics.Bitmap
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.R
import com.wangzan101.mika.jev.JevController
import com.wangzan101.mika.jev.JevCrop
import com.wangzan101.mika.jev.JevImages
import com.wangzan101.mika.jev.JevImageOcr
import com.wangzan101.mika.jev.JevPhase
import com.wangzan101.mika.jev.JevState
import com.wangzan101.mika.ui.components.ButtonKind
import com.wangzan101.mika.ui.components.FooterNote
import com.wangzan101.mika.ui.components.GroupCard
import com.wangzan101.mika.ui.components.MikaButton
import com.wangzan101.mika.ui.components.MikaDialog
import com.wangzan101.mika.ui.components.SubPage
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.coroutines.coroutineContext

/** Jev 只校验用户选中的文字区域，不自动截屏、不改变手机操作或聊天模型。 */
@Composable
fun JevScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val controller = remember { JevController.get(context) }
    val state by controller.state.collectAsState()
    var imageUri by rememberSaveable { mutableStateOf<String?>(null) }
    var imageName by rememberSaveable { mutableStateOf<String?>(null) }
    var selectionVersion by remember { mutableStateOf(0) }
    var selection by remember { mutableStateOf<JevSelection?>(null) }
    var imageLoading by remember { mutableStateOf(false) }
    var imageError by remember { mutableStateOf(false) }
    var crop by remember(selection) { mutableStateOf(JevCrop.Full) }
    var text by rememberSaveable { mutableStateOf("") }
    val pageScope = rememberCoroutineScope()
    var ocrBusy by remember { mutableStateOf(false) }
    var ocrError by remember { mutableStateOf<Int?>(null) }
    var ocrSelection by remember(selection, crop) { mutableStateOf<JevOcrSelection?>(null) }
    var ocrJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var ocrVersion by remember { mutableStateOf(0) }
    LaunchedEffect(selection, crop) {
        ocrVersion++
        ocrJob?.cancel()
        ocrSelection = null
        ocrError = null
        ocrBusy = false
    }
    var submittedSource by remember { mutableStateOf<JevImages.Source?>(null) }
    var submittedCrop by remember { mutableStateOf<JevCrop?>(null) }
    var submittedText by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            selection = null
            imageName = null
            imageLoading = true
            imageUri = uri.toString()
            selectionVersion++
            submittedSource = null
        }
    }
    LaunchedEffect(controller) { controller.refresh() }
    DisposableEffect(controller) { onDispose { controller.cancel() } }
    LaunchedEffect(imageUri, selectionVersion) {
        val requestVersion = selectionVersion
        val requestUri = imageUri
        selection = null
        imageName = null
        imageError = false
        val uri = imageUri?.let(Uri::parse)
        imageLoading = uri != null
        var decoded: Bitmap? = null
        var transferred = false
        try {
            if (uri != null) {
                val result = withContext(Dispatchers.IO) {
                    val source = JevImages.read(context, uri)
                    val preview = JevImages.preview(source).also { decoded = it }
                    JevSelection(source, preview)
                }
                if (requestVersion == selectionVersion && requestUri == imageUri) {
                    selection = result
                    // 已交给 Compose 的缩略图由 GC 回收，不能在仍可能引用它的绘制帧中 recycle。
                    transferred = true
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { if (requestVersion == selectionVersion && requestUri == imageUri) imageError = true }
        catch (_: OutOfMemoryError) { if (requestVersion == selectionVersion && requestUri == imageUri) imageError = true }
        finally {
            if (!transferred) decoded?.recycle()
            if (requestVersion == selectionVersion && requestUri == imageUri) imageLoading = false
        }
        val name = if (uri == null) null else withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }
            }.getOrNull() ?: uri.lastPathSegment
        }
        if (requestVersion == selectionVersion && requestUri == imageUri) imageName = name
    }
    val close = { controller.cancel(); onBack() }
    BackHandler(onBack = close)
    // 图或文字改过后不展示上一份输入的分数，避免把旧结果当成当前结果。
    val visible = if (submittedSource != null && submittedSource === selection?.source && submittedText == text && submittedCrop == crop) state
        else state.copy(score = null, cacheHit = false, loadMs = null, scoreMs = null)
    JevContent(
        state = visible, imageName = imageName, hasImage = selection != null, text = text,
        onText = { text = it.take(MAX_TEXT_LENGTH) }, onPick = { picker.launch("image/*") },
        onScore = {
            selection?.let { selected ->
                submittedSource = selected.source
                submittedCrop = crop
                submittedText = text
                controller.score(selected.source, text, crop)
            }
        },
        onDownload = controller::download, onCancel = controller::cancel, onRemove = controller::remove,
        onBack = close, onRecheck = controller::refresh,
        imageEditor = {
            if (imageLoading) LinearProgressIndicator(Modifier.fillMaxWidth().testTag("jev-image-loading"))
            if (imageError) Text(stringResource(R.string.jev_image_error), color = MikaTheme.colors.bad)
            selection?.let { selected ->
                JevCropEditor(selected.preview, crop, { crop = it }, state.phase == JevPhase.Idle && !ocrBusy)
                MikaButton(stringResource(R.string.jev_read_image), {
                    val selectedCrop = crop
                    val requestVersion = ++ocrVersion
                    fun currentRequest() = requestVersion == ocrVersion && selection?.source === selected.source && crop == selectedCrop
                    ocrBusy = true
                    ocrError = null
                    ocrJob = pageScope.launch {
                        try {
                            val result = withContext(Dispatchers.IO) { JevImageOcr.read(selected.source, selectedCrop) }
                            if (currentRequest()) {
                                ocrSelection = JevOcrSelection(selected.source, selectedCrop, result)
                                ocrError = R.string.jev_ocr_empty.takeIf { result.lines.isEmpty() }
                            }
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: OutOfMemoryError) { coroutineContext.ensureActive(); if (currentRequest()) ocrError = R.string.jev_memory_low }
                        catch (_: Exception) { if (currentRequest()) ocrError = R.string.jev_ocr_empty }
                        finally { if (requestVersion == ocrVersion) ocrBusy = false }
                    }
                }, Modifier.fillMaxWidth().testTag("jev-read-image"), enabled = !ocrBusy && state.phase == JevPhase.Idle, kind = ButtonKind.Secondary)
                if (ocrBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
                ocrError?.let { Text(stringResource(it), color = MikaTheme.colors.warn) }
                val recognized = ocrSelection
                recognized?.result?.lines?.forEach { line ->
                    TextButton(onClick = {
                        // 候选绑定到源图和请求框；快速重复点击或旧一帧的按钮不能把框再套到新区域。
                        if (recognized.source === selection?.source && recognized.requestedCrop == crop) {
                            text = line.text.take(MAX_TEXT_LENGTH)
                            recognized.result.cropFor(line.bounds)?.let { crop = it }
                        }
                    }, enabled = state.phase == JevPhase.Idle) { Text(line.text) }
                }
            }
        },
    )
}

/** 不带系统操作的内容层，便于验证下载、停止、输入和评分入口。 */
@Composable
internal fun JevContent(
    state: JevState,
    imageName: String?,
    hasImage: Boolean,
    text: String,
    onText: (String) -> Unit,
    onPick: () -> Unit,
    onScore: () -> Unit,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onRemove: () -> Unit,
    onBack: () -> Unit,
    imageEditor: @Composable () -> Unit = {},
    onRecheck: () -> Unit = {},
) {
    val busy = state.phase != JevPhase.Idle
    var confirmRemove by remember { mutableStateOf(false) }
    val c = MikaTheme.colors
    SubPage(stringResource(R.string.jev_title), onBack, Modifier.testTag("jev-page")) {
        GroupCard {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("MiniCPM-OCR-Jev · Q4_K_M", style = MikaType.Body, fontWeight = FontWeight.SemiBold, color = c.ink)
                Text(stringResource(R.string.jev_intro), style = MikaType.Caption, color = c.ink2)
                Text(stringResource(R.string.jev_download_note), style = MikaType.Caption, color = c.ink2)
                state.deviceNote?.let { Text(it, Modifier.testTag("jev-device-note"), style = MikaType.Caption, color = c.ink2) }
                if (!busy && !state.downloadAllowed) TextButton(onClick = onRecheck) { Text(stringResource(R.string.jev_recheck)) }
                if (!state.supported) Text(stringResource(R.string.jev_unavailable), Modifier.testTag("jev-unavailable"), style = MikaType.Caption, color = c.warn)
                if (busy) {
                    Text(stringResource(when (state.phase) {
                        JevPhase.Downloading -> R.string.jev_downloading
                        JevPhase.Verifying -> R.string.jev_verifying
                        JevPhase.Loading -> R.string.jev_loading
                        JevPhase.Scoring -> R.string.jev_scoring
                        JevPhase.Idle -> R.string.jev_ready
                    }), Modifier.testTag("jev-progress-label"), style = MikaType.Body, color = c.ink)
                    if (state.phase == JevPhase.Downloading && state.total > 0) {
                        val progress = (state.done.toDouble() / state.total).toFloat().coerceIn(0f, 1f)
                        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().testTag("jev-progress"))
                        Text(stringResource(R.string.jev_progress, (progress * 100).toInt()), style = MikaType.Caption, color = c.ink2)
                    } else LinearProgressIndicator(Modifier.fillMaxWidth().testTag("jev-progress"))
                    MikaButton(stringResource(R.string.jev_stop), onCancel, Modifier.fillMaxWidth().testTag("jev-stop"), kind = ButtonKind.Secondary)
                } else if (state.ready) {
                    Text(stringResource(R.string.jev_ready), Modifier.testTag("jev-ready"), style = MikaType.Caption, color = c.ok)
                    MikaButton(stringResource(R.string.jev_remove), { confirmRemove = true }, Modifier.fillMaxWidth().testTag("jev-remove"), kind = ButtonKind.Text)
                } else {
                    MikaButton(stringResource(R.string.jev_download), onDownload, Modifier.fillMaxWidth().testTag("jev-download"), enabled = state.supported && state.downloadAllowed)
                    if (state.done > 0L) MikaButton(
                        stringResource(R.string.jev_remove), { confirmRemove = true },
                        Modifier.fillMaxWidth().testTag("jev-remove"), kind = ButtonKind.Text,
                    )
                }
            }
        }
        FooterNote(stringResource(R.string.jev_leave_note))
        state.error?.let { Text(it, Modifier.padding(vertical = 8.dp).testTag("jev-error"), style = MikaType.Caption, color = c.bad) }
        if (state.ready) {
            GroupCard(Modifier.padding(top = 12.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.jev_input_title), style = MikaType.Body, fontWeight = FontWeight.SemiBold, color = c.ink)
                    Text(stringResource(R.string.jev_crop_hint), style = MikaType.Caption, color = c.ink2)
                    MikaButton(stringResource(R.string.jev_choose_image), onPick, Modifier.fillMaxWidth().testTag("jev-pick-image"), kind = ButtonKind.Secondary, enabled = !busy && state.supported)
                    if (hasImage) Text(imageName ?: stringResource(R.string.jev_image_selected), Modifier.testTag("jev-image-name"), style = MikaType.Caption, color = c.ink2)
                    imageEditor()
                    OutlinedTextField(
                        value = text, onValueChange = onText,
                        modifier = Modifier.fillMaxWidth().testTag("jev-text"),
                        enabled = !busy && state.supported, minLines = 3, maxLines = 8,
                        label = { Text(stringResource(R.string.jev_text_label)) },
                        supportingText = { Text(stringResource(R.string.jev_text_count, text.length, MAX_TEXT_LENGTH)) },
                    )
                    MikaButton(stringResource(R.string.jev_score), onScore, Modifier.fillMaxWidth().testTag("jev-score"), enabled = !busy && state.supported && hasImage && text.isNotBlank() && text.length <= MAX_TEXT_LENGTH)
                    state.score?.takeIf { it.isFinite() && it in 0.0..1.0 }?.let { score ->
                        Text(stringResource(R.string.jev_score_value, String.format(Locale.ROOT, "%.3f", score)), Modifier.testTag("jev-score-value"), style = MikaType.Title, color = c.ink)
                        Text(stringResource(R.string.jev_score_note), Modifier.testTag("jev-score-note"), style = MikaType.Caption, color = c.ink2)
                        if (state.cacheHit) Text(stringResource(R.string.jev_cached), Modifier.testTag("jev-cached"), style = MikaType.Caption, color = c.ink2)
                        else if (state.loadMs != null && state.scoreMs != null) Text(
                            stringResource(R.string.jev_timing,
                                String.format(Locale.ROOT, "%.1f", state.loadMs / 1000.0),
                                String.format(Locale.ROOT, "%.1f", state.scoreMs / 1000.0)),
                            Modifier.testTag("jev-timing"), style = MikaType.Caption, color = c.ink2,
                        )
                    }
                }
            }
        }
    }
    if (confirmRemove) MikaDialog(
        onDismissRequest = { confirmRemove = false },
        title = { Text(stringResource(R.string.jev_remove)) },
        text = { Text(stringResource(R.string.jev_remove_confirm)) },
        confirmButton = { TextButton({ confirmRemove = false; onRemove() }, enabled = !busy, modifier = Modifier.testTag("jev-remove-confirm")) { Text(stringResource(R.string.jev_remove)) } },
        dismissButton = { TextButton({ confirmRemove = false }) { Text(stringResource(R.string.cancel)) } },
    )
}

private const val MAX_TEXT_LENGTH = 1_000

private data class JevSelection(val source: JevImages.Source, val preview: Bitmap)
private data class JevOcrSelection(val source: JevImages.Source, val requestedCrop: JevCrop, val result: JevImageOcr.Result)
