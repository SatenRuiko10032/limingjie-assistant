package com.landosol.toolbox.labyrinth

import com.landosol.toolbox.labyrinth.vision.EntryPixelRect
import com.landosol.toolbox.labyrinth.vision.LabyrinthRelicMatch
import com.landosol.toolbox.labyrinth.vision.LabyrinthShopCategoryState
import com.landosol.toolbox.labyrinth.vision.LabyrinthShopImprintForm
import com.landosol.toolbox.labyrinth.vision.LabyrinthShopItemKind
import com.landosol.toolbox.labyrinth.vision.LabyrinthShopItemMatch
import com.landosol.toolbox.labyrinth.vision.LabyrinthShopItemStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** One parameterized policy suite; no Android, device, clock or gesture dependencies. */
@RunWith(Parameterized::class)
class LabyrinthShopBudgetPolicyTest(private val scenario: Scenario) {
    data class Scenario(
        val name: String,
        val expected: String,
        val area: Int? = 3,
        val items: List<LabyrinthShopItemMatch> = emptyList(),
        val entryBalance: Int? = 16_000,
        val currentBalance: Int? = 16_000,
        val shelfScanComplete: Boolean = true,
        val visibleShelfComplete: Boolean = true,
        val refreshAvailable: Boolean = false,
        val refreshKnown: Boolean = true,
        val successfulRefreshes: Int = 0,
        val relicPurchases: Int = 0,
        val acquired: List<LabyrinthObservedRelic> = emptyList(),
        val calibratedStacks: Map<LabyrinthRelicMark, Int> = emptyMap(),
        val lockedFocus: LabyrinthRelicMark? = null,
        val policy: LabyrinthShopPolicy = LabyrinthShopPolicy(),
        val expectedPicker: Boolean? = null,
        val expectedFocus: LabyrinthRelicMark? = null,
        val expectedPhase: LabyrinthRelicChoicePhase? = null,
        val legacyCall: Boolean = false,
    ) {
        override fun toString(): String = name
    }

    @Test
    fun budgetOrderSafetyBrowsingAndLegacyRules() {
        val decision = if (scenario.legacyCall) {
            // Deliberately omit all four new arguments: old calls must compile, not authorize new-area buys.
            scenario.policy.decide(
                currentArea = scenario.area,
                items = scenario.items,
                acquired = scenario.acquired,
                calibratedStacks = scenario.calibratedStacks,
                refreshAvailable = scenario.refreshAvailable,
                lockedFocus = scenario.lockedFocus,
                relicPurchasesInCycle = scenario.relicPurchases,
                refreshAvailabilityKnown = scenario.refreshKnown,
                refreshesUsedInVisit = scenario.successfulRefreshes,
            )
        } else {
            scenario.policy.decide(
                currentArea = scenario.area,
                items = scenario.items,
                acquired = scenario.acquired,
                calibratedStacks = scenario.calibratedStacks,
                refreshAvailable = scenario.refreshAvailable,
                lockedFocus = scenario.lockedFocus,
                relicPurchasesInCycle = scenario.relicPurchases,
                refreshAvailabilityKnown = scenario.refreshKnown,
                refreshesUsedInVisit = scenario.successfulRefreshes,
                entryBalance = scenario.entryBalance,
                currentBalance = scenario.currentBalance,
                shelfScanComplete = scenario.shelfScanComplete,
                visibleShelfComplete = scenario.visibleShelfComplete,
            )
        }
        val actual = when (decision) {
            is LabyrinthShopDecision.BuyRelic -> "relic:${decision.item.slotId}"
            is LabyrinthShopDecision.BuyRoleImprint -> "imprint:${decision.item.slotId}"
            is LabyrinthShopDecision.Browse -> "browse"
            is LabyrinthShopDecision.Refresh -> "refresh"
            is LabyrinthShopDecision.Close -> "close"
            is LabyrinthShopDecision.Wait -> "wait"
        }
        assertEquals(scenario.name, scenario.expected, actual)
        scenario.expectedPicker?.let {
            assertTrue(scenario.name, decision is LabyrinthShopDecision.BuyRoleImprint)
            assertEquals(it, (decision as LabyrinthShopDecision.BuyRoleImprint).opensRolePicker)
        }
        scenario.expectedFocus?.let {
            assertTrue(scenario.name, decision is LabyrinthShopDecision.BuyRelic)
            assertEquals(it, (decision as LabyrinthShopDecision.BuyRelic).relicDecision.focusMark)
        }
        scenario.expectedPhase?.let {
            assertTrue(scenario.name, decision is LabyrinthShopDecision.BuyRelic)
            assertEquals(it, (decision as LabyrinthShopDecision.BuyRelic).relicDecision.phase)
        }
        if (scenario.area != null && scenario.area in 3..5) {
            val bought = when (decision) {
                is LabyrinthShopDecision.BuyRelic -> decision.item
                is LabyrinthShopDecision.BuyRoleImprint -> decision.item
                else -> null
            }
            bought?.let {
                assertTrue(scenario.name, it.identitySafe && it.purchasable)
                assertTrue(scenario.name, it.priceCoins != null && requireNotNull(it.priceCoins) > 0)
                assertTrue(scenario.name, scenario.entryBalance != null && scenario.currentBalance != null)
                assertTrue(scenario.name, requireNotNull(it.priceCoins) <= requireNotNull(scenario.currentBalance))
            }
            if (decision is LabyrinthShopDecision.Refresh) {
                assertFalse("A refill reason must not claim an unobserved live 300-coin fee", decision.reason.contains("300"))
            }
        }
    }

