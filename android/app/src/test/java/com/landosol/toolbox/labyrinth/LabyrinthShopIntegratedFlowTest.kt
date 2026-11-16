package com.landosol.toolbox.labyrinth

import com.landosol.toolbox.automation.AutomationAction
import com.landosol.toolbox.automation.AutomationBackendResult
import com.landosol.toolbox.automation.AutomationSessionManager
import com.landosol.toolbox.automation.ScreenPoint
import com.landosol.toolbox.automation.SessionBoundActionExecutor
import com.landosol.toolbox.labyrinth.node.LabyrinthNodeTypes
import com.landosol.toolbox.labyrinth.vision.*
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

/** Real Session page/evidence/action/commit handlers; only capture, OCR and the backend are fakes. */
class LabyrinthShopIntegratedFlowTest {
    @Test fun purchaseCompactionUsesFreshEvidenceAndCountsEachCompletionOnce() = runBlocking {
        val h = Harness(area = 3)
        try {
            h.start()
            val initialRequest = h.session.shopEvidenceRequest
            val a = relic("a")
            h.settleShop(listOf(a), 15_001, viewport(152, 10))
            h.assertLastTap(a.buyButtonRect)
            assertEquals(0, h.relicPurchases)
            assertEquals(15_001, h.visit.entryBalance)
            assertNull(h.visit.currentBalance)

            h.finishRelicPurchase(requireNotNull(a.relicMatch), expectedCount = 1)
            assertEquals(listOf("a"), h.store.snapshot.observedRelics.map { it.relicId })
            assertEquals(1, h.relicPurchases)
            assertEquals(0, h.refreshes)

            // B replaces A at the same physical slot. New timestamps must not relabel old OCR epochs.
            val b = relic("b")
            val tapsBeforeB = h.actions.size
            repeat(2) {
                h.deliver(h.capture(h.now + 200, listOf(b), 14_416, viewport(152, 20),
                    request = initialRequest))
            }
            assertNotEquals(initialRequest.epoch, h.session.shopEvidenceRequest.epoch)
            assertEquals(tapsBeforeB, h.actions.size)
            assertNull(h.visit.currentBalance)

            // A fresh shop/balance is insufficient when the card still belongs to the old epoch.
            repeat(2) {
                h.deliver(h.capture(h.now + 200, listOf(b), 14_416, viewport(152, 20),
                    itemRequest = initialRequest))
            }
            assertEquals(tapsBeforeB, h.actions.size)
            assertEquals(14_416, h.visit.currentBalance)
            assertEquals(15_001, h.visit.entryBalance)
            h.shop(listOf(b), 14_416, viewport(152, 20), after = 1_600)
            h.assertLastTap(b.buyButtonRect)
            assertEquals(1, h.relicPurchases) // The second buy ACK is not another success.
            h.finishRelicPurchase(requireNotNull(b.relicMatch), expectedCount = 2)
            assertEquals(listOf("a", "b"), h.store.snapshot.observedRelics.map { it.relicId })
            assertEquals(2, h.relicPurchases)
            assertEquals(15_001, h.visit.entryBalance)
            assertEquals(0, h.refreshes)
            assertEquals(0, h.visit.downAttempts)
            assertEquals(0, h.visit.adjustmentAttempts)
        } finally { h.close() }
    }

