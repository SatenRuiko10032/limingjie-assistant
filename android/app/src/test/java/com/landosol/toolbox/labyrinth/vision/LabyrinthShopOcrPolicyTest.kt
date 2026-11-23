package com.landosol.toolbox.labyrinth.vision

import org.junit.Assert.*
import org.junit.Test

/** Exercises the production pure admission policy and actual stage dispatcher, not Android adapters. */
class LabyrinthShopOcrPolicyTest {
    private data class Applied(val result: String, val calls: List<String>, val inputs: List<String>)

    private fun apply(plan: LabyrinthShopOcrPlan): Applied {
        val calls = mutableListOf<String>()
        val inputs = mutableListOf<String>()
        fun stage(name: String): (String) -> String = { input ->
            calls += name
            inputs += input
            "$input/$name"
        }
        val result = plan.apply(
            initial = "seed",
            readTitles = stage("titles"),
            readWallet = stage("wallet"),
            readDialog = stage("dialog"),
        )
        return Applied(result, calls.toList(), inputs.toList())
    }

    @Test fun admissionRequiresStrictlyNewCaptureAndExactlyTheCurrentEvidenceVersion() {
        for (phase in LabyrinthShopOcrPhase.entries) {
            val policy = LabyrinthShopOcrPolicy(evidenceVersion = 7L, capturedAfter = 1_000L, phase = phase)
            for (capturedAt in listOf(Long.MIN_VALUE, 999L, 1_000L)) {
                assertFalse("phase=$phase capture=$capturedAt", policy.accepts(capturedAt, 7L))
            }
            assertTrue(policy.accepts(1_001L, 7L))
            assertTrue(policy.accepts(Long.MAX_VALUE, 7L))
            assertFalse("old evidence version is not current", policy.accepts(1_001L, 6L))
            assertFalse("a different version is not silently adopted", policy.accepts(1_001L, 8L))
        }
        assertFalse(LabyrinthShopOcrPolicy().accepts(Long.MIN_VALUE, 0L))
        assertTrue(LabyrinthShopOcrPolicy().accepts(0L, 0L))
    }

    private data class Route(val phase: LabyrinthShopOcrPhase, val modal: Boolean, val calls: List<String>)

    @Test fun phaseModalAndConnectingRouteOnlyTheAllowedOcrStages() {
        val routes = listOf(
            Route(LabyrinthShopOcrPhase.STOCK, false, listOf("titles", "wallet")),
            Route(LabyrinthShopOcrPhase.STOCK, true, listOf("titles", "dialog")),
            Route(LabyrinthShopOcrPhase.REFRESH_DIALOG, false, listOf("wallet")),
            Route(LabyrinthShopOcrPhase.REFRESH_DIALOG, true, listOf("dialog")),
            Route(LabyrinthShopOcrPhase.REFRESH_RECEIPT, false, listOf("wallet")),
            Route(LabyrinthShopOcrPhase.REFRESH_RECEIPT, true, emptyList()),
        )
        for (route in routes) for (connecting in listOf(false, true)) {
            val policy = LabyrinthShopOcrPolicy(7L, 1_000L, route.phase)
            val plan = policy.plan(1_001L, 7L, connecting, route.modal)
            val expectedCalls = if (connecting) emptyList() else route.calls
            assertEquals("route=$route connecting=$connecting", LabyrinthShopOcrPlan(
                titles = "titles" in expectedCalls,
                wallet = "wallet" in expectedCalls,
                dialog = "dialog" in expectedCalls,
            ), plan)
            val applied = apply(plan)
            assertEquals("each allowed stage executes exactly once", expectedCalls, applied.calls)
            assertEquals(expectedCalls.fold("seed") { result, name -> "$result/$name" }, applied.result)
        }
    }

    private data class Rejected(val capturedAt: Long, val expectedVersion: Long, val connecting: Boolean)

    @Test fun staleVersionCutoffAndConnectingPlansExecuteNoCallbacksInAnyPhaseOrModalShape() {
        val rejected = listOf(
            Rejected(999L, 7L, false),
            Rejected(1_000L, 7L, false),
            Rejected(1_001L, 6L, false),
            Rejected(1_001L, 8L, false),
            Rejected(1_001L, 7L, true),
        )
        for (phase in LabyrinthShopOcrPhase.entries) for (modal in listOf(false, true)) for (case in rejected) {
            val policy = LabyrinthShopOcrPolicy(7L, 1_000L, phase)
            val plan = policy.plan(case.capturedAt, case.expectedVersion, case.connecting, modal)
            assertEquals("phase=$phase modal=$modal case=$case", LabyrinthShopOcrPlan(), plan)
            val applied = apply(plan)
            assertTrue("no OCR stage may run for rejected input", applied.calls.isEmpty())
            assertTrue(applied.inputs.isEmpty())
            assertEquals("seed", applied.result)
        }
    }

    @Test fun applyPropagatesEachStageResultInTitlesWalletDialogOrderForEveryStageSubset() {
        for (titles in listOf(false, true)) for (wallet in listOf(false, true)) for (dialog in listOf(false, true)) {
            val plan = LabyrinthShopOcrPlan(titles, wallet, dialog)
            val expectedCalls = listOfNotNull(
                if (titles) "titles" else null,
                if (wallet) "wallet" else null,
                if (dialog) "dialog" else null,
            )
            val applied = apply(plan)
            assertEquals("plan=$plan", expectedCalls, applied.calls)
            val expectedInputs = expectedCalls.indices.map { index ->
                expectedCalls.take(index).fold("seed") { result, name -> "$result/$name" }
            }
            assertEquals("each callback receives the previous stage result", expectedInputs, applied.inputs)
            assertEquals(expectedCalls.fold("seed") { result, name -> "$result/$name" }, applied.result)
        }
    }
}
