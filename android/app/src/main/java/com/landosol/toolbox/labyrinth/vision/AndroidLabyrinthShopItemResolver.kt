package com.landosol.toolbox.labyrinth.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.landosol.toolbox.clanbattle.recognition.AndroidPixelImageAdapter
import kotlin.math.abs

/** Bounded, same-capture shop OCR. Owns one physical input until its callback releases it;
 * invalidating the logical epoch never launches another crop behind an abandoned request. */
class AndroidLabyrinthShopItemResolver(
    private val submitTextRead: (Bitmap, (String?) -> Unit) -> Unit = AndroidChineseRoleNameOcr::readAsync,
    context: Context? = null,
) {
    private data class CardState(val column: String, val buttonTop: Int, val startedAt: Long,
        var attempts: Int = 0, var completed: Int = 0, var lastCapturedAt: Long = Long.MIN_VALUE,
        var evidence: ShopRecognitionScoped.Evidence? = null)
    private val balance = ShopMoneyReadBudget()
    private val refresh = ShopMoneyReadBudget(totalTimeoutMillis = 45_000L, parse = ShopMoney::parseRefresh, startOnFirstRequest = true)
    private val cards = linkedMapOf<String, CardState>()
    private val iconMatcher = ShopImprintIconMatcher(loadIcons(context))
    private var epoch = Long.MIN_VALUE
    private var after = Long.MIN_VALUE
    private var generation = 0L
    private var sequence = 0L
    private var physicalPending = false
    private val viewportReadGate = ShopViewportReadGate()

    @Synchronized fun invalidateAfterAction(evidenceEpoch: Long, afterMillis: Long) {
        bind(evidenceEpoch, afterMillis, android.os.SystemClock.elapsedRealtime())
    }

    private fun bind(evidenceEpoch: Long, afterMillis: Long, now: Long) {
        if (epoch == evidenceEpoch && after == afterMillis) return
        epoch = evidenceEpoch
        after = afterMillis
        generation++
        cards.clear()
        viewportReadGate.reset()
        balance.bind(evidenceEpoch, afterMillis, now)
        refresh.bind(evidenceEpoch, afterMillis, now)
        // Do NOT clear physicalPending: the previous input is still owned by ML Kit.
    }

    @Synchronized fun resolve(bitmap: Bitmap, result: LabyrinthEntryFrameResult,
        capturedAtMillis: Long = Long.MIN_VALUE, shopEvidenceEpoch: Long = 0,
        shopEvidenceAfterMillis: Long = Long.MIN_VALUE): LabyrinthEntryFrameResult {
        val now = android.os.SystemClock.elapsedRealtime()
        bind(shopEvidenceEpoch, shopEvidenceAfterMillis, now)
        val shop = result.shopObservation ?: return result
        if (result.observation.state != LabyrinthEntryPageState.SHOP) {
            // Modal/fading/unknown frames do not restart the per-epoch currency read budgets.
            return result.copy(shopObservation = shop.copy(evidenceEpoch = epoch, capturedAtMillis = capturedAtMillis))
        }
        val fresh = capturedAtMillis > after
        val stableViewport = fresh && viewportReadGate.observe(shop.viewport, capturedAtMillis)
        val pixels = AndroidPixelImageAdapter.from(bitmap)
        if (fresh && !physicalPending) {
            val moneyTicket = balance.request(now, capturedAtMillis)
            if (moneyTicket != null) {
                val rect = ShopViewport.map(bitmap.width, bitmap.height, ShopViewport.balanceReference)
                startMoney(bitmap, rect, moneyTicket, balance, "shop-balance", generation)
            }
        }
        val items = shop.items.map { item ->
            val rects = listOf(item.screenRect) + listOfNotNull(item.titleRect, item.descriptionRect, item.iconRect, item.priceRect) + item.buyButtonRect
            val fingerprint = ShopViewport.fingerprint(pixels, rects)
            if (item.status == LabyrinthShopItemStatus.DISABLED) {
                // Complete disabled controls are stock evidence, never a purchase authorization.
                return@map item.copy(identitySafe = false, categoryState = LabyrinthShopCategoryState.READY,
                    evidenceEpoch = epoch, evidenceCapturedAtMillis = capturedAtMillis)
            }
            if (!stableViewport) return@map item.copy(identitySafe = false,
                categoryState = LabyrinthShopCategoryState.READING, evidenceEpoch = epoch,
                evidenceCapturedAtMillis = Long.MIN_VALUE)
            val column = item.recognitionScopeId.substringBefore(':')
            val near = cards.entries.firstOrNull { it.value.column == column &&
                abs(it.value.buttonTop - item.buyButtonRect.top) <= maxOf(3, item.buyButtonRect.height / 8) }
            val state = near?.value ?: if (cards.size < 9) CardState(column, item.buyButtonRect.top, now).also {
                cards[item.recognitionScopeId] = it
            } else null
            if (state == null) return@map item.copy(identitySafe = false, categoryState = LabyrinthShopCategoryState.EXHAUSTED,
                evidenceEpoch = epoch, evidenceCapturedAtMillis = Long.MIN_VALUE)
            val exhausted = (state.completed >= 3) || now - state.startedAt >= CARD_TOTAL_TIMEOUT_MILLIS
            val resolved = ShopRecognitionScoped.resolve(item, state.evidence, epoch, after, fingerprint, state.completed, exhausted)
            if (fresh && !physicalPending && !resolved.identitySafe && !exhausted && state.attempts < 3 &&
                capturedAtMillis > state.lastCapturedAt && item.status == LabyrinthShopItemStatus.AVAILABLE &&
                item.descriptionRect != null && item.priceRect != null) {
                val variant = state.attempts++
                state.lastCapturedAt = capturedAtMillis
                val ownerGeneration = generation
                val id = ++sequence
                // Icon, badge, price and full enabled button all belong to this exact source frame.
                val roleLabel = item.iconRect?.let { iconMatcher.match(pixels, it) }
                val input = try { cardInput(bitmap, item, variant) } catch (_: Exception) { null }
                if (input == null) {
                    state.completed++
                } else submitOwned(input, "shop-card-" + item.recognitionScopeId, capturedAtMillis) { text ->
                    if (ownerGeneration == generation) {
                        state.completed++
                        state.evidence = ShopRecognitionScoped.Evidence(epoch, capturedAtMillis, fingerprint, id,
                            ShopRecognitionScoped.sections(text), roleLabel)
                    }
                }
            }
            resolved
        }
        val money = balance.snapshot(now)
        // Do not spend two OCR turns on Refresh while a normal safe Buy is still being resolved.
        if (fresh && !physicalPending && (money.coins != null || money.exhausted) && items.all {
                it.status == LabyrinthShopItemStatus.DISABLED || it.identitySafe || it.categoryState == LabyrinthShopCategoryState.EXHAUSTED
            }) {
            val ticket = refresh.request(now, capturedAtMillis)
            if (ticket != null) startMoney(bitmap, ShopViewport.map(bitmap.width, bitmap.height, ShopViewport.refreshPriceReference),
                ticket, refresh, "shop-refresh-price", generation)
        }
        val refreshMoney = refresh.snapshot(now)
        val complete = shop.viewport?.complete == true && items.all { it.status == LabyrinthShopItemStatus.DISABLED || it.identitySafe }
        return result.copy(shopObservation = shop.copy(items = items,
            balanceCoins = if (fresh) money.coins else null, balanceReadingExhausted = money.exhausted,
            balanceCapturedAtMillis = money.capturedAtMillis, evidenceEpoch = epoch, capturedAtMillis = capturedAtMillis,
            refreshPriceCoins = if (fresh) refreshMoney.coins else null,
            refreshPriceReadingExhausted = refreshMoney.exhausted, refreshPriceCapturedAtMillis = refreshMoney.capturedAtMillis,
            viewport = shop.viewport?.copy(complete = complete)))
    }

    private fun startMoney(bitmap: Bitmap, rect: EntryPixelRect?, ticket: ShopMoneyReadBudget.Ticket,
        budget: ShopMoneyReadBudget, name: String, ownerGeneration: Long) {
        val input = try { rect?.let { crop(bitmap, it, if (ticket.id % 3L == 1L) 2 else 3) } } catch (_: Exception) { null }
        if (input == null) {
            budget.complete(ticket, null, android.os.SystemClock.elapsedRealtime())
            return
        }
        submitOwned(input, name, ticket.capturedAtMillis) { text ->
            if (ownerGeneration == generation) budget.complete(ticket, text, android.os.SystemClock.elapsedRealtime())
        }
    }

    private fun submitOwned(input: Bitmap, name: String, capturedAtMillis: Long, complete: (String?) -> Unit) {
        physicalPending = true
        val diagnostic = RelicRoiOcrTelemetry.requested(name, capturedAtMillis, android.os.SystemClock.elapsedRealtime())
        var released = false
        fun release(text: String?) = synchronized(this) {
            if (!released) {
                released = true
                physicalPending = false
                try { complete(text) } finally {
                    RelicRoiOcrTelemetry.completed(diagnostic, android.os.SystemClock.elapsedRealtime(), text?.length)
                    if (!input.isRecycled) input.recycle()
                }
            }
        }
        try { submitTextRead(input, ::release) } catch (_: Exception) { release(null) }
    }

    /** Vertically stacked, explicitly delimited crops let the pure parser refuse a missing marker.
     * In particular PRICE never comes from a description's "1人", attribute count, or Close. */
    private fun cardInput(bitmap: Bitmap, item: LabyrinthShopItemMatch, variant: Int): Bitmap {
        val scale = if (variant == 0) 2 else 3
        val regions = listOf("TITLE" to if (item.titleCropped) null else item.titleRect,
            "DETAIL" to item.descriptionRect, "PRICE" to item.priceRect)
        val width = maxOf(520, regions.maxOf { (it.second?.width ?: 0) * scale + 32 })
        val height = regions.sumOf { 72 + (it.second?.height ?: 0) * scale }
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        canvas.drawColor(Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 40f }
        var y = 0
        try {
            for ((tag, rect) in regions) {
                canvas.drawText(tag, 16f, (y + 44).toFloat(), paint)
                y += 56
                if (rect != null) {
                    val tile = crop(bitmap, rect, scale)
                    try { canvas.drawBitmap(tile, 16f, y.toFloat(), null) } finally { tile.recycle() }
                    y += rect.height * scale
                }
                y += 16
            }
            return output
        } catch (failure: Exception) {
            output.recycle()
            throw failure
        }
    }

    private fun crop(bitmap: Bitmap, rect: EntryPixelRect, scale: Int): Bitmap {
        val original = Bitmap.createBitmap(bitmap, rect.left, rect.top, rect.width, rect.height)
        if (scale == 1) return original
        return try { Bitmap.createScaledBitmap(original, original.width * scale, original.height * scale, false) }
            finally { original.recycle() }
    }

    private fun loadIcons(context: Context?): List<ShopImprintIconTemplate> {
        if (context == null) return emptyList()
        val names = linkedMapOf("增益型职能印记" to "random_gain.png", "干扰型职能印记" to "random_interference.png",
            "强化型职能印记" to "random_strengthening.png", "攻击型职能印记" to "random_attack.png", "治疗型职能印记" to "random_healing.png")
        return names.map { (label, name) ->
            val bitmap = context.assets.open("shop/" + name).use { requireNotNull(BitmapFactory.decodeStream(it)) }
            ShopImprintIconTemplate(label, AndroidPixelImageAdapter.from(bitmap)).also { bitmap.recycle() }
        }
    }

    private companion object { const val CARD_TOTAL_TIMEOUT_MILLIS = 90_000L }
}
