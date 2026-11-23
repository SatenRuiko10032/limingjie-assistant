package com.landosol.toolbox.labyrinth.vision

/** One recognition policy for both four- and six-thread runs. No new timer or success fallback. */
enum class LabyrinthShopOcrPhase { STOCK, REFRESH_DIALOG, REFRESH_RECEIPT }

data class LabyrinthShopOcrPolicy(
    val evidenceVersion: Long = 0,
    val capturedAfter: Long = Long.MIN_VALUE,
    val phase: LabyrinthShopOcrPhase = LabyrinthShopOcrPhase.STOCK,
) {
    fun accepts(capturedAt: Long, expectedVersion: Long): Boolean =
        capturedAt > capturedAfter && expectedVersion == evidenceVersion

    fun plan(capturedAt: Long, expectedVersion: Long, connecting: Boolean, modal: Boolean): LabyrinthShopOcrPlan {
        if (!accepts(capturedAt, expectedVersion) || connecting) return LabyrinthShopOcrPlan()
        return LabyrinthShopOcrPlan(
            titles = phase == LabyrinthShopOcrPhase.STOCK,
            wallet = !modal,
            // The authorization modal belongs to the REQUESTED phase. After confirmation, old
            // modal reads cannot help the payment receipt and must not compete with the wallet.
            dialog = modal && phase != LabyrinthShopOcrPhase.REFRESH_RECEIPT,
        )
    }
}

/** Testable dispatch of the very same shop OCR stages used by the Android processor. */
data class LabyrinthShopOcrPlan(val titles: Boolean = false, val wallet: Boolean = false, val dialog: Boolean = false) {
    fun <T> apply(initial: T, readTitles: (T) -> T, readWallet: (T) -> T, readDialog: (T) -> T): T {
        var result = initial
        if (titles) result = readTitles(result)
        if (wallet) result = readWallet(result)
        if (dialog) result = readDialog(result)
        return result
    }
}

/** Recheck after crop preparation, immediately before transferring an owned input to OCR. */
internal fun <T> submitLabyrinthShopOcrIfCurrent(
    owned: T,
    requestCurrent: () -> Boolean,
    submit: (T) -> Unit,
    discard: (T) -> Unit,
): Boolean {
    if (!requestCurrent()) {
        discard(owned)
        return false
    }
    submit(owned)
    return true
}
