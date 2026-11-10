package com.wangzan101.mika.cap.phone.display

import android.content.Context
import android.hardware.display.DisplayManager
import com.wangzan101.mika.shell.IMikaShell

/** 用户在同一会话重新发起任务时接回 helper 仍保留的原屏；不恢复上一轮任务执行。 */
object RecoverableDisplay {
    @Volatile private var tokens: DisplaySessionTokens? = null
    private fun tokens(context: Context): DisplaySessionTokens = tokens ?: synchronized(this) {
        tokens ?: context.applicationContext.getSharedPreferences("display_sessions", Context.MODE_PRIVATE).let { prefs ->
            DisplaySessionTokens({ prefs.getString(it, null) }, { key, value ->
                check(prefs.edit().apply { if (value == null) remove(key) else putString(key, value) }.commit()) {
                    "cannot save display session"
                }
            }).also { tokens = it }
        }
    }

    fun create(context: Context, shell: IMikaShell, conversation: String, run: String, width: Int, height: Int, dpi: Int): AgentDisplay {
        val store = tokens(context)
        val token = store.acquire(conversation, run)
        return try {
            AgentDisplay(shell, width, height, dpi, context.getSystemService(DisplayManager::class.java), token) {
                store.release(conversation, run, token)
            }
        } catch (error: Throwable) {
            store.release(conversation, run, token)
            throw error
        }
    }
}
