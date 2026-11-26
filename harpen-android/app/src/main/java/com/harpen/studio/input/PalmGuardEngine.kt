package com.harpen.studio.input

import android.view.MotionEvent
import com.harpen.studio.data.settings.HarpenSettings
import com.harpen.studio.model.PalmGuardMode

data class PointerSample(
    val pointerId: Int,
    val pointerIndex: Int,
    val x: Float,
    val y: Float,
    val pressure: Float,
    val size: Float,
    val touchMajor: Float,
    val touchMinor: Float,
    val toolType: Int
)

sealed class PalmGuardDecision {
    data class Accept(val primaryPointerId: Int, val note: String? = null) : PalmGuardDecision()
    data class Ignore(val reason: String) : PalmGuardDecision()
    data class CancelStrokeAndRecover(val reason: String) : PalmGuardDecision()
    data class PassToGestureEngine(val reason: String) : PalmGuardDecision()
}

data class PalmGuardStatus(
    val mode: PalmGuardMode = PalmGuardMode.SINGLE_POINTER,
    val activePrimaryPointerId: Int? = null,
    val isInConflictRecovery: Boolean = false,
    val lastConflictReason: String? = null,
    val rejectedEventsCount: Int = 0,
    val acceptedEventsCount: Int = 0
)

/**
 * HARPEN PalmGuard — Transparent, non-locking experimental input protection.
 *
 * Design guarantees:
 * - Never hardcodes coordinates or Y=200 bands.
 * - Never assumes pointerIndex 0 is always the stylus.
 * - Never permanently locks input after multi-touch or palm contact.
 * - Treats universal capacitive styluses realistically (often reported as TOOL_TYPE_FINGER).
 */
class PalmGuardEngine {

    private var activePrimaryPointerId: Int? = null
    private val activePointerIds = linkedSetOf<Int>()
    private val cancelledPointerIds = linkedSetOf<Int>()
    private var conflictRecoveryUntilMs: Long = 0L
    private var waitingForAllPointersUp: Boolean = false
    private var lastConflictReason: String? = null
    private var rejectedCount = 0
    private var acceptedCount = 0

    fun getStatus(mode: PalmGuardMode, nowMs: Long = System.currentTimeMillis()): PalmGuardStatus {
        val inRecovery = waitingForAllPointersUp && nowMs < conflictRecoveryUntilMs
        if (waitingForAllPointersUp && nowMs >= conflictRecoveryUntilMs && activePointerIds.isEmpty()) {
            waitingForAllPointersUp = false
        }
        return PalmGuardStatus(
            mode = mode,
            activePrimaryPointerId = activePrimaryPointerId,
            isInConflictRecovery = inRecovery,
            lastConflictReason = lastConflictReason,
            rejectedEventsCount = rejectedCount,
            acceptedEventsCount = acceptedCount
        )
    }

    fun getCancelledPointers(): Set<Int> = cancelledPointerIds.toSet()

    fun manualReset() {
        activePrimaryPointerId = null
        activePointerIds.clear()
        cancelledPointerIds.clear()
        waitingForAllPointersUp = false
        conflictRecoveryUntilMs = 0L
        lastConflictReason = null
    }

