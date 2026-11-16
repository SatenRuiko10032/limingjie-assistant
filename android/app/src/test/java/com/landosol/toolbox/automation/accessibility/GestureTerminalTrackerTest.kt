package com.landosol.toolbox.automation.accessibility

import org.junit.Assert.*
import org.junit.Test

/** Pure ownership/protocol tests. These do NOT prove Android delivery or a game-level ACTION_UP. */
class GestureTerminalTrackerTest {
    private fun queue(
        tracker: GestureTerminalTracker,
        holdMillis: Long = 0L,
        owner: GestureOwnership = GestureOwnership(11L, 3L, 2),
    ): GestureActionRecord = requireNotNull(tracker.tryQueue(owner, GestureDispatchProtocol(400L, holdMillis)))

    private fun submit(
        tracker: GestureTerminalTracker,
        record: GestureActionRecord,
        phase: GestureSegmentPhase = GestureSegmentPhase.MAIN,
        nowMillis: Long = 100L,
    ): GestureSubmission = requireNotNull(tracker.beginSubmission(record, phase, nowMillis)).also {
        tracker.submissionAccepted(record, it)
    }

    private fun callback(
        tracker: GestureTerminalTracker,
        record: GestureActionRecord,
        submission: GestureSubmission,
        completed: Boolean = true,
    ) = tracker.frameworkCallback(record, submission, completed, "framework-cancelled")

    private fun assertAllSharedEntrancesBlocked(tracker: GestureTerminalTracker) {
        // The source protocol assertion checks that all of these route to this SAME shared guard.
        for (entrance in listOf("tap", "swipe", "back", "direct", "global")) {
            assertFalse("$entrance must not displace an unresolved pointer", tracker.allowsBusinessInput())
        }
    }

    @Test fun finiteShopStrokeHasNoTailAndHeldMapUsesTwoZeroOriginSingleSegments() {
        val finite = GestureDispatchProtocol(400L, 0L)
        assertFalse(finite.main.willContinue)
        assertFalse(finite.main.continuesPrevious)
        assertNull(finite.release)
        assertNull(finite.cleanup)

        val held = GestureDispatchProtocol(400L, 300L)
        val independentDescriptions = listOf(listOf(held.main), listOf(requireNotNull(held.release)))
        assertEquals(listOf(1, 1), independentDescriptions.map { it.size })
        assertEquals(listOf(0L, 0L), independentDescriptions.map { it.single().startTimeMillis })
        assertEquals(listOf(true, false), independentDescriptions.map { it.single().willContinue })
        assertEquals(listOf(false, true), independentDescriptions.map { it.single().continuesPrevious })
        assertEquals(300L, requireNotNull(held.release).durationMillis)
    }

    @Test fun cancellingQueuedWaitPreventsSubmissionWithoutInventingAnUp() {
        val tracker = GestureTerminalTracker()
        val record = queue(tracker)
        assertEquals(GestureActionState.QUEUED, tracker.snapshot(record).state)
        assertEquals(GestureTrackerCommand.TERMINAL, tracker.abandonWait(record))
        assertEquals(GestureTerminalOutcome.NOT_SUBMITTED, tracker.snapshot(record).terminalOutcome)
        assertNull(tracker.beginSubmission(record, GestureSegmentPhase.MAIN, 100L))
        assertTrue(tracker.allowsBusinessInput())
    }

    @Test fun cancelStopOrTimeoutOfSubmittedWaitKeepsGuardUntilRealFiniteFrameworkCallback() {
        for (completed in listOf(true, false)) {
            val tracker = GestureTerminalTracker()
            val record = queue(tracker)
            val main = submit(tracker, record)
            assertEquals(GestureActionState.SUBMITTED, tracker.snapshot(record).state)
            assertEquals(GestureTrackerCommand.NONE, tracker.abandonWait(record))
            assertTrue(tracker.snapshot(record).waiterDetached)
            assertEquals(GestureActionState.DRAINING, tracker.snapshot(record).state)
            assertAllSharedEntrancesBlocked(tracker)
            assertNull(tracker.tryQueue(GestureOwnership(12L, 4L, 0), GestureDispatchProtocol(60L, 0L)))
            assertEquals(GestureTrackerCommand.TERMINAL, callback(tracker, record, main, completed))
            assertEquals(if (completed) GestureTerminalOutcome.COMPLETED else GestureTerminalOutcome.CANCELLED,
                tracker.snapshot(record).terminalOutcome)
            assertTrue(tracker.allowsBusinessInput())
        }
    }

