package com.landosol.toolbox.automation.accessibility

/** Framework-terminal evidence, not an acknowledgement that the game received ACTION_UP. */
internal enum class GestureActionState { QUEUED, SUBMITTED, DRAINING, TERMINAL, UNKNOWN }
internal enum class GestureSegmentPhase { MAIN, RELEASE, CLEANUP }
internal enum class GestureTerminalOutcome { NOT_SUBMITTED, COMPLETED, CANCELLED, RELEASED_AFTER_FAILURE }
internal enum class GestureTrackerCommand { NONE, RELEASE, CLEANUP, TERMINAL, UNKNOWN }

internal data class GestureOwnership(val actionId: Long, val serviceGeneration: Long, val displayId: Int)

internal data class GestureSegmentSpec(
    val phase: GestureSegmentPhase,
    val durationMillis: Long,
    val willContinue: Boolean,
    val continuesPrevious: Boolean,
    // Every segment is dispatched in its OWN GestureDescription, so its clock starts at zero.
    val startTimeMillis: Long = 0L,
)

/** Pure description protocol. Android paths/builders and caller continuations do not belong here. */
internal class GestureDispatchProtocol(
    durationMillis: Long,
    holdMillis: Long,
    val callbackGraceMillis: Long = 2_000L,
    cleanupDurationMillis: Long = 60L,
) {
    init {
        require(durationMillis > 0L && holdMillis >= 0L)
        require(callbackGraceMillis > 0L && cleanupDurationMillis > 0L)
    }

    val main = GestureSegmentSpec(GestureSegmentPhase.MAIN, durationMillis, holdMillis > 0L, false)
    val release = if (holdMillis > 0L) {
        GestureSegmentSpec(GestureSegmentPhase.RELEASE, holdMillis, false, true)
    } else null
    val cleanup = if (holdMillis > 0L) {
        GestureSegmentSpec(GestureSegmentPhase.CLEANUP, cleanupDurationMillis, false, true)
    } else null
}

internal class GestureSubmission internal constructor(
    val number: Int,
    val segment: GestureSegmentSpec,
    val submittedAtMillis: Long,
    val deadlineMillis: Long,
) {
    internal enum class Acceptance { DISPATCHING, ACCEPTED, REJECTED, UNCERTAIN }
    internal var acceptance = Acceptance.DISPATCHING
}

/** A bounded, scalar-only record may survive an Android 9 callback leak or a new service generation. */
internal class GestureActionRecord internal constructor(
    val owner: GestureOwnership,
    val protocol: GestureDispatchProtocol,
) {
    internal var state = GestureActionState.QUEUED
    internal var terminalOutcome: GestureTerminalOutcome? = null
    internal var reason: String? = null
    internal var unknownReason: String? = null
    internal var waiterDetached = false
    internal var expectedPhase: GestureSegmentPhase? = GestureSegmentPhase.MAIN
    internal var cleanupAttempted = false
    internal var currentSubmission: GestureSubmission? = null
    internal val submissions = ArrayList<GestureSubmission>(3)
}

internal data class GestureActionSnapshot(
    val owner: GestureOwnership,
    val state: GestureActionState,
    val terminalOutcome: GestureTerminalOutcome?,
    val reason: String?,
    val unknownReason: String?,
    val waiterDetached: Boolean,
    val segment: GestureSegmentPhase?,
    val deadlineMillis: Long?,
    val cleanupAttempted: Boolean,
)

/**
 * Shared input ownership is independent of the coroutine waiting for a result.
 * Cancellation only detaches that waiter. A submitted action continues to own ALL input entrances
 * until a finite final stroke really calls back, or remains UNKNOWN if that evidence is absent.
 * There is deliberately no reset-on-session/start/reconnect API.
 */
internal class GestureTerminalTracker {
    private var owned: GestureActionRecord? = null

    @Synchronized
    fun tryQueue(owner: GestureOwnership, protocol: GestureDispatchProtocol): GestureActionRecord? {
        if (owned != null) return null
        return GestureActionRecord(owner, protocol).also { owned = it }
    }

    @Synchronized
    fun allowsBusinessInput(): Boolean = owned == null

    @Synchronized
    fun blockingSnapshot(): GestureActionSnapshot? = owned?.let(::snapshotOf)

