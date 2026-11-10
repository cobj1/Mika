package com.wangzan101.mika.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wangzan101.mika.R
import com.wangzan101.mika.cap.phone.setup.SetupAutomationState
import com.wangzan101.mika.ui.components.ButtonKind
import com.wangzan101.mika.ui.components.GroupCard
import com.wangzan101.mika.ui.components.MikaButton
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType

/** 自动设置是可选入口；手动清单继续保留，随时可以停止接手。 */
@Composable
internal fun SetupAutomationCard(state: SetupAutomationState, onStart: () -> Unit, onStop: () -> Unit) {
    val c = MikaTheme.colors
    Column(Modifier.padding(bottom = 16.dp).testTag("setup-automation")) {
        GroupCard {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.setup_auto_title), style = MikaType.Body, fontWeight = FontWeight.SemiBold, color = c.ink)
                Text(stringResource(R.string.setup_auto_intro), fontSize = 14.sp, lineHeight = 22.sp, color = c.ink2)
                if (state.message != R.string.setup_auto_intro) {
                    Text(stringResource(state.message), Modifier.testTag("setup-automation-status"), fontSize = 14.sp, lineHeight = 22.sp, color = c.ink)
                }
                MikaButton(
                    stringResource(if (state.active) R.string.setup_auto_stop else R.string.setup_auto_start),
                    if (state.active) onStop else onStart,
                    Modifier.fillMaxWidth().testTag(if (state.active) "setup-automation-stop" else "setup-automation-start"),
                    kind = if (state.active) ButtonKind.Secondary else ButtonKind.Primary,
                )
            }
        }
    }
}

/** 系统设置页上的小状态条：告知正在做什么，并提供立即停止入口。 */
@Composable
internal fun SetupAutomationOverlay(state: SetupAutomationState, onStop: () -> Unit) {
    MikaTheme {
        Surface(shape = RoundedCornerShape(16.dp), color = MikaTheme.colors.surface, shadowElevation = 4.dp) {
            Row(
                Modifier.widthIn(max = 320.dp).padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(stringResource(state.message), Modifier.weight(1f), fontSize = 13.sp, lineHeight = 18.sp, color = MikaTheme.colors.ink)
                TextButton(onStop) { Text(stringResource(R.string.setup_auto_stop)) }
            }
        }
    }
}