    companion object {
        private val rect = EntryPixelRect(0, 0, 10, 10)

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun scenarios(): Collection<Array<Any>> = buildList<Scenario> {
            // Fixed entry-balance gate, not the shrinking current balance.
            add(Scenario("area3 entry14000 refuses star2", "close", items = listOf(relic("two", 2)),
                entryBalance = 14_000, currentBalance = 20_000))
            add(Scenario("area3 entry14001 permits star2", "relic:two", items = listOf(relic("two", 2)),
                entryBalance = 14_001, currentBalance = 1_820))
            add(Scenario("area3 fixed entry20000 still permits star2 at current14000", "relic:two",
                items = listOf(relic("two", 2)), entryBalance = 20_000, currentBalance = 14_000))
            add(Scenario("area3 entry13999 refuses star2 after balance changes", "close",
                items = listOf(relic("two", 2)), entryBalance = 13_999, currentBalance = 20_000))
            add(Scenario("area5 has no area3 star2 entry threshold", "relic:two", area = 5,
                items = listOf(relic("two", 2)), entryBalance = 10_000, currentBalance = 1_820))

            // Exact role-only reserves: B-P-300 >= 7620; B-P-600 >= 3510; after two, affordability only.
            add(Scenario("area3 zero refills budget equality", "imprint:random", items = listOf(randomImprint()),
                currentBalance = 8_700, expectedPicker = false))
            add(Scenario("area3 zero refills one coin below reserve", "close", items = listOf(randomImprint()),
                currentBalance = 8_699))
            add(Scenario("area3 one refill budget equality", "imprint:random", items = listOf(randomImprint()),
                currentBalance = 4_890, successfulRefreshes = 1))
            add(Scenario("area3 one refill one coin below reserve", "close", items = listOf(randomImprint()),
                currentBalance = 4_889, successfulRefreshes = 1))
            add(Scenario("area3 two refills no reserve at exact price", "imprint:random", items = listOf(randomImprint()),
                currentBalance = 780, successfulRefreshes = 2))
            add(Scenario("area3 two refills still requires affordability", "close", items = listOf(randomImprint()),
                currentBalance = 779, successfulRefreshes = 2))
            add(Scenario("area3 negative refill count cannot bypass first reserve", "close", items = listOf(randomImprint()),
                currentBalance = 780, successfulRefreshes = -1))
            add(Scenario("area3 reserves do not block star1", "relic:one", items = listOf(relic()), currentBalance = 585))
            add(Scenario("area3 reserves do not block allowed star2", "relic:two", items = listOf(relic("two", 2)),
                currentBalance = 1_820))
            add(Scenario("area3 actual cheaper imprint price at reserve equality", "imprint:random",
                items = listOf(randomImprint(price = 390)), currentBalance = 8_310))
            add(Scenario("area3 actual cheaper imprint price one coin below reserve", "close",
                items = listOf(randomImprint(price = 390)), currentBalance = 8_309))
            add(Scenario("area3 actual dearer imprint not assumed to cost780", "close",
                items = listOf(randomImprint(price = 1_800)), currentBalance = 8_700))
            add(Scenario("area3 reserve arithmetic does not overflow", "close",
                items = listOf(randomImprint(price = Int.MAX_VALUE)), currentBalance = Int.MAX_VALUE))
            add(Scenario("area3 after two refills actual maximal price is affordable", "imprint:random",
                items = listOf(randomImprint(price = Int.MAX_VALUE)), currentBalance = Int.MAX_VALUE, successfulRefreshes = 2))

            // Current buys outrank both hidden lower shelves and historical purchase counters.
            for (area in listOf(3, 5)) {
                add(Scenario("area$area normal star1 before random and star2", "relic:one", area = area,
                    items = listOf(relic("two", 2), randomImprint(), relic()), refreshAvailable = true,
                    shelfScanComplete = false, visibleShelfComplete = false, relicPurchases = 3))
                add(Scenario("area$area normal random before eligible star2", "imprint:random", area = area,
                    items = listOf(relic("two", 2), randomImprint()), refreshAvailable = true, relicPurchases = 3))
                add(Scenario("area$area unknown-price star1 still buys safe random", "imprint:random", area = area,
                    items = listOf(relic(price = null), randomImprint()), shelfScanComplete = false, visibleShelfComplete = false))
                add(Scenario("area$area unknown-price random still buys safe star2", "relic:two", area = area,
                    items = listOf(randomImprint(price = null), relic("two", 2)), shelfScanComplete = false, visibleShelfComplete = false))
                add(Scenario("area$area budget or price refusal still permits safe star2", "relic:two", area = area,
                    items = listOf(randomImprint(price = 20_000), relic("two", 2)), refreshAvailable = true,
                    shelfScanComplete = false))
                add(Scenario("area$area three visible random imprints do not trigger early refill", "imprint:random-a", area = area,
                    items = listOf(randomImprint("random-c"), randomImprint("random-a"), randomImprint("random-b")),
                    refreshAvailable = true, relicPurchases = 3, policy = LabyrinthShopPolicy(finalArea = 3)))
                add(Scenario("area$area owned star1 is filtered before random", "imprint:random", area = area,
                    items = listOf(relic(), randomImprint()), acquired = listOf(owned("one"))))
                add(Scenario("area$area only owned relic can browse", "browse", area = area,
                    items = listOf(relic()), acquired = listOf(owned("one")), shelfScanComplete = false))
                add(Scenario("area$area relic picker metadata failure does not discard random", "imprint:random", area = area,
                    items = listOf(relic(attribute = null), randomImprint()), refreshAvailable = true))
                add(Scenario("area$area relic picker metadata failure alone is not empty stock", "wait", area = area,
                    items = listOf(relic(attribute = null)), refreshAvailable = true, shelfScanComplete = false))
                add(Scenario("area$area one unaffordable relic does not hide an affordable same-rarity relic", "relic:cheap", area = area,
                    items = listOf(relic("dear", price = 20_000), relic("cheap", price = 585))))
            }
            add(Scenario("area3 random reserve failure still buys allowed star2", "relic:two",
                items = listOf(randomImprint(), relic("two", 2)), currentBalance = 8_699, refreshAvailable = true))
            add(Scenario("area3 configured original star2 skip is respected", "close", items = listOf(relic("two", 2)),
                policy = LabyrinthShopPolicy(skipRelicRaritiesByArea = mapOf(3 to setOf(2, 3, 4)))))

            // Area 4 fixes role mode from entry, then checks B >= 8000 before (not after) a purchase.
            add(Scenario("area4 entry10000 does not open role mode", "close", area = 4,
                items = listOf(randomImprint()), entryBalance = 10_000, currentBalance = 20_000, refreshAvailable = true))
            add(Scenario("area4 saving mode does not need another current read", "close", area = 4,
                entryBalance = 10_000, currentBalance = null))
            add(Scenario("area4 entry10001 current8000 may spend last purchase below8000", "imprint:random", area = 4,
                items = listOf(randomImprint()), entryBalance = 10_001, currentBalance = 8_000))
            add(Scenario("area4 role mode survives current9999", "imprint:random", area = 4,
                items = listOf(randomImprint()), entryBalance = 20_000, currentBalance = 9_999))
            add(Scenario("area4 role mode survives current8001", "imprint:random", area = 4,
                items = listOf(randomImprint()), entryBalance = 20_000, currentBalance = 8_001))
            add(Scenario("area4 current7999 stops role purchases", "close", area = 4,
                items = listOf(randomImprint()), entryBalance = 20_000, currentBalance = 7_999, refreshAvailable = true))
            add(Scenario("area4 actual price may consume all8000", "imprint:choice", area = 4,
                items = listOf(choice(price = 8_000)), currentBalance = 8_000, expectedPicker = true))
            add(Scenario("area4 enabled button alone cannot authorize price8001 at balance8000", "close", area = 4,
                items = listOf(choice(price = 8_001)), currentBalance = 8_000))
            add(Scenario("area4 ignores relic priority and buys role", "imprint:random", area = 4,
                items = listOf(relic(), randomImprint()), refreshAvailable = true))
            add(Scenario("area4 allows all-roster choice", "imprint:all", area = 4,
                items = listOf(allChoice()), expectedPicker = true))
            add(Scenario("area4 random retains priority over choices", "imprint:random", area = 4,
                items = listOf(allChoice(), choice(), randomImprint())))
            add(Scenario("area4 never refreshes even with lowered start area", "close", area = 4,
                items = listOf(relic()), refreshAvailable = true, policy = LabyrinthShopPolicy(finalArea = 3)))
            add(Scenario("area4 must browse role-only lower shelf before exit", "browse", area = 4,
                items = listOf(relic()), shelfScanComplete = false, refreshAvailable = true))

            // Area 3's original all-roster exception remains a budgeted fallback, not a clearance license.
            add(Scenario("area3 all-roster fallback after two committed refills", "imprint:all",
                items = listOf(allChoice()), currentBalance = 2_600, successfulRefreshes = 2,
                refreshAvailable = true, policy = LabyrinthShopPolicy(finalArea = 3), expectedPicker = true))
            add(Scenario("area3 all-roster fallback after confirmed unavailable refill at first reserve equality", "imprint:all",
                items = listOf(allChoice()), currentBalance = 10_520, policy = LabyrinthShopPolicy(finalArea = 3)))
            add(Scenario("area3 all-roster fallback still obeys first reserve", "close", items = listOf(allChoice()),
                currentBalance = 10_519, policy = LabyrinthShopPolicy(finalArea = 3)))
            add(Scenario("area3 all-roster fallback obeys one-refill reserve", "imprint:all", items = listOf(allChoice()),
                currentBalance = 6_710, successfulRefreshes = 1, policy = LabyrinthShopPolicy(finalArea = 3)))
            add(Scenario("area3 all-roster fallback never bypasses random or star2", "imprint:random",
                items = listOf(allChoice(), relic("two", 2), randomImprint()), successfulRefreshes = 2))
            add(Scenario("area3 all-roster fallback after allowed star2", "relic:two",
                items = listOf(allChoice(), relic("two", 2)), successfulRefreshes = 2))
            add(Scenario("area3 all-roster does not replace a permitted refill", "refresh", items = listOf(allChoice()),
                refreshAvailable = true, policy = LabyrinthShopPolicy(finalArea = 3)))
            add(Scenario("area3 refreshable all-roster-only visible row must browse before refill", "browse",
                items = listOf(allChoice()), refreshAvailable = true, shelfScanComplete = false,
                policy = LabyrinthShopPolicy(finalArea = 3)))
            add(Scenario("area3 unknown refill does not unlock all-roster fallback", "wait", items = listOf(allChoice()),
                refreshKnown = false, policy = LabyrinthShopPolicy(finalArea = 3)))
            add(Scenario("area3 disabled refresh allows only the old all-roster exception", "imprint:all",
                items = listOf(choice(), relic("three", 3), relic("four", 4), allChoice()),
                policy = LabyrinthShopPolicy(refreshEnabled = false), expectedPicker = true))
            add(Scenario("area3 role-specific choice and star3 star4 stay prohibited after refills", "close",
                items = listOf(choice(), relic("three", 3), relic("four", 4)), successfulRefreshes = 2,
                policy = LabyrinthShopPolicy(finalArea = 3)))

            // Every clearance rung outranks all later rungs, irrespective of slot names and counters.
            val ladder = listOf(relic(), randomImprint(), relic("two", 2), allChoice(), relic("three", 3), choice(), relic("four", 4))
            for (index in 0..5) {
                val expected = if (index in listOf(0, 2, 4)) "relic:${ladder[index].slotId}" else "imprint:${ladder[index].slotId}"
                add(Scenario("area5 confirmed-dead-refill clearance rung$index", expected, area = 5,
                    items = ladder.drop(index).reversed(), successfulRefreshes = 40, relicPurchases = 3,
                    shelfScanComplete = false, visibleShelfComplete = false,
                    expectedPicker = if (index == 3 || index == 5) true else null))
            }
            add(Scenario("area5 clearance never buys star4", "close", area = 5, items = listOf(relic("four", 4))))
            add(Scenario("area5 star4 remains forbidden even with old configurable rarity gates removed", "close", area = 5,
                items = listOf(relic("four", 4)), policy = LabyrinthShopPolicy(skipRelicRaritiesByArea = emptyMap(),
                    clearanceRelicRarities = setOf(3, 4))))
            add(Scenario("area5 all-roster choice does not unlock before refill dies", "refresh", area = 5,
                items = listOf(allChoice(), choice(), relic("three", 3)), refreshAvailable = true))
            add(Scenario("area5 unknown refill cannot unlock clearance", "wait", area = 5,
                items = listOf(allChoice(), choice(), relic("three", 3)), refreshKnown = false))
            add(Scenario("area5 manually disabled refill does not unlock clearance", "close", area = 5,
                items = listOf(allChoice(), choice(), relic("three", 3)), policy = LabyrinthShopPolicy(refreshEnabled = false)))
            add(Scenario("area5 start-area exclusion does not unlock clearance", "close", area = 5,
                items = listOf(allChoice(), choice(), relic("three", 3)), policy = LabyrinthShopPolicy(finalArea = 6)))
            add(Scenario("area5 clearance still uses actual imprint price", "relic:three", area = 5,
                items = listOf(allChoice(price = 20_000), relic("three", 3)), currentBalance = 5_000))
            add(Scenario("area5 unknown-price all-roster does not discard safe star3", "relic:three", area = 5,
                items = listOf(allChoice(price = null), relic("three", 3))))

            // Switches, strict scan evidence and successful-refresh caps.
            for (area in listOf(3, 4, 5)) {
                add(Scenario("area$area missing entry amount waits", "wait", area = area, items = listOf(randomImprint()), entryBalance = null))
                add(Scenario("area$area missing current amount waits", "wait", area = area, items = listOf(randomImprint()), currentBalance = null))
                add(Scenario("area$area negative entry amount waits", "wait", area = area, items = listOf(randomImprint()), entryBalance = -1))
                add(Scenario("area$area negative current amount waits", "wait", area = area, items = listOf(randomImprint()), currentBalance = -1))
                add(Scenario("area$area omitted new arguments cannot buy", "wait", area = area,
                    items = listOf(randomImprint()), legacyCall = true))
                add(Scenario("area$area master purchase switch controls whole shop", "close", area = area,
                    items = listOf(relic(), randomImprint(), allChoice(), unknown()), refreshAvailable = true,
                    shelfScanComplete = false, visibleShelfComplete = false, entryBalance = null, currentBalance = null,
                    policy = LabyrinthShopPolicy(buyRelics = false)))
                add(Scenario("area$area incomplete visible evidence waits not browse", "wait", area = area,
                    visibleShelfComplete = false, shelfScanComplete = false, refreshAvailable = true))
                add(Scenario("area$area completed scan cannot bypass an incomplete visible shelf", "wait", area = area,
                    visibleShelfComplete = false, shelfScanComplete = true, refreshAvailable = true))
                add(Scenario("area$area current empty observed shelf browses before refill or exit", "browse", area = area,
                    shelfScanComplete = false, refreshAvailable = true, policy = LabyrinthShopPolicy(finalArea = 3)))
            }
            for (used in listOf(0, 1, 2)) {
                add(Scenario("area3 successful-refill cap at$used", if (used < 2) "refresh" else "close",
                    successfulRefreshes = used, refreshAvailable = true, policy = LabyrinthShopPolicy(finalArea = 3)))
            }
            for (used in listOf(0, 1, 2, 20, Int.MAX_VALUE)) {
                add(Scenario("area5 remains unlimited after$used successful refills", "refresh", area = 5,
                    successfulRefreshes = used, refreshAvailable = true,
                    policy = LabyrinthShopPolicy(refreshLimitsByArea = mapOf(3 to 1, 5 to 1))))
            }
            add(Scenario("area3 finalArea preference prevents early refill", "close", refreshAvailable = true))
            add(Scenario("area3 unknown button waits only after shelf scanning", "wait", refreshKnown = false,
                policy = LabyrinthShopPolicy(finalArea = 3)))
            add(Scenario("area5 unknown refill still browses an unobserved lower shelf first", "browse", area = 5,
                shelfScanComplete = false, refreshKnown = false))
            add(Scenario("area3 budget-refused random permits lower-shelf search", "browse", items = listOf(randomImprint()),
                currentBalance = 8_699, shelfScanComplete = false))
            add(Scenario("area5 manual refresh switch still allows normal random", "imprint:random", area = 5,
                items = listOf(randomImprint()), policy = LabyrinthShopPolicy(refreshEnabled = false)))
            add(Scenario("area5 manual refresh switch still allows normal star2", "relic:two", area = 5,
                items = listOf(relic("two", 2)), policy = LabyrinthShopPolicy(refreshEnabled = false)))
            add(Scenario("area5 manual refresh switch must still inspect unseen normal goods", "browse", area = 5,
                items = listOf(choice(), relic("three", 3)), shelfScanComplete = false,
                policy = LabyrinthShopPolicy(refreshEnabled = false)))
            add(Scenario("confirmed disabled buttons are independently non-buyable", "refresh", area = 5,
                items = listOf(unknown().copy(status = LabyrinthShopItemStatus.DISABLED, buyButtonEnabledEvidence = 0.0)),
                refreshAvailable = true))

            // An unresolved column never authorizes purchase/browse/depletion; it also never hides a safe column.
            val unsafeColumns = listOf(
                unknown(),
                relic("button-unknown").copy(status = LabyrinthShopItemStatus.UNKNOWN),
                relic("identity-unsafe").copy(identitySafe = false),
                relic("reading").copy(categoryState = LabyrinthShopCategoryState.READING),
                relic("exhausted").copy(categoryState = LabyrinthShopCategoryState.EXHAUSTED, categoryAttempt = 3),
                relic("price-unread", price = null),
                relic("price-zero", price = 0),
                relic("price-negative", price = -1),
                relic("rarity-unread", rarity = 0),
                randomImprint("form-unknown").copy(imprintForm = LabyrinthShopImprintForm.UNKNOWN),
                randomImprint("label-unread").copy(roleImprintLabel = null, imprintLabelPending = false),
                randomImprint("choice-must-not-look-random").copy(choiceRoleImprint = true),
            )
            for (area in listOf(3, 4, 5)) {
                val safe = if (area == 4) randomImprint("safe") else relic("safe")
                for (unsafe in unsafeColumns) {
                    add(Scenario("area$area rejects unresolved ${unsafe.slotId} as empty shelf", "wait", area = area,
                        items = listOf(unsafe), shelfScanComplete = false, refreshAvailable = true,
                        policy = LabyrinthShopPolicy(finalArea = 3)))
                    add(Scenario("area$area keeps safe column beside ${unsafe.slotId}",
                        if (area == 4) "imprint:safe" else "relic:safe", area = area, items = listOf(unsafe, safe),
                        shelfScanComplete = false, visibleShelfComplete = false, refreshAvailable = true,
                        policy = LabyrinthShopPolicy(finalArea = 3)))
                }
            }
            add(Scenario("area5 exhausted identity cannot close even during clearance", "wait", area = 5,
                items = listOf(unknown().copy(categoryState = LabyrinthShopCategoryState.EXHAUSTED)), refreshAvailable = false))
            add(Scenario("area5 unknown-price safe identity cannot close after full scanning", "wait", area = 5,
                items = listOf(randomImprint(price = null)), refreshAvailable = false))
            add(Scenario("new form authorizes picker even when legacy bool absent", "imprint:choice", area = 4,
                items = listOf(choice().copy(choiceRoleImprint = false)), expectedPicker = true))

            // Reuse original relic ranking, calibrated stacks, locked focus and configured baseline deadline.
            val stacks = mapOf(LabyrinthRelicMark.ACCELERATION to 8, LabyrinthRelicMark.CRITICAL to 4,
                LabyrinthRelicMark.DEFENSE to 3)
            val contenders = listOf(relic("defense", attribute = "守备"), relic("speed", attribute = "加速"))
            for (end in listOf(2, 3)) {
                add(Scenario("area3 original configured baseline deadline$end", if (end == 3) "relic:defense" else "relic:speed",
                    items = contenders, calibratedStacks = stacks,
                    policy = LabyrinthShopPolicy(LabyrinthStrategySettings(baselineLastArea = end).relicPolicy())))
            }
            add(Scenario("new area5 branch retains locked focus", "relic:critical", area = 5,
                items = listOf(relic("speed", attribute = "加速"), relic("critical", attribute = "会心")),
                calibratedStacks = mapOf(LabyrinthRelicMark.ACCELERATION to 8, LabyrinthRelicMark.CRITICAL to 8,
                    LabyrinthRelicMark.DEFENSE to 8), lockedFocus = LabyrinthRelicMark.CRITICAL,
                expectedFocus = LabyrinthRelicMark.CRITICAL))
            add(Scenario("new branch retains calibrated immediate-capstone choice", "relic:critical",
                items = listOf(relic("defense", attribute = "守备"), relic("critical", attribute = "会心")),
                calibratedStacks = mapOf(LabyrinthRelicMark.CRITICAL to 14), expectedFocus = LabyrinthRelicMark.CRITICAL,
                expectedPhase = LabyrinthRelicChoicePhase.REACH_ANY_FIFTEEN))

            // Legacy areas need no new monetary/identity fields and keep original relic-first/cycle behavior.
            for (area in listOf(1, 2)) {
                val legacyRelic = relic("legacy-four", 4).copy(priceCoins = null, identitySafe = false)
                val legacyImprint = choice().copy(priceCoins = null, identitySafe = false, imprintForm = LabyrinthShopImprintForm.UNKNOWN)
                add(Scenario("area$area legacy star4 still outranks imprint without new fields", "relic:legacy-four", area = area,
                    items = listOf(legacyImprint, legacyRelic), legacyCall = true))
                add(Scenario("area$area legacy role-specific choice remains allowed", "imprint:choice", area = area,
                    items = listOf(legacyImprint), legacyCall = true, expectedPicker = true))
                add(Scenario("area$area legacy cycle still refreshes before imprint", "refresh", area = area,
                    items = listOf(legacyImprint), legacyCall = true, relicPurchases = 3, refreshAvailable = true,
                    policy = LabyrinthShopPolicy(finalArea = 1)))
                add(Scenario("area$area legacy master switch unchanged", "close", area = area,
                    items = listOf(legacyRelic), legacyCall = true, policy = LabyrinthShopPolicy(buyRelics = false)))
            }
        }.map { arrayOf<Any>(it) }

        private fun relic(
            id: String = "one",
            rarity: Int = 1,
            price: Int? = when (rarity) { 1 -> 585; 2 -> 1_820; 3 -> 3_705; 4 -> 6_500; else -> null },
            attribute: String? = "会心",
            bonus: Int? = 1,
        ): LabyrinthShopItemMatch = LabyrinthShopItemMatch(
            slotId = id,
            screenRect = rect,
            buyButtonRect = rect,
            buyButtonScore = 0.95,
            buyButtonEnabledEvidence = 1.0,
            status = LabyrinthShopItemStatus.AVAILABLE,
            relicMatch = LabyrinthRelicMatch(
                slotId = id, relicId = id, displayName = id, attribute = attribute,
                attributeBonus = bonus?.toString(), effect = null, rarity = rarity, confidence = 0.95,
                screenRect = rect, iconRect = rect, rivalMargin = 0.2,
            ),
            kind = LabyrinthShopItemKind.RELIC,
            priceCoins = price,
            identitySafe = true,
        )

        private fun randomImprint(id: String = "random", price: Int? = 780): LabyrinthShopItemMatch =
            imprint(id, LabyrinthShopImprintForm.RANDOM, "攻击", price)

        private fun choice(price: Int? = 1_820): LabyrinthShopItemMatch =
            imprint("choice", LabyrinthShopImprintForm.CHOICE, "治疗", price)

        private fun allChoice(price: Int? = 2_600): LabyrinthShopItemMatch =
            imprint("all", LabyrinthShopImprintForm.CHOICE, "全体选择印记", price)

        private fun imprint(id: String, form: LabyrinthShopImprintForm, label: String, price: Int?): LabyrinthShopItemMatch =
            LabyrinthShopItemMatch(
                slotId = id, screenRect = rect, buyButtonRect = rect, buyButtonScore = 0.95,
                buyButtonEnabledEvidence = 1.0, status = LabyrinthShopItemStatus.AVAILABLE,
                kind = LabyrinthShopItemKind.ROLE_IMPRINT, roleImprintLabel = label,
                choiceRoleImprint = form == LabyrinthShopImprintForm.CHOICE,
                priceCoins = price, imprintForm = form, identitySafe = true,
            )

        private fun unknown(): LabyrinthShopItemMatch = LabyrinthShopItemMatch(
            slotId = "unknown", screenRect = rect, buyButtonRect = rect, buyButtonScore = 0.95,
            buyButtonEnabledEvidence = 1.0, status = LabyrinthShopItemStatus.AVAILABLE,
        )

        private fun owned(id: String): LabyrinthObservedRelic = LabyrinthObservedRelic(
            relicId = id, displayName = id, attribute = "会心", attributeBonus = "1", effect = "",
            confidence = 0.95, firstSeenAt = 1, lastSeenAt = 1,
        )
    }
}
