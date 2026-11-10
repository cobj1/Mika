package com.wangzan101.mika.cap.phone.setup

import java.util.Locale

/** 只匹配设置页面的已知标题；不把说明文字里的「USB 调试」等词当作开关。 */
internal enum class SetupTarget(val labels: Set<String>) {
    BuildNumber(setOf("Build number", "版本号", "版本號", "内部版本号", "內部版本號", "版本编号", "版本編號")),
    SoftwareInfo(setOf("Software information", "软件信息", "軟體資訊", "软件版本", "軟體版本", "详细信息与规格", "詳細資訊與規格")),
    Usb(setOf("USB debugging", "USB 调试", "USB調試", "USB 偵錯", "USB偵錯")),
    Wireless(setOf("Wireless debugging", "无线调试", "無線調試", "無線偵錯")),
    Pair(setOf("Pair device with pairing code", "使用配对码配对设备", "使用配對碼配對裝置", "使用配对码配对", "使用配對碼配對")),
}

/** Android 节点的只读投影；纯逻辑测试不需要设备，也不记录页面内容。 */
internal data class SetupNode(
    val id: Int,
    val parent: Int? = null,
    val text: String = "",
    val resource: String = "",
    val visible: Boolean = true,
    val enabled: Boolean = true,
    val clickable: Boolean = false,
    val checkable: Boolean = false,
    val checked: Boolean = false,
    val editable: Boolean = false,
    val password: Boolean = false,
    val scrollable: Boolean = false,
    val dialog: Boolean = false,
)

/** 遇到身份验证、确认框或歧义时不给动作；调用方只能操作返回的唯一节点。 */
internal class SetupAutomationTree(val nodes: List<SetupNode>, val trusted: Boolean = true) {
    private val byId = nodes.associateBy { it.id }
    val blocked: Boolean = !trusted || nodes.any {
        it.visible && (it.password || it.editable || it.dialog || it.resource in DIALOG_BUTTONS ||
            (it.clickable && normalized(it.text) in CONFIRM_LABELS))
    }

    private fun normalized(text: String) = text.lowercase(Locale.ROOT).filterNot { it.isWhitespace() }

    fun label(target: SetupTarget, requireEnabled: Boolean = true): SetupNode? {
        if (blocked) return null
        val labels = target.labels.map(::normalized).toSet()
        return nodes.filter { it.visible && (!requireEnabled || it.enabled) && normalized(it.text) in labels }.singleOrNull()
    }

    fun click(target: SetupTarget): Int? = label(target)?.let(::clickable)?.id

    private fun ancestors(node: SetupNode) = generateSequence(node) { it.parent?.let(byId::get) }
        .take(4).takeWhile { it.visible && it.enabled && !it.scrollable }
    private fun clickable(node: SetupNode) = ancestors(node).firstOrNull {
        it.parent != null && it.visible && it.enabled && it.clickable && !it.checkable
    }

    private fun inside(node: SetupNode, row: SetupNode): Boolean =
        generateSequence(node) { it.parent?.let(byId::get) }.take(nodes.size).any { it.id == row.id }

    /** 只选择标题所在的最小行中的唯一开关，禁止向整页祖先借别的行的开关。 */
    fun turnOn(target: SetupTarget): Int? {
        val title = label(target) ?: return null
        if (title.checkable) return title.takeIf { !it.checked && it.clickable }?.id
        // 第一个可点击祖先才是标题所属的行；不能越过它去列表容器找相邻行的开关。
        val row = clickable(title) ?: return null
        val toggle = nodes.filter { it.visible && it.enabled && it.checkable && inside(it, row) }.singleOrNull() ?: return null
        if (toggle.checked) return null
        // 有独立可点开关时直接点它，避免把双目标设置行当成导航入口。
        return if (toggle.clickable) toggle.id else row.id
    }

    /** 无线调试详情页只有一个总开关时，才允许操作它。 */
    fun wirelessPageSwitch(): Int? {
        // 无线调试关闭时配对入口会变灰，仍可用于确认所在页面，不能用于点击。
        if (blocked || label(SetupTarget.Wireless) == null || label(SetupTarget.Pair, requireEnabled = false) == null) return null
        return nodes.filter { it.visible && it.enabled && it.checkable }.singleOrNull()
            ?.takeIf { !it.checked && it.clickable }?.id
    }

    fun scroll(): Int? = if (blocked) null else
        nodes.filter { it.visible && it.enabled && it.scrollable }.singleOrNull()?.id

    private companion object {
        val DIALOG_BUTTONS = setOf("android:id/button1", "android:id/button2", "android:id/button3")
        val CONFIRM_LABELS = setOf("allow", "ok", "cancel", "允许", "允許", "确定", "確定", "取消", "同意")
    }
}
