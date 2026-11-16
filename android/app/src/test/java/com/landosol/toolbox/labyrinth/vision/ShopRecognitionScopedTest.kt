package com.landosol.toolbox.labyrinth.vision

import com.landosol.toolbox.clanbattle.recognition.PixelImage
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import kotlin.math.abs

class ShopRecognitionScopedTest {
    private val rect = EntryPixelRect(10, 10, 100, 60)
    private fun item(cropped: Boolean = false) = LabyrinthShopItemMatch("column-1-card-1", rect, rect, 0.90,
        1.0, LabyrinthShopItemStatus.AVAILABLE, titleCropped = cropped)
    private fun evidence(title: String, description: String = "", price: String = "780", icon: String? = null,
        epoch: Long = 7, capture: Long = 201, fingerprint: Long = 99) = ShopRecognitionScoped.Evidence(epoch, capture,
        fingerprint, 10, ShopRecognitionScoped.Sections(title, description, price), icon)
    private fun resolve(item: LabyrinthShopItemMatch, e: ShopRecognitionScoped.Evidence?) =
        ShopRecognitionScoped.resolve(item, e, 7, 200, 99, 1, false)

    @Test fun legacyTitleGateSurvivesMissingPriceButNewBudgetNeverSpendsIt() {
        val relic = LabyrinthRelicMatch("legacy", "legacy", "旧遗物", "守备", "1", null,
            rarity = 1, confidence = 0.95, screenRect = rect, iconRect = rect, rivalMargin = 0.2)
        val checked = resolve(item().copy(relicMatch = relic), evidence("旧遗物", price = "?"))
        assertEquals(LabyrinthShopCategoryState.READY, checked.categoryState)
        assertNull(checked.priceCoins)
        assertFalse(checked.identitySafe)
        val policy = com.landosol.toolbox.labyrinth.LabyrinthShopPolicy()
        assertTrue(policy.decide(2, listOf(checked), emptyList(), refreshAvailable = false)
            is com.landosol.toolbox.labyrinth.LabyrinthShopDecision.BuyRelic)
        assertTrue(policy.decide(3, listOf(checked), emptyList(), refreshAvailable = false,
            entryBalance = 15001, currentBalance = 15001, shelfScanComplete = true, visibleShelfComplete = true)
            is com.landosol.toolbox.labyrinth.LabyrinthShopDecision.Wait)
        val choice = resolve(item(), evidence("治疗型职能选择", price = "?"))
        assertTrue(choice.choiceRoleImprint)
        assertEquals(LabyrinthShopImprintForm.UNKNOWN, choice.imprintForm)
        assertFalse(choice.identitySafe)
    }

    @Test fun positiveTitleCannotOverrideConflictingDescriptionOrRoleBadge() {
        assertFalse(resolve(item(), evidence("增益型职能的随机印记",
            "能够从强化型职能中随机选出1人成为同伴。", icon = "增益型职能印记")).identitySafe)
        assertFalse(resolve(item(), evidence("增益型职能的随机印记", icon = "强化型职能印记")).identitySafe)
        assertFalse(resolve(item(), evidence("治疗型职能的选择印记",
            "能够从治疗型职能中随机选出1人成为同伴。")).identitySafe)
        assertFalse(resolve(item(), evidence("增益型职能的随机印记",
            "能够从增益型职能中选择1人成为同伴。")).identitySafe)
    }

    @Test fun labelledSectionsNeverTakeDescriptionCountsAsPrice() {
        val read = ShopRecognitionScoped.sections("TITLE\n增益型职能的随机印记\nDETAIL\n能够从增益型职能中随机选出1人成为同伴。\nPRICE\n780")!!
        assertEquals(780, ShopMoney.parse(read.price))
        assertNull(ShopRecognitionScoped.sections("增益型职能的随机印记\n1\n780"))
        assertNull(ShopRecognitionScoped.sections("TITLE\nX\nPRICE\n780\nDETAIL\nY"))
        assertNull(ShopRecognitionScoped.sections("TITLE\nX\nDETAIL\nY\nPRICE\n780\nPRICE\n900"))
        assertNull(ShopMoney.parse("780\n900"))
    }

