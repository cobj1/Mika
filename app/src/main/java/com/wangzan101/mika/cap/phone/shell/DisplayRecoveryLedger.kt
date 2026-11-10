package com.wangzan101.mika.cap.phone.shell

/** 助手内存中的屏归属；displayId 不作为恢复凭证。调用方在同一锁内处理 Android 屏对象。 */
internal class DisplayRecoveryLedger {
    data class Entry(val token: String, val id: Int, val width: Int, val height: Int, val dpi: Int,
        val keepAwake: Boolean, val expiresAt: Long? = null)
    private val entries = LinkedHashMap<String, Entry>()

    @Synchronized fun find(token: String): Entry? = entries[token]
    @Synchronized fun add(entry: Entry) { require(entry.id > 0 && validToken(entry.token)); check(entry.token !in entries); entries[entry.token] = entry }
    @Synchronized fun parked(id: Int): Boolean = entries.values.any { it.id == id && it.expiresAt != null }
    @Synchronized fun remove(id: Int) { entries.entries.removeAll { it.value.id == id } }
    @Synchronized fun clear() = entries.clear()
    @Synchronized fun park(now: Long): List<Entry> = entries.values.filter { it.expiresAt == null }.map {
        it.copy(expiresAt = now + RETAIN_MS).also { parked -> entries[parked.token] = parked }
    }
    @Synchronized fun attach(token: String, now: Long): Boolean {
        val entry = entries[token] ?: return false
        val deadline = entry.expiresAt ?: return false
        if (now >= deadline) return false
        entries[token] = entry.copy(expiresAt = null)
        return true
    }
    @Synchronized fun expired(now: Long): List<Int> = entries.values.filter { it.expiresAt?.let { deadline -> now >= deadline } == true }.map { it.id }

    companion object {
        const val RETAIN_MS = 120_000L
        fun validToken(token: String): Boolean = TOKEN.matches(token)
        private val TOKEN = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    }
}