    @Test fun browsingRequiresMotionAndIndependentStableFramesThenRechecksReturnedFirstRow() = runBlocking {
        val h = Harness(area = 5)
        try {
            h.start()
            val initialRequest = h.session.shopEvidenceRequest
            // A resumed scrolled shop must really return to the first row before it can buy or browse.
            h.settleShop(disabled(), 20_000, viewport(180, 1))
            h.assertLastSwipe(LabyrinthShopScrollKind.TOP)
            assertEquals(1, h.visit.returnAttempts)
            val beforeReturn = h.actions.size
            h.deliver(h.capture(h.now + 200, disabled(), 20_000, viewport(152, 2), request = initialRequest))
            repeat(2) { h.shop(disabled(), 20_000, viewport(180, 1)) }
            assertEquals(beforeReturn, h.actions.size)
            assertTrue(h.visit.scrollPending)

            val first = relic("first")
            val firstRow = listOf(first, unknown("cropped-neighbour", cropped = true))
            h.shop(firstRow, 20_000, viewport(152, 10))
            val changed = h.capture(h.now + 200, firstRow, 20_000, viewport(152, 11))
            h.deliver(changed)
            h.deliver(changed, deliveredAt = h.now + 200) // Same capture is not a second stable frame.
            assertEquals(beforeReturn, h.actions.size)
            assertTrue(h.visit.scrollPending)
            h.shop(firstRow, 20_000, viewport(152, 11), after = 400)
            h.assertLastTap(first.buyButtonRect)
            assertEquals(0, h.swipeCount(LabyrinthShopScrollKind.DOWN))
            assertEquals(0, h.swipeCount(LabyrinthShopScrollKind.ADJUST))
            h.finishRelicPurchase(requireNotNull(first.relicMatch), expectedCount = 1)

            val beforeDownRequest = h.session.shopEvidenceRequest
            h.settleShop(disabled(), 19_415, viewport(152, 20), firstAfter = 1_600)
            h.assertLastSwipe(LabyrinthShopScrollKind.DOWN)
            assertEquals(1, h.visit.downAttempts)
            val afterDown = h.actions.size
            val croppedRole = imprint(cropped = true)
            h.deliver(h.capture(h.now + 200, listOf(croppedRole), 19_415, viewport(208, 30),
                request = beforeDownRequest))
            repeat(2) { h.shop(disabled(), 19_415, viewport(152, 20)) }
            assertEquals(afterDown, h.actions.size)
            assertTrue(h.visit.scrollPending)

            h.shop(listOf(croppedRole), 19_415, viewport(208, 30))
            val moving = h.capture(h.now + 200, listOf(croppedRole), 19_415, viewport(208, 31))
            h.deliver(moving)
            h.deliver(moving, deliveredAt = h.now + 200)
            assertEquals(afterDown, h.actions.size)
            h.shop(listOf(croppedRole), 19_415, viewport(208, 31), after = 400)
            h.assertLastSwipe(LabyrinthShopScrollKind.ADJUST)
            assertTrue(h.visit.bottomObserved)
            assertEquals(1, h.visit.adjustmentAttempts)
            val afterAdjust = h.actions.size
            repeat(2) { h.shop(listOf(croppedRole), 19_415, viewport(208, 31)) }
            assertEquals(afterAdjust, h.actions.size) // ADJUST receipt without movement cannot buy.

            val fullRole = croppedRole.copy(titleCropped = false)
            repeat(2) { h.shop(listOf(fullRole), 19_415, viewport(204, 32)) }
            h.shop(listOf(fullRole), 19_415, viewport(204, 32), after = 800)
            h.assertLastTap(fullRole.buyButtonRect)
            h.finishImprintPurchase(expectedRelicCount = 1)
            assertTrue(h.visit.bottomObserved)
            assertEquals(1, h.visit.downAttempts)
            assertEquals(1, h.visit.adjustmentAttempts)
            assertEquals(1, h.visit.returnAttempts)

            // Compaction/micro-adjustment must not demand another DOWN to rediscover the same bottom.
            h.settleShop(disabled(), 18_635, viewport(204, 33), firstAfter = 1_600)
            h.assertLastSwipe(LabyrinthShopScrollKind.TOP)
            assertEquals(1, h.swipeCount(LabyrinthShopScrollKind.DOWN))
            assertEquals(1, h.swipeCount(LabyrinthShopScrollKind.ADJUST))
            assertEquals(2, h.visit.returnAttempts)
            assertFalse(h.visit.shelfScanComplete)
            val returned = relic("returned-first-row", buyRect = EntryPixelRect(300, 490, 100, 36))
            repeat(2) { h.shop(listOf(returned), 18_635, viewport(152, 40)) }
            assertTrue(h.visit.shelfScanComplete)
            h.shop(listOf(returned), 18_635, viewport(152, 40), after = 1_200)
            h.assertLastTap(returned.buyButtonRect) // Recompute: do not execute the pre-return Close.
            assertEquals("returned-first-row", h.get("plannedShopPurchaseRelicId"))
            assertEquals(1, h.relicPurchases)
            assertEquals(0, h.refreshes)
            assertTrue(h.visit.downAttempts <= LabyrinthShopVisitState.MAX_DOWN_ATTEMPTS)
            assertTrue(h.visit.adjustmentAttempts <= LabyrinthShopVisitState.MAX_ADJUSTMENTS)
            assertTrue(h.visit.returnAttempts <= LabyrinthShopVisitState.MAX_RETURN_ATTEMPTS)
        } finally { h.close() }
    }