    @Test fun randomRoleChoiceIsChoiceAndAllChoiceKeepsItsLabel() {
        val choice = resolve(item(), evidence("随机职能的选择印记", price = "1,820"))
        assertTrue(choice.identitySafe)
        assertTrue(choice.choiceRoleImprint)
        assertEquals(LabyrinthShopImprintForm.CHOICE, choice.imprintForm)
        assertEquals("随机职能印记", choice.roleImprintLabel)
        assertTrue(LabyrinthShopItemText.isChoiceRoleImprint("随机职能的选择印记"))
        val all = resolve(item(), evidence("全体选择印记", price = "2,340"))
        assertTrue(all.identitySafe)
        assertEquals("全体选择印记", all.roleImprintLabel)
        assertEquals(LabyrinthShopImprintForm.CHOICE, all.imprintForm)
    }

    @Test fun croppedRandomNeedsDescriptionIconPriceCompleteEnabledButtonTogether() {
        val description = "能够从增益型职能中随机选出1人成为同伴。"
        val safe = resolve(item(true), evidence("", description, "780", "增益型职能印记"))
        assertTrue(safe.identitySafe)
        assertEquals(LabyrinthShopImprintForm.RANDOM, safe.imprintForm)
        assertEquals("增益型职能印记", safe.roleImprintLabel)
        assertFalse(resolve(item(true), evidence("", description)).identitySafe)
        assertFalse(resolve(item(true), evidence("", description, "780", "强化型职能印记")).identitySafe)
        assertFalse(resolve(item(true), evidence("", description, "7?0", "增益型职能印记")).identitySafe)
        assertFalse(resolve(item(true).copy(buyButtonScore = 0.54), evidence("", description, icon = "增益型职能印记")).identitySafe)
        assertFalse(resolve(item(true).copy(buyButtonEnabledEvidence = 0.49), evidence("", description, icon = "增益型职能印记")).identitySafe)
        assertFalse(resolve(item(true).copy(status = LabyrinthShopItemStatus.DISABLED), evidence("", description, icon = "增益型职能印记")).identitySafe)
        assertFalse(resolve(item(true), evidence("", "能够从增益型职能中选择1人成为同伴。", icon = "增益型职能印记")).identitySafe)
        // Real older shop price is 900, so the safe path uses the measured price, not a hard 780.
        assertEquals(900, resolve(item(true), evidence("", description, "900", "增益型职能印记")).priceCoins)
    }

    @Test fun oldTitleCannotBeStitchedToNewFrameOrEpochAndCallbackTimeIsNotCapture() {
        assertFalse(resolve(item(), evidence("增益型职能的随机印记", epoch = 6)).identitySafe)
        assertFalse(resolve(item(), evidence("增益型职能的随机印记", fingerprint = 100)).identitySafe)
        assertFalse(resolve(item(), evidence("增益型职能的随机印记", capture = 200)).identitySafe)
        val fresh = resolve(item(), evidence("增益型职能的随机印记", capture = 201))
        assertTrue(fresh.identitySafe)
        assertEquals(201L, fresh.evidenceCapturedAtMillis)
        assertEquals(7L, fresh.evidenceEpoch)
    }

    @Test fun relicStillNeedsCompleteMatchingTitleAndRGBIdentity() {
        val relic = LabyrinthRelicMatch("card", "10001", "强化宝石", "强化", "1", null,
            confidence = 0.90, screenRect = rect, iconRect = rect)
        val good = item().copy(relicMatch = relic)
        assertTrue(resolve(good, evidence("强化宝石", price = "1,620")).identitySafe)
        // Same-frame noisy OCR keeps the original semantic title-check + trusted RGB path.
        assertTrue(resolve(good, evidence("强化宝右石", price = "1,620")).identitySafe)
        assertFalse(resolve(good, evidence("", price = "1,620")).identitySafe)
        assertFalse(resolve(good, evidence("增益型职能的随机印记", price = "1,620")).kind == LabyrinthShopItemKind.RELIC)
        assertFalse(resolve(good.copy(titleCropped = true), evidence("强化宝石", price = "1,620")).identitySafe)
        assertFalse(resolve(item(), evidence("强化宝石", price = "1,620")).identitySafe)
    }

