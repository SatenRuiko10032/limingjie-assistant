package com.landosol.toolbox.labyrinth

import com.landosol.toolbox.labyrinth.vision.LabyrinthShopObservation
import com.landosol.toolbox.labyrinth.vision.LabyrinthShopViewport
import com.landosol.toolbox.automation.AutomationAction
import com.landosol.toolbox.automation.ScreenPoint

/** One immutable request travels through the existing vision root; no new worker or queue. */
data class LabyrinthShopEvidenceRequest(val epoch: Long = 0, val afterMillis: Long = Long.MIN_VALUE)

internal enum class LabyrinthShopScrollKind { DOWN, ADJUST, TOP }
internal sealed interface LabyrinthShopEvidenceGate {
    data object Ready : LabyrinthShopEvidenceGate
    data class Wait(val reason: String) : LabyrinthShopEvidenceGate
    data class Blocked(val reason: String) : LabyrinthShopEvidenceGate
}

/** Exact fresh balance corroborates a paid refresh; fees are read, never guessed. */
internal fun labyrinthShopRefreshPaymentConfirmed(
    before: Int?, fee: Int?, after: Int?, oldSignature: Long?, newSignature: Long?,
): Boolean = before != null && fee != null && after != null && before >= 0 && fee >= 0 && before >= fee &&
    after == before - fee && (fee > 0 || (oldSignature != null && newSignature != null && oldSignature != newSignature))

/** Finite shop-only single strokes. Click targets are always detected from current cards. */
internal fun labyrinthShopScrollAction(kind: LabyrinthShopScrollKind, width: Int, height: Int): AutomationAction.Swipe? {
    if (width <= 0 || height <= 0) return null
    val scale = minOf(width / 1280f, height / 720f)
    val offsetX = (width - 1280f * scale) / 2f
    val offsetY = (height - 720f * scale) / 2f
    // Points use the same centred fit as ROIs, without inventing a 1px rectangle that the ROI
    // mapper correctly rejects as too small. The stroke stays strictly inside the shop content.
    fun point(y: Int): ScreenPoint = ScreenPoint(offsetX + 1120f * scale, offsetY + y * scale)
    val startY = when (kind) {
        LabyrinthShopScrollKind.DOWN -> 500
        LabyrinthShopScrollKind.ADJUST -> 330
        LabyrinthShopScrollKind.TOP -> 350
    }
    val endY = when (kind) {
        LabyrinthShopScrollKind.DOWN -> 350
        LabyrinthShopScrollKind.ADJUST -> 375
        LabyrinthShopScrollKind.TOP -> 540
    }
    return AutomationAction.Swipe(start = point(startY), end = point(endY),
        durationMillis = if (kind == LabyrinthShopScrollKind.ADJUST) 450L else 650L, holdMillis = 0L)
}

/** Visit-local transaction/viewport evidence. A gesture receipt never establishes movement. */
internal class LabyrinthShopVisitState {
    var active = false
        private set
    @Volatile var evidenceRequest = LabyrinthShopEvidenceRequest()
        private set
    var entryBalance: Int? = null
        private set
    var currentBalance: Int? = null
        private set
    var successfulRefreshes = 0
        private set
    var downAttempts = 0
        private set
    var adjustmentAttempts = 0
        private set
    var returnAttempts = 0
        private set
    var shelfScanComplete = false
        private set
    var lowerScanStarted = false
        private set
    var bottomObserved = false
        private set
    var returningToTop = false
        private set
    private var lastRefreshTransactionId: Long? = null
    private var waitSince = Long.MIN_VALUE
    private var lastCapture = Long.MIN_VALUE
    private var stableSince = Long.MIN_VALUE
    private var stableCount = 0
    private var stableKey: Triple<Long, Int?, Int?>? = null
    private data class Scroll(
        val kind: LabyrinthShopScrollKind,
        val origin: LabyrinthShopViewport,
        val submittedAt: Long,
        var receiptAt: Long = Long.MIN_VALUE,
        var movement: Boolean = false,
    )
    private var scroll: Scroll? = null
    val scrollPending: Boolean get() = scroll != null