    @Test fun singleViewportRefreshNeedsPaymentAndAreaFourLastPurchaseReturnsBeforeExit() = runBlocking {
        val refresh = Harness(area = 3, refreshEnabled = true)
        try {
            refresh.start()
            val bothEdges = viewport(152, 50, singleViewport = true)
            refresh.settleShop(disabled(), 15_001, bothEdges)
            assertTrue(refresh.visit.shelfScanComplete)
            assertTrue(refresh.actions.isEmpty()) // A complete single viewport needs no futile DOWN.
            val beforeRefresh = refresh.session.shopEvidenceRequest
            refresh.shop(disabled(), 15_001, bothEdges, after = 1_600)
            refresh.assertLastTap(refreshRect)
            assertEquals(600, refresh.get("plannedShopRefreshPrice"))
            assertEquals(0, refresh.refreshes)
            assertEquals(0, refresh.visit.successfulRefreshes)
            refresh.genericRefreshConfirmation(after = 1_600)
            refresh.assertLastTap(confirmRect)
            assertEquals(0, refresh.refreshes)
            val afterConfirmActions = refresh.actions.size
            refresh.genericRefreshConfirmation()
            assertEquals(afterConfirmActions, refresh.actions.size)

            // Old-generation paid-looking evidence is frozen at capture, never retagged at delivery.
            refresh.deliver(refresh.capture(refresh.now + 600, disabled(), 14_401,
                viewport(152, 51, singleViewport = true), request = beforeRefresh))
            assertEquals(0, refresh.refreshes)
            repeat(2) { refresh.shop(disabled(), 15_001, bothEdges) }
            assertEquals(0, refresh.refreshes) // Stable shelf + ACK + unchanged coins cannot commit.
            val paid = refresh.capture(refresh.now + 200, disabled(), 14_401,
                viewport(152, 51, singleViewport = true))
            refresh.deliver(paid)
            refresh.deliver(paid, deliveredAt = refresh.now + 200)
            assertEquals(0, refresh.refreshes)
            val committed = refresh.capture(refresh.now + 200, disabled(), 14_401,
                viewport(152, 51, singleViewport = true))
            refresh.deliver(committed)
            assertEquals(1, refresh.refreshes)
            assertEquals(1, refresh.visit.successfulRefreshes)
            assertEquals(15_001, refresh.visit.entryBalance)
            assertNull(refresh.get("plannedShopRefreshPrice"))
            assertNull(refresh.visit.currentBalance)
            assertFalse(refresh.visit.shelfScanComplete)
            repeat(2) { refresh.deliver(committed, deliveredAt = refresh.now + 200) }
            assertEquals(1, refresh.refreshes)
            assertEquals(afterConfirmActions, refresh.actions.size)
            assertEquals(0, refresh.swipeCount(LabyrinthShopScrollKind.DOWN))
        } finally { refresh.close() }

        val saving = Harness(area = 4, refreshEnabled = true)
        try {
            saving.start()
            saving.settleShop(disabled(), 10_001, viewport(152, 60))
            saving.assertLastSwipe(LabyrinthShopScrollKind.DOWN)
            val role = imprint()
            repeat(2) { saving.shop(listOf(role), 8_000, viewport(208, 61)) }
            saving.shop(listOf(role), 8_000, viewport(208, 61), after = 1_200)
            saving.assertLastTap(role.buyButtonRect)
            assertEquals(10_001, saving.visit.entryBalance)
            saving.finishImprintPurchase(expectedRelicCount = 0)
            val anotherRole = imprint(id = "another-role", buyRect = EntryPixelRect(950, 480, 100, 36))
            saving.settleShop(listOf(anotherRole), 7_220, viewport(208, 62), firstAfter = 1_600)
            saving.assertLastSwipe(LabyrinthShopScrollKind.TOP)
            val beforeTop = saving.actions.size
            assertEquals(0, saving.swipeCount(LabyrinthShopScrollKind.ADJUST))
            assertEquals(0, saving.refreshes)
            saving.shop(listOf(anotherRole), 7_220, viewport(208, 62))
            assertEquals(beforeTop, saving.actions.size)
            repeat(2) { saving.shop(listOf(anotherRole), 7_220, viewport(152, 63)) }
            assertTrue(saving.visit.shelfScanComplete)
            assertEquals(beforeTop, saving.actions.size) // Return receipt/cooldown is not an exit tap.
            saving.shop(listOf(anotherRole), 7_220, viewport(152, 63), after = 1_200)
            saving.assertLastTap(closeRect)
            assertEquals(10_001, saving.visit.entryBalance)
            assertEquals(7_220, saving.visit.currentBalance)
            assertEquals(1, saving.swipeCount(LabyrinthShopScrollKind.DOWN))
            assertEquals(1, saving.swipeCount(LabyrinthShopScrollKind.TOP))
            assertEquals(0, saving.relicPurchases)
            assertEquals(0, saving.refreshes)
        } finally { saving.close() }
    }

