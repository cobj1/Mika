package com.wangzan101.mika.cap.phone.shell

import org.junit.Assert.*
import org.junit.Test

class DisplayRecoveryLedgerTest {
    private val token = "11111111-2222-3333-4444-555555555555"
    private fun ledger() = DisplayRecoveryLedger().apply {
        add(DisplayRecoveryLedger.Entry(token, 7, 720, 1600, 320, true))
    }

    @Test fun activeScreenCannotBeAdoptedByAnotherRun() {
        val ledger = ledger()
        assertFalse(ledger.attach(token, 1))
        assertFalse(ledger.parked(7))
    }
    @Test fun deathParksScreenAndMatchingTokenCanAttachOnce() {
        val ledger = ledger()
        assertEquals(listOf(7), ledger.park(100).map { it.id })
        assertTrue(ledger.parked(7))
        assertFalse(ledger.attach("wrong-token", 101))
        assertTrue(ledger.attach(token, 101))
        assertFalse(ledger.parked(7))
        assertFalse(ledger.attach(token, 102))
        assertEquals(7, ledger.find(token)!!.id)
    }
    @Test fun repeatedDeathsDoNotExtendAnOrphansDeadline() {
        val ledger = ledger()
        ledger.park(100)
        assertTrue(ledger.park(500).isEmpty())
        assertTrue(ledger.expired(120_099).isEmpty())
        assertEquals(listOf(7), ledger.expired(120_100))
        assertFalse(ledger.attach(token, 120_100))
    }
    @Test fun attachedScreenIsNotExpiredByOldTimer() {
        val ledger = ledger()
        ledger.park(100)
        assertTrue(ledger.attach(token, 200))
        assertTrue(ledger.expired(120_100).isEmpty())
        ledger.park(121_000)
        assertTrue(ledger.expired(121_001).isEmpty())
        assertEquals(listOf(7), ledger.expired(241_000))
    }
    @Test fun releaseAndReleaseAllRemoveRecoveryCredentials() {
        val ledger = ledger()
        ledger.park(10)
        ledger.remove(7)
        assertNull(ledger.find(token))
        assertFalse(ledger.attach(token, 11))
        ledger.add(DisplayRecoveryLedger.Entry(token, 8, 720, 1600, 320, false))
        ledger.clear()
        assertNull(ledger.find(token))
    }
    @Test fun onlyFullPrivateTokensAreAccepted() {
        assertTrue(DisplayRecoveryLedger.validToken(token))
        listOf("7", "", "../$token", "$token\n", "111-22-33-44-55").forEach {
            assertFalse(it, DisplayRecoveryLedger.validToken(it))
        }
    }
}
