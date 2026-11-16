package com.landosol.toolbox.labyrinth

import com.landosol.toolbox.labyrinth.vision.LabyrinthShopObservation
import com.landosol.toolbox.labyrinth.vision.LabyrinthShopViewport
import org.junit.Assert.*
import org.junit.Test

class LabyrinthShopVisitStateTest {
    private fun viewport(top: Int = 152, signature: Long = top.toLong()) = LabyrinthShopViewport(
        signature = signature, scrollThumbTop = top, scrollThumbBottom = top + 350,
        atTop = top == 152, atBottom = top == 208, complete = true,
    )
    private fun shop(state: LabyrinthShopVisitState, at: Long, balance: Int? = 15001,
        top: Int = 152, signature: Long = top.toLong()) = LabyrinthShopObservation(
        items = emptyList(), balanceCoins = balance, balanceCapturedAtMillis = at,
        capturedAtMillis = at, evidenceEpoch = state.evidenceRequest.epoch,
        viewport = viewport(top, signature),
    )
    private fun finish(state: LabyrinthShopVisitState, kind: LabyrinthShopScrollKind,
        origin: Int, target: Int, at: Long) {
        assertTrue(state.beginScroll(kind, viewport(origin), at))
        state.scrollReceipt(at + 100)
        assertTrue(state.observe(shop(state, at + 101, top = target), at + 101, true)
            is LabyrinthShopEvidenceGate.Wait)
        assertEquals(LabyrinthShopEvidenceGate.Ready,
            state.observe(shop(state, at + 300, top = target), at + 300, true))
        assertFalse(state.scrollPending)
    }

    @Test fun entryQualificationSurvivesPurchasesAndConfirmedRefreshOnly() {
        val state = LabyrinthShopVisitState()
        state.beginVisit(1000)
        val old = shop(state, 1001)
        assertEquals(LabyrinthShopEvidenceGate.Ready, state.observe(old, 1001, false))
        assertEquals(15001, state.entryBalance)
        state.purchaseCompleted(1100)
        assertNull(state.currentBalance)
        assertTrue(state.observe(old, 1101, false) is LabyrinthShopEvidenceGate.Wait)
        assertEquals(LabyrinthShopEvidenceGate.Ready, state.observe(shop(state, 1102, 13900), 1102, false))
        assertEquals(15001, state.entryBalance)
        assertTrue(state.refreshCompleted(1200, 1201))
        assertFalse(state.refreshCompleted(1200, 1202))
        assertEquals(1, state.successfulRefreshes)
        assertNull(state.currentBalance)
        state.observe(shop(state, 1203, 13600), 1203, false)
        assertEquals(15001, state.entryBalance)
    }

    @Test fun receiptWithoutVisualMovementCannotAuthorizeAnythingAndTimesOut() {
        val state = LabyrinthShopVisitState()
        state.beginVisit(1000)
        assertTrue(state.beginScroll(LabyrinthShopScrollKind.DOWN, viewport(), 1100))
        state.scrollReceipt(1200)
        repeat(2) { index ->
            val at = 1201L + index * 200
            assertTrue(state.observe(shop(state, at), at, true) is LabyrinthShopEvidenceGate.Wait)
        }
        assertTrue(state.scrollPending)
        val deadline = 1200 + LabyrinthShopVisitState.MAX_EVIDENCE_WAIT_MILLIS + 1
        assertTrue(state.observe(shop(state, deadline), deadline, true) is LabyrinthShopEvidenceGate.Blocked)
    }

    @Test fun motionAndStableIndependentNewFramesAreBothRequired() {
        val state = LabyrinthShopVisitState()
        state.beginVisit(1000)
        assertTrue(state.beginScroll(LabyrinthShopScrollKind.DOWN, viewport(), 1100))
        val beforeReceipt = shop(state, 1110, top = 208)
        state.scrollReceipt(1200)
        assertTrue(state.observe(beforeReceipt, 1201, true) is LabyrinthShopEvidenceGate.Wait)
        val first = shop(state, 1201, top = 208)
        assertTrue(state.observe(first, 1201, true) is LabyrinthShopEvidenceGate.Wait)
        assertTrue(state.observe(first, 1400, true) is LabyrinthShopEvidenceGate.Wait)
        assertTrue(state.scrollPending)
        assertEquals(LabyrinthShopEvidenceGate.Ready,
            state.observe(shop(state, 1401, top = 208), 1401, true))
        assertFalse(state.scrollPending)
    }

