package com.landosol.toolbox.labyrinth.vision

import android.graphics.Bitmap
import android.os.SystemClock

/** Two independent, action-current wallet reads; no core-count branch or payment fallback. */
class AndroidLabyrinthShopCoinResolver(
    private val submitTextRead: (Bitmap, (String?) -> Unit) -> Unit = AndroidChineseRoleNameOcr::readAsync,
) {
    private val scheduler = LabyrinthShopCoinOcrScheduler({ SystemClock.elapsedRealtime() })

    @Synchronized
    fun clear(capturedAfter: Long = Long.MIN_VALUE) {
        scheduler.clear(capturedAfter)
    }

    @Synchronized
    fun resolve(bitmap: Bitmap, result: LabyrinthEntryFrameResult, capturedAtMillis: Long,
        requestCurrent: () -> Boolean = { true },
    ): LabyrinthEntryFrameResult {
        val shop = result.shopObservation
        if (!requestCurrent()) {
            clear(capturedAtMillis)
            return result.copy(shopObservation = shop?.copy(coinBalance = null, coinBalanceConfirmed = false))
        }
        if (result.observation.state != LabyrinthEntryPageState.SHOP ||
            shop == null || shop.dialogState != LabyrinthShopDialogState.NONE
        ) {
            clear(capturedAtMillis)
            return result
        }
        val rect = ReferenceFitMapper.map(bitmap.width, bitmap.height, STANDARD_REFERENCE, COIN_ROI)
            ?: return result
        val fingerprint = relicRoiFingerprint(bitmap, rect)
        val read = scheduler.cached(fingerprint, capturedAtMillis)
        scheduler.request(fingerprint, capturedAtMillis)?.let { ticket ->
            val diagnosticId = RelicRoiOcrTelemetry.requested("shop-coin", capturedAtMillis, SystemClock.elapsedRealtime())
            var input: Bitmap? = null
            try {
                val crop = Bitmap.createBitmap(bitmap, rect.left, rect.top, rect.width, rect.height)
                input = crop
                val scaled = Bitmap.createScaledBitmap(crop, crop.width * 2, crop.height * 2, false)
                input = scaled
                if (scaled !== crop) crop.recycle()
                val submitted = submitLabyrinthShopOcrIfCurrent(scaled, requestCurrent,
                    submit = { current -> submitTextRead(current) { text ->
                        try {
                            val accepted = scheduler.complete(ticket, text)
                            android.util.Log.d("LabyrinthShopCoin", "read=" + ticket.id +
                                " capturedAt=" + ticket.capturedAtMillis + " epoch=" + ticket.epoch +
                                " amount=" + accepted?.amount + " confirmations=" + accepted?.confirmations +
                                " accepted=" + (accepted != null))
                        } finally {
                            RelicRoiOcrTelemetry.completed(diagnosticId, SystemClock.elapsedRealtime(), text?.length)
                            if (!current.isRecycled) current.recycle()
                        }
                    } },
                    discard = { rejected ->
                        try {
                            clear(capturedAtMillis)
                            scheduler.complete(ticket, null) // No engine owns this input yet.
                        } finally {
                            RelicRoiOcrTelemetry.completed(diagnosticId, SystemClock.elapsedRealtime(), null)
                            if (!rejected.isRecycled) rejected.recycle()
                        }
                    },
                )
                input = null
                if (!submitted) return result.copy(shopObservation = shop.copy(coinBalance = null, coinBalanceConfirmed = false))
                Unit
            } catch (_: Exception) {
                scheduler.complete(ticket, null)
                RelicRoiOcrTelemetry.completed(diagnosticId, SystemClock.elapsedRealtime(), null)
                input?.let { if (!it.isRecycled) it.recycle() }
            }
        }
        return result.copy(shopObservation = shop.copy(
            coinBalance = read?.amount,
            coinBalanceConfirmed = read?.amount != null && read.confirmations >= 2,
        ))
    }

    private companion object {
        val STANDARD_REFERENCE = EntryReferenceSize(1920, 1080)
        val COIN_ROI = EntryReferenceRect(1215, 942, 210, 72)
    }
}