    @Test fun heldMainCompletionAfterWaitCancellationStillRequiresASeparateFiniteRelease() {
        val tracker = GestureTerminalTracker()
        val record = queue(tracker, holdMillis = 300L)
        val main = submit(tracker, record)
        tracker.abandonWait(record) // A business pause/stale permit must NOT govern the required tail.
        assertEquals(GestureTrackerCommand.RELEASE, callback(tracker, record, main))
        assertEquals(GestureActionState.DRAINING, tracker.snapshot(record).state)
        assertNull(tracker.snapshot(record).terminalOutcome)
        assertAllSharedEntrancesBlocked(tracker)
        val release = submit(tracker, record, GestureSegmentPhase.RELEASE, 500L)
        assertEquals(0L, release.segment.startTimeMillis)
        assertFalse(release.segment.willContinue)
        assertTrue(release.segment.continuesPrevious)
        assertEquals(record.owner, tracker.blockingSnapshot()?.owner)
        assertEquals(GestureTrackerCommand.TERMINAL, callback(tracker, record, release))
        assertEquals(GestureTerminalOutcome.COMPLETED, tracker.snapshot(record).terminalOutcome)
        assertTrue(tracker.allowsBusinessInput())
    }

    @Test fun mainOrTailFailureHasOneBoundedContinuationCleanupAndNeverReportsOverallCompleted() {
        for (failure in listOf("main-cancel", "main-timeout", "main-exception", "tail-refusal", "tail-cancel", "tail-timeout")) {
            val tracker = GestureTerminalTracker()
            val record = queue(tracker, holdMillis = 300L)
            val main = submit(tracker, record)
            val command = when (failure) {
                "main-cancel" -> callback(tracker, record, main, completed = false)
                "main-timeout" -> tracker.deadlineExpired(record, main, main.deadlineMillis, "main-timeout")
                "main-exception" -> tracker.submissionFailed(record, main, "IPC-uncertain", uncertain = true)
                else -> {
                    assertEquals(GestureTrackerCommand.RELEASE, callback(tracker, record, main))
                    val tail = requireNotNull(tracker.beginSubmission(record, GestureSegmentPhase.RELEASE, 500L))
                    if (failure == "tail-refusal") tracker.submissionFailed(record, tail, "tail-refused")
                    else {
                        tracker.submissionAccepted(record, tail)
                        if (failure == "tail-cancel") callback(tracker, record, tail, completed = false)
                        else tracker.deadlineExpired(record, tail, tail.deadlineMillis, "tail-timeout")
                    }
                }
            }
            assertEquals(failure, GestureTrackerCommand.CLEANUP, command)
            assertAllSharedEntrancesBlocked(tracker)
            val cleanup = submit(tracker, record, GestureSegmentPhase.CLEANUP, 3_000L)
            assertEquals(0L, cleanup.segment.startTimeMillis)
            assertEquals(60L, cleanup.segment.durationMillis)
            assertFalse(cleanup.segment.willContinue)
            assertTrue(cleanup.segment.continuesPrevious)
            assertEquals(GestureActionState.DRAINING, tracker.snapshot(record).state)
            assertTrue(tracker.snapshot(record).cleanupAttempted)
            assertNull(tracker.beginSubmission(record, GestureSegmentPhase.CLEANUP, 3_001L))
            assertEquals(GestureTrackerCommand.TERMINAL, callback(tracker, record, cleanup))
            assertEquals(GestureTerminalOutcome.RELEASED_AFTER_FAILURE, tracker.snapshot(record).terminalOutcome)
            assertNotNull(tracker.snapshot(record).reason)
            assertTrue(tracker.allowsBusinessInput())
        }
    }

    @Test fun failedCleanupStaysUnknownAndNewSessionDisplayOrGenerationCannotClearIt() {
        for (failure in listOf("refusal", "cancel", "missing-callback")) {
            val tracker = GestureTerminalTracker()
            val record = queue(tracker, holdMillis = 300L)
            val main = submit(tracker, record)
            assertEquals(GestureTrackerCommand.CLEANUP, callback(tracker, record, main, completed = false))
            val cleanup = requireNotNull(tracker.beginSubmission(record, GestureSegmentPhase.CLEANUP, 500L))
            val command = if (failure == "refusal") tracker.submissionFailed(record, cleanup, "cleanup-refused")
                else {
                    tracker.submissionAccepted(record, cleanup)
                    if (failure == "cancel") callback(tracker, record, cleanup, completed = false)
                    else tracker.deadlineExpired(record, cleanup, cleanup.deadlineMillis, "cleanup-timeout")
                }
            assertEquals(GestureTrackerCommand.UNKNOWN, command)
            assertEquals(GestureActionState.UNKNOWN, tracker.snapshot(record).state)
            assertNotNull(tracker.snapshot(record).unknownReason)
            assertAllSharedEntrancesBlocked(tracker)
            assertNull(tracker.tryQueue(GestureOwnership(99L, 4L, 0), GestureDispatchProtocol(60L, 0L)))
            assertEquals(GestureTrackerCommand.NONE, tracker.serviceDisconnected(record, 4L, "new-generation"))
            assertEquals(GestureTrackerCommand.NONE, callback(tracker, record, main))
            assertEquals(record.owner, tracker.blockingSnapshot()?.owner)
            assertNull(tracker.beginSubmission(record, GestureSegmentPhase.CLEANUP, 8_000L))
        }
    }

