package com.wangzan101.mika.ui

import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.R
import com.wangzan101.mika.localmodel.LocalModelController
import com.wangzan101.mika.localmodel.LocalModelPhase
import com.wangzan101.mika.localmodel.LocalModelState
import com.wangzan101.mika.settings.ProviderStore
import com.wangzan101.mika.ui.components.ButtonKind
import com.wangzan101.mika.ui.components.FooterNote
import com.wangzan101.mika.ui.components.GroupCard
import com.wangzan101.mika.ui.components.MikaButton
import com.wangzan101.mika.ui.components.MikaDialog
import com.wangzan101.mika.ui.components.ProgressDots
import com.wangzan101.mika.ui.components.SubPage
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType

/** 首次第二步先选运行方式；不在展示页面时下载或替换用户已有的主模型。 */
@Composable
internal fun ModelModeChoice(onLocal: () -> Unit, onOnline: () -> Unit, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    SubPage(stringResource(R.string.local_mode_title), onBack, Modifier.testTag("model-mode-choice")) {
        Text(stringResource(R.string.local_mode_body), style = MikaType.Body, color = MikaTheme.colors.ink2,
            modifier = Modifier.padding(bottom = 16.dp))
        GroupCard {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.local_model_title), style = MikaType.Title)
                Text(stringResource(R.string.local_mode_local_body), style = MikaType.Body, color = MikaTheme.colors.ink2)
                MikaButton(stringResource(R.string.local_mode_local), onLocal,
                    Modifier.fillMaxWidth().testTag("model-mode-local"))
            }
        }
        GroupCard(Modifier.padding(top = 12.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.local_mode_online), style = MikaType.Title)
                Text(stringResource(R.string.local_mode_online_body), style = MikaType.Body, color = MikaTheme.colors.ink2)
                MikaButton(stringResource(R.string.local_mode_online), onOnline,
                    Modifier.fillMaxWidth().testTag("model-mode-online"), kind = ButtonKind.Secondary)
            }
        }
        ProgressDots(Onboarding.DOTS, Onboarding.dot(OnboardingStep.Model), Modifier.padding(top = 24.dp, bottom = 12.dp))
    }
}

@Composable
internal fun LocalModelScreen(store: ProviderStore, onBack: () -> Unit, onActivated: () -> Unit) {
    val context = LocalContext.current
    val controller = remember { LocalModelController.get(context) }
    val state by controller.state.collectAsState()
    LaunchedEffect(controller, store) { controller.refresh(store) }
    DisposableEffect(controller) { onDispose { controller.cancel() } }
    val close = { controller.cancel(); onBack() }
    BackHandler(onBack = close)
    LocalModelContent(state, onBack = close, onRefresh = { controller.refresh(store) },
        onDownload = controller::download, onCancel = controller::cancel,
        onRemove = { controller.remove(it, store) },
        onUse = { controller.activate(it, store, onActivated) })
}

/** 无系统副作用的内容层：下载、启用、删除均由明确点击触发。 */
@Composable
internal fun LocalModelContent(
    state: LocalModelState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onDownload: (String) -> Unit,
    onCancel: () -> Unit,
    onRemove: (String) -> Unit,
    onUse: (String) -> Unit,
) {
    val context = LocalContext.current
    val busy = state.phase != LocalModelPhase.Idle
    var removing by remember { mutableStateOf<String?>(null) }
    val colors = MikaTheme.colors
    SubPage(stringResource(R.string.local_model_title), onBack, Modifier.testTag("local-model-page")) {
        Text(stringResource(R.string.local_model_intro), style = MikaType.Body, color = colors.ink2)
        FooterNote(stringResource(R.string.local_model_limits))
        if (!state.supported) Text(stringResource(R.string.local_error_unsupported),
            Modifier.padding(vertical = 8.dp).testTag("local-model-unsupported"), color = colors.warn)
        TextButton(onRefresh, enabled = !busy, modifier = Modifier.testTag("local-model-recheck")) {
            Text(stringResource(R.string.jev_recheck))
        }
        state.error?.let { Text(it, Modifier.padding(bottom = 12.dp).testTag("local-model-error"), color = colors.bad) }
        if (busy) {
            Text(stringResource(when (state.phase) {
                LocalModelPhase.Downloading -> R.string.jev_downloading
                LocalModelPhase.Verifying -> R.string.jev_verifying
                LocalModelPhase.Activating -> R.string.local_model_activating
                LocalModelPhase.Idle -> R.string.jev_ready
            }), Modifier.testTag("local-model-progress-label"), style = MikaType.Body)
            if (state.phase == LocalModelPhase.Downloading && state.total > 0) {
                val progress = (state.done.toDouble() / state.total).toFloat().coerceIn(0f, 1f)
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                Text(stringResource(R.string.jev_progress, (progress * 100).toInt()), style = MikaType.Caption)
            } else LinearProgressIndicator(Modifier.fillMaxWidth())
            MikaButton(stringResource(R.string.jev_stop), onCancel,
                Modifier.fillMaxWidth().padding(vertical = 8.dp).testTag("local-model-stop"), kind = ButtonKind.Secondary)
        }
        state.rows.sortedByDescending { it.recommended }.forEach { item ->
            val current = state.enabledId == item.id
            GroupCard(Modifier.padding(top = 12.dp).testTag("local-model-${item.id}")) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(item.name, style = MikaType.Body, fontWeight = FontWeight.SemiBold)
                    Text(stringResource(R.string.local_model_size, Formatter.formatShortFileSize(context, item.bytes)), style = MikaType.Caption, color = colors.ink2)
                    if (item.recommended) Text(stringResource(R.string.local_model_recommended), style = MikaType.Caption, color = colors.accent)
                    if (current) Text(stringResource(R.string.local_model_current), Modifier.testTag("local-current-${item.id}"), style = MikaType.Caption, color = colors.ok)
                    else if (item.ready) Text(stringResource(R.string.jev_ready), style = MikaType.Caption, color = colors.ok)
                    item.reason?.let { Text(it, style = MikaType.Caption, color = colors.warn) }
                    if (item.ready) {
                        MikaButton(stringResource(R.string.local_model_use), { onUse(item.id) },
                            Modifier.fillMaxWidth().testTag("local-use-${item.id}"), enabled = !busy && state.supported && item.reason == null)
                    } else {
                        MikaButton(stringResource(R.string.jev_download), { onDownload(item.id) },
                            Modifier.fillMaxWidth().testTag("local-download-${item.id}"), enabled = !busy && state.supported && item.downloadAllowed)
                    }
                    if (item.ready || item.downloadedBytes > 0) {
                        MikaButton(stringResource(R.string.jev_remove), { removing = item.id },
                            Modifier.fillMaxWidth().testTag("local-remove-${item.id}"), enabled = !busy && !current, kind = ButtonKind.Text)
                    }
                }
            }
        }
        FooterNote(stringResource(R.string.local_model_leave_note))
    }
    removing?.let { id ->
        MikaDialog(onDismissRequest = { removing = null }, title = { Text(stringResource(R.string.jev_remove)) },
            text = { Text(stringResource(R.string.jev_remove_confirm)) },
            confirmButton = { TextButton({ removing = null; onRemove(id) }, enabled = !busy,
                modifier = Modifier.testTag("local-model-remove-confirm")) { Text(stringResource(R.string.jev_remove)) } },
            dismissButton = { TextButton({ removing = null }) { Text(stringResource(R.string.cancel)) } })
    }
}
