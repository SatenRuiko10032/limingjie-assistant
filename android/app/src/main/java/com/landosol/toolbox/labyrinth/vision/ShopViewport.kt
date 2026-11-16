package com.landosol.toolbox.labyrinth.vision

import com.landosol.toolbox.clanbattle.recognition.PixelImage
import kotlin.math.roundToInt

internal val EntryPixelRect.right: Int get() = left + width
internal val EntryPixelRect.bottom: Int get() = top + height

/** Position is visual scrollbar evidence, never inferred from a swipe callback or drag distance. */
data class LabyrinthShopViewport(
    val signature: Long,
    val scrollThumbTop: Int? = null,
    val scrollThumbBottom: Int? = null,
    val atTop: Boolean = false,
    val atBottom: Boolean = false,
    /** All visible columns' controls are complete/judged. UNKNOWN is not empty stock. */
    val complete: Boolean = false,
)

/** Do not create/consume card OCR scopes while the shelf is still moving. */
internal class ShopViewportReadGate {
    private var key: Triple<Long, Int?, Int?>? = null
    private var lastCapture = Long.MIN_VALUE
    private var stableSince = Long.MIN_VALUE
    private var frames = 0

    fun reset() {
        key = null
        lastCapture = Long.MIN_VALUE
        stableSince = Long.MIN_VALUE
        frames = 0
    }

    fun observe(viewport: LabyrinthShopViewport?, capturedAtMillis: Long): Boolean {
        if (viewport == null) { reset(); return false }
        if (capturedAtMillis > lastCapture) {
            val next = Triple(viewport.signature, viewport.scrollThumbTop, viewport.scrollThumbBottom)
            if (next == key) frames++ else {
                key = next
                frames = 1
                stableSince = capturedAtMillis
            }
            lastCapture = capturedAtMillis
        }
        return frames >= 2 && capturedAtMillis - stableSince >= 150L
    }
}

object ShopViewport {
    private val reference = EntryReferenceSize(1280, 720)
    private val trackReference = EntryReferenceRect(1214, 152, 12, 415)
    private val contentReference = EntryReferenceRect(44, 134, 1190, 449)
    val balanceReference = EntryReferenceRect(776, 626, 164, 46)
    // Includes the COMPLETE 用xxx更新 label; parser requires both Chinese delimiters.
    val refreshPriceReference = EntryReferenceRect(382, 626, 154, 45)

    fun map(frameWidth: Int, frameHeight: Int, rect: EntryReferenceRect): EntryPixelRect? =
        ReferenceFitMapper.map(frameWidth, frameHeight, reference, rect)

    fun contentRect(frame: PixelImage): EntryPixelRect =
        requireNotNull(map(frame.width, frame.height, contentReference))

    fun recognize(frame: PixelImage, controlsComplete: Boolean): LabyrinthShopViewport {
        val track = map(frame.width, frame.height, trackReference)
        val content = contentRect(frame)
        val signature = fingerprint(frame, listOf(content), stride = maxOf(2, content.width / 180), quantize = true)
        if (track == null || track.width < 3 || track.height < 15) return LabyrinthShopViewport(signature)
        val rows = BooleanArray(track.height) { dy ->
            var blue = 0
            var count = 0
            // Ignore the track's antialiased outer border; require several blue interior pixels.
            for (x in track.left + track.width / 3 until track.left + track.width * 2 / 3 + 1) {
                val color = frame[x, track.top + dy]
                val r = color ushr 16 and 255
                val g = color ushr 8 and 255
                val b = color and 255
                if (b >= 130 && g >= 85 && b - r >= 35 && b - g >= 10) blue++
                count++
            }
            count > 0 && blue * 3 >= count * 2
        }
        // A one-pixel highlight seam does not split one thumb into two independent candidates.
        for (i in 1 until rows.lastIndex) if (!rows[i] && rows[i - 1] && rows[i + 1]) rows[i] = true
        val runs = mutableListOf<IntRange>()
        var start = -1
        for (i in rows.indices) {
            if (rows[i] && start < 0) start = i
            if (start >= 0 && (!rows[i] || i == rows.lastIndex)) {
                val end = if (rows[i]) i else i - 1
                if (end - start + 1 >= maxOf(15, track.height / 12)) runs += start..end
                start = -1
            }
        }
        // Competing sizeable blue runs are ambiguous; do not guess the larger one.
        if (runs.size != 1) return LabyrinthShopViewport(signature, complete = false)
        val thumb = runs.single()
        val top = track.top + thumb.first
        val bottom = track.top + thumb.last + 1
        val tolerance = maxOf(2, (4.0 * track.height / 415).roundToInt())
        return LabyrinthShopViewport(signature, top, bottom,
            top <= track.top + tolerance, bottom >= track.bottom - tolerance, controlsComplete)
    }

    fun clip(rect: EntryPixelRect, bounds: EntryPixelRect): EntryPixelRect? {
        val left = maxOf(rect.left, bounds.left)
        val top = maxOf(rect.top, bounds.top)
        val right = minOf(rect.right, bounds.right)
        val bottom = minOf(rect.bottom, bounds.bottom)
        return if (right > left && bottom > top) EntryPixelRect(left, top, right - left, bottom - top) else null
    }

    /** Exact full-card pixels for identity/price reuse; sparse, quantized pixels only for scrolling. */
    fun fingerprint(frame: PixelImage, rects: List<EntryPixelRect>, stride: Int = 1, quantize: Boolean = false): Long {
        var hash = 1125899906842597L
        for (rect in rects) {
            hash = hash * 31 + rect.left
            hash = hash * 31 + rect.top
            hash = hash * 31 + rect.width
            hash = hash * 31 + rect.height
            var y = rect.top
            while (y < rect.bottom) {
                var x = rect.left
                while (x < rect.right) {
                    val pixel = frame[x, y]
                    hash = hash * 31 + if (quantize) pixel and 0x00f0f0f0 else pixel
                    x += stride
                }
                y += stride
            }
        }
        return hash
    }
}