    @Test fun finiteDrainDeadlineNeedsAuthenticMatchingCallbackAndOldCallbackCannotClearNextOwner() {
        val tracker = GestureTerminalTracker()
        val record = queue(tracker)
        val main = submit(tracker, record)
        tracker.abandonWait(record)
        assertEquals(GestureTrackerCommand.NONE, tracker.deadlineExpired(record, main, main.deadlineMillis - 1L, "too-early"))
        assertEquals(GestureTrackerCommand.UNKNOWN, tracker.deadlineExpired(record, main, main.deadlineMillis, "finite-timeout"))
        assertAllSharedEntrancesBlocked(tracker)
        val forgedToken = GestureSubmission(main.number, main.segment, main.submittedAtMillis, main.deadlineMillis)
        val forgedRecord = GestureActionRecord(record.owner, record.protocol)
        assertEquals(GestureTrackerCommand.NONE, callback(tracker, record, forgedToken))
        assertEquals(GestureTrackerCommand.NONE, callback(tracker, forgedRecord, main))
        assertAllSharedEntrancesBlocked(tracker)
        assertEquals(GestureTrackerCommand.TERMINAL, callback(tracker, record, main))
        val next = queue(tracker, owner = GestureOwnership(12L, 4L, 0))
        submit(tracker, next)
        assertEquals(GestureTrackerCommand.NONE, callback(tracker, record, main))
        assertEquals(next.owner, tracker.blockingSnapshot()?.owner)
        assertAllSharedEntrancesBlocked(tracker)
    }

    @Test fun disconnectRetainsOldHeldOwnerAndMainCallbackCannotReleaseThroughNewServiceGeneration() {
        val tracker = GestureTerminalTracker()
        val record = queue(tracker, holdMillis = 300L)
        val main = submit(tracker, record)
        assertEquals(GestureTrackerCommand.UNKNOWN, tracker.serviceDisconnected(record, 3L, "disconnected"))
        assertEquals(GestureTrackerCommand.NONE, callback(tracker, record, main))
        assertNull(tracker.beginSubmission(record, GestureSegmentPhase.RELEASE, 500L))
        assertNull(tracker.tryQueue(GestureOwnership(12L, 4L, 0), GestureDispatchProtocol(60L, 0L)))
        assertEquals(record.owner, tracker.blockingSnapshot()?.owner)
        assertAllSharedEntrancesBlocked(tracker)

        val queuedTracker = GestureTerminalTracker()
        val queued = queue(queuedTracker)
        assertEquals(GestureTrackerCommand.TERMINAL, queuedTracker.serviceDisconnected(queued, 3L, "not-submitted"))
        assertEquals(GestureTerminalOutcome.NOT_SUBMITTED, queuedTracker.snapshot(queued).terminalOutcome)
        assertTrue(queuedTracker.allowsBusinessInput())
    }

    @Test fun olderTailCompletionCannotUnblockNewerAcceptedFiniteCleanup() {
        val tracker = GestureTerminalTracker()
        val record = queue(tracker, holdMillis = 300L)
        val main = submit(tracker, record)
        assertEquals(GestureTrackerCommand.RELEASE, callback(tracker, record, main))
        val tail = submit(tracker, record, GestureSegmentPhase.RELEASE, 500L)
        assertEquals(GestureTrackerCommand.CLEANUP, tracker.deadlineExpired(record, tail, tail.deadlineMillis, "tail-timeout"))
        val cleanup = submit(tracker, record, GestureSegmentPhase.CLEANUP, tail.deadlineMillis)
        assertEquals(GestureTrackerCommand.NONE, callback(tracker, record, tail))
        assertAllSharedEntrancesBlocked(tracker)
        assertEquals(GestureTrackerCommand.TERMINAL, callback(tracker, record, cleanup))
        assertEquals(GestureTerminalOutcome.RELEASED_AFTER_FAILURE, tracker.snapshot(record).terminalOutcome)
        assertTrue(tracker.allowsBusinessInput())
    }
}
