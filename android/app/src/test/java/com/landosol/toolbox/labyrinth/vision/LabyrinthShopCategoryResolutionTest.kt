package com.landosol.toolbox.labyrinth.vision

import com.landosol.toolbox.labyrinth.LabyrinthShopDecision
import com.landosol.toolbox.labyrinth.LabyrinthShopPolicy
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import com.landosol.toolbox.clanbattle.recognition.PixelImage
import kotlinx.serialization.json.*

class LabyrinthShopCategoryResolutionTest {
    @Test fun `logged screenshot classifies imprints before scoped price authorization and area budgeting`() {
        val root = generateSequence(File(".").canonicalFile, File::getParentFile)
            .first { File(it, "android/app/src/test/resources/shop/imprints-20260914.jpg").isFile }
        val resources = File(root, "android/app/src/main/assets/resource-packs/cn-bilibili")
        val templates = File(resources, "icons/relics").listFiles()!!.filter { it.extension == "png" }.map {
            LabyrinthRelicTemplate(it.nameWithoutExtension, it.nameWithoutExtension, "弱体", "1", "", readImage(it))
        }
        val frame = readImage(File(root, "android/app/src/test/resources/shop/imprints-20260914.jpg"))
        val raw = LabyrinthShopRecognizer(readImage(File(resources, "vision/shop/shop_buy_button.png")), templates)
            .recognize(frame)
        val log = Json.parseToJsonElement(File(root, "android/app/src/test/resources/shop/imprints-20260914.json").readText()).jsonObject
        val titles = log.getValue("items").jsonArray.map { it.jsonObject.getValue("titleText").jsonPrimitive.content }
        assertEquals(3, raw.items.size)
        assertTrue(raw.items.all { it.purchasable })
        val classified = raw.items.mapIndexed { i, item -> resolver.resolve(item, titles[i], i.toLong(), 0) }
        assertTrue(classified.all { it.kind == LabyrinthShopItemKind.ROLE_IMPRINT && it.relicMatch == null })
        assertEquals("增益型职能印记", classified[2].roleImprintLabel)
        // Legacy title classification alone is not a new-area price/identity receipt.
        assertTrue(classified.none { it.identitySafe })
        for (area in 1..2) assertTrue(legacyDecide(classified, area) is LabyrinthShopDecision.BuyRoleImprint)
        for (area in listOf(3, 5)) assertTrue(decide(classified, area, entryBalance = 4_890,
            currentBalance = 4_890, shelfScanComplete = false) is LabyrinthShopDecision.Wait)

        // This saved screenshot actually shows 900 on all three cards and a current balance of 4890.
        val confirmed = raw.items.mapIndexed { i, card ->
            val fingerprint = ShopViewport.fingerprint(frame,
                listOfNotNull(card.titleRect, card.descriptionRect, card.iconRect, card.priceRect) + card.buyButtonRect)
            scoped(card, "900", title = titles[i], fingerprint = fingerprint)
        }
        assertTrue(confirmed.all { it.identitySafe && it.imprintForm == LabyrinthShopImprintForm.RANDOM })
        assertTrue(confirmed.all { it.kind == LabyrinthShopItemKind.ROLE_IMPRINT && it.relicMatch == null })
        assertTrue(confirmed.all { it.priceCoins == 900 && it.evidenceEpoch == 7L && it.evidenceCapturedAtMillis == 501L })
        assertEquals("增益型职能印记", confirmed[2].roleImprintLabel)
        assertTrue(raw.viewport?.complete == true)
        assertTrue(decide(confirmed, area = 5, entryBalance = 4_890, currentBalance = 4_890,
            shelfScanComplete = false, visibleShelfComplete = raw.viewport?.complete == true) is LabyrinthShopDecision.BuyRoleImprint)
        // Area 3's zero-refill reserve cannot spend this balance: inspect the lower shelf, not infer no stock.
        assertTrue(decide(confirmed, area = 3, entryBalance = 4_890, currentBalance = 4_890,
            shelfScanComplete = false, visibleShelfComplete = raw.viewport?.complete == true) is LabyrinthShopDecision.Browse)
    }

