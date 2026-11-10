package com.wangzan101.mika.cap.phone.setup

import com.wangzan101.mika.core.model.ModelReply
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.StopReason
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import java.security.MessageDigest
import java.util.Locale

internal enum class SetupModelStage { Developer, Usb, Wireless, Pair }
internal enum class SetupModelAction(val wire: String) {
    Navigate("navigate"), Build("build"), Enable("enable"), Pair("pair"), Scroll("scroll")
}

internal data class SetupModelDecision(
    val action: SetupModelAction,
    val nodeId: Int,
    internal val fingerprint: String,
)

internal data class SetupModelCandidate(val nodeId: Int, val title: String, val actions: Set<SetupModelAction>)
internal data class SetupModelPage(val stage: SetupModelStage, val candidates: List<SetupModelCandidate>) {
    val json: String = buildJsonObject {
        put("stage", stage.name)
        put("candidates", JsonArray(candidates.map { candidate -> buildJsonObject {
            put("node_id", candidate.nodeId)
            put("title", candidate.title)
            put("actions", JsonArray(candidate.actions.map { JsonPrimitive(it.wire) }))
        } }))
    }.toString()
    // 包含节点编号、动作集合与阶段，不能把上一棵树的编号套到重新排列的节点上。
    val fingerprint: String = MessageDigest.getInstance("SHA-256").digest(json.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}

/** 模型只挑选经过筛选的设置行。它既不能获得整个页面，也不能扩大执行器的动作范围。 */
internal object SetupModelPolicy {
    const val TOOL_NAME = "choose_setup_action"
    private const val MAX_CANDIDATES = 36
    private const val MAX_TITLE = 72
    private val json = Json

    fun page(stage: SetupModelStage, tree: SetupAutomationTree): SetupModelPage? {
        if (tree.blocked || !tree.trusted || tree.nodes.map { it.id }.distinct().size != tree.nodes.size) return null
        val byId = tree.nodes.associateBy { it.id }
        fun ancestors(node: SetupNode) = generateSequence(node) { it.parent?.let(byId::get) }.take(tree.nodes.size)
        fun inside(node: SetupNode, row: SetupNode) = ancestors(node).any { it.id == row.id }
        fun visible(node: SetupNode) = ancestors(node).all { it.visible && it.enabled && !it.password && !it.editable }
        fun rowFor(node: SetupNode) = ancestors(node).takeWhile { !it.scrollable }.firstOrNull {
            it.parent != null && it.clickable && visible(it)
        }

        val labels = tree.nodes.mapNotNull { node ->
            if (!visible(node) || node.resource.endsWith("/summary")) return@mapNotNull null
            // 合并描述只接收独立、短小的设置标题；不发送 summary、版本值、标识符或整行读屏描述。
            val raw = node.text.ifBlank {
                // 描述可能合并了标题和个人值。只接受已知的独立描述，不发送任意合并内容。
                node.contentDescription.takeIf { normalized(it) in SAFE_DESCRIPTIONS }.orEmpty()
            }
            val title = safeTitle(raw) ?: return@mapNotNull null
            val row = rowFor(node) ?: return@mapNotNull null
            if (!isTitleResource(node.resource) && node !== row) return@mapNotNull null
            Triple(node, row, title)
        }
        // 同一行可能同时通过父描述与子标题暴露相同文字；只有跨行重名才有定位歧义。
        val duplicates = labels.groupBy { normalized(it.third) }
            .filterValues { rowLabels -> rowLabels.map { it.second.id }.distinct().size > 1 }.keys
        val candidates = labels.groupBy { it.second.id }.mapNotNull { (_, rowLabels) ->
            val distinct = rowLabels.distinctBy { normalized(it.third) }
            if (distinct.size != 1) return@mapNotNull null
            val (_, row, title) = distinct.single()
            if (normalized(title) in duplicates) return@mapNotNull null
            val rowTitles = tree.nodes.filter { visible(it) && isTitleResource(it.resource) && inside(it, row) }
                .map { it.text.ifBlank { it.contentDescription } }.filter(String::isNotBlank).map(::normalized).distinct()
            if (rowTitles.size > 1) return@mapNotNull null
            // 隐藏/禁用的开关也不能让整行被误认为普通导航项。
            val switches = tree.nodes.filter { it.checkable && inside(it, row) }
            val toggle = switches.singleOrNull()
            val actions = linkedSetOf<SetupModelAction>()
            if (switches.isEmpty()) {
                if (navigationTitle(stage, title)) actions += SetupModelAction.Navigate
                if (stage == SetupModelStage.Developer && buildTitle(title)) actions += SetupModelAction.Build
                if (stage == SetupModelStage.Pair && pairTitle(title)) actions += SetupModelAction.Pair
            } else if (toggle != null && visible(toggle) && !toggle.checked && debuggingTitle(stage, title)) {
                actions += SetupModelAction.Enable
            }
            if (actions.isEmpty()) return@mapNotNull null
            val actionId = if (SetupModelAction.Enable in actions && toggle?.clickable == true) toggle.id else row.id
            SetupModelCandidate(actionId, title, actions)
        }.take(MAX_CANDIDATES)
        // 无标题可判断时不调用模型判断一个显而易见的滚动操作，留给现有规则处理。
        if (candidates.isEmpty()) return null
        val scroll = tree.scroll()?.let { SetupModelCandidate(it, "Settings list", setOf(SetupModelAction.Scroll)) }
        return SetupModelPage(stage, candidates + listOfNotNull(scroll))
    }