    @Test fun realBottomShelfFindsAllActualControlsAndRandomRoleIcons() {
        val fixture = ShopRecognitionScopedFixtures.image("android/app/src/test/resources/shop/reference_shop_scrolled_20261115.png")
        val button = ShopRecognitionScopedFixtures.image("android/app/src/main/assets/resource-packs/cn-bilibili/vision/shop/shop_buy_button.png")
        val raw = LabyrinthShopRecognizer(button).recognize(fixture)
        assertEquals(6, raw.items.size)
        assertEquals(6, raw.items.map { it.slotId }.distinct().size)
        assertEquals(3, raw.items.count { it.status == LabyrinthShopItemStatus.AVAILABLE })
        assertEquals(3, raw.items.count { it.status == LabyrinthShopItemStatus.DISABLED })
        assertTrue(raw.items.none { it.status == LabyrinthShopItemStatus.UNKNOWN })
        assertTrue(raw.viewport!!.complete)
        val log = Json.parseToJsonElement(File(ShopRecognitionScopedFixtures.root,
            "android/app/src/test/resources/shop/reference_shop_scrolled_20261115.json").readText()).jsonObject
        assertEquals(6685, ShopMoney.parse(log.getValue("balanceText").jsonPrimitive.content))
        assertEquals(300, ShopMoney.parseRefresh(log.getValue("refreshText").jsonPrimitive.content))
        val available = raw.items.filter { it.purchasable }.sortedBy { it.buyButtonRect.left }
        val matcher = ShopImprintIconMatcher(ShopRecognitionScopedFixtures.imprintIcons())
        for ((index, card) in available.withIndex()) {
            val expected = log.getValue("items").jsonArray[index].jsonObject
            assertTrue(card.titleCropped)
            assertTrue(abs(card.buyButtonRect.top + card.buyButtonRect.height / 2 - 280) <= 8)
            // Each real column has its own absolute glyph bounds. Reusing column 1's x-range
            // for columns 2/3 would incorrectly fail otherwise correct same-card dynamic ROIs.
            val glyph = expected.getValue("priceGlyphBounds").jsonArray.map { it.jsonPrimitive.int }
            val price = requireNotNull(card.priceRect)
            val column = expected.getValue("column").jsonPrimitive.int
            assertTrue("column " + column + " dynamic price ROI " + price + " must contain " + glyph,
                price.left <= glyph[0] && price.top <= glyph[1] && price.right >= glyph[2] && price.bottom >= glyph[3])
            val label = matcher.match(fixture, card.iconRect!!)
            assertEquals("column " + column + " dynamic icon ROI " + card.iconRect,
                expected.getValue("roleLabel").jsonPrimitive.content, label)
            val fingerprint = ShopViewport.fingerprint(fixture, listOfNotNull(card.titleRect, card.descriptionRect, card.iconRect, card.priceRect) + card.buyButtonRect)
            val proof = ShopRecognitionScoped.Evidence(7, 501, fingerprint, index.toLong(),
                ShopRecognitionScoped.Sections("", expected.getValue("descriptionText").jsonPrimitive.content,
                    expected.getValue("priceText").jsonPrimitive.content), label)
            val resolved = ShopRecognitionScoped.resolve(card, proof, 7, 500, fingerprint, 1, false)
            assertTrue(resolved.identitySafe)
            assertEquals(780, resolved.priceCoins)
            assertEquals(LabyrinthShopImprintForm.RANDOM, resolved.imprintForm)
        }
        for (card in raw.items.filter { it.status == LabyrinthShopItemStatus.DISABLED }) {
            assertTrue(abs(card.buyButtonRect.top + card.buyButtonRect.height / 2 - 535) <= 8)
        }
        assertTrue(log.getValue("items").jsonArray.drop(3).all { ShopMoney.parse(it.jsonObject.getValue("priceText").jsonPrimitive.content) == 1170 })
    }

