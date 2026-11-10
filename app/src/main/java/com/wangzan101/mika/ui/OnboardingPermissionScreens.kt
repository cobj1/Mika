package com.wangzan101.mika.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wangzan101.mika.R
import com.wangzan101.mika.ui.components.ButtonKind
import com.wangzan101.mika.ui.components.GroupCard
import com.wangzan101.mika.ui.components.MikaButton
import com.wangzan101.mika.ui.components.PageBackground
import com.wangzan101.mika.ui.components.ProgressDots
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType

/** 首次上手只请求无障碍；高级调试设置留到模型配置完成后，由用户另外发起。 */
@Composable
fun AccessibilityOnboardingScreen(
    connected: Boolean,
    onOpen: () -> Unit,
    onAppInfo: () -> Unit,
    onSkip: () -> Unit,
    onContinue: () -> Unit,
) {
    BackHandler(onBack = onSkip)
    PermissionOnboardingPage(
        title = stringResource(R.string.onboarding_access_title),
        dot = 0,
        tag = "onboarding-accessibility",
        actions = {
            MikaButton(
                stringResource(if (connected) R.string.onboarding_access_continue else R.string.onboarding_access_open),
                if (connected) onContinue else onOpen,
                Modifier.fillMaxWidth().testTag(if (connected) "onboarding-accessibility-continue" else "onboarding-accessibility-open"),
            )
            MikaButton(stringResource(R.string.onboarding_later), onSkip, Modifier.fillMaxWidth().testTag("onboarding-accessibility-skip"), kind = ButtonKind.Text)
        },
    ) {
        Text(stringResource(R.string.onboarding_access_body), style = MikaType.Body, color = MikaTheme.colors.ink2)
        if (connected) {
            Text(
                stringResource(R.string.onboarding_access_connected), Modifier.testTag("onboarding-accessibility-connected"),
                style = MikaType.Body, color = MikaTheme.colors.ok,
            )
        } else {
            MikaButton(
                stringResource(R.string.onboarding_access_app_info), onAppInfo,
                Modifier.fillMaxWidth().testTag("onboarding-accessibility-app-info"), kind = ButtonKind.Text,
            )
        }
    }
}

/** 保存模型后的独立提示；进入页面不会自动启动设置，只有点击开启才执行。 */
@Composable
fun AdvancedFeaturesScreen(onEnable: () -> Unit, onLater: () -> Unit, modelSaved: Boolean = true) {
    BackHandler(onBack = onLater)
    PermissionOnboardingPage(
        title = stringResource(R.string.onboarding_advanced_title),
        dot = 2,
        tag = "onboarding-advanced",
        actions = {
            MikaButton(
                stringResource(R.string.onboarding_advanced_enable), onEnable,
                Modifier.fillMaxWidth().testTag("onboarding-advanced-enable"),
            )
            MikaButton(stringResource(R.string.onboarding_later), onLater, Modifier.fillMaxWidth().testTag("onboarding-advanced-later"), kind = ButtonKind.Text)
        },
    ) {
        if (modelSaved) Text(stringResource(R.string.onboarding_model_saved), style = MikaType.Body, fontWeight = FontWeight.SemiBold, color = MikaTheme.colors.ok)
        Text(stringResource(R.string.onboarding_advanced_body), style = MikaType.Body, color = MikaTheme.colors.ink2)
        Text(stringResource(R.string.onboarding_advanced_confirmation), style = MikaType.Caption, color = MikaTheme.colors.ink3)
    }
}

@Composable
private fun PermissionOnboardingPage(
    title: String,
    dot: Int,
    tag: String,
    actions: @Composable ColumnScope.() -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    PageBackground {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars).windowInsetsPadding(WindowInsets.navigationBars).testTag(tag)) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 32.dp)) {
                Text(title, Modifier.padding(bottom = 24.dp), style = MikaType.LargeTitle, fontSize = 26.sp, color = MikaTheme.colors.ink)
                GroupCard {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(16.dp), content = content)
                }
            }
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ProgressDots(3, dot, Modifier.padding(bottom = 4.dp))
                actions()
            }
        }
    }
}