    fun decision(reply: ModelReply, page: SetupModelPage): SetupModelDecision? {
        if (reply.stopReason == StopReason.MaxTokens || reply.stopReason == StopReason.Other) return null
        val calls = reply.parts.filterIsInstance<Part.ToolCall>()
        if (calls.size != 1 || calls.single().name != TOOL_NAME) return null
        // 服务商可能同时返回说明文字；文字不参与动作解析，只校验唯一的封闭工具调用。
        if (reply.parts.any { it !is Part.ToolCall && it !is Part.Thinking && it !is Part.Text }) return null
        val raw = calls.single().argsJson
        if (raw.length > 256 || Regex("\"action\"\\s*:").findAll(raw).count() != 1 ||
            Regex("\"node_id\"\\s*:").findAll(raw).count() != 1) return null
        val obj = runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull() ?: return null
        if (obj.keys != setOf("action", "node_id")) return null
        val actionValue = obj["action"] as? JsonPrimitive ?: return null
        val idValue = obj["node_id"] as? JsonPrimitive ?: return null
        if (!actionValue.isString || idValue.isString) return null
        if (actionValue.content == "manual") return null
        val action = SetupModelAction.entries.singleOrNull { it.wire == actionValue.content } ?: return null
        val id = idValue.intOrNull ?: return null
        val candidate = page.candidates.singleOrNull { it.nodeId == id && action in it.actions } ?: return null
        return SetupModelDecision(action, candidate.nodeId, page.fingerprint)
    }

    private fun normalized(text: String) = text.lowercase(Locale.ROOT).filterNot(Char::isWhitespace)
    private fun containsAny(text: String, words: Set<String>) = words.any { normalized(it) in normalized(text) }
    private fun isTitleResource(resource: String) = resource.substringAfterLast('/') in setOf("title", "preference_title", "switch_text")

    private fun safeTitle(raw: String): String? {
        val title = raw.trim()
        if (title.isBlank() || title.length > MAX_TITLE || title.any { it.isDigit() || it.isISOControl() } ||
            title.any { it in "@:：/\\<>{}\"`='’" } || containsAny(title, DENIED)) return null
        return title.takeIf { containsAny(it, RELEVANT) }
    }

