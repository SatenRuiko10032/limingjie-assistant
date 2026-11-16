package com.landosol.toolbox.automation.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import com.landosol.toolbox.automation.AUTOMATION_PRE_DISPATCH_SUPPRESSED_REASON
import com.landosol.toolbox.automation.AutomationAction
import com.landosol.toolbox.automation.AutomationActionBackend
import com.landosol.toolbox.automation.AutomationBackendResult
import com.landosol.toolbox.automation.capture.CaptureStateRegistry
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

const val GAME_NOT_FOREGROUND_REASON = "游戏不在前台"

/**
 * TYPE_APPLICATION_OVERLAY 会以宿主包名和 android.view.View 上报窗口事件。
 * 它不是前台 Activity，不能覆盖真正的前台游戏包名。
 */
internal fun shouldTrackForegroundWindow(
    servicePackageName: String,
    eventPackageName: String?,
    eventClassName: String?,
): Boolean {
    if (eventPackageName.isNullOrBlank()) return false
    if (eventPackageName != servicePackageName) return true
    return eventClassName?.startsWith("$servicePackageName.") == true
}

internal fun shouldRefreshForegroundWindow(eventType: Int): Boolean =
    eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
        eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED

object AccessibilityConnectionRegistry {
    private val connected = MutableStateFlow(false)

    fun observe(): StateFlow<Boolean> = connected.asStateFlow()

    fun isConnected(): Boolean = connected.value

    internal fun update(value: Boolean) {
        connected.value = value
    }
}

