package com.landosol.toolbox.labyrinth.vision

import org.junit.Assert.*
import org.junit.Test

/** Pure financial evidence checks: no Bitmap, Android clock, OCR engine, or real timer. */
class LabyrinthShopCoinOcrSchedulerTest {
    private class Fixture {
        var now = 0L
        val scheduler = LabyrinthShopCoinOcrScheduler(clock = { now })

        fun request(fingerprint: Long, capturedAt: Long): LabyrinthShopCoinOcrScheduler.Ticket {
            val ticket = requireNotNull(scheduler.request(fingerprint, capturedAt)) {
                "expected an OCR ticket for fingerprint=$fingerprint capture=$capturedAt"
            }
            now += 400L
            return ticket
        }

        fun read(fingerprint: Long, capturedAt: Long, text: String?): LabyrinthShopCoinOcrScheduler.Read =
            requireNotNull(scheduler.complete(request(fingerprint, capturedAt), text))
    }

    @Test fun canonicalAmountsAcrossCapturesAndFingerprintsConfirmOnlyTheCurrentCropThenStop() {
        val fixture = Fixture()
        val first = fixture.read(10L, 100L, "7,460")
        assertEquals(7_460, first.amount)
        assertEquals(1, first.confirmations)
        assertEquals(first, fixture.scheduler.cached(10L, 100L))
        assertNull(fixture.scheduler.cached(20L, 500L))

        val second = fixture.read(20L, 500L, " 7，460 \t\n ")
        assertNotEquals(first.id, second.id)
        assertEquals(7_460, second.amount)
        assertEquals(2, second.confirmations)
        assertEquals(second, fixture.scheduler.cached(20L, 500L))
        assertNull("an old fingerprint must not authorize current pixels", fixture.scheduler.cached(10L, 500L))
        assertNull("a completed read cannot authorize an earlier capture", fixture.scheduler.cached(20L, 499L))

        repeat(20) { index ->
            fixture.now += 400L
            val capturedAt = 900L + index * 400L
            assertNull("confirmed unchanged pixels stop scheduling", fixture.scheduler.request(20L, capturedAt))
            assertEquals(second, fixture.scheduler.cached(20L, capturedAt))
        }

        assertNull(fixture.scheduler.cached(30L, 9_000L))
        val current = fixture.read(30L, 9_000L, "7460")
        assertEquals(2, current.confirmations)
        assertEquals(current, fixture.scheduler.cached(30L, 9_000L))
        assertNull(fixture.scheduler.cached(20L, 9_000L))
    }

    private data class Interruption(val text: String?, val amount: Int?)

    @Test fun differentAmountsAndInvalidReadsNeverConfirmButMatchingCapturesRecover() {
        val cases = listOf(
            Interruption("7450", 7_450),
            Interruption("0", 0),
            Interruption(null, null),
            Interruption("", null),
            Interruption("7O460", null),
            Interruption("7460金币", null),
        )
        for (case in cases) {
            val fixture = Fixture()
            assertEquals(1, fixture.read(31L, 100L, "7,460").confirmations)
            val interrupted = fixture.read(31L, 500L, case.text)
            assertEquals("case=$case", case.amount, interrupted.amount)
            assertEquals("case=$case", if (case.amount == null) 0 else 1, interrupted.confirmations)

            val recoveryAmount = case.amount ?: 7_460
            val next = fixture.read(31L, 900L, recoveryAmount.toString())
            assertEquals(recoveryAmount, next.amount)
            if (case.amount != null) {
                // A -> B -> B on the SAME pixels must not stop after the disagreement.
                assertEquals("case=$case", 2, next.confirmations)
                assertEquals(next, fixture.scheduler.cached(31L, 900L))
            } else {
                assertEquals("invalid OCR cannot count as an agreeing look", 1, next.confirmations)
                val recovered = fixture.read(31L, 1_300L, "7,460")
                assertEquals(7_460, recovered.amount)
                assertEquals(2, recovered.confirmations)
            }
        }
    }

    @Test fun repeatedCallbacksAndRepeatedCapturesCannotManufactureAgreement() {
        val fixture = Fixture()
        val firstTicket = fixture.request(10L, 100L)
        val first = requireNotNull(fixture.scheduler.complete(firstTicket, "7460"))
        assertEquals(1, first.confirmations)
        assertNull(fixture.scheduler.complete(firstTicket, "7460"))
        assertEquals(first, fixture.scheduler.cached(10L, 500L))

        assertNull(fixture.scheduler.request(10L, 100L))
        assertNull("a changed fingerprint is still the same capture", fixture.scheduler.request(20L, 100L))
        assertNull(fixture.scheduler.request(10L, 99L))
        assertEquals(1, fixture.scheduler.cached(10L, 500L)?.confirmations)

        val secondTicket = fixture.request(10L, 500L)
        assertNotEquals(firstTicket.id, secondTicket.id)
        assertNull(fixture.scheduler.complete(firstTicket, "9999"))
        assertNull("an old callback must not release the current ticket", fixture.scheduler.request(10L, 900L))
        assertEquals(first, fixture.scheduler.cached(10L, 500L))

        val second = requireNotNull(fixture.scheduler.complete(secondTicket, "7,460"))
        assertEquals(7_460, second.amount)
        assertEquals(2, second.confirmations)
        assertNull(fixture.scheduler.complete(secondTicket, "7,460"))
        assertEquals(second, fixture.scheduler.cached(10L, 900L))
        assertEquals(2, fixture.scheduler.cached(10L, 1_300L)?.confirmations)
    }

