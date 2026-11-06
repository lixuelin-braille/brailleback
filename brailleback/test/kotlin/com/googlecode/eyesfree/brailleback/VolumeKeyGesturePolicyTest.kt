package com.googlecode.eyesfree.brailleback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks down the volume-key regression: onKeyEvent() must never consume a
 * volume key, otherwise Android cannot change the stream volume.
 */
class VolumeKeyGesturePolicyTest {

    @Test
    fun volumeKeyEventsAreNeverConsumed() {
        assertFalse(
            "consuming a volume key breaks system volume adjustment",
            VolumeKeyGesturePolicy.shouldConsumeVolumeKeyEvent()
        )
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
        assertTrue(VolumeKeyGesturePolicy.isDoublePress(1000, 800, 350))
        assertTrue(VolumeKeyGesturePolicy.isDoublePress(1000, 999, 350))
    }

    @Test
    fun tapAtWindowBoundaryIsNotDoublePress() {
        assertFalse(VolumeKeyGesturePolicy.isDoublePress(1150, 800, 350))
        assertFalse(VolumeKeyGesturePolicy.isDoublePress(1500, 800, 350))
    }

    @Test
    fun noPreviousTapIsNotDoublePress() {
        assertFalse(VolumeKeyGesturePolicy.isDoublePress(10, 0, 350))
        assertFalse(VolumeKeyGesturePolicy.isDoublePress(349, 0, 350))
    }

    @Test
    fun clockGoingBackwardsIsNotDoublePress() {
        assertFalse(VolumeKeyGesturePolicy.isDoublePress(500, 900, 350))
    }

    @Test
    fun timeoutParameterIsHonoured() {
        assertTrue(VolumeKeyGesturePolicy.isDoublePress(1000, 950, 100))
        assertFalse(VolumeKeyGesturePolicy.isDoublePress(1000, 800, 100))
    }
}