    fun evaluate(
        actionMasked: Int,
        actionIndex: Int,
        pointers: List<PointerSample>,
        settings: HarpenSettings,
        nowMs: Long
    ): PalmGuardDecision {
        // Recover automatically when timeout passes or all fingers lifted
        if (waitingForAllPointersUp && (pointers.isEmpty() || nowMs >= conflictRecoveryUntilMs)) {
            waitingForAllPointersUp = false
            cancelledPointerIds.clear()
        }

        val mode = settings.palmGuardMode

        when (actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                cancelledPointerIds.clear()
                val first = pointers.firstOrNull() ?: return PalmGuardDecision.Ignore("Empty pointer list")
                activePointerIds.clear()
                activePointerIds.add(first.pointerId)

                if (waitingForAllPointersUp && nowMs < conflictRecoveryUntilMs) {
                    waitingForAllPointersUp = false
                }

                // Mode D or Mode E contact heuristic pre-check (only if device actually reports > 0 contact area)
                if (shouldCheckContactHeuristic(mode, settings) &&
                    settings.palmGuardIgnoreSuspiciousBeforeStroke &&
                    isSuspiciousLargeContact(first, settings)
                ) {
                    rejectedCount++
                    cancelledPointerIds.add(first.pointerId)
                    lastConflictReason = "Large contact heuristic (size=${"%.2f".format(first.size)}, major=${first.touchMajor.toInt()}px)"
                    return PalmGuardDecision.Ignore(lastConflictReason!!)
                }

                activePrimaryPointerId = first.pointerId
                acceptedCount++
                return PalmGuardDecision.Accept(first.pointerId)
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                val newPointer = pointers.getOrNull(actionIndex)
                if (newPointer != null) {
                    activePointerIds.add(newPointer.pointerId)
                }

                // If active stylus appears while a finger started first, allow stylus takeover if not locked
                if (newPointer != null &&
                    newPointer.toolType == MotionEvent.TOOL_TYPE_STYLUS &&
                    activePrimaryPointerId != null
                ) {
                    val currentPrimary = pointers.firstOrNull { it.pointerId == activePrimaryPointerId }
                    if (currentPrimary != null && currentPrimary.toolType != MotionEvent.TOOL_TYPE_STYLUS) {
                        activePrimaryPointerId = newPointer.pointerId
                        acceptedCount++
                        return PalmGuardDecision.Accept(newPointer.pointerId, "Active stylus took primary focus")
                    }
                }

                return when (mode) {
                    PalmGuardMode.NATIVE_RAW -> {
                        val id = activePrimaryPointerId ?: pointers.first().pointerId
                        acceptedCount++
                        PalmGuardDecision.Accept(id)
                    }
                    PalmGuardMode.SINGLE_POINTER -> {
                        if (pointers.size >= 2) {
                            rejectedCount++
                            if (newPointer != null) cancelledPointerIds.add(newPointer.pointerId)
                            PalmGuardDecision.PassToGestureEngine("Secondary pointer ignored for stroke; available to gesture recognizer")
                        } else {
                            val id = activePrimaryPointerId ?: pointers.first().pointerId
                            acceptedCount++
                            PalmGuardDecision.Accept(id)
                        }
                    }
                    PalmGuardMode.MULTI_TOUCH_CANCEL -> {
                        if (activePrimaryPointerId != null && pointers.size >= 2) {
                            triggerConflictCancel(
                                reason = "Multi-touch conflict (${pointers.size} pointers active during stroke)",
                                settings = settings,
                                nowMs = nowMs,
                                pointers = pointers
                            )
                        } else {
                            PalmGuardDecision.PassToGestureEngine("Multi-touch gesture candidate")
                        }
                    }
                    PalmGuardMode.CONTACT_HEURISTIC -> {
                        if (newPointer != null && isSuspiciousLargeContact(newPointer, settings)) {
                            rejectedCount++
                            cancelledPointerIds.add(newPointer.pointerId)
                            PalmGuardDecision.Ignore("Secondary large contact filtered")
                        } else {
                            PalmGuardDecision.PassToGestureEngine("Secondary touch routed to gestures")
                        }
                    }
                    PalmGuardMode.EXPERIMENTAL_HYBRID -> {
                        val suspiciousNew = newPointer != null &&
                            settings.palmGuardContactHeuristicEnabled &&
                            isSuspiciousLargeContact(newPointer, settings)

                        if (suspiciousNew) {
                            rejectedCount++
                            cancelledPointerIds.add(newPointer!!.pointerId)
                            if (settings.palmGuardCancelOnMultiTouch && activePrimaryPointerId != null) {
                                triggerConflictCancel(
                                    reason = "Hybrid: suspicious contact added during stroke",
                                    settings = settings,
                                    nowMs = nowMs,
                                    pointers = pointers
                                )
                            } else {
                                PalmGuardDecision.Ignore("Hybrid: suspicious secondary contact filtered")
                            }
                        } else if (settings.palmGuardCancelOnMultiTouch && activePrimaryPointerId != null && pointers.size >= 2) {
                            triggerConflictCancel(
                                reason = "Hybrid: multi-touch conflict cancellation",
                                settings = settings,
                                nowMs = nowMs,
                                pointers = pointers
                            )
                        } else {
                            PalmGuardDecision.PassToGestureEngine("Hybrid: multi-touch routed to gesture recognizer")
                        }
                    }
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (waitingForAllPointersUp) {
                    if (nowMs >= conflictRecoveryUntilMs) {
                        waitingForAllPointersUp = false
                    } else {
                        rejectedCount++
                        return PalmGuardDecision.Ignore("Waiting for pointer release after conflict")
                    }
                }

                val primaryId = activePrimaryPointerId
                if (primaryId == null) {
                    return if (mode == PalmGuardMode.NATIVE_RAW && pointers.isNotEmpty()) {
                        val fallback = pointers.first().pointerId
                        activePrimaryPointerId = fallback
                        acceptedCount++
                        PalmGuardDecision.Accept(fallback)
                    } else {
                        PalmGuardDecision.Ignore("No active primary stroke pointer")
                    }
                }

                val primarySample = pointers.firstOrNull { it.pointerId == primaryId }
                    ?: return PalmGuardDecision.Ignore("Primary pointer not present in MOVE frame")

                if (shouldCheckContactHeuristic(mode, settings) &&
                    isSuspiciousLargeContact(primarySample, settings)
                ) {
                    rejectedCount++
                    cancelledPointerIds.add(primaryId)
                    return if (settings.palmGuardCancelOnMultiTouch) {
                        triggerConflictCancel(
                            reason = "Contact expanded beyond threshold (major=${primarySample.touchMajor.toInt()}px)",
                            settings = settings,
                            nowMs = nowMs,
                            pointers = pointers
                        )
                    } else {
                        PalmGuardDecision.Ignore("Contact heuristic filtered MOVE sample")
                    }
                }

                acceptedCount++
                return PalmGuardDecision.Accept(primaryId)
            }

            MotionEvent.ACTION_POINTER_UP -> {
                val lifted = pointers.getOrNull(actionIndex)
                if (lifted != null) {
                    activePointerIds.remove(lifted.pointerId)
                    if (lifted.pointerId == activePrimaryPointerId) {
                        // Never allow another lingering finger to hijack or connect to the released primary stroke!
                        activePrimaryPointerId = null
                    }
                }
                if (activePointerIds.isEmpty()) {
                    waitingForAllPointersUp = false
                    cancelledPointerIds.clear()
                }
                return PalmGuardDecision.Ignore("Pointer lifted safely without connecting pointers")
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                activePointerIds.clear()
                activePrimaryPointerId = null
                waitingForAllPointersUp = false
                if (actionMasked == MotionEvent.ACTION_CANCEL) {
                    pointers.forEach { cancelledPointerIds.add(it.pointerId) }
                    lastConflictReason = "System ACTION_CANCEL received (e.g. Samsung firmware palm rejection)"
                    return PalmGuardDecision.CancelStrokeAndRecover(lastConflictReason!!)
                }
                cancelledPointerIds.clear()
                return PalmGuardDecision.Ignore("Stroke finished")
            }

            else -> return PalmGuardDecision.Ignore("Unhandled motion action $actionMasked")
        }
    }