    @Synchronized
    fun snapshot(record: GestureActionRecord): GestureActionSnapshot = snapshotOf(record)

    @Synchronized
    fun beginSubmission(
        record: GestureActionRecord,
        phase: GestureSegmentPhase,
        nowMillis: Long,
    ): GestureSubmission? {
        if (owned !== record || record.state == GestureActionState.UNKNOWN ||
            record.state == GestureActionState.TERMINAL || record.expectedPhase != phase
        ) return null
        val segment = when (phase) {
            GestureSegmentPhase.MAIN -> record.protocol.main
            GestureSegmentPhase.RELEASE -> record.protocol.release
            GestureSegmentPhase.CLEANUP -> record.protocol.cleanup?.takeUnless { record.cleanupAttempted }
        } ?: return null
        if (phase == GestureSegmentPhase.CLEANUP) record.cleanupAttempted = true
        record.expectedPhase = null
        record.state = if (record.waiterDetached || record.reason != null || phase == GestureSegmentPhase.CLEANUP) {
            GestureActionState.DRAINING
        } else GestureActionState.SUBMITTED
        return GestureSubmission(
            record.submissions.size + 1,
            segment,
            nowMillis,
            nowMillis + segment.durationMillis + record.protocol.callbackGraceMillis,
        ).also {
            record.currentSubmission = it
            record.submissions.add(it)
        }
    }

    @Synchronized
    fun submissionAccepted(record: GestureActionRecord, submission: GestureSubmission) {
        if (owned === record && record.submissions.any { it === submission }) {
            submission.acceptance = GestureSubmission.Acceptance.ACCEPTED
        }
    }

    @Synchronized
    fun rejectBeforeSubmission(record: GestureActionRecord, reason: String): GestureTrackerCommand =
        if (owned === record && record.submissions.isEmpty()) {
            terminal(record, GestureTerminalOutcome.NOT_SUBMITTED, reason)
        } else GestureTrackerCommand.NONE

    @Synchronized
    fun abandonWait(record: GestureActionRecord): GestureTrackerCommand {
        if (owned !== record) return GestureTrackerCommand.NONE
        record.waiterDetached = true
        if (record.submissions.isEmpty()) {
            return terminal(record, GestureTerminalOutcome.NOT_SUBMITTED, "等待取消，尚未派发")
        }
        if (record.state == GestureActionState.SUBMITTED) record.state = GestureActionState.DRAINING
        return GestureTrackerCommand.NONE
    }

    @Synchronized
    fun submissionFailed(
        record: GestureActionRecord,
        submission: GestureSubmission,
        reason: String,
        uncertain: Boolean = false,
    ): GestureTrackerCommand {
        if (owned !== record || record.submissions.none { it === submission }) return GestureTrackerCommand.NONE
        submission.acceptance = if (uncertain) GestureSubmission.Acceptance.UNCERTAIN
            else GestureSubmission.Acceptance.REJECTED
        if (record.state == GestureActionState.UNKNOWN) return GestureTrackerCommand.UNKNOWN
        return when (submission.segment.phase) {
            GestureSegmentPhase.MAIN -> if (!uncertain) {
                // dispatchGesture(false) is a refusal BEFORE framework submission, not a game UP.
                terminal(record, GestureTerminalOutcome.NOT_SUBMITTED, reason)
            } else requestCleanup(record, reason)
            GestureSegmentPhase.RELEASE -> requestCleanup(record, reason)
            GestureSegmentPhase.CLEANUP -> unknown(record, reason)
        }
    }