    fun beginVisit(now: Long) {
        active = true
        entryBalance = null
        currentBalance = null
        successfulRefreshes = 0
        lastRefreshTransactionId = null
        resetShelf()
        invalidate(now)
    }

    private fun resetShelf() {
        downAttempts = 0
        adjustmentAttempts = 0
        returnAttempts = 0
        lowerScanStarted = false
        bottomObserved = false
        returningToTop = false
        shelfScanComplete = false
        scroll = null
    }

    /** Invalidates OCR and coordinates together. Entry qualification is deliberately not reset. */
    fun invalidate(now: Long) {
        evidenceRequest = LabyrinthShopEvidenceRequest(evidenceRequest.epoch + 1, now)
        currentBalance = null
        waitSince = now
        resetStability()
    }

    fun purchaseCompleted(now: Long) {
        // Browsing/compaction is not a new stock cycle and does not refill any swipe allowance.
        invalidate(now)
    }

    fun refreshCompleted(transactionId: Long, now: Long): Boolean {
        if (transactionId == Long.MIN_VALUE || lastRefreshTransactionId == transactionId) return false
        lastRefreshTransactionId = transactionId
        successfulRefreshes++
        resetShelf()
        invalidate(now)
        return true
    }

    fun markScanFinishedAtTop(viewport: LabyrinthShopViewport): Boolean {
        if (!viewport.atTop) return false
        returningToTop = false
        shelfScanComplete = true
        return true
    }

    fun planReturnToTop() { returningToTop = true }

    fun canScroll(kind: LabyrinthShopScrollKind): Boolean = scroll == null && when (kind) {
        LabyrinthShopScrollKind.DOWN -> downAttempts < MAX_DOWN_ATTEMPTS
        LabyrinthShopScrollKind.ADJUST -> adjustmentAttempts < MAX_ADJUSTMENTS
        LabyrinthShopScrollKind.TOP -> returnAttempts < MAX_RETURN_ATTEMPTS
    }

    fun beginScroll(kind: LabyrinthShopScrollKind, origin: LabyrinthShopViewport, now: Long): Boolean {
        if (!canScroll(kind)) return false
        when (kind) {
            LabyrinthShopScrollKind.DOWN -> { downAttempts++; lowerScanStarted = true }
            LabyrinthShopScrollKind.ADJUST -> adjustmentAttempts++
            LabyrinthShopScrollKind.TOP -> returnAttempts++
        }
        scroll = Scroll(kind, origin, now)
        // Count submitted-but-unresolved attempts too. A late failure cannot allow blind replay.
        invalidate(now)
        return true
    }

    fun cancelUnsubmittedScroll(now: Long) {
        val pending = scroll ?: return
        when (pending.kind) {
            LabyrinthShopScrollKind.DOWN -> {
                downAttempts = (downAttempts - 1).coerceAtLeast(0)
                if (downAttempts == 0) lowerScanStarted = false
            }
            LabyrinthShopScrollKind.ADJUST -> adjustmentAttempts = (adjustmentAttempts - 1).coerceAtLeast(0)
            LabyrinthShopScrollKind.TOP -> returnAttempts = (returnAttempts - 1).coerceAtLeast(0)
        }
        scroll = null
        invalidate(now)
    }

    fun scrollReceipt(now: Long) {
        scroll?.receiptAt = now
        // The final callback is only a new-frame lower bound, not proof the game moved/stopped.
        invalidate(now)
    }

    private fun resetStability() {
        lastCapture = Long.MIN_VALUE
        stableSince = Long.MIN_VALUE
        stableCount = 0
        stableKey = null
    }

