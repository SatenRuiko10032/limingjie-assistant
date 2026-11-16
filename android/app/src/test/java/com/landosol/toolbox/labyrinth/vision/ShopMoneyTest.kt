package com.landosol.toolbox.labyrinth.vision

import org.junit.Assert.*
import org.junit.Test

class ShopMoneyTest {
    @Test fun strictIntegersAndRealPrices() {
        for ((text, expected) in listOf("0" to 0, "780" to 780, "900" to 900, "1,170" to 1170,
            "6,685" to 6685, "6，685" to 6685, "6685" to 6685, "2,147,483,647" to Int.MAX_VALUE)) {
            assertEquals(text, expected, ShopMoney.parse(text))
        }
        for (text in listOf("", "00", "0780", "6,68", "66,85", "6,685.0", "6.685", "-780", "+780",
            "6 685", "6, 685", "O", "I170", "780 900", "持有6,685", "2,147,483,648")) assertNull(text, ShopMoney.parse(text))
        assertEquals(300, ShopMoney.parseRefresh("用300更新"))
        assertEquals(300, ShopMoney.parseRefresh("用 300 更新"))
        assertNull(ShopMoney.parseRefresh("300"))
        assertNull(ShopMoney.parseRefresh("300更新"))
        assertNull(ShopMoney.parseRefresh("用3 00更新"))
        assertNull(ShopMoney.parseRefresh("用300或900更新"))
    }

    @Test fun zeroNeedsTwoDifferentCapturesNotTwoCacheHits() {
        val budget = ShopMoneyReadBudget()
        budget.bind(4, 99, 100)
        val first = requireNotNull(budget.request(100, 100))
        budget.complete(first, "0", 200)
        assertNull(budget.snapshot(200).coins)
        assertNull(budget.request(201, 100))
        val second = requireNotNull(budget.request(202, 101))
        budget.complete(second, "0", 300)
        assertEquals(0, budget.snapshot(300).coins)
        assertEquals(100L, budget.snapshot(300).capturedAtMillis)
        assertEquals(2, budget.snapshot(300).completedReads)
        assertNull(budget.request(301, 102))
    }

    @Test fun invalidFirstReadGetsOnlyTwoRemainingIndependentChances() {
        val budget = ShopMoneyReadBudget()
        budget.bind(1, 0, 10)
        for ((i, text) in listOf("6,68", "6,685", "6,685").withIndex()) {
            val ticket = requireNotNull(budget.request(10L + i * 10, 1L + i))
            budget.complete(ticket, text, 15L + i * 10)
        }
        assertEquals(6685, budget.snapshot(40).coins)
        assertEquals(3, budget.snapshot(40).completedReads)
        assertNull(budget.request(41, 4))
    }

    @Test fun conflictIsNotResolvedByMajorityOrChangingFrameKeys() {
        val budget = ShopMoneyReadBudget()
        budget.bind(8, 0, 1)
        val first = requireNotNull(budget.request(1, 1))
        budget.complete(first, "6,685", 2)
        val second = requireNotNull(budget.request(3, 2))
        budget.complete(second, "6,085", 4)
        assertTrue(budget.snapshot(5).exhausted)
        assertNull(budget.snapshot(5).coins)
        // No third majority vote, no per-frame/fingerprint reset when bind sees the same scope.
        budget.bind(8, 0, 6)
        assertNull(budget.request(7, 3))
    }

    @Test fun threeCompletedFailuresNeverRearmOnAnotherFrame() {
        val budget = ShopMoneyReadBudget()
        budget.bind(2, 0, 1)
        for (i in 1..3) {
            val ticket = requireNotNull(budget.request(i.toLong(), i.toLong()))
            budget.complete(ticket, null, i.toLong())
        }
        assertTrue(budget.snapshot(4).exhausted)
        budget.bind(2, 0, 5)
        assertNull(budget.request(6, 6))
    }

    @Test fun actionEpochAndCaptureBoundDiscardLateCallbacks() {
        val budget = ShopMoneyReadBudget()
        budget.bind(1, 0, 1)
        val old = requireNotNull(budget.request(1, 1))
        budget.bind(2, 100, 101)
        budget.complete(old, "6,685", 110)
        assertEquals(0, budget.snapshot(110).completedReads)
        assertNull(budget.request(110, 100))
        for (capture in 101L..102L) {
            val next = requireNotNull(budget.request(111, capture))
            budget.complete(next, "5,905", 120)
        }
        assertEquals(5905, budget.snapshot(121).coins)
        assertEquals(101L, budget.snapshot(121).capturedAtMillis)
    }

    @Test fun deferredRefreshDeadlineStartsOnFirstPhysicalRequestNotWhileBuying() {
        val budget = ShopMoneyReadBudget(totalTimeoutMillis = 100, parse = ShopMoney::parseRefresh, startOnFirstRequest = true)
        budget.bind(9, 0, 1)
        assertFalse(budget.snapshot(1000).exhausted)
        val first = requireNotNull(budget.request(1000, 1000))
        budget.complete(first, "用300更新", 1020)
        val second = requireNotNull(budget.request(1030, 1030))
        budget.complete(second, "用300更新", 1050)
        assertEquals(300, budget.snapshot(1060).coins)
        assertEquals(1000L, budget.snapshot(1060).capturedAtMillis)
    }

    @Test fun overallDeadlineDoesNotUseLateCallbackTimeAsFreshCapture() {
        val budget = ShopMoneyReadBudget(totalTimeoutMillis = 100)
        budget.bind(1, 0, 10)
        val pending = requireNotNull(budget.request(11, 1))
        budget.complete(pending, "6,685", 111)
        assertTrue(budget.snapshot(111).exhausted)
        assertNull(budget.snapshot(111).coins)
        assertNull(budget.request(112, 2))
    }
}