    @Synchronized
    fun frameworkCallback(
        record: GestureActionRecord,
        submission: GestureSubmission,
        completed: Boolean,
        cancelledReason: String,
    ): GestureTrackerCommand {
        if (owned !== record || record.submissions.none { it === submission } ||
            submission.acceptance == GestureSubmission.Acceptance.REJECTED ||
            latestPotentialSubmission(record) !== submission
        ) return GestureTrackerCommand.NONE
        // A real callback is submission evidence even if the native call threw after IPC.
        submission.acceptance = GestureSubmission.Acceptance.ACCEPTED
        if (completed) {
            if (submission.segment.willContinue) {
                // The main callback does NOT lift a continuing pointer. Never report Completed here.
                if (record.state == GestureActionState.UNKNOWN || record.cleanupAttempted) return GestureTrackerCommand.NONE
                record.expectedPhase = GestureSegmentPhase.RELEASE
                return GestureTrackerCommand.RELEASE
            }
            return terminal(
                record,
                if (record.reason == null) GestureTerminalOutcome.COMPLETED
                else GestureTerminalOutcome.RELEASED_AFTER_FAILURE,
                record.reason,
            )
        }
        return when (submission.segment.phase) {
            GestureSegmentPhase.MAIN -> if (!submission.segment.willContinue) {
                terminal(record, GestureTerminalOutcome.CANCELLED, cancelledReason)
            } else requestCleanup(record, cancelledReason)
            GestureSegmentPhase.RELEASE -> requestCleanup(record, cancelledReason)
            GestureSegmentPhase.CLEANUP -> unknown(record, cancelledReason)
        }
    }

    @Synchronized
    fun deadlineExpired(
        record: GestureActionRecord,
        submission: GestureSubmission,
        nowMillis: Long,
        reason: String,
    ): GestureTrackerCommand {
        if (owned !== record || record.state == GestureActionState.UNKNOWN ||
            record.currentSubmission !== submission || nowMillis < submission.deadlineMillis ||
            submission.acceptance == GestureSubmission.Acceptance.REJECTED
        ) return GestureTrackerCommand.NONE
        return if (submission.segment.phase == GestureSegmentPhase.CLEANUP ||
            (submission.segment.phase == GestureSegmentPhase.MAIN && !submission.segment.willContinue)
        ) unknown(record, reason) else requestCleanup(record, reason)
    }

    @Synchronized
    fun cannotRelease(record: GestureActionRecord, reason: String): GestureTrackerCommand =
        if (owned === record) unknown(record, reason) else GestureTrackerCommand.NONE

    @Synchronized
    fun serviceDisconnected(record: GestureActionRecord, generation: Long, reason: String): GestureTrackerCommand {
        if (owned !== record || record.owner.serviceGeneration != generation) return GestureTrackerCommand.NONE
        return if (record.submissions.isEmpty()) {
            terminal(record, GestureTerminalOutcome.NOT_SUBMITTED, reason)
        } else unknown(record, reason)
    }

    private fun requestCleanup(record: GestureActionRecord, reason: String): GestureTrackerCommand {
        if (record.state == GestureActionState.UNKNOWN) return GestureTrackerCommand.UNKNOWN
        if (record.reason == null) record.reason = reason
        if (record.protocol.cleanup == null || record.cleanupAttempted) return unknown(record, reason)
        record.state = GestureActionState.DRAINING
        record.expectedPhase = GestureSegmentPhase.CLEANUP
        return GestureTrackerCommand.CLEANUP
    }

    private fun unknown(record: GestureActionRecord, reason: String): GestureTrackerCommand {
        if (record.reason == null) record.reason = reason
        if (record.unknownReason == null) record.unknownReason = reason
        record.state = GestureActionState.UNKNOWN
        record.expectedPhase = null
        // Retain ownership, including its old generation/display. Only authentic final evidence can resolve it.
        return GestureTrackerCommand.UNKNOWN
    }

    private fun terminal(
        record: GestureActionRecord,
        outcome: GestureTerminalOutcome,
        reason: String?,
    ): GestureTrackerCommand {
        record.state = GestureActionState.TERMINAL
        record.terminalOutcome = outcome
        record.reason = reason
        record.expectedPhase = null
        owned = null
        return GestureTrackerCommand.TERMINAL
    }

    private fun latestPotentialSubmission(record: GestureActionRecord): GestureSubmission? =
        record.submissions.lastOrNull { it.acceptance != GestureSubmission.Acceptance.REJECTED }

    private fun snapshotOf(record: GestureActionRecord) = GestureActionSnapshot(
        owner = record.owner,
        state = record.state,
        terminalOutcome = record.terminalOutcome,
        reason = record.reason,
        unknownReason = record.unknownReason,
        waiterDetached = record.waiterDetached,
        segment = record.currentSubmission?.segment?.phase,
        deadlineMillis = record.currentSubmission?.deadlineMillis,
        cleanupAttempted = record.cleanupAttempted,
    )
}