    private inner class Harness(area: Int, refreshEnabled: Boolean = false) {
        val actions = CopyOnWriteArrayList<AutomationAction>()
        val store = MemoryStore()
        @Volatile var now = 1_000L
        private var sessionId = 0L
        private val currentArea = area
        private val manager = AutomationSessionManager()
        val session = LabyrinthEntryRecognitionSession(
            sessionManager = manager, captureActive = { true }, captureStop = {},
            processorFactory = { { error("no image/OCR worker in shop handler replays") } },
            actionExecutor = SessionBoundActionExecutor(manager) { action ->
                actions += action
                AutomationBackendResult.Completed
            },
            actionsAvailable = { true }, gameLauncher = { true }, runStateStore = store,
            strategyProvider = { LabyrinthStrategySnapshot(LabyrinthStrategySettings(
                buyRelics = true, refreshShop = refreshEnabled, refreshFromArea = 3), null) },
            clock = { now },
        )
        val visit get() = get("shopVisit") as LabyrinthShopVisitState
        val relicPurchases get() = get("shopRelicPurchasesThisCycle") as Int
        val refreshes get() = get("shopRefreshesThisVisit") as Int
        private val ledger get() = get("relicStackLedger") as LabyrinthRelicStackLedger

        suspend fun start() {
            val started = session.startAutomation()
            assertTrue(started is LabyrinthEntryRecognitionStartResult.Started)
            sessionId = (started as LabyrinthEntryRecognitionStartResult.Started).sessionId.value
            call("prepareNodeTransitionContext", LabyrinthNodeTypes.SHOP, currentArea)
        }
        suspend fun close() { session.stop(releaseCapture = false) }
        fun get(name: String): Any? = session.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(session)
        private fun method(name: String, count: Int) = session.javaClass.declaredMethods.single {
            (it.name == name || it.name.startsWith("$name-")) && !it.name.endsWith("\$default") &&
                it.parameterCount == count
        }.apply { isAccessible = true }
        private fun call(name: String, vararg args: Any?): Any? = try {
            method(name, args.size).invoke(session, *args)
        } catch (failure: InvocationTargetException) { throw failure.targetException }

        /** Every capture freezes its request and every OCR stamp; delivery cannot borrow a newer epoch. */
        fun capture(at: Long, items: List<LabyrinthShopItemMatch>, balance: Int?, view: LabyrinthShopViewport,
            dialog: LabyrinthShopDialogState = LabyrinthShopDialogState.NONE,
            candidate: LabyrinthRelicMatch? = null, genericConfirmation: Boolean = false,
            request: LabyrinthShopEvidenceRequest = session.shopEvidenceRequest,
            itemRequest: LabyrinthShopEvidenceRequest = request,
        ): FrozenFrame {
            val page = when {
                genericConfirmation -> LabyrinthEntryPageState.UNKNOWN
                dialog == LabyrinthShopDialogState.PURCHASE_CONFIRMATION -> LabyrinthEntryPageState.SHOP_PURCHASE_CONFIRMATION
                dialog == LabyrinthShopDialogState.PURCHASE_COMPLETE -> LabyrinthEntryPageState.SHOP_PURCHASE_COMPLETE
                else -> LabyrinthEntryPageState.SHOP
            }
            val matches = if (dialog == LabyrinthShopDialogState.PURCHASE_CONFIRMATION) {
                mapOf(EntryAnchorId.SHOP_EXIT_CONFIRM_BUTTON to EntryAnchorMatch(0.99, confirmRect))
            } else if (page == LabyrinthEntryPageState.SHOP) {
                mapOf(EntryAnchorId.SHOP_REFRESH_BUTTON to EntryAnchorMatch(0.99, refreshRect),
                    EntryAnchorId.SHOP_CLOSE to EntryAnchorMatch(0.99, closeRect))
            } else emptyMap()
            val shop = LabyrinthShopObservation(
                items = items.map { it.copy(evidenceEpoch = itemRequest.epoch, evidenceCapturedAtMillis = at) },
                dialogState = dialog, purchaseCandidate = candidate, refreshButtonEnabledEvidence = 1.0,
                balanceCoins = balance, balanceCapturedAtMillis = at,
                evidenceEpoch = request.epoch, capturedAtMillis = at,
                refreshPriceCoins = 600, refreshPriceCapturedAtMillis = at, viewport = view,
            )
            return FrozenFrame(at, LabyrinthEntryFrameResult(
                LabyrinthEntryPageObservation(page, 1.0, emptyMap(),
                    LabyrinthAnchorScores(matches.mapValues { it.value.score })),
                emptyList(), 0, anchorMatches = matches, shopObservation = shop,
                nodeMoveConfirmation = if (genericConfirmation)
                    LabyrinthNodeMoveConfirmationObservation(0.99, confirmRect, cancelRect) else null,
                frameWidth = 1_280, frameHeight = 720,
            ))
        }

        suspend fun shop(items: List<LabyrinthShopItemMatch>, balance: Int?, view: LabyrinthShopViewport,
            after: Long = 200) = deliver(capture(now + after, items, balance, view))
        suspend fun settleShop(items: List<LabyrinthShopItemMatch>, balance: Int?, view: LabyrinthShopViewport,
            firstAfter: Long = 200) {
            repeat(3) { shop(items, balance, view, if (it == 0) firstAfter else 200) }
        }
        suspend fun genericRefreshConfirmation(after: Long = 200) = deliver(capture(now + after,
            emptyList(), null, viewport(152, 50), genericConfirmation = true))

        suspend fun finishRelicPurchase(candidate: LabyrinthRelicMatch, expectedCount: Int) {
            val before = relicPurchases
            repeat(2) { index ->
                deliver(capture(now + if (index == 0) 1_600 else 200, emptyList(), null, viewport(152, 100),
                    dialog = LabyrinthShopDialogState.PURCHASE_CONFIRMATION, candidate = candidate))
            }
            assertLastTap(confirmRect)
            assertEquals(before, relicPurchases)
            finishPurchaseCompletion(expectedCount)
        }
        suspend fun finishImprintPurchase(expectedRelicCount: Int) {
            repeat(2) { index ->
                deliver(capture(now + if (index == 0) 1_600 else 200, emptyList(), null, viewport(208, 100),
                    dialog = LabyrinthShopDialogState.PURCHASE_CONFIRMATION))
            }
            assertLastTap(confirmRect)
            assertEquals(expectedRelicCount, relicPurchases)
            finishPurchaseCompletion(expectedRelicCount)
        }
        private suspend fun finishPurchaseCompletion(expectedCount: Int) {
            val complete = capture(now + 200, emptyList(), null, viewport(152, 101),
                dialog = LabyrinthShopDialogState.PURCHASE_COMPLETE)
            deliver(complete)
            assertEquals(expectedCount, relicPurchases)
            deliver(complete, deliveredAt = now + 200) // Replay the same completion, before dismiss cooldown.
            assertEquals(expectedCount, relicPurchases)
            deliver(capture(now + 1_600, emptyList(), null, viewport(152, 101),
                dialog = LabyrinthShopDialogState.PURCHASE_COMPLETE))
            assertEquals(expectedCount, relicPurchases)
            assertNull(get("plannedShopPurchaseRelicId"))
            assertNull(get("plannedShopRoleImprintLabel"))
            assertNull(visit.currentBalance)
        }

        /** Same shop-owned ordering as processFrameResult, not direct calls to policy/state commits. */
        suspend fun deliver(frame: FrozenFrame, deliveredAt: Long = frame.at) {
            now = maxOf(now, deliveredAt)
            @Suppress("UNCHECKED_CAST")
            val state = get("_state") as MutableStateFlow<LabyrinthEntryRecognitionSessionState>
            state.value = state.value.copy(lastResult = frame.result)
            call("invalidatePreviousPageState", frame.result.observation.state, false)
            call("observeRelicIdentities", frame.result, frame.at)
            call("applyRelicCancellations", ledger)
            assertTrue(persist(frame, ledger.fork()))
            val refreshOwns = call("handleShopRefreshConfirmationFrame", sessionId, frame.result, frame.at) as Boolean
            if (!refreshOwns) call("handlePostEntryPage", sessionId, frame.result, 1_280, 720, frame.at)
            withTimeout(5_000) { while ((get("actionInFlight") as AtomicBoolean).get()) delay(1) }
            assertTrue(session.state.value.message, session.state.value.running)
        }
        private suspend fun persist(frame: FrozenFrame, staged: LabyrinthRelicStackLedger): Boolean =
            suspendCoroutine { continuation: Continuation<Boolean> ->
                try {
                    val result = method("persistObservation", 5).invoke(session, sessionId, frame.result,
                        frame.at, staged, continuation)
                    if (result !== COROUTINE_SUSPENDED) continuation.resume(result as Boolean)
                } catch (failure: InvocationTargetException) {
                    continuation.resumeWithException(failure.targetException)
                }
            }
        fun assertLastTap(rect: EntryPixelRect) = assertEquals(
            AutomationAction.Tap(ScreenPoint(rect.left + rect.width / 2f, rect.top + rect.height / 2f)), actions.last())
        fun assertLastSwipe(kind: LabyrinthShopScrollKind) = assertTrue("Expected shop stroke $kind",
            actions.last().let { it is AutomationAction.Swipe && isStroke(it, kind) })
        fun swipeCount(kind: LabyrinthShopScrollKind): Int = actions.filterIsInstance<AutomationAction.Swipe>()
            .count { isStroke(it, kind) }
    }