class LandosolAccessibilityService : AccessibilityService() {
    @Volatile
    private var serviceGeneration = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        // Reconnection never erases an older unresolved pointer ownership.
        activeGesture.get()?.let { retainGestureOnDisconnect(it.tracked.owner.serviceGeneration) }
        serviceGeneration = serviceGenerationSequence.incrementAndGet()
        activeService = this
        AccessibilityConnectionRegistry.update(true)
        Log.i(GESTURE_LOG_TAG, "accessibility-connected uptimeMillis=${SystemClock.elapsedRealtime()} sdk=${Build.VERSION.SDK_INT}")
        refreshForegroundPackageFromRoot()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (shouldRefreshForegroundWindow(event.eventType)) {
            val eventPackageName = event.packageName?.toString()
            val eventClassName = event.className?.toString()
            if (
                shouldTrackForegroundWindow(
                    servicePackageName = packageName,
                    eventPackageName = eventPackageName,
                    eventClassName = eventClassName,
                )
            ) {
                foregroundPackageName = eventPackageName
                // 只有窗口状态变化事件携带 Activity 类名；内容变化事件给的是 View 类名，
                // 拿它覆盖会把 MainActivity 冲掉，导致启动闸门误判。
                if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                    foregroundActivityName = eventClassName
                }
            }
        }
    }

    private fun refreshForegroundPackageFromRoot(): String? {
        val packageFromRoot = runCatching {
            rootInActiveWindow?.packageName?.toString()
        }.getOrNull()?.takeIf(String::isNotBlank)
        if (packageFromRoot != null) foregroundPackageName = packageFromRoot
        return packageFromRoot
    }

    override fun onInterrupt() {
        Log.w(GESTURE_LOG_TAG, "accessibility-interrupted connected=${isConnected()} uptimeMillis=${SystemClock.elapsedRealtime()}")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        Log.w(GESTURE_LOG_TAG, "accessibility-unbound uptimeMillis=${SystemClock.elapsedRealtime()}")
        retainGestureOnDisconnect(serviceGeneration)
        if (activeService === this) {
            activeService = null
            foregroundPackageName = null
            foregroundActivityName = null
            AccessibilityConnectionRegistry.update(false)
        }
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        Log.w(GESTURE_LOG_TAG, "accessibility-destroyed uptimeMillis=${SystemClock.elapsedRealtime()}")
        retainGestureOnDisconnect(serviceGeneration)
        if (activeService === this) {
            activeService = null
            foregroundPackageName = null
            foregroundActivityName = null
            AccessibilityConnectionRegistry.update(false)
        }
        super.onDestroy()
    }

    internal suspend fun perform(
        action: AutomationAction,
        gestureDisplayId: Int = Display.DEFAULT_DISPLAY,
        permit: (() -> Boolean)? = null,
    ): AutomationBackendResult = when (action) {
        is AutomationAction.Tap -> {
            Log.i(GESTURE_LOG_TAG, "准备点击 display=$gestureDisplayId x=${action.point.x} y=${action.point.y}")
            dispatchGesture(
                path = Path().apply { moveTo(action.point.x, action.point.y) },
                durationMillis = TAP_DURATION_MILLIS,
                displayId = gestureDisplayId,
                description = "tap x=${action.point.x} y=${action.point.y}",
                permit = permit,
            )
        }
        is AutomationAction.Swipe -> {
            val hold = action.holdMillis.coerceAtLeast(0L)
            if (action.durationMillis !in 1..MAX_GESTURE_DURATION_MILLIS ||
                hold > MAX_GESTURE_DURATION_MILLIS - action.durationMillis
            ) {
                AutomationBackendResult.Rejected("滑动时长超出范围")
            } else {
                dispatchGesture(
                    path = Path().apply {
                        moveTo(action.start.x, action.start.y)
                        lineTo(action.end.x, action.end.y)
                    },
                    durationMillis = action.durationMillis,
                    displayId = gestureDisplayId,
                    holdMillis = hold,
                    holdX = action.end.x,
                    holdY = action.end.y,
                    description = "swipe from=${action.start.x},${action.start.y} to=${action.end.x},${action.end.y}",
                    permit = permit,
                )
            }
        }
        AutomationAction.Back -> performGlobalBack(permit)
    }

    private suspend fun performGlobalBack(
        permit: (() -> Boolean)? = null,
        queuedGeneration: Long = serviceGeneration,
    ): AutomationBackendResult = onMainThread {
        if (permit?.invoke() == false) {
            AutomationBackendResult.Rejected(AUTOMATION_PRE_DISPATCH_SUPPRESSED_REASON)
        } else if (!canDispatchBusinessInput("back", queuedGeneration)) {
            AutomationBackendResult.Rejected(inputBlockedReason())
        } else if (performGlobalAction(GLOBAL_ACTION_BACK)) {
            AutomationBackendResult.Completed
        } else {
            AutomationBackendResult.Rejected("系统拒绝返回操作")
        }
    }

    private suspend fun dispatchGesture(
        path: Path,
        durationMillis: Long,
        displayId: Int,
        holdMillis: Long = 0L,
        holdX: Float = 0f,
        holdY: Float = 0f,
        description: String = "gesture",
        gestureId: Long = gestureSequence.incrementAndGet(),
        queuedAtMillis: Long = SystemClock.elapsedRealtime(),
        permit: (() -> Boolean)? = null,
    ): AutomationBackendResult {
        if (durationMillis !in 1..MAX_GESTURE_DURATION_MILLIS ||
            holdMillis < 0L || holdMillis > MAX_GESTURE_DURATION_MILLIS - durationMillis
        ) return AutomationBackendResult.Rejected("滑动时长超出范围")
        val protocol = GestureDispatchProtocol(durationMillis, holdMillis, GESTURE_CALLBACK_GRACE_MILLIS)
        return withTimeoutOrNull(durationMillis + holdMillis + GESTURE_CALLBACK_GRACE_MILLIS) {
            suspendCancellableCoroutine<AutomationBackendResult> { continuation ->
                val tracked = gestureTerminalTracker.tryQueue(
                    GestureOwnership(gestureId, serviceGeneration, displayId), protocol,
                )
                if (tracked == null) {
                    continuation.resume(AutomationBackendResult.Rejected(inputBlockedReason()))
                    return@suspendCancellableCoroutine
                }
                val record = GestureCallbackRecord(tracked, description, holdX, holdY)
                record.pending.set(continuation)
                activeGesture.set(record)
                val queuedInput = Runnable {
                    if (!continuation.isActive) return@Runnable
                    // Business input is gated inside the Android-main runnable, not only at T2.
                    if (permit?.invoke() == false) {
                        handleTrackerCommand(record, gestureTerminalTracker.rejectBeforeSubmission(
                            tracked, AUTOMATION_PRE_DISPATCH_SUPPRESSED_REASON,
                        ))
                        return@Runnable
                    }
                    if (!isCurrentGeneration(tracked.owner.serviceGeneration)) {
                        handleTrackerCommand(record, gestureTerminalTracker.rejectBeforeSubmission(tracked, "无障碍服务连接已失效"))
                        return@Runnable
                    }
                    val dispatchedAt = SystemClock.elapsedRealtime()
                    Log.i(GESTURE_LOG_TAG, "gesture-dispatch id=$gestureId $description display=$displayId " +
                        "generation=${tracked.owner.serviceGeneration} stage=QUEUED nativeSubmitted=false " +
                        "explicitDisplay=${Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && displayId != Display.DEFAULT_DISPLAY} " +
                        "queueMillis=${dispatchedAt - queuedAtMillis} durationMillis=$durationMillis holdMillis=$holdMillis " +
                        "connected=${isConnected()} foreground=$foregroundPackageName activity=$foregroundActivityName")
                    val gesture = try {
                        val main = protocol.main
                        val mainStroke = GestureDescription.StrokeDescription(
                            path, main.startTimeMillis, main.durationMillis, main.willContinue,
                        )
                        record.native.set(NativeGestureState(mainStroke))
                        // Exactly ONE stroke in this Builder. A continuing tail is another dispatch,
                        // after the main callback, with its own startTime=0 and willContinue=false.
                        buildSingleStrokeGesture(mainStroke, displayId)
                    } catch (error: RuntimeException) {
                        Log.w(GESTURE_LOG_TAG, "gesture-prepare-failed id=$gestureId type=${error.javaClass.simpleName}")
                        handleTrackerCommand(record, gestureTerminalTracker.rejectBeforeSubmission(tracked, "无障碍手势构造失败"))
                        return@Runnable
                    }
                    // A changed business permit may suppress ONLY the unsubmitted main segment.
                    if (permit?.invoke() == false) {
                        handleTrackerCommand(record, gestureTerminalTracker.rejectBeforeSubmission(
                            tracked, AUTOMATION_PRE_DISPATCH_SUPPRESSED_REASON,
                        ))
                        return@Runnable
                    }
                    if (!continuation.isActive) {
                        handleTrackerCommand(record, gestureTerminalTracker.abandonWait(tracked))
                        return@Runnable
                    }
                    if (!isCurrentGeneration(tracked.owner.serviceGeneration)) {
                        handleTrackerCommand(record, gestureTerminalTracker.rejectBeforeSubmission(tracked, "无障碍服务连接已失效"))
                        return@Runnable
                    }
                    val submission = gestureTerminalTracker.beginSubmission(
                        tracked, GestureSegmentPhase.MAIN, SystemClock.elapsedRealtime(),
                    ) ?: return@Runnable
                    try {
                        val accepted = dispatchGesture(
                            gesture, TrackedGestureCallback(record, submission), mainHandler,
                        )
                        if (accepted) {
                            gestureTerminalTracker.submissionAccepted(tracked, submission)
                            Log.i(GESTURE_LOG_TAG, "gesture-native-accepted id=$gestureId segment=MAIN gameUpVerified=false")
                            scheduleTerminalDeadline(record, submission)
                        } else {
                            handleTrackerCommand(record, gestureTerminalTracker.submissionFailed(
                                tracked, submission, "系统拒绝显示器 $displayId 的无障碍手势",
                            ))
                        }
                    } catch (error: RuntimeException) {
                        Log.w(GESTURE_LOG_TAG, "gesture-native-exception id=$gestureId type=${error.javaClass.simpleName}")
                        // An IPC exception cannot prove that nothing reached the framework.
                        handleTrackerCommand(record, gestureTerminalTracker.submissionFailed(
                            tracked, submission, "无障碍手势派发异常，终态未确认", uncertain = true,
                        ))
                    }
                }
                continuation.invokeOnCancellation {
                    // Android 9 retains native callback objects. Clear the caller chain, NOT its
                    // submitted action ownership or the main stroke needed for a natural release.
                    record.pending.set(null)
                    mainHandler.removeCallbacks(queuedInput)
                    val command = gestureTerminalTracker.abandonWait(tracked)
                    Log.i(GESTURE_LOG_TAG, "gesture-wait-detached id=$gestureId state=${gestureTerminalTracker.snapshot(tracked).state} gameUpVerified=false")
                    mainHandler.post { handleTrackerCommand(record, command) }
                }
                if (continuation.isActive && !mainHandler.post(queuedInput)) {
                    handleTrackerCommand(record, gestureTerminalTracker.rejectBeforeSubmission(tracked, "无障碍主线程拒绝手势排队"))
                }
            }
        } ?: run {
            // This is a waiter timeout, not a game UP. The independent deadline observer keeps
            // draining an accepted finite stroke (or releasing a held one) and rejects new input.
            Log.w(GESTURE_LOG_TAG, "gesture-wait-timeout id=$gestureId display=$displayId elapsedMillis=${SystemClock.elapsedRealtime() - queuedAtMillis} " +
                "owner=${gestureTerminalTracker.blockingSnapshot()} gameUpVerified=false")
            AutomationBackendResult.Rejected("显示器 $displayId 的无障碍手势回调超时")
        }
    }

    private suspend fun onMainThread(action: () -> AutomationBackendResult): AutomationBackendResult =
        suspendCancellableCoroutine { continuation ->
            mainHandler.post {
                if (continuation.isActive) continuation.resume(action())
            }
        }

    /** Final-main-thread gate for ACTION_CLICK and other direct input, including a stale service. */
    internal suspend fun performDirectAction(action: () -> Boolean): Boolean {
        val queuedGeneration = serviceGeneration
        return onMainThreadCompat {
            canDispatchBusinessInput("direct", queuedGeneration) && runCatching(action).getOrDefault(false)
        }
    }

    private fun canDispatchBusinessInput(entrance: String, queuedGeneration: Long): Boolean {
        val connected = activeService === this && serviceGeneration == queuedGeneration && isCurrentGeneration(queuedGeneration)
        val allowed = connected && gestureTerminalTracker.allowsBusinessInput()
        if (!allowed) Log.w(GESTURE_LOG_TAG, "input-rejected entrance=$entrance generation=$serviceGeneration " +
            "connected=$connected owner=${gestureTerminalTracker.blockingSnapshot()}")
        return allowed
    }

    private class NativeGestureState(val mainStroke: GestureDescription.StrokeDescription) {
        var watchdog: Runnable? = null
    }

    private class GestureCallbackRecord(
        val tracked: GestureActionRecord,
        val description: String,
        val holdX: Float,
        val holdY: Float,
    ) {
        // The framework callback retains only this clearable slot, never a caller continuation,
        // permit, Session or Frame directly. Android 9 can retain callbacks for the service lifetime.
        val pending = AtomicReference<CancellableContinuation<AutomationBackendResult>?>(null)
        val native = AtomicReference<NativeGestureState?>(null)
    }

    // Not an inner class: no enclosing service/coroutine/permit is captured by Android callbacks.
    private class TrackedGestureCallback(
        private val record: GestureCallbackRecord,
        private val submission: GestureSubmission,
    ) : AccessibilityService.GestureResultCallback() {
        override fun onCompleted(gestureDescription: GestureDescription?) {
            postFrameworkCallback(record, submission, completed = true)
        }

        override fun onCancelled(gestureDescription: GestureDescription?) {
            postFrameworkCallback(record, submission, completed = false)
        }
    }

    companion object {
        const val GLOBAL_ACTION_RECENTS = AccessibilityService.GLOBAL_ACTION_RECENTS
        private const val TAP_DURATION_MILLIS = 60L
        private const val MAX_GESTURE_DURATION_MILLIS = 5_000L
        private const val GESTURE_CALLBACK_GRACE_MILLIS = 2_000L
        private const val GESTURE_LOG_TAG = "LandosolGesture"
        private const val SWIPE_UP_DURATION_MILLIS = 400L
        private const val SWIPE_X = 540f
        private const val SWIPE_Y_START = 900f
        private const val SWIPE_Y_END = 300f
        private val mainHandler = Handler(Looper.getMainLooper())
        private val gestureSequence = AtomicLong(0L)
        private val serviceGenerationSequence = AtomicLong(0L)
        private val gestureTerminalTracker = GestureTerminalTracker()
        // Process/service-generation shared ownership. New sessions and reconnects never reset it.
        private val activeGesture = AtomicReference<GestureCallbackRecord?>(null)

        private fun isCurrentGeneration(generation: Long): Boolean = generation > 0L &&
            activeService?.serviceGeneration == generation && AccessibilityConnectionRegistry.isConnected()

        private fun inputBlockedReason(): String {
            val owner = gestureTerminalTracker.blockingSnapshot() ?: return "无障碍服务未连接或代际已失效"
            return "上一个无障碍手势尚未确认终态 state=${owner.state} id=${owner.owner.actionId} " +
                "generation=${owner.owner.serviceGeneration} display=${owner.owner.displayId}"
        }

        private fun retainGestureOnDisconnect(generation: Long) {
            val record = activeGesture.get() ?: return
            handleTrackerCommand(record, gestureTerminalTracker.serviceDisconnected(
                record.tracked, generation, "无障碍服务断开，无法确认原代际触点终态",
            ))
        }

        private fun buildSingleStrokeGesture(
            stroke: GestureDescription.StrokeDescription,
            displayId: Int,
        ): GestureDescription {
            val builder = GestureDescription.Builder().addStroke(stroke)
            // Default display targeting remains implicit (MuMu may ACK explicit display 0 without
            // delivering to the game). Both independent dispatches use the SAME original display.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && displayId != Display.DEFAULT_DISPLAY) {
                builder.setDisplayId(displayId)
            }
            return builder.build()
        }

        private fun postFrameworkCallback(record: GestureCallbackRecord, submission: GestureSubmission, completed: Boolean) {
            // Posting also keeps a callback from racing native acceptance bookkeeping. This runnable
            // captures only the bounded record/segment, never a caller coroutine, frame or permit.
            mainHandler.post {
                val owner = record.tracked.owner
                val command = gestureTerminalTracker.frameworkCallback(
                    record.tracked, submission, completed,
                    "显示器 ${owner.displayId} 的无障碍手势被系统取消",
                )
                Log.i(GESTURE_LOG_TAG, "gesture-framework-callback id=${owner.actionId} generation=${owner.serviceGeneration} " +
                    "display=${owner.displayId} segment=${submission.segment.phase} completed=$completed " +
                    "callbackMillis=${SystemClock.elapsedRealtime() - submission.submittedAtMillis} " +
                    "finiteFinal=${!submission.segment.willContinue} command=$command gameUpVerified=false")
                handleTrackerCommand(record, command)
            }
        }

        private fun scheduleTerminalDeadline(record: GestureCallbackRecord, submission: GestureSubmission) {
            val native = record.native.get() ?: return
            native.watchdog?.let(mainHandler::removeCallbacks)
            val watchdog = Runnable {
                val command = gestureTerminalTracker.deadlineExpired(
                    record.tracked, submission, SystemClock.elapsedRealtime(),
                    "显示器 ${record.tracked.owner.displayId} 的无障碍手势 ${submission.segment.phase} 终态回调超时",
                )
                Log.w(GESTURE_LOG_TAG, "gesture-drain-deadline id=${record.tracked.owner.actionId} " +
                    "segment=${submission.segment.phase} command=$command gameUpVerified=false")
                handleTrackerCommand(record, command)
            }
            native.watchdog = watchdog
            if (!mainHandler.postDelayed(watchdog, (submission.deadlineMillis - SystemClock.elapsedRealtime()).coerceAtLeast(0L))) {
                handleTrackerCommand(record, gestureTerminalTracker.cannotRelease(record.tracked, "主线程停止，无法观察手势终态"))
            }
        }

        private fun handleTrackerCommand(record: GestureCallbackRecord, command: GestureTrackerCommand) {
            when (command) {
                GestureTrackerCommand.NONE -> Unit
                GestureTrackerCommand.RELEASE -> dispatchContinuation(record, GestureSegmentPhase.RELEASE)
                GestureTrackerCommand.CLEANUP -> dispatchContinuation(record, GestureSegmentPhase.CLEANUP)
                GestureTrackerCommand.TERMINAL, GestureTrackerCommand.UNKNOWN -> settleRecord(record)
            }
        }

        private fun dispatchContinuation(record: GestureCallbackRecord, phase: GestureSegmentPhase) {
            val owner = record.tracked.owner
            val native = record.native.get()
            val service = activeService
            // Required release is not business input: it MUST NOT consult pause/stale permits or
            // allowsBusinessInput(). It may only use its original service generation and stroke.
            if (native == null || service == null || !isCurrentGeneration(owner.serviceGeneration)) {
                handleTrackerCommand(record, gestureTerminalTracker.cannotRelease(record.tracked, "原手势服务或续段来源已失效，无法确认释放"))
                return
            }
            val submission = gestureTerminalTracker.beginSubmission(record.tracked, phase, SystemClock.elapsedRealtime()) ?: return
            val segment = submission.segment
            val gesture = try {
                // Stationary path: starts AND ends at the original main endpoint. No arbitrary tap,
                // global back or new unrelated stroke is ever claimed to force ACTION_UP.
                val tailPath = Path().apply { moveTo(record.holdX, record.holdY) }
                val tailStroke = native.mainStroke.continueStroke(
                    tailPath, segment.startTimeMillis, segment.durationMillis, segment.willContinue,
                )
                buildSingleStrokeGesture(tailStroke, owner.displayId)
            } catch (error: RuntimeException) {
                Log.w(GESTURE_LOG_TAG, "gesture-continuation-prepare-failed id=${owner.actionId} segment=$phase type=${error.javaClass.simpleName}")
                handleTrackerCommand(record, gestureTerminalTracker.submissionFailed(
                    record.tracked, submission, "无障碍手势续段构造失败，释放未确认",
                ))
                return
            }
            Log.i(GESTURE_LOG_TAG, "gesture-release-dispatch id=${owner.actionId} generation=${owner.serviceGeneration} " +
                "display=${owner.displayId} segment=$phase durationMillis=${segment.durationMillis} " +
                "startTimeMillis=${segment.startTimeMillis} willContinue=${segment.willContinue} forcedUpAvailable=false")
            try {
                val accepted = service.dispatchGesture(gesture, TrackedGestureCallback(record, submission), mainHandler)
                if (accepted) {
                    gestureTerminalTracker.submissionAccepted(record.tracked, submission)
                    Log.i(GESTURE_LOG_TAG, "gesture-native-accepted id=${owner.actionId} segment=$phase gameUpVerified=false")
                    scheduleTerminalDeadline(record, submission)
                } else {
                    handleTrackerCommand(record, gestureTerminalTracker.submissionFailed(
                        record.tracked, submission, "系统拒绝显示器 ${owner.displayId} 的无障碍手势续段，释放未确认",
                    ))
                }
            } catch (error: RuntimeException) {
                Log.w(GESTURE_LOG_TAG, "gesture-continuation-native-exception id=${owner.actionId} segment=$phase type=${error.javaClass.simpleName}")
                handleTrackerCommand(record, gestureTerminalTracker.submissionFailed(
                    record.tracked, submission, "无障碍手势续段派发异常，释放未确认", uncertain = true,
                ))
            }
        }

        private fun settleRecord(record: GestureCallbackRecord) {
            val snapshot = gestureTerminalTracker.snapshot(record.tracked)
            if (snapshot.state != GestureActionState.TERMINAL && snapshot.state != GestureActionState.UNKNOWN) return
            record.native.getAndSet(null)?.watchdog?.let(mainHandler::removeCallbacks)
            if (snapshot.state == GestureActionState.TERMINAL) activeGesture.compareAndSet(record, null)
            // UNKNOWN retains scalar ownership but no caller/frame/path/service reference.
            val result = if (snapshot.state == GestureActionState.TERMINAL && snapshot.terminalOutcome == GestureTerminalOutcome.COMPLETED) {
                AutomationBackendResult.Completed
            } else AutomationBackendResult.Rejected(
                if (snapshot.state == GestureActionState.UNKNOWN) "无障碍手势触点终态未知，禁止继续输入：${snapshot.unknownReason ?: snapshot.reason}"
                else snapshot.reason ?: "无障碍手势未完成",
            )
            record.pending.getAndSet(null)?.let { if (it.isActive) it.resume(result) }
            Log.i(GESTURE_LOG_TAG, "gesture-observed-state id=${snapshot.owner.actionId} generation=${snapshot.owner.serviceGeneration} " +
                "display=${snapshot.owner.displayId} state=${snapshot.state} outcome=${snapshot.terminalOutcome} " +
                "reason=${snapshot.reason} unknownReason=${snapshot.unknownReason} gameUpVerified=false forcedUpAvailable=false")
        }

        @Volatile
        private var activeService: LandosolAccessibilityService? = null

        @Volatile
        private var foregroundPackageName: String? = null

        /** 前台 Activity 类名，仅由 TYPE_WINDOW_STATE_CHANGED 更新。 */
        @Volatile
        private var foregroundActivityName: String? = null

        internal fun current(): LandosolAccessibilityService? = activeService
        fun isConnected(): Boolean = activeService != null && AccessibilityConnectionRegistry.isConnected()
        /** Diagnostic sampling must not query or modify the active accessibility window. */
        internal fun observedForegroundPackage(): String? = foregroundPackageName
        internal fun foregroundPackage(): String? {
            activeService?.refreshForegroundPackageFromRoot()
            return foregroundPackageName
        }

        /** 前台 Activity 类名；未观察到窗口状态变化时为 null。 */
        internal fun foregroundActivity(): String? = foregroundActivityName

        /** 通过无障碍全局动作派发（例如最近任务），返回是否被系统接受 */
        suspend fun dispatchGlobalAction(action: Int): Boolean {
            val service = current() ?: return false
            val queuedGeneration = service.serviceGeneration
            return onMainThreadCompat {
                service.canDispatchBusinessInput("global", queuedGeneration) && service.performGlobalAction(action)
            }
        }

        /** 从屏幕上方往下滑（用于划掉最近任务卡片里的游戏卡片） */
        suspend fun dispatchSwipeUp(durationMillis: Long = SWIPE_UP_DURATION_MILLIS): Boolean {
            val service = current() ?: return false
            val path = Path().apply {
                moveTo(SWIPE_X, SWIPE_Y_START)
                lineTo(SWIPE_X, SWIPE_Y_END)
            }
            return service.dispatchGesture(
                path = path,
                durationMillis = durationMillis,
                displayId = Display.DEFAULT_DISPLAY,
            ) == AutomationBackendResult.Completed
        }

        private suspend fun onMainThreadCompat(action: () -> Boolean): Boolean =
            kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
                mainHandler.post {
                    if (continuation.isActive) continuation.resume(action())
                }
            }
    }
}