    @Test fun fullyChecked900FirstRowCanBrowseWhenRemainingBudgetCannotBuyWithoutClaimingLowerStockEmpty() {
        val frame = ShopRecognitionScopedFixtures.image("android/app/src/test/resources/shop/imprints-20260914.jpg")
        val button = ShopRecognitionScopedFixtures.image("android/app/src/main/assets/resource-packs/cn-bilibili/vision/shop/shop_buy_button.png")
        val raw = LabyrinthShopRecognizer(button).recognize(frame)
        val log = Json.parseToJsonElement(File(ShopRecognitionScopedFixtures.root,
            "android/app/src/test/resources/shop/imprints-20260914.json").readText()).jsonObject
        val known = raw.items.mapIndexed { index, card ->
            val fingerprint = ShopViewport.fingerprint(frame, listOfNotNull(card.titleRect, card.descriptionRect, card.iconRect, card.priceRect) + card.buyButtonRect)
            val title = log.getValue("items").jsonArray[index].jsonObject.getValue("titleText").jsonPrimitive.content
            val proof = ShopRecognitionScoped.Evidence(7, 501, fingerprint, index.toLong(),
                ShopRecognitionScoped.Sections(title, "", "900"), null)
            ShopRecognitionScoped.resolve(card, proof, 7, 500, fingerprint, 1, false)
        }
        assertEquals(3, known.size)
        assertTrue(known.all { it.identitySafe && it.categoryState == LabyrinthShopCategoryState.READY })
        assertTrue(known.all { requireNotNull(it.priceCoins) > 800 }) // fixed remaining visit budget
        // This is complete FIRST-row evidence for the parent's Browse decision, not empty lower stock.
        assertTrue(raw.viewport!!.complete && known.all { it.identitySafe })
        assertTrue(raw.viewport!!.atTop)
        assertFalse(raw.viewport!!.atBottom)
        assertTrue(known.all { it.buyButtonRect.top < 600 })
    }

    @Test fun missingAnyFirstRowCompleteControlBlocksCompleteCoverage() {
        val original = ShopRecognitionScopedFixtures.image("android/app/src/test/resources/shop/imprints-20260914.jpg")
        val pixels = original.pixels.copyOf()
        // Remove one entire real first-row control; the partly visible lower card has no control.
        for (y in 480 until 570) for (x in 350 until 640) pixels[y * original.width + x] = 0xffffffff.toInt()
        val frame = PixelImage(original.width, original.height, pixels)
        val button = ShopRecognitionScopedFixtures.image("android/app/src/main/assets/resource-packs/cn-bilibili/vision/shop/shop_buy_button.png")
        val raw = LabyrinthShopRecognizer(button).recognize(frame)
        assertFalse(raw.viewport!!.complete)
        assertTrue(raw.items.any { it.status == LabyrinthShopItemStatus.UNKNOWN })
    }

    @Test fun old900AndLowerShelfWithoutPriceOrButtonStaySupportedWithoutBlindBuying() {
        val button = ShopRecognitionScopedFixtures.image("android/app/src/main/assets/resource-packs/cn-bilibili/vision/shop/shop_buy_button.png")
        val old = LabyrinthShopRecognizer(button).recognize(ShopRecognitionScopedFixtures.image("android/app/src/test/resources/shop/imprints-20260914.jpg"))
        assertEquals(3, old.items.size)
        assertTrue(old.items.all { it.purchasable && !it.titleCropped })
        assertTrue(old.viewport!!.complete)
        val live = LabyrinthShopRecognizer(button).recognize(ShopRecognitionScopedFixtures.image("android/app/src/test/resources/shop/live_shop_20260909.jpg"))
        assertEquals(3, live.items.size)
        assertTrue(live.items.all { it.buyButtonRect.top < 600 })
        assertTrue(live.viewport!!.complete)
    }
}

internal object ShopRecognitionScopedFixtures {
    val root: File by lazy { generateSequence(File(".").canonicalFile, File::getParentFile)
        .first { File(it, "android/app/src/test/resources/shop/reference_shop_scrolled_20261115.png").isFile } }
    fun image(path: String): PixelImage {
        val image = Class.forName("javax.imageio.ImageIO").getMethod("read", File::class.java).invoke(null, File(root, path))
        val width = image.javaClass.getMethod("getWidth").invoke(image) as Int
        val height = image.javaClass.getMethod("getHeight").invoke(image) as Int
        val pixels = IntArray(width * height)
        image.javaClass.getMethod("getRGB", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, IntArray::class.java,
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType).invoke(image, 0, 0, width, height, pixels, 0, width)
        return PixelImage(width, height, pixels)
    }
    fun imprintIcons() = listOf("增益型职能印记" to "random_gain.png", "干扰型职能印记" to "random_interference.png",
        "强化型职能印记" to "random_strengthening.png", "攻击型职能印记" to "random_attack.png", "治疗型职能印记" to "random_healing.png")
        .map { (label, name) -> ShopImprintIconTemplate(label, image("android/app/src/main/assets/shop/" + name)) }
}