    private fun buildTitle(title: String): Boolean = containsAny(title, BUILD) && !containsAny(title, setOf("android", "安卓", "security", "patch", "补丁", "補丁"))
    private fun debuggingTitle(stage: SetupModelStage, title: String): Boolean = containsAny(title, DEBUG) && when (stage) {
        SetupModelStage.Usb -> normalized(title).contains("usb")
        SetupModelStage.Wireless -> containsAny(title, WIRELESS)
        else -> false
    }
    private fun pairTitle(title: String) = containsAny(title, PAIR) && containsAny(title, CODE)
    private fun navigationTitle(stage: SetupModelStage, title: String): Boolean = when (stage) {
        SetupModelStage.Developer -> containsAny(title, INFO)
        SetupModelStage.Usb -> containsAny(title, DEVELOPER) || containsAny(title, DEBUG) || containsAny(title, SETTINGS)
        SetupModelStage.Wireless, SetupModelStage.Pair -> containsAny(title, DEVELOPER) || containsAny(title, DEBUG) || containsAny(title, SETTINGS)
    }

    // 这些词限定设置的主题，不要求厂商完整标题一致；最终语义由模型在候选中判断。
    private val BUILD = setOf("build", "version", "版本", "版本號", "miui", "hyperos", "coloros", "originos", "one ui", "harmonyos", "ビルド", "빌드", "numéro de build", "compilación", "compilação", "сборк", "versi")
    private val DEBUG = setOf("debug", "调试", "調試", "偵錯", "デバッグ", "디버깅", "depuración", "depuração", "отлад", "debugging", "تصحيح")
    private val WIRELESS = setOf("wireless", "wifi", "wi-fi", "无线", "無線", "ワイヤレス", "무선", "inalámbr", "sem fio", "беспровод", "nirkabel", "لاسلكي")
    private val PAIR = setOf("pair", "配对", "配對", "ペア", "페어", "vincular", "emparelh", "сопряж", "sanding", "إقران")
    private val CODE = setOf("code", "码", "碼", "コード", "코드", "código", "код", "kode", "رمز")
    private val DEVELOPER = setOf("developer", "开发者", "開發者", "开发人员", "開發人員", "開発者", "개발자", "desarrollador", "desenvolvedor", "разработчик", "pengembang", "المطور")
    private val SETTINGS = setOf("settings", "system", "advanced", "additional", "设置", "設定", "系统", "系統", "更多", "其他", "詳細", "設定", "システム", "설정", "ajustes", "configura", "систем", "настрой", "setelan", "pengaturan", "النظام", "الإعدادات")
    private val INFO = setOf("about", "software", "details", "specification", "system", "version", "device information", "关于", "關於", "软件", "軟體", "系统", "系統", "版本", "详细", "詳細", "规格", "規格", "参数", "參數", "ソフトウェア", "端末情報", "デバイス情報", "バージョン", "소프트웨어", "휴대전화 정보", "acerca", "información", "informações", "sobre", "телефон", "программ", "versi", "tentang", "حول", "البرنامج") + DEVELOPER + BUILD
    private val RELEVANT = INFO + SETTINGS + DEBUG + WIRELESS + PAIR
    private val SAFE_DESCRIPTIONS = (SetupTarget.entries.flatMap { it.labels } +
        setOf("About phone", "About tablet", "About device", "关于手机", "关于本机", "關於手機", "System", "系统", "系統", "Advanced", "高级", "高級", "Additional settings", "更多设置", "其他设置"))
        .map(::normalized).toSet()
    private val DENIED = setOf(
        "reset", "wipe", "erase", "factory", "delete", "remove", "unlock", "oem", "bootloader", "install", "revoke", "credential", "password", "account", "serial", "imei", "meid", "address", "device name", "phone number", "mac address", "ip address", "secure", "security", "payment", "purchase", "allow", "confirm", "cancel", "agree", "accept",
        "重置", "重設", "恢复", "還原", "恢复出厂", "清除", "清空", "删除", "刪除", "卸载", "卸載", "安装", "安裝", "解锁", "解鎖", "撤销", "撤銷", "安全", "密码", "密碼", "账号", "帐号", "帳號", "账户", "帳戶", "序列", "设备名称", "裝置名稱", "电话号码", "電話號碼", "地址", "允许", "允許", "确定", "確定", "取消", "同意", "付款", "支付",
        "リセット", "削除", "パスワード", "アカウント", "허용", "초기화", "삭제", "비밀번호", "restablecer", "borrar", "contraseña", "redefinir", "apagar", "senha", "сброс", "удал", "парол", "hapus", "sandi", "حذف", "السماح", "كلمة المرور",
    )
}