class AndroidAccessibilityActionBackend(
    /**
     * 每次动作时取值：切换到另一渠道的账号后，校验目标随之改变。
     * 不可为 null——前台包校验没有「关闭」这一档；解析不出目标时由调用方传永不匹配的占位包名。
     */
    private val expectedPackageName: () -> String,
) : AutomationActionBackend {

    override suspend fun execute(action: AutomationAction): AutomationBackendResult = executeInternal(action, null)

    override suspend fun executeGuarded(action: AutomationAction, permit: () -> Boolean): AutomationBackendResult =
        executeInternal(action, permit)

    private suspend fun executeInternal(action: AutomationAction, permit: (() -> Boolean)?): AutomationBackendResult {
        if (!action.hasValidCoordinates()) return AutomationBackendResult.Rejected("动作坐标无效")
        val service = LandosolAccessibilityService.current()
            ?: return AutomationBackendResult.Rejected("无障碍服务未连接")
        val foregroundPackage = LandosolAccessibilityService.foregroundPackage()
        if (foregroundPackage != expectedPackageName()) {
            return AutomationBackendResult.Rejected(GAME_NOT_FOREGROUND_REASON)
        }
        return service.perform(
            action = action,
            gestureDisplayId = CaptureStateRegistry.gestureDisplayId(),
            permit = permit,
        )
    }

    private fun AutomationAction.hasValidCoordinates(): Boolean = when (this) {
        is AutomationAction.Tap -> point.x >= 0f && point.y >= 0f
        is AutomationAction.Swipe -> start.x >= 0f && start.y >= 0f && end.x >= 0f && end.y >= 0f
        AutomationAction.Back -> true
    }
}
