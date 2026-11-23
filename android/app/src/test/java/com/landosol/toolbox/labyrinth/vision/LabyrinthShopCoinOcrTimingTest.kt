package com.landosol.toolbox.labyrinth.vision

import org.junit.Assert.*
import org.junit.Test

/** Wallet-only boundary regressions, independent of Android or wall-clock performance. */
class LabyrinthShopCoinOcrTimingTest {
    @Test fun spacingRejects349MillisAndAdmits350WithoutChargingRejectedRequests() {
        var now = 100L
        val scheduler = LabyrinthShopCoinOcrScheduler({ now }, maximumAttempts = 2)
        val first = requireNotNull(scheduler.request(1L, now))
        assertEquals(1, scheduler.complete(first, "7460")?.confirmations)
        for (elapsed in listOf(1L, 100L, 349L)) {
            now = 100L + elapsed
            repeat(10) { assertNull(scheduler.request(1L, now)) }
        }
        now = 450L
        val second = requireNotNull(scheduler.request(1L, now))
        assertEquals(2, scheduler.complete(second, "7,460")?.confirmations)
        assertEquals(second.id, scheduler.cached(1L, now)?.id)
    }

    @Test fun confirmedAmountKeepsProgressingAcrossMoreThanSixChangedFingerprints() {
        var now = 100L
        val scheduler = LabyrinthShopCoinOcrScheduler({ now })
        val first = requireNotNull(scheduler.request(1L, now))
        assertEquals(1, scheduler.complete(first, "7460")?.confirmations)
        now += 350L
        val second = requireNotNull(scheduler.request(1L, now))
        assertEquals(2, scheduler.complete(second, "7,460")?.confirmations)
        for (fingerprint in 2L..10L) {
            now += 350L
            assertNull(scheduler.cached(fingerprint, now))
            val ticket = requireNotNull(scheduler.request(fingerprint, now))
            val read = requireNotNull(scheduler.complete(ticket, " 7，460 "))
            assertEquals(2, read.confirmations)
            assertEquals(read, scheduler.cached(fingerprint, now))
            assertNull(scheduler.cached(fingerprint - 1L, now))
        }
    }
}