    private data class FrozenFrame(val at: Long, val result: LabyrinthEntryFrameResult)
    private fun isStroke(action: AutomationAction.Swipe, kind: LabyrinthShopScrollKind): Boolean = when (kind) {
        LabyrinthShopScrollKind.DOWN -> action.end.y < action.start.y
        LabyrinthShopScrollKind.ADJUST -> action.end.y > action.start.y && action.durationMillis == 450L
        LabyrinthShopScrollKind.TOP -> action.end.y > action.start.y && action.durationMillis == 650L
    }
    private fun viewport(top: Int, signature: Long, singleViewport: Boolean = false) = LabyrinthShopViewport(
        signature, top, if (singleViewport) 567 else top + 359,
        atTop = top == 152, atBottom = top == 208 || singleViewport, complete = true)
    private val cardRect = EntryPixelRect(80, 200, 350, 350)
    private val relicBuyRect = EntryPixelRect(120, 490, 100, 36)
    private val roleBuyRect = EntryPixelRect(800, 480, 100, 36)
    private val refreshRect = EntryPixelRect(420, 626, 140, 40)
    private val closeRect = EntryPixelRect(1170, 60, 40, 40)
    private val confirmRect = EntryPixelRect(730, 480, 120, 44)
    private val cancelRect = EntryPixelRect(540, 480, 120, 44)
    private fun relic(id: String, buyRect: EntryPixelRect = relicBuyRect) = LabyrinthShopItemMatch(
        slotId = "same-slot", screenRect = cardRect, buyButtonRect = buyRect,
        buyButtonScore = 0.99, buyButtonEnabledEvidence = 1.0, status = LabyrinthShopItemStatus.AVAILABLE,
        relicMatch = LabyrinthRelicMatch(slotId = "same-slot", relicId = id, displayName = id,
            attribute = "守备", attributeBonus = "1", effect = "", confidence = 0.99,
            rarity = 1, screenRect = cardRect, iconRect = cardRect, rivalMargin = 0.2),
        kind = LabyrinthShopItemKind.RELIC, priceCoins = 585, identitySafe = true,
        categoryState = LabyrinthShopCategoryState.READY,
    )
    private fun imprint(id: String = "lower-role", cropped: Boolean = false,
        buyRect: EntryPixelRect = roleBuyRect) = LabyrinthShopItemMatch(
        slotId = id, screenRect = cardRect, buyButtonRect = buyRect,
        buyButtonScore = 0.99, buyButtonEnabledEvidence = 1.0, status = LabyrinthShopItemStatus.AVAILABLE,
        kind = LabyrinthShopItemKind.ROLE_IMPRINT, titleText = "攻击型职能的随机印记",
        roleImprintLabel = "攻击型职能印记", imprintForm = LabyrinthShopImprintForm.RANDOM,
        priceCoins = 780, identitySafe = true, categoryState = LabyrinthShopCategoryState.READY,
        titleCropped = cropped,
    )
    private fun unknown(id: String, cropped: Boolean = false, status: LabyrinthShopItemStatus = LabyrinthShopItemStatus.AVAILABLE) =
        LabyrinthShopItemMatch(slotId = id, screenRect = cardRect, buyButtonRect = roleBuyRect,
            buyButtonScore = 0.99, buyButtonEnabledEvidence = if (status == LabyrinthShopItemStatus.AVAILABLE) 1.0 else 0.0,
            status = status, categoryState = LabyrinthShopCategoryState.READING, titleCropped = cropped)
    private fun disabled() = listOf("left", "middle", "right").map { unknown(it, status = LabyrinthShopItemStatus.DISABLED) }

