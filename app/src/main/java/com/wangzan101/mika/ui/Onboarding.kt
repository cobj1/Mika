package com.wangzan101.mika.ui

import android.content.Context

/** 首次上手：无障碍 → 模型 → 高级功能邀请；Phone 是用户选择解锁后的设置页。 */
enum class OnboardingStep { Welcome, Accessibility, Model, Advanced, Phone }

/**
 * 首次打开时走哪几步(纯函数,便于测试)。和手机引导一样按「看过没有」判断,不每次都弹:
 * 已经配过模型的老用户不重复要求无障碍。高级功能只邀请，不在启动/保存模型时自行开启调试。
 */
object Onboarding {
    data class Inputs(
        val hasMainModel: Boolean,
        val modelSetupDone: Boolean,
        /** 系统够新且手机操作还没就绪。 */
        val phoneNeeded: Boolean,
        val phoneGuideSeen: Boolean,
        val accessibilityConnected: Boolean = false,
        val accessibilityGuideSeen: Boolean = false,
        val advancedPromptSeen: Boolean = false,
    )

    fun start(i: Inputs): OnboardingStep? = when {
        !i.hasMainModel && !i.modelSetupDone ->
            if (i.accessibilityConnected || i.accessibilityGuideSeen) OnboardingStep.Model else OnboardingStep.Accessibility
        offerAdvanced(i) -> OnboardingStep.Advanced
        else -> null
    }

    /** [step] 做完后的下一步;没有了为 null(进对话)。 */
    fun next(step: OnboardingStep, i: Inputs): OnboardingStep? = when (step) {
        OnboardingStep.Welcome -> start(i)
        OnboardingStep.Accessibility -> if (!i.hasMainModel && !i.modelSetupDone) OnboardingStep.Model
            else if (offerAdvanced(i)) OnboardingStep.Advanced else null
        OnboardingStep.Model -> if (offerAdvanced(i)) OnboardingStep.Advanced else null
        // 只有「解锁高级功能」的显式点击才调用这一步。
        OnboardingStep.Advanced -> if (i.phoneNeeded) OnboardingStep.Phone else null
        OnboardingStep.Phone -> null
    }

    /** 底部分页点:三步里的第几步。 */
    fun dot(step: OnboardingStep): Int = when (step) {
        OnboardingStep.Welcome, OnboardingStep.Accessibility -> 0
        OnboardingStep.Model -> 1
        OnboardingStep.Advanced, OnboardingStep.Phone -> 2
    }
    const val DOTS = 3

    private fun offerAdvanced(i: Inputs) = i.phoneNeeded && !i.phoneGuideSeen && !i.advancedPromptSeen
}

/** 上手流程里「配置模型」做完没有;存在本机。 */
class OnboardingState(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun modelSetupDone(): Boolean = prefs.getBoolean(MODEL_DONE, false)

    fun markModelSetupDone() {
        prefs.edit().putBoolean(MODEL_DONE, true).apply()
    }

    fun accessibilityGuideSeen(): Boolean = prefs.getBoolean(ACCESSIBILITY_SEEN, false)

    fun markAccessibilityGuideSeen() { prefs.edit().putBoolean(ACCESSIBILITY_SEEN, true).apply() }

    fun advancedPromptSeen(): Boolean = prefs.getBoolean(ADVANCED_SEEN, false)

    fun markAdvancedPromptSeen() { prefs.edit().putBoolean(ADVANCED_SEEN, true).apply() }

    private companion object {
        const val PREFS = "onboarding"
        const val MODEL_DONE = "model_setup_done"
        const val ACCESSIBILITY_SEEN = "accessibility_guide_seen"
        const val ADVANCED_SEEN = "advanced_prompt_seen"
    }
}