    @Test fun unknownMoneyIsNotZeroAndOldAmountIsNeverReused() {
        val state = LabyrinthShopVisitState()
        state.beginVisit(1000)
        state.observe(shop(state, 1001, 10000), 1001, false)
        assertTrue(state.observe(shop(state, 1002, null), 1002, false) is LabyrinthShopEvidenceGate.Wait)
        assertNull(state.currentBalance)
        assertEquals(10000, state.entryBalance)
        assertEquals(LabyrinthShopEvidenceGate.Ready, state.observe(shop(state, 1003, 0), 1003, false))
        assertEquals(0, state.currentBalance)
        val exhausted = shop(state, 1004).copy(balanceReadingExhausted = true)
        assertTrue(state.observe(exhausted, 1004, false) is LabyrinthShopEvidenceGate.Blocked)
    }

    @Test fun finiteScanAllowancesSurviveCompactionAndResetOnlyAfterPaidRefresh() {
        val state = LabyrinthShopVisitState()
        state.beginVisit(1000)
        finish(state, LabyrinthShopScrollKind.DOWN, 152, 180, 1100)
        finish(state, LabyrinthShopScrollKind.DOWN, 180, 208, 1500)
        state.purchaseCompleted(1900)
        assertEquals(2, state.downAttempts)
        assertFalse(state.canScroll(LabyrinthShopScrollKind.DOWN))
        finish(state, LabyrinthShopScrollKind.ADJUST, 208, 204, 2000)
        finish(state, LabyrinthShopScrollKind.ADJUST, 204, 200, 2400)
        assertFalse(state.canScroll(LabyrinthShopScrollKind.ADJUST))
        state.planReturnToTop()
        assertFalse(state.markScanFinishedAtTop(viewport(200)))
        finish(state, LabyrinthShopScrollKind.TOP, 200, 152, 2800)
        assertTrue(state.shelfScanComplete)
        assertTrue(state.refreshCompleted(3300, 3301))
        assertEquals(0, state.downAttempts)
        assertEquals(0, state.adjustmentAttempts)
        assertFalse(state.shelfScanComplete)
        assertTrue(state.canScroll(LabyrinthShopScrollKind.DOWN))
    }

    @Test fun actualRefreshFeeAndFiniteSingleStrokeContract() {
        for (fee in listOf(300, 600, 490, 900)) {
            assertTrue(labyrinthShopRefreshPaymentConfirmed(10000, fee, 10000 - fee, 1, 2))
            assertFalse(labyrinthShopRefreshPaymentConfirmed(10000, fee, 10000, 1, 2))
        }
        assertFalse(labyrinthShopRefreshPaymentConfirmed(10000, null, 9700, 1, 2))
        assertFalse(labyrinthShopRefreshPaymentConfirmed(10000, 0, 10000, 1, 1))
        assertTrue(labyrinthShopRefreshPaymentConfirmed(10000, 0, 10000, 1, 2))
        for (kind in LabyrinthShopScrollKind.entries) {
            for ((width, height) in listOf(1280 to 720, 1920 to 1080, 1920 to 1200)) {
                val action = requireNotNull(labyrinthShopScrollAction(kind, width, height))
                assertEquals(0L, action.holdMillis)
                assertTrue(action.durationMillis in 1..1000)
                assertTrue(action.start.x in 0f..width.toFloat() && action.end.x in 0f..width.toFloat())
                assertTrue(action.start.y in 0f..height.toFloat() && action.end.y in 0f..height.toFloat())
            }
        }
        assertNull(labyrinthShopScrollAction(LabyrinthShopScrollKind.DOWN, 0, 720))
    }
}