    fun observe(shop: LabyrinthShopObservation, now: Long, requireViewport: Boolean): LabyrinthShopEvidenceGate {
        val request = evidenceRequest
        if (shop.evidenceEpoch != request.epoch || shop.capturedAtMillis <= request.afterMillis) {
            return waiting(now, "等待本次动作后的新商店画面，旧代次证据不消费")
        }
        if (shop.balanceReadingExhausted) {
            return LabyrinthShopEvidenceGate.Blocked("金币小区域有限重读仍未确认，停止本店消费，不复用旧余额")
        }
        currentBalance = shop.balanceCoins?.takeIf {
            it >= 0 && shop.balanceCapturedAtMillis > request.afterMillis
        }
        if (currentBalance != null && entryBalance == null) entryBalance = currentBalance
        if (currentBalance == null) return waiting(now, "等待确认本店当前金币余额，不使用旧金额连续消费")
        if (!requireViewport) return LabyrinthShopEvidenceGate.Ready
        val viewport = shop.viewport ?: return waiting(now, "等待可靠货架位置与滚动条证据")
        val pending = scroll
        if (pending != null) {
            if (pending.receiptAt == Long.MIN_VALUE || shop.capturedAtMillis <= pending.receiptAt) {
                return waiting(now, "滑动尚在收尾，等待终态回执后的新帧")
            }
            val before = pending.origin.scrollThumbTop
            val after = viewport.scrollThumbTop
            val delta = if (before != null && after != null) after - before else null
            pending.movement = pending.movement || when (pending.kind) {
                LabyrinthShopScrollKind.DOWN -> delta != null && delta >= MIN_MOVEMENT_PIXELS
                LabyrinthShopScrollKind.ADJUST, LabyrinthShopScrollKind.TOP ->
                    delta != null && delta <= -MIN_MOVEMENT_PIXELS
            }
            if (!pending.movement) return waiting(now, "手势回执已到但尚无可靠货架位移，禁止购买或重放旧点击")
        }
        if (shop.capturedAtMillis > lastCapture) {
            val key = Triple(viewport.signature, viewport.scrollThumbTop, viewport.scrollThumbBottom)
            if (key == stableKey) stableCount++ else {
                stableKey = key
                stableCount = 1
                stableSince = shop.capturedAtMillis
            }
            lastCapture = shop.capturedAtMillis
        }
        if (stableCount < STABLE_FRAMES || shop.capturedAtMillis - stableSince < STABLE_MILLIS) {
            return waiting(now, "货架已有新帧，等待连续独立画面停稳")
        }
        if (viewport.atBottom && lowerScanStarted) bottomObserved = true
        if (pending != null) {
            scroll = null
            if (pending.kind == LabyrinthShopScrollKind.TOP && viewport.atTop && returningToTop) {
                markScanFinishedAtTop(viewport)
            }
        }
        return LabyrinthShopEvidenceGate.Ready
    }

    fun waiting(now: Long, reason: String): LabyrinthShopEvidenceGate {
        if (waitSince == Long.MIN_VALUE) waitSince = now
        return if (now - waitSince >= MAX_EVIDENCE_WAIT_MILLIS) {
            LabyrinthShopEvidenceGate.Blocked("商店证据在限定时间内未确认：$reason")
        } else LabyrinthShopEvidenceGate.Wait(reason)
    }

    /** A safe buy is actual progress; OCR messages/new frames alone never extend the deadline. */
    fun progress(now: Long) { waitSince = now }

    companion object {
        const val MAX_DOWN_ATTEMPTS = 2
        const val MAX_ADJUSTMENTS = 2
        const val MAX_RETURN_ATTEMPTS = 3
        const val MAX_EVIDENCE_WAIT_MILLIS = 90_000L
        private const val MIN_MOVEMENT_PIXELS = 2
        private const val STABLE_FRAMES = 2
        private const val STABLE_MILLIS = 150L
    }
}