    private fun readImage(file: File): PixelImage {
        val image = Class.forName("javax.imageio.ImageIO").getMethod("read", File::class.java).invoke(null, file)
        val width = image.javaClass.getMethod("getWidth").invoke(image) as Int
        val height = image.javaClass.getMethod("getHeight").invoke(image) as Int
        val pixels = IntArray(width * height)
        image.javaClass.getMethod("getRGB", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, IntArray::class.java,
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            .invoke(image, 0, 0, width, height, pixels, 0, width)
        return PixelImage(width, height, pixels)
    }
    private val resolver = LabyrinthShopCategoryResolution()
    private val rect = EntryPixelRect(0, 0, 100, 100)
    private fun item(id: String = "3", recognized: Boolean = false) = LabyrinthShopItemMatch(
        id, rect, rect, 0.99, 1.0, LabyrinthShopItemStatus.AVAILABLE,
        relicMatch = LabyrinthRelicMatch(slotId = id, relicId = if (recognized) "40005" else null,
            displayName = "候选遗物", attribute = "弱体", attributeBonus = "1", effect = null,
            confidence = 0.715, screenRect = rect, iconRect = rect, rivalMargin = 0.000338,
            rarity = if (recognized) 1 else 0),
    )

    // Simulated same-card OCR receipt. Safety is established by Scoped, never by copying identitySafe=true.
    private fun scoped(
        item: LabyrinthShopItemMatch,
        priceText: String,
        title: String? = item.titleText,
        fingerprint: Long = item.slotId.hashCode().toLong(),
    ): LabyrinthShopItemMatch {
        val evidence = ShopRecognitionScoped.Evidence(
            epoch = 7, capturedAtMillis = 501, fingerprint = fingerprint,
            readId = item.titleEvidenceId ?: 1L,
            sections = ShopRecognitionScoped.Sections(title.orEmpty(), "", priceText), iconRoleLabel = null,
        )
        return ShopRecognitionScoped.resolve(
            item, evidence, epoch = 7, afterMillis = 500, currentFingerprint = fingerprint,
            attempts = item.categoryAttempt, exhausted = item.categoryState == LabyrinthShopCategoryState.EXHAUSTED,
        )
    }

    private fun decide(
        items: List<LabyrinthShopItemMatch>,
        area: Int = 3,
        purchased: Int = 0,
        refreshAvailable: Boolean = true,
        entryBalance: Int? = 20_000,
        currentBalance: Int? = 20_000,
        shelfScanComplete: Boolean = true,
        visibleShelfComplete: Boolean = true,
        policy: LabyrinthShopPolicy = LabyrinthShopPolicy(),
    ) = policy.decide(
        area, items, emptyList(), refreshAvailable = refreshAvailable, relicPurchasesInCycle = purchased,
        entryBalance = entryBalance, currentBalance = currentBalance,
        shelfScanComplete = shelfScanComplete, visibleShelfComplete = visibleShelfComplete,
    )

    private fun legacyDecide(
        items: List<LabyrinthShopItemMatch>,
        area: Int,
        purchased: Int = 0,
        policy: LabyrinthShopPolicy = LabyrinthShopPolicy(),
    ): LabyrinthShopDecision {
        require(area in 1..2)
        return policy.decide(area, items, emptyList(), refreshAvailable = true, relicPurchasesInCycle = purchased)
    }

    @Test fun `title classification overrides even a recognized relic icon`() {
        val result = resolver.resolve(item(recognized = true), "增益型识能的随机印记", 22, 0)
        assertEquals(LabyrinthShopItemKind.ROLE_IMPRINT, result.kind)
        assertEquals("增益型职能印记", result.roleImprintLabel)
        assertNull(result.relicMatch)
    }

    @Test fun `relic needs checked title scoped price identity and confirmed new-area balances`() {
        val unread = resolver.resolve(item(recognized = true), null, null, 0)
        val checked = resolver.resolve(item(recognized = true), "候选遗物", 1, 0)
        assertEquals(LabyrinthShopCategoryState.READING, unread.categoryState)
        assertEquals(LabyrinthShopCategoryState.READY, checked.categoryState)
        assertEquals(LabyrinthShopItemKind.RELIC, checked.kind)
        assertFalse(checked.identitySafe)
        val confirmed = scoped(checked, "585")
        assertTrue(confirmed.identitySafe)
        assertEquals(585, confirmed.priceCoins)
        assertEquals(LabyrinthShopItemKind.RELIC, confirmed.kind)
        for (area in listOf(3, 5)) {
            assertTrue(decide(listOf(unread), area) is LabyrinthShopDecision.Wait)
            assertTrue(decide(listOf(checked), area) is LabyrinthShopDecision.Wait)
            assertTrue(decide(listOf(confirmed), area) is LabyrinthShopDecision.BuyRelic)
            assertTrue(decide(listOf(confirmed), area, entryBalance = null) is LabyrinthShopDecision.Wait)
            assertTrue(decide(listOf(confirmed), area, currentBalance = null) is LabyrinthShopDecision.Wait)
        }
        for (area in 1..2) {
            assertTrue(legacyDecide(listOf(unread), area) is LabyrinthShopDecision.Wait)
            assertTrue(legacyDecide(listOf(checked), area) is LabyrinthShopDecision.BuyRelic)
        }
    }

    @Test fun `variant advances on completed OCR, not on elapsed time`() {
        // A pending read (completed = false) never advances the variant, however long it waits,
        // as long as the engine is answering other slots: this is the bundle-134750 bug, where a
        // 3 s clock retired titles whose OCR was still queued and closed the shop after one buy.
        assertEquals(0, resolver.attempt("3", 100, 0))
        resolver.noteVariantOutcome("3", 100, 0, false, engineLastCompletedAt = 2_500, now = 3_000)
        assertEquals(0, resolver.attempt("3", 100, 3_000))
        // Third slot in a serial queue waits ~16 s for its turn on the reporting device; engine
        // progress in that window must keep it waiting rather than burn a crop variant.
        resolver.noteVariantOutcome("3", 100, 0, false, engineLastCompletedAt = 14_000, now = 16_000)
        assertEquals(0, resolver.attempt("3", 100, 16_000))
        // A completed-but-unresolved read retires the variant.
        resolver.noteVariantOutcome("3", 100, 0, true, engineLastCompletedAt = 16_500, now = 16_500)
        assertEquals(1, resolver.attempt("3", 100, 16_600))
        resolver.noteVariantOutcome("3", 100, 1, true, engineLastCompletedAt = 17_000, now = 17_000)
        assertEquals(2, resolver.attempt("3", 100, 17_100))
        resolver.noteVariantOutcome("3", 100, 2, true, engineLastCompletedAt = 17_500, now = 17_500)
        assertEquals(3, resolver.attempt("3", 100, 17_600))
        // A stale variant index cannot double-advance, and EXHAUSTED never advances further.
        resolver.noteVariantOutcome("3", 100, 1, true, engineLastCompletedAt = 17_700, now = 17_700)
        assertEquals(3, resolver.attempt("3", 100, 17_800))
        // A new fingerprint and clear() both reset the counter.
        assertEquals(0, resolver.attempt("3", 101, 18_000))
        resolver.clear()
        assertEquals(0, resolver.attempt("3", 101, 18_001))
    }

    @Test fun `only a silent OCR engine trips the backstop so the shop can never hang`() {
        assertEquals(0, resolver.attempt("s", 7, 0))
        // Engine still answering other slots: no advance, no matter how long this one waits.
        resolver.noteVariantOutcome("s", 7, 0, false, engineLastCompletedAt = 30_000, now = 45_000)
        assertEquals(0, resolver.attempt("s", 7, 45_000))
        // Engine silent since 30_000: one tick short of the backstop still waits.
        resolver.noteVariantOutcome("s", 7, 0, false, engineLastCompletedAt = 30_000, now = 49_999)
        assertEquals(0, resolver.attempt("s", 7, 49_999))
        resolver.noteVariantOutcome("s", 7, 0, false, engineLastCompletedAt = 30_000, now = 50_000)
        assertEquals(1, resolver.attempt("s", 7, 50_000))
    }

    @Test fun `exhausted identity never proves empty stock while another safe imprint remains buyable`() {
        val imprint = resolver.resolve(item("1"), "攻击型职能的随机印记", 1, 0)
        val safeImprint = scoped(imprint, "780")
        val reading = scoped(resolver.resolve(item(), "看不清", 2, 0), "780")
        val skipped = scoped(resolver.resolve(item(), "看不清", 4, 3), "780")
        assertTrue(safeImprint.identitySafe)
        assertEquals(LabyrinthShopImprintForm.RANDOM, safeImprint.imprintForm)
        assertEquals(LabyrinthShopCategoryState.READING, reading.categoryState)
        assertEquals(LabyrinthShopCategoryState.EXHAUSTED, skipped.categoryState)
        assertEquals(LabyrinthShopItemKind.UNKNOWN, skipped.kind)
        assertFalse(skipped.identitySafe)
        assertEquals(780, skipped.priceCoins) // missing identity, not missing amount/price
        for (area in listOf(3, 5)) {
            assertTrue(decide(listOf(safeImprint, reading), area) is LabyrinthShopDecision.BuyRoleImprint)
            assertTrue(decide(listOf(safeImprint, skipped), area) is LabyrinthShopDecision.BuyRoleImprint)
            assertTrue(decide(listOf(reading), area) is LabyrinthShopDecision.Wait)
            assertTrue(decide(listOf(skipped), area) is LabyrinthShopDecision.Wait)
        }
        for (area in 1..2) {
            assertTrue(legacyDecide(listOf(imprint, reading), area) is LabyrinthShopDecision.Wait)
            assertTrue(legacyDecide(listOf(imprint, skipped), area) is LabyrinthShopDecision.BuyRoleImprint)
            assertTrue(legacyDecide(listOf(skipped), area) is LabyrinthShopDecision.Close)
        }
    }

    /**
     * 2026-11-09: a shop title read as "职能印记" with no role name. That is a READY imprint, so
     * the policy used to end the visit; the recovery window below is what keeps the remaining crop
     * variants (and therefore the purchase) alive without letting a run park forever.
     */
    @Test fun `a nameless imprint keeps a bounded grace window for the remaining crops`() {
        val nameless = resolver.resolve(item(), "职能印记", 1, 0)
        assertEquals(LabyrinthShopItemKind.ROLE_IMPRINT, nameless.kind)
        assertNull(nameless.roleImprintLabel)
        assertTrue(resolver.needsRoleName(nameless))

        assertTrue(resolver.labelGraceOpen("3", 1_000))
        // The window starts on the first nameless read, so later frames do not extend it.
        assertTrue(resolver.labelGraceOpen("3", 12_999))
        assertFalse(resolver.labelGraceOpen("3", 13_000))
        // A named imprint never asks for a retry.
        assertFalse(resolver.needsRoleName(resolver.resolve(item(), "增益型职能的随机印记", 2, 0)))
        // A new shop visit restarts the window.
        resolver.clear()
        assertTrue(resolver.labelGraceOpen("3", 20_000))
    }

    @Test fun `committed relic count never bypasses reading or exhausted identity in new areas`() {
        val reading = scoped(resolver.resolve(item(), null, null, 0), "780")
        val exhausted = scoped(resolver.resolve(item(), null, null, 3), "780")
        assertEquals(LabyrinthShopCategoryState.READING, reading.categoryState)
        assertEquals(LabyrinthShopCategoryState.EXHAUSTED, exhausted.categoryState)
        assertFalse(reading.identitySafe)
        assertFalse(exhausted.identitySafe)
        assertEquals(780, reading.priceCoins)
        assertEquals(780, exhausted.priceCoins)
        val recovered = scoped(resolver.resolve(item(), "治疗型职能的随机印记", 4, 0), "780")
        assertTrue(recovered.identitySafe)
        for (area in listOf(3, 5)) {
            assertTrue(decide(listOf(reading), area, purchased = 3) is LabyrinthShopDecision.Wait)
            assertTrue(decide(listOf(exhausted), area, purchased = 3) is LabyrinthShopDecision.Wait)
            assertTrue(decide(listOf(recovered), area, purchased = 3) is LabyrinthShopDecision.BuyRoleImprint)
        }
        for (area in 1..2) {
            assertTrue(legacyDecide(listOf(reading), area, purchased = 3) is LabyrinthShopDecision.Wait)
            assertTrue(legacyDecide(listOf(exhausted), area, purchased = 3) is LabyrinthShopDecision.Close)
        }
    }

    @Test fun `three safe imprints buy before refresh without inferring relic depletion in new areas`() {
        val classified = listOf("攻击型职能的随机印记", "治疗型职能的随机印记", "增益型识能的随机印记")
            .mapIndexed { i, title -> resolver.resolve(item(i.toString()), title, i.toLong(), 0) }
        assertTrue(classified.all { it.categoryState == LabyrinthShopCategoryState.READY &&
            it.kind == LabyrinthShopItemKind.ROLE_IMPRINT && it.relicMatch == null })
        val prices = listOf("780", "900", "1170")
        val confirmed = classified.mapIndexed { i, card -> scoped(card, prices[i]) }
        assertTrue(confirmed.all { it.identitySafe && it.imprintForm == LabyrinthShopImprintForm.RANDOM })
        assertEquals(listOf(780, 900, 1170), confirmed.map { it.priceCoins })
        for (area in listOf(3, 5)) {
            assertTrue(decide(classified, area) is LabyrinthShopDecision.Wait)
            for (purchased in listOf(0, 3)) {
                val decision = decide(confirmed, area, purchased) as LabyrinthShopDecision.BuyRoleImprint
                assertEquals("0", decision.item.slotId)
                assertFalse(decision.opensRolePicker)
            }
        }
        for (area in 1..2) {
            assertTrue(legacyDecide(classified, area) is LabyrinthShopDecision.BuyRoleImprint)
            // The original stock-cycle recovery remains confined to the original 1/2 flow.
            assertTrue(legacyDecide(classified, area, policy = LabyrinthShopPolicy(finalArea = 1)) is LabyrinthShopDecision.Refresh)
        }
    }

    /**
     * 2026-09-22 shop: two 坦克型职能的随机印记 at 780 beside a 破防型职能的选择印记 at 1,820.
     * Both grant a character of the named class, but the choice variant costs more than twice as
     * much and opens a full-roster picker the run then has to answer, so the random one wins.
     */
    @Test fun `random before choice respects scoped prices new refresh clearance and legacy forms`() {
        val classifiedChoice = resolver.resolve(item("1"), "破防型职能的选择印记", 1, 0)
        val classifiedRandom = resolver.resolve(item("2"), "坦克型职能的随机印记", 2, 0)
        assertTrue(classifiedChoice.choiceRoleImprint)
        assertFalse(classifiedRandom.choiceRoleImprint)
        val choice = scoped(classifiedChoice, "1,820")
        val random = scoped(classifiedRandom, "780")
        assertTrue(choice.identitySafe && random.identitySafe)
        assertEquals(LabyrinthShopImprintForm.CHOICE, choice.imprintForm)
        assertEquals(LabyrinthShopImprintForm.RANDOM, random.imprintForm)
        assertEquals(1_820, choice.priceCoins)
        assertEquals(780, random.priceCoins)

        // Listed choice-first, the current random purchase still precedes both it and refresh.
        for (area in listOf(3, 5)) {
            val preferred = decide(listOf(choice, random), area) as LabyrinthShopDecision.BuyRoleImprint
            assertEquals("2", preferred.item.slotId)
            assertFalse(preferred.opensRolePicker)
        }
        assertTrue("area 3 never buys a role-specific choice imprint",
            decide(listOf(choice), area = 3) is LabyrinthShopDecision.Close)
        assertTrue("area 5 refreshes only after confirmed complete stock has no normal buy",
            decide(listOf(choice), area = 5) is LabyrinthShopDecision.Refresh)
        val clearance = decide(listOf(choice), area = 5, refreshAvailable = false) as LabyrinthShopDecision.BuyRoleImprint
        assertEquals("1", clearance.item.slotId)
        assertTrue(clearance.opensRolePicker)
        assertTrue("manual refresh disabling must not unlock choice clearance",
            decide(listOf(choice), area = 5, refreshAvailable = false,
                policy = LabyrinthShopPolicy(refreshEnabled = false)) is LabyrinthShopDecision.Close)

        // Original areas continue to accept the legacy category receipt without the new monetary fields.
        for (area in 1..2) {
            val fallback = legacyDecide(listOf(classifiedChoice), area) as LabyrinthShopDecision.BuyRoleImprint
            assertEquals("1", fallback.item.slotId)
            assertTrue(fallback.opensRolePicker)
        }
        val second = scoped(resolver.resolve(item("3"), "坦克型职能的随机印记", 3, 0), "780")
        val tie = decide(listOf(second, random), area = 5) as LabyrinthShopDecision.BuyRoleImprint
        assertEquals("2", tie.item.slotId)
    }
}
