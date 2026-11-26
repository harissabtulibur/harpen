package com.harpen.studio

import android.view.MotionEvent
import com.harpen.studio.data.settings.HarpenSettings
import com.harpen.studio.input.PalmGuardDecision
import com.harpen.studio.input.PalmGuardEngine
import com.harpen.studio.input.PointerSample
import com.harpen.studio.model.PalmGuardMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PalmGuardEngineTest {

    @Test
    fun singlePointerModeIgnoresSecondaryPointerAndNeverLocks() {
        val engine = PalmGuardEngine()
        val settings = HarpenSettings(palmGuardMode = PalmGuardMode.SINGLE_POINTER)

        val p1 = PointerSample(pointerId = 7, pointerIndex = 0, x = 420f, y = 120f, pressure = 1f, size = 0.05f, touchMajor = 12f, touchMinor = 10f, toolType = MotionEvent.TOOL_TYPE_FINGER)
        val p2 = PointerSample(pointerId = 9, pointerIndex = 1, x = 680f, y = 410f, pressure = 1f, size = 0.25f, touchMajor = 70f, touchMinor = 55f, toolType = MotionEvent.TOOL_TYPE_FINGER)

        val down = engine.evaluate(MotionEvent.ACTION_DOWN, 0, listOf(p1), settings, 1000L)
        assertTrue(down is PalmGuardDecision.Accept)

        val ptrDown = engine.evaluate(MotionEvent.ACTION_POINTER_DOWN, 1, listOf(p1, p2), settings, 1020L)
        assertTrue(ptrDown is PalmGuardDecision.PassToGestureEngine)

        val up = engine.evaluate(MotionEvent.ACTION_UP, 0, listOf(p1), settings, 1040L)
        assertTrue(up is PalmGuardDecision.Ignore)

        // Ensure next stroke starts cleanly without lockout
        val nextDown = engine.evaluate(MotionEvent.ACTION_DOWN, 0, listOf(p1), settings, 1060L)
        assertTrue(nextDown is PalmGuardDecision.Accept)
        assertFalse(engine.getStatus(PalmGuardMode.SINGLE_POINTER, 1060L).isInConflictRecovery)
    }

    @Test
    fun multiTouchCancelRecoversAfterReleaseOrTimeout() {
        val engine = PalmGuardEngine()
        val settings = HarpenSettings(
            palmGuardMode = PalmGuardMode.MULTI_TOUCH_CANCEL,
            palmGuardRecoveryTimeoutMs = 200
        )

        val p1 = PointerSample(1, 0, 100f, 150f, 1f, 0.04f, 10f, 10f, MotionEvent.TOOL_TYPE_FINGER)
        val p2 = PointerSample(2, 1, 300f, 350f, 1f, 0.1f, 40f, 40f, MotionEvent.TOOL_TYPE_FINGER)

        engine.evaluate(MotionEvent.ACTION_DOWN, 0, listOf(p1), settings, 1000L)
        val cancel = engine.evaluate(MotionEvent.ACTION_POINTER_DOWN, 1, listOf(p1, p2), settings, 1010L)
        assertTrue(cancel is PalmGuardDecision.CancelStrokeAndRecover)

        // Release all pointers -> immediate recovery
        engine.evaluate(MotionEvent.ACTION_UP, 0, emptyList(), settings, 1050L)
        val freshDown = engine.evaluate(MotionEvent.ACTION_DOWN, 0, listOf(p1), settings, 1060L)
        assertTrue(freshDown is PalmGuardDecision.Accept)
    }
}
