package com.landosol.toolbox.labyrinth.vision

/** Existing wallet grammar: separators are presentation, never guessed letters or extra labels. */
internal object LabyrinthShopCoinText {
    fun parse(text: String?): Int? {
        if (text.isNullOrBlank()) return null
        val digits = StringBuilder()
        for (character in text) {
            when {
                character in '0'..'9' -> digits.append(character)
                character == ',' || character == '，' || character.isWhitespace() -> Unit
                else -> return null
            }
        }
        if (digits.isEmpty()) return null
        return digits.toString().toLongOrNull()?.takeIf { it in 0L..999_999_999L }?.toInt()
    }
}

/**
 * Wallet-only, single physical OCR request. Financial confirmation is two distinct captures of
 * the same canonical amount within one evidence epoch, not two spellings of one cached read.
 * A completed older crop may supply the first look, but NEVER authorizes the current frame:
 * cached() still requires the latest completed crop to match the current wallet pixels.
 */
internal class LabyrinthShopCoinOcrScheduler(
    private val clock: () -> Long,
    private val spacingMillis: Long = 350L,
    private val maximumAttempts: Int = 6,
) {
    data class Ticket(val id: Long, val epoch: Long, val fingerprint: Long, val capturedAtMillis: Long)
    data class Read(
        val id: Long,
        val fingerprint: Long,
        val capturedAtMillis: Long,
        val amount: Int?,
        val confirmations: Int,
    )
    private var epoch = 0L
    private var sequence = 0L
    private var capturedAfter = Long.MIN_VALUE
    private var lastRequestedCapture = Long.MIN_VALUE
    private var attempts = 0
    private var nextStart = Long.MIN_VALUE
    private var pending: Ticket? = null
    private var latest: Read? = null

    init { require(maximumAttempts >= 2 && spacingMillis >= 0) }

    @Synchronized fun clear(after: Long = Long.MIN_VALUE) {
        epoch++
        capturedAfter = after
        lastRequestedCapture = Long.MIN_VALUE
        attempts = 0
        latest = null
        // A reset invalidates the answer, not ownership of the bitmap still being read.
    }

    @Synchronized fun cached(fingerprint: Long, capturedAtMillis: Long): Read? = latest?.takeIf {
        capturedAtMillis > capturedAfter && capturedAtMillis >= it.capturedAtMillis && it.fingerprint == fingerprint
    }

    @Synchronized fun request(fingerprint: Long, capturedAtMillis: Long): Ticket? {
        if (capturedAtMillis <= capturedAfter || capturedAtMillis <= lastRequestedCapture ||
            pending != null || clock() < nextStart) return null
        val previous = latest
        if (previous != null && previous.confirmations >= 2) {
            if (previous.fingerprint == fingerprint) return null
            // Changed wallet pixels need a fresh look; never hand the old cached approval to them.
            attempts = 0
        }
        if (attempts >= maximumAttempts) return null
        attempts++
        lastRequestedCapture = capturedAtMillis
        nextStart = clock() + spacingMillis
        return Ticket(++sequence, epoch, fingerprint, capturedAtMillis).also { pending = it }
    }

    @Synchronized fun complete(ticket: Ticket, text: String?): Read? {
        if (pending != ticket) return null
        pending = null
        if (ticket.epoch != epoch || ticket.capturedAtMillis <= capturedAfter) return null
        val previous = latest
        if (previous != null && ticket.capturedAtMillis <= previous.capturedAtMillis) return null
        val amount = LabyrinthShopCoinText.parse(text)
        val confirmations = when {
            amount == null -> 0
            previous?.amount == amount && previous.confirmations > 0 -> minOf(2, previous.confirmations + 1)
            else -> 1
        }
        return Read(ticket.id, ticket.fingerprint, ticket.capturedAtMillis, amount, confirmations).also { latest = it }
    }
}
