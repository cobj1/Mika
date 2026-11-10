package com.wangzan101.mika.cap.phone.display

import java.util.UUID

/** 私有持久凭证按会话留底，正常结束删除；同进程的并行运行不能取得相同凭证。 */
internal class DisplaySessionTokens(
    private val read: (String) -> String?,
    private val write: (String, String?) -> Unit,
    private val newToken: () -> String = { UUID.randomUUID().toString() },
) {
    private val live = HashMap<String, String>()
    @Synchronized fun acquire(conversation: String, run: String): String {
        val saved = read(conversation)
        val token = saved?.takeUnless { it in live } ?: newToken()
        write(conversation, token)
        live[token] = run
        return token
    }
    @Synchronized fun release(conversation: String, run: String, token: String) {
        if (live[token] != run) return
        live.remove(token)
        if (read(conversation) == token) write(conversation, null)
    }
}