    private class MemoryStore : LabyrinthRunStateStore {
        var snapshot = LabyrinthRunSnapshot("shop-flow", null, "ACTIVE", null, 0, emptyList(), 0,
            startedAt = 100, relicStacks = mapOf(LabyrinthRelicMark.DEFENSE to
                LabyrinthRelicStackRecord(0, LabyrinthRelicStackSource.MAP)))
        override suspend fun begin(accountId: Long?, nowMillis: Long) = snapshot
        override suspend fun load(accountId: Long?) = snapshot
        override suspend fun startNew(accountId: Long?, nowMillis: Long): LabyrinthRunSnapshot {
            snapshot = snapshot.copy(startedAt = maxOf(nowMillis, snapshot.startedAt + 1), observedRelics = emptyList())
            return snapshot
        }
        override suspend fun recordPage(accountId: Long?, page: String, characters: List<LabyrinthCharacterMatch>,
            nowMillis: Long, relics: List<LabyrinthRelicMatch>, replaceCharacters: Boolean,
            expectedStartedAt: Long?, relicStacks: Map<LabyrinthRelicMark, LabyrinthRelicStackRecord>?,
            openingRelicReceipt: LabyrinthOpeningRelicReceipt?): LabyrinthRunSnapshot {
            check(expectedStartedAt == snapshot.startedAt)
            snapshot = snapshot.copy(currentPage = page, updatedAt = nowMillis,
                joinedCharacters = LabyrinthRosterMerger.merge(snapshot.joinedCharacters, characters, nowMillis, replaceCharacters),
                observedRelics = LabyrinthRelicMerger.merge(snapshot.observedRelics, relics, nowMillis),
                relicStacks = relicStacks ?: snapshot.relicStacks,
                openingRelicReceipt = openingRelicReceipt ?: snapshot.openingRelicReceipt)
            return snapshot
        }
    }
}
