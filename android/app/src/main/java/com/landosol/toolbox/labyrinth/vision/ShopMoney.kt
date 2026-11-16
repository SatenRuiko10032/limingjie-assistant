package com.landosol.toolbox.labyrinth.vision

/** Integer coins only. No OCR glyph substitutions, decimal rounding, or majority voting. */
object ShopMoney {
    private val integer = Regex("(?:0|[1-9][0-9]*|[1-9][0-9]{0,2}(?:,[0-9]{3})+)")
    fun parse(text: String?): Int? {
        val value = text?.trim()?.replace('，', ',') ?: return null
        if (!integer.matches(value)) return null
        return value.replace(",", "").toIntOrNull()
    }
    fun parseRefresh(text: String?): Int? {
        val value = text?.trim() ?: return null
        val match = Regex("用\\s*([0-9,，]+)\\s*更新").matchEntire(value)
        return match?.let { parse(it.groupValues[1]) }
    }
}

/** One explicit evidence epoch, at most three physical requests, and two independent captures.
 * Pixel/fingerprint changes NEVER restart this budget. Any conflicting valid value poisons it;
 * 6,685 / 6,685 is confirmation, 6,685 / 6,085 / 6,685 is not a majority decision. */
class ShopMoneyReadBudget(
    private val totalTimeoutMillis: Long = 30_000L,
    private val parse: (String?) -> Int? = ShopMoney::parse,
    private val startOnFirstRequest: Boolean = false,
) {
    data class Ticket(val epoch: Long, val generation: Long, val id: Long, val capturedAtMillis: Long)
    data class Snapshot(val coins: Int?, val exhausted: Boolean, val capturedAtMillis: Long,
        val completedReads: Int)
    private var epoch = Long.MIN_VALUE
    private var after = Long.MIN_VALUE
    private var generation = 0L
    private var sequence = 0L
    private var startedAt = Long.MIN_VALUE
    private var attempts = 0
    private var completed = 0
    private var pending: Ticket? = null
    private var lastRequestedCapture = Long.MIN_VALUE
    private var value: Int? = null
    private var confirmations = 0
    private var evidenceAt = Long.MIN_VALUE
    private var conflict = false

    init { require(totalTimeoutMillis > 0) }

    @Synchronized fun bind(evidenceEpoch: Long, afterMillis: Long, now: Long) {
        if (epoch == evidenceEpoch && after == afterMillis) return
        epoch = evidenceEpoch
        after = afterMillis
        generation++
        startedAt = if (startOnFirstRequest) Long.MIN_VALUE else now
        attempts = 0
        completed = 0
        pending = null
        lastRequestedCapture = Long.MIN_VALUE
        value = null
        confirmations = 0
        evidenceAt = Long.MIN_VALUE
        conflict = false
    }

    @Synchronized fun request(now: Long, capturedAtMillis: Long): Ticket? {
        if (epoch == Long.MIN_VALUE || capturedAtMillis <= after || capturedAtMillis <= lastRequestedCapture) return null
        if (startedAt == Long.MIN_VALUE) startedAt = now
        if (snapshot(now).exhausted || confirmations >= 2 || pending != null || attempts >= 3) return null
        attempts++
        lastRequestedCapture = capturedAtMillis
        return Ticket(epoch, generation, ++sequence, capturedAtMillis).also { pending = it }
    }

    @Synchronized fun complete(ticket: Ticket, text: String?, now: Long) {
        if (ticket != pending || ticket.generation != generation || ticket.epoch != epoch) return
        pending = null
        completed++
        if (now - startedAt >= totalTimeoutMillis || ticket.capturedAtMillis <= after) return
        val next = parse(text) ?: return
        val old = value
        if (old != null && old != next) {
            conflict = true
            return
        }
        value = next
        confirmations++
        // Conservative source age: both agreeing captures must be newer than the action.
        evidenceAt = if (evidenceAt == Long.MIN_VALUE) ticket.capturedAtMillis else minOf(evidenceAt, ticket.capturedAtMillis)
    }

    @Synchronized fun snapshot(now: Long): Snapshot {
        if (startedAt == Long.MIN_VALUE) return Snapshot(null, false, Long.MIN_VALUE, 0)
        val confirmed = confirmations >= 2 && !conflict
        return Snapshot(if (confirmed) value else null,
            !confirmed && (conflict || completed >= 3 || now - startedAt >= totalTimeoutMillis),
            if (confirmed) evidenceAt else Long.MIN_VALUE, completed)
    }
}
