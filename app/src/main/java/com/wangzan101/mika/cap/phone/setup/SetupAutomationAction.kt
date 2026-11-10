package com.wangzan101.mika.cap.phone.setup

/** Retry 只表示尚未下发动作，可以在下一轮重新取页面；已下发但被拒绝的动作不能盲目重试。 */
internal enum class SetupActionResult { Applied, Retry, Failed }

/** 保留整树校验，但把页面瞬时变化与实际动作失败分开，避免计时文字刷新就结束整个设置。 */
internal object SetupAutomationAction {
    enum class RetryReason { WindowChanged, Blocked, SnapshotChanged }

    fun retryReason(before: SetupAutomationTree, after: SetupAutomationTree, sameWindow: Boolean): RetryReason? = when {
        !sameWindow -> RetryReason.WindowChanged
        before.blocked || after.blocked -> RetryReason.Blocked
        before.nodes != after.nodes -> RetryReason.SnapshotChanged
        else -> null
    }

    fun apply(
        before: SetupAutomationTree,
        after: SetupAutomationTree,
        sameWindow: Boolean,
        perform: () -> Boolean,
    ): SetupActionResult {
        if (retryReason(before, after, sameWindow) != null) return SetupActionResult.Retry
        return if (perform()) SetupActionResult.Applied else SetupActionResult.Failed
    }
}
