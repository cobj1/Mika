package com.wangzan101.mika.cap.phone.display

import org.junit.Assert.*
import org.junit.Test

class DisplaySessionTokensTest {
    private val saved = HashMap<String, String>()
    private var sequence = 0
    private fun store() = DisplaySessionTokens(saved::get, { key, value ->
        if (value == null) saved.remove(key) else saved[key] = value
    }, { "token-${++sequence}" })

    @Test fun newProcessReusesOnlyTheSameConversationsCredential() {
        val first = store().acquire("conversation-a", "run-1")
        val restarted = store()
        assertEquals(first, restarted.acquire("conversation-a", "run-2"))
        assertNotEquals(first, restarted.acquire("conversation-b", "run-3"))
    }
    @Test fun concurrentRunCannotTakeAnActiveScreensCredential() {
        val store = store()
        val first = store.acquire("same", "run-1")
        val second = store.acquire("same", "run-2")
        assertNotEquals(first, second)
        store.release("same", "run-1", first)
        assertEquals(second, saved["same"])
    }
    @Test fun normalEndRemovesCredentialAndNextRunCreatesNewOne() {
        val store = store()
        val first = store.acquire("same", "run-1")
        store.release("same", "wrong-run", first)
        assertEquals(first, saved["same"])
        store.release("same", "run-1", first)
        assertNull(saved["same"])
        assertNotEquals(first, store.acquire("same", "run-2"))
    }
}
