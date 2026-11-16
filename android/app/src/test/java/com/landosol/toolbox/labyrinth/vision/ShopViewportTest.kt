package com.landosol.toolbox.labyrinth.vision

import com.landosol.toolbox.clanbattle.recognition.PixelImage
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class ShopViewportTest {
    @Test fun movingAndDuplicateFramesCannotConsumeCardReadScopes() {
        val gate = ShopViewportReadGate()
        val first = LabyrinthShopViewport(1L, 152, 510)
        val moved = LabyrinthShopViewport(2L, 208, 567)
        org.junit.Assert.assertFalse(gate.observe(first, 1000))
        org.junit.Assert.assertFalse(gate.observe(first, 1000))
        org.junit.Assert.assertFalse(gate.observe(moved, 1100))
        org.junit.Assert.assertFalse(gate.observe(moved, 1200))
        org.junit.Assert.assertTrue(gate.observe(moved, 1300))
        gate.reset()
        org.junit.Assert.assertFalse(gate.observe(moved, 1500))
        org.junit.Assert.assertFalse(gate.observe(null, 1800))
    }

    @Test fun realScrollbarShowsBottomAndTopWithoutGuessingSwipeDistance() {
        val bottom = ShopViewport.recognize(ShopRecognitionScopedFixtures.image("android/app/src/test/resources/shop/reference_shop_scrolled_20261115.png"), true)
        assertTrue(abs(requireNotNull(bottom.scrollThumbTop) - 208) <= 3)
        assertTrue(abs(requireNotNull(bottom.scrollThumbBottom) - 567) <= 3)
        assertFalse(bottom.atTop)
        assertTrue(bottom.atBottom)
        assertTrue(bottom.complete)
        val top = ShopViewport.recognize(ShopRecognitionScopedFixtures.image("android/app/src/test/resources/shop/imprints-20260914.jpg"), false)
        assertTrue(top.atTop)
        assertFalse(top.atBottom)
        assertFalse(top.complete)
        assertNotEquals(bottom.signature, top.signature)
    }

    @Test fun missingOrConflictingThumbNeverMeansTopBottomOrComplete() {
        val pixels = IntArray(1280 * 720) { 0xffffffff.toInt() }
        val empty = ShopViewport.recognize(PixelImage(1280, 720, pixels), true)
        assertNull(empty.scrollThumbTop)
        assertFalse(empty.atTop)
        assertFalse(empty.atBottom)
        assertFalse(empty.complete)
        for (y in 152..250) for (x in 1214..1225) pixels[y * 1280 + x] = 0xff5990e6.toInt()
        for (y in 400..566) for (x in 1214..1225) pixels[y * 1280 + x] = 0xff5990e6.toInt()
        val ambiguous = ShopViewport.recognize(PixelImage(1280, 720, pixels), true)
        assertNull(ambiguous.scrollThumbTop)
        assertFalse(ambiguous.complete)
    }

    @Test fun balanceAndPriceUseFitMappingAndNeverIncludeClose() {
        assertEquals(EntryPixelRect(776, 626, 164, 46), ShopViewport.map(1280, 720, ShopViewport.balanceReference))
        val mapped = ShopViewport.map(1920, 1080, ShopViewport.balanceReference)!!
        assertEquals(1164, mapped.left)
        assertEquals(939, mapped.top)
        assertEquals(246, mapped.width)
        assertTrue(mapped.right < 1420)
        val wide = ShopViewport.map(1600, 720, ShopViewport.balanceReference)!!
        assertEquals(936, wide.left)
        assertEquals(626, wide.top)
        assertEquals(164, wide.width)
    }

    @Test fun exactCardFingerprintChangesOnOneDigitPixelButViewportDoesNotPretendToBeCardEvidence() {
        val pixels = IntArray(300 * 200) { 0xffd0d0d0.toInt() }
        val roi = EntryPixelRect(10, 10, 80, 40)
        val first = ShopViewport.fingerprint(PixelImage(300, 200, pixels), listOf(roi))
        pixels[17 * 300 + 37] = 0xff202020.toInt()
        val changed = ShopViewport.fingerprint(PixelImage(300, 200, pixels), listOf(roi))
        assertNotEquals(first, changed)
    }
}