    @Test fun clearRetainsPhysicalPendingAndOldEpochCallbacksCannotReleaseANewerTicket() {
        val fixture = Fixture()
        val old = fixture.request(10L, 2_000L)
        repeat(20) { index ->
            // The old capture is newer than this cutoff, so its rejection must use the epoch.
            fixture.scheduler.clear(after = 1_000L)
            fixture.now += 400L
            assertNull(fixture.scheduler.cached(10L, 3_000L + index * 400L))
            assertNull("clear invalidates evidence, not physical ownership",
                fixture.scheduler.request(20L, 3_000L + index * 400L))
        }
        assertNull(fixture.scheduler.complete(old, "7460"))
        assertNull(fixture.scheduler.cached(10L, 20_000L))

        val freshTicket = fixture.request(20L, 20_000L)
        assertTrue(freshTicket.epoch > old.epoch)
        assertTrue(freshTicket.id > old.id)
        assertNull(fixture.scheduler.complete(old, "7460"))
        assertNull("duplicate old callback cannot release the fresh physical request",
            fixture.scheduler.request(30L, 20_400L))
        val first = requireNotNull(fixture.scheduler.complete(freshTicket, "7460"))
        assertEquals("the discarded old epoch cannot supply the first look", 1, first.confirmations)
        assertEquals(first, fixture.scheduler.cached(20L, 20_000L))

        val second = fixture.read(20L, 20_400L, "7,460")
        assertEquals(2, second.confirmations)
    }

    @Test fun sixAttemptBudgetIsSharedByChangingPixelsAndFailuresWithoutChargingRejectedRequests() {
        val cases = listOf<List<String?>>(
            listOf("100", "200", "100", "200", "100", "200"),
            listOf(null, "invalid", null, "invalid", null, "invalid"),
            listOf("7460", null, "7450", "7O460", "7440", null),
        )
        for (texts in cases) {
            val fixture = Fixture()
            fixture.scheduler.clear(after = 99L)
            repeat(10) { assertNull(fixture.scheduler.request(99L, 99L)) }
            var lastTicket: LabyrinthShopCoinOcrScheduler.Ticket? = null
            for ((index, text) in texts.withIndex()) {
                val capturedAt = 100L + index * 400L
                val ticket = fixture.request(100L + index, capturedAt)
                lastTicket = ticket
                val read = requireNotNull(fixture.scheduler.complete(ticket, text))
                assertEquals(LabyrinthShopCoinText.parse(text), read.amount)
                assertEquals(if (read.amount == null) 0 else 1, read.confirmations)
                assertNull(fixture.scheduler.request(1_000L + index, capturedAt))
                assertNull(fixture.scheduler.complete(ticket, text))
            }
            assertNull("unconfirmed fingerprint changes do not reset the six-ticket budget",
                fixture.scheduler.request(9_999L, 2_500L))
            fixture.now += 10_000L
            assertNull("elapsed time alone does not rearm an exhausted epoch",
                fixture.scheduler.request(9_998L, 2_900L))

            fixture.scheduler.clear(after = 2_100L)
            val restartedTicket = fixture.request(9_999L, 2_500L)
            assertTrue(restartedTicket.epoch > requireNotNull(lastTicket).epoch)
            assertEquals(7L, restartedTicket.id)
            val restarted = requireNotNull(fixture.scheduler.complete(restartedTicket, "300"))
            assertEquals(300, restarted.amount)
            assertEquals(1, restarted.confirmations)
        }
    }

    @Test fun captureCutoffRejectsEarlierAndEqualFramesBeforeAnyTicketOrCachedRead() {
        val fixture = Fixture()
        assertNull(fixture.scheduler.request(10L, Long.MIN_VALUE))
        fixture.scheduler.clear(after = 1_000L)
        for (capturedAt in listOf(Long.MIN_VALUE, 999L, 1_000L)) {
            fixture.now += 400L
            assertNull("capture=$capturedAt", fixture.scheduler.request(10L, capturedAt))
            assertNull(fixture.scheduler.cached(10L, capturedAt))
        }
        val first = fixture.read(10L, 1_001L, "7460")
        assertEquals(1, first.confirmations)
        assertEquals(first, fixture.scheduler.cached(10L, 1_001L))
        assertNull(fixture.scheduler.cached(10L, 1_000L))
        assertNull(fixture.scheduler.request(10L, 1_001L))
        assertEquals(2, fixture.read(10L, 1_002L, "7,460").confirmations)
    }

    @Test fun amountGrammarKeepsOnlyAsciiDigitsPresentationSeparatorsAndTheOriginalRange() {
        val valid = mapOf(
            "0" to 0,
            "0000" to 0,
            "7460" to 7_460,
            "7,460" to 7_460,
            "7，460" to 7_460,
            " 7 460\t\n " to 7_460,
            "999999999" to 999_999_999,
            "999,999,999" to 999_999_999,
        )
        for ((text, amount) in valid) assertEquals("text=$text", amount, LabyrinthShopCoinText.parse(text))
        val invalid = listOf<String?>(
            null, "", " \t\n ", ",， \t\n", "-1", "+1", "1.0", "1_000",
            "7O460", "7460金币", "持有阿尔法金币7460", "７４６０",
            "1000000000", "1,000,000,000", "9223372036854775808", "999999999999999999999999",
        )
        for (text in invalid) assertNull("text=$text", LabyrinthShopCoinText.parse(text))
    }
}
