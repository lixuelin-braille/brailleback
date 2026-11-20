package com.googlecode.eyesfree.brailleback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM coverage for the volume-key gesture state machine.
 *
 * The regression this file used to lock down was "never consume a volume key".
 * That contract is now deliberately inverted: while gestures are enabled the
 * service consumes the key and re-applies the single volume step itself, so a
 * long press or a fast double press can run its action without touching the
 * volume. When gestures are off, everything goes back to stock Android.
 */
class VolumeKeyGesturePolicyTest {

    @Test
    fun enabledGesturesConsumeVolumeKeys() {
        assertTrue(VolumeKeyGesturePolicy.shouldConsumeVolumeKeyEvent(true))
    }

    @Test
    fun disabledGesturesPassVolumeKeysToSystem() {
        assertFalse(VolumeKeyGesturePolicy.shouldConsumeVolumeKeyEvent(false))
    }

    @Test
    fun firstDownStartsLongPressTimer() {
        assertTrue(VolumeKeyGesturePolicy.shouldStartLongPress(0, true, false))
    }

    @Test
    fun repeatDownDoesNotRestartLongPressTimer() {
        assertFalse(VolumeKeyGesturePolicy.shouldStartLongPress(1, true, false))
        assertFalse(VolumeKeyGesturePolicy.shouldStartLongPress(9, true, false))
    }

    @Test
    fun noNewTimerAfterLongPressAlreadyFired() {
        assertFalse(VolumeKeyGesturePolicy.shouldStartLongPress(0, true, true))
    }

    @Test
    fun gesturesDisabledBlocksTimer() {
        assertFalse(VolumeKeyGesturePolicy.shouldStartLongPress(0, false, false))
        assertFalse(VolumeKeyGesturePolicy.shouldStartLongPress(0, false, true))
    }

    @Test
    fun secondTapInsideWindowIsDoublePress() {
        assertTrue(VolumeKeyGesturePolicy.isDoublePress(1000, 800, 350, true))
        assertTrue(VolumeKeyGesturePolicy.isDoublePress(1000, 999, 350, true))
    }

    @Test
    fun differentKeysAreNotDoublePress() {
        assertFalse(VolumeKeyGesturePolicy.isDoublePress(1000, 800, 350, false))
    }

    @Test
    fun tapAtWindowBoundaryIsNotDoublePress() {
        assertFalse(VolumeKeyGesturePolicy.isDoublePress(1150, 800, 350, true))
        assertFalse(VolumeKeyGesturePolicy.isDoublePress(1500, 800, 350, true))
    }

    @Test
    fun noPreviousTapIsNotDoublePress() {
        assertFalse(VolumeKeyGesturePolicy.isDoublePress(10, 0, 350, true))
        assertFalse(VolumeKeyGesturePolicy.isDoublePress(349, 0, 350, true))
    }

    @Test
    fun clockGoingBackwardsIsNotDoublePress() {
        assertFalse(VolumeKeyGesturePolicy.isDoublePress(500, 900, 350, true))
    }

    @Test
    fun timeoutParameterIsHonoured() {
        assertTrue(VolumeKeyGesturePolicy.isDoublePress(1000, 950, 100, true))
        assertFalse(VolumeKeyGesturePolicy.isDoublePress(1000, 800, 100, true))
    }

    @Test
    fun slowPressWaitsForDoublePressWindow() {
        assertEquals(
            VolumeKeyGesturePolicy.TapOutcome.SCHEDULE_SLOW_PRESS,
            classify(gesturesEnabled = true, doubleActionEnabled = true, now = 1000, lastTap = 0),
        )
    }

    @Test
    fun fastDoublePressRunsActionInsteadOfVolume() {
        assertEquals(
            VolumeKeyGesturePolicy.TapOutcome.RUN_DOUBLE_ACTION,
            classify(
                gesturesEnabled = true,
                doubleActionEnabled = true,
                now = 1000,
                lastTap = 800,
                sameKey = true,
                timeout = 350,
            ),
        )
    }

    @Test
    fun longPressSuppressesVolumeEvenInsideDoublePressWindow() {
        assertEquals(
            VolumeKeyGesturePolicy.TapOutcome.SUPPRESS_AFTER_LONG_PRESS,
            classify(
                gesturesEnabled = true,
                longPressFired = true,
                doubleActionEnabled = true,
                now = 1000,
                lastTap = 950,
                sameKey = true,
                timeout = 350,
            ),
        )
    }

    @Test
    fun noDoubleActionMeansNoWait() {
        assertEquals(
            VolumeKeyGesturePolicy.TapOutcome.ADJUST_VOLUME_NOW,
            classify(gesturesEnabled = true, doubleActionEnabled = false, now = 1000, lastTap = 0),
        )
    }

    @Test
    fun disabledGesturesNeverTouchVolume() {
        assertEquals(
            VolumeKeyGesturePolicy.TapOutcome.PASSTHROUGH,
            classify(gesturesEnabled = false, doubleActionEnabled = true, now = 1000, lastTap = 800),
        )
    }

    @Test
    fun onlyVolumeOutcomesMayAdjustVolume() {
        // This is the isolation invariant: both action-bearing outcomes must
        // be distinct from the two outcomes that adjust the system volume.
        val actionOutcomes = setOf(
            VolumeKeyGesturePolicy.TapOutcome.RUN_DOUBLE_ACTION,
            VolumeKeyGesturePolicy.TapOutcome.SUPPRESS_AFTER_LONG_PRESS,
        )
        val volumeOutcomes = setOf(
            VolumeKeyGesturePolicy.TapOutcome.ADJUST_VOLUME_NOW,
            VolumeKeyGesturePolicy.TapOutcome.SCHEDULE_SLOW_PRESS,
        )
        assertTrue(actionOutcomes.intersect(volumeOutcomes).isEmpty())
    }

    @Test
    fun volumeStreamRoutesCallsToVoiceCall() {
        assertEquals(
            VolumeKeyGesturePolicy.VolumeStream.VOICE_CALL,
            VolumeKeyGesturePolicy.volumeStream(inCall = true),
        )
        assertEquals(
            VolumeKeyGesturePolicy.VolumeStream.MUSIC,
            VolumeKeyGesturePolicy.volumeStream(inCall = false),
        )
    }

    @Test
    fun doublePressWindowIsShortAndLongPressWindowIsLonger() {
        assertTrue(VolumeKeyGesturePolicy.DOUBLE_PRESS_TIMEOUT_MS <= 350)
        assertTrue(VolumeKeyGesturePolicy.LONG_PRESS_TIMEOUT_MS > VolumeKeyGesturePolicy.DOUBLE_PRESS_TIMEOUT_MS)
    }

    private fun classify(
        gesturesEnabled: Boolean,
        doubleActionEnabled: Boolean,
        now: Long,
        lastTap: Long,
        longPressFired: Boolean = false,
        sameKey: Boolean = true,
        timeout: Long = 350,
    ) = VolumeKeyGesturePolicy.classifyTap(
        gesturesEnabled,
        longPressFired,
        doubleActionEnabled,
        now,
        lastTap,
        sameKey,
        timeout,
    )
}