    private fun triggerConflictCancel(
        reason: String,
        settings: HarpenSettings,
        nowMs: Long,
        pointers: List<PointerSample>
    ): PalmGuardDecision.CancelStrokeAndRecover {
        rejectedCount++
        activePrimaryPointerId = null
        waitingForAllPointersUp = true
        conflictRecoveryUntilMs = nowMs + settings.palmGuardRecoveryTimeoutMs.coerceIn(100, 1500)
        lastConflictReason = reason
        pointers.forEach { cancelledPointerIds.add(it.pointerId) }
        return PalmGuardDecision.CancelStrokeAndRecover(reason)
    }

    private fun shouldCheckContactHeuristic(mode: PalmGuardMode, settings: HarpenSettings): Boolean {
        return mode == PalmGuardMode.CONTACT_HEURISTIC ||
            (mode == PalmGuardMode.EXPERIMENTAL_HYBRID && settings.palmGuardContactHeuristicEnabled)
    }

    private fun isSuspiciousLargeContact(sample: PointerSample, settings: HarpenSettings): Boolean {
        if (sample.toolType == MotionEvent.TOOL_TYPE_STYLUS) return false
        val sizeExceeded = sample.size > 0f && sample.size > settings.palmGuardMaxContactSize
        val majorExceeded = sample.touchMajor > 0f && sample.touchMajor > settings.palmGuardMaxTouchMajorPx
        return sizeExceeded || majorExceeded
    }
}
