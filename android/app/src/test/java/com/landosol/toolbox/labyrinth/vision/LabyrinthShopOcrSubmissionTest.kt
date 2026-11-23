package com.landosol.toolbox.labyrinth.vision

import org.junit.Assert.*
import org.junit.Test

/** Uses the same final submit gate as the Android wallet, title and refresh-modal paths. */
class LabyrinthShopOcrSubmissionTest {
    private data class Input(val id: Int, var releases: Int = 0)

    @Test fun policyChangingDuringPreparationRejectsOwnedInputBeforePhysicalSubmit() {
        var policy = LabyrinthShopOcrPolicy(7L, 100L, LabyrinthShopOcrPhase.REFRESH_DIALOG)
        val expected = policy
        var now = 150L
        val scheduler = LabyrinthShopCoinOcrScheduler({ now })
        scheduler.complete(requireNotNull(scheduler.request(1L, now)), "7460")
        now = 500L
        val reserved = requireNotNull(scheduler.request(1L, 200L))
        val owned = Input(1)
        val current = { policy == expected && policy.accepts(200L, 7L) }
        assertTrue(current()) // Resolver-entry admission, before hashing/cropping/scaling.
        policy = LabyrinthShopOcrPolicy(8L, 250L, LabyrinthShopOcrPhase.REFRESH_RECEIPT)
        var submissions = 0
        var invalidations = 0
        val submitted = submitLabyrinthShopOcrIfCurrent(owned, current,
            submit = { submissions++ },
            discard = {
                invalidations++
                scheduler.clear(after = policy.capturedAfter)
                assertNull(scheduler.complete(reserved, null))
                it.releases++
            },
        )
        assertFalse(submitted); assertEquals(0, submissions)
        assertEquals(1, invalidations); assertEquals(1, owned.releases)
        assertNull(scheduler.cached(1L, 900L))
        now = 900L
        val fresh = requireNotNull(scheduler.request(1L, now))
        assertTrue(fresh.epoch > reserved.epoch)
        assertEquals("discarded old evidence cannot confirm the fresh epoch", 1, scheduler.complete(fresh, "7460")?.confirmations)
    }

    @Test fun eachPhysicalTitleOrModalFieldRechecksAndNeverReleasesAlreadySubmittedInput() {
        var current = true
        val inputs = (1..3).map(::Input)
        val accepted = mutableListOf<Int>()
        val discarded = mutableListOf<Int>()
        for (owned in inputs) {
            submitLabyrinthShopOcrIfCurrent(owned, { current },
                submit = { accepted += it.id; current = false },
                discard = { discarded += it.id; it.releases++ },
            )
        }
        assertEquals(listOf(1), accepted); assertEquals(listOf(2, 3), discarded)
        assertEquals(0, inputs.first().releases) // Physical owner is still reading it.
        inputs.first().releases++ // Only its completion callback releases the accepted input.
        assertTrue(inputs.all { it.releases == 1 })
    }

    @Test fun admittedInputTransfersWithoutRecyclingAndTheSubmitExceptionStaysWithCaller() {
        val owned = Input(1)
        var handedOff: Input? = null
        assertTrue(submitLabyrinthShopOcrIfCurrent(owned, { true },
            submit = { handedOff = it }, discard = { fail("current input must not be discarded") }))
        assertSame(owned, handedOff); assertEquals(0, owned.releases)
        val failure = IllegalStateException("submit")
        try {
            submitLabyrinthShopOcrIfCurrent(Input(2), { true }, submit = { throw failure },
                discard = { fail("submission error belongs to the caller's existing cleanup path") })
            fail("submit failure must propagate")
        } catch (actual: IllegalStateException) {
            assertSame(failure, actual)
        }
    }
}
