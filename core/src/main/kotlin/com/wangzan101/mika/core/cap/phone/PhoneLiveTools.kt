package com.wangzan101.mika.core.cap.phone

import com.wangzan101.mika.core.prompt.Prompts
import com.wangzan101.mika.core.risk.GateTarget
import com.wangzan101.mika.core.tool.ArgError
import com.wangzan101.mika.core.tool.Args
import com.wangzan101.mika.core.tool.Risk
import com.wangzan101.mika.core.tool.ToolContext
import com.wangzan101.mika.core.tool.ToolDef
import com.wangzan101.mika.core.tool.ToolExecution
import com.wangzan101.mika.core.tool.Verb
import com.wangzan101.mika.core.tool.int
import com.wangzan101.mika.core.tool.str
import com.wangzan101.mika.core.uistate.action
import com.wangzan101.mika.core.uistate.screen
import com.wangzan101.mika.core.uistate.toPx
import com.wangzan101.mika.core.visual.FrameWatch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 每次只改变一根手指，继续经过正常写操作入口；不自动等待静止或判定业务完成。 */
internal object PhoneTouch : PhoneTool {
    override val verb = Verb.PhoneTouch
    override val risk = Risk.External
    override val gateTarget = GateTarget.Pointer
    override val label = "持续触摸"
    override val logged = true
    override val def = ToolDef("phone_touch", Prompts.tool("phone_touch"), mapOf(
        "op" to str("down, move, up, or cancel. cancel releases all held fingers on this task's display.", listOf("down", "move", "up", "cancel")),
        "pointerId" to int("Finger ID, 0-9. Required except for cancel.", 0, 9),
        "x" to int("X in normalized 0-999 screen coordinates. Required except for cancel.", 0, 999),
        "y" to int("Y in normalized 0-999 screen coordinates. Required except for cancel.", 0, 999),
        "target" to str("Short visible label or description of the touched control, especially on a canvas without accessibility nodes."),
    ), listOf("op"))

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val op = a.str("op")!!
        if (op == "cancel") return ctx.action(ctx.screen.pointer(op, 0, 0, 0))
        if (op !in listOf("down", "move", "up")) throw ArgError("op", "invalid operation")
        val id = a.int("pointerId", 0, 9) ?: throw ArgError("pointerId", "required")
        val x = a.int("x", 0, 999) ?: throw ArgError("x", "required")
        val y = a.int("y", 0, 999) ?: throw ArgError("y", "required")
        // 风险门已为本次操作实时读屏；这里仅换算坐标，不再重复遍历整棵节点树。
        val current = ctx.screen.viewport()
        if (!current.hasWindow) return ctx.action(com.wangzan101.mika.core.uistate.ActionResult.Failed("no_app_open"))
        return ctx.action(ctx.screen.pointer(op, id, toPx(x, current.width), toPx(y, current.height)))
    }
}

/** 仅调用期间采样，返回少量有时间标记的变化帧，避免把实时预览直接灌给大模型。 */
internal object PhoneWatch : PhoneTool {
    override val verb = Verb.PhoneWatch
    override val risk = Risk.Read
    override val label = "连续观察画面"
    override val yieldsToSteer = true
    override val sandboxCallable = false
    override fun offered(ctx: ToolContext) = ctx.config.visionEnabled
    override fun available(ctx: ToolContext) = ctx.config.visionEnabled
    override val def = ToolDef("phone_watch", Prompts.tool("phone_watch"), mapOf(
        "durationMs" to int("Maximum observation time, 250-10000 ms (default 3000).", 250, 10000),
        "intervalMs" to int("Sampling interval, 250-2000 ms (default 500).", 250, 2000),
        "maxFrames" to int("Stop after this many changed frames, 1-8 (default 4).", 1, 8),
    ))

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val result = FrameWatch.collect(
            (a.int("durationMs", 250, 10000) ?: 3000).toLong(),
            (a.int("intervalMs", 250, 2000) ?: 500).toLong(),
            a.int("maxFrames", 1, 8) ?: 4,
            capture = ctx.screen::observationFrame, protected = ctx.screen::isProtected,
        )
        return ToolExecution(buildJsonObject {
            put("ok", result.entries.isNotEmpty())
            put("elapsedMs", result.elapsedMs); put("newFrames", result.newFrames)
            put("stoppedBecause", result.reason)
            if (result.entries.isEmpty()) put("error", result.reason)
            put("frames", JsonArray(result.entries.mapIndexed { index, entry -> buildJsonObject {
                put("imageIndex", index); put("sampledAtMs", entry.sampledAtMs)
                put("producerTimestampNanos", entry.frame.timestampNanos)
                put("width", entry.frame.width); put("height", entry.frame.height)
            } }))
            put("note", "Sampled changes, not a complete video or proof of task completion. Observe again before acting on an old frame.")
        }, images = result.entries.map { it.frame.png })
    }
}
