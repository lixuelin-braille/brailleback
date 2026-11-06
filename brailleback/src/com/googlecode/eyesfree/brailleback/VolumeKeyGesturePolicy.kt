package com.googlecode.eyesfree.brailleback

/**
 * Pure decision logic for the volume-key gestures. Kept free of Android types
 * so the rules can be covered by JVM unit tests.
 *
 * The contract that matters most is [shouldConsumeVolumeKeyEvent]: it must
 * always return false. An AccessibilityService whose `onKeyEvent()` returns
 * true swallows the key before Android has a chance to adjust the stream
 * volume, which breaks short presses, long-press repeats and the volume panel.
 * Gestures are therefore detected as a passive observer, never by consuming
 * the event.
 */
object VolumeKeyGesturePolicy {

    const val LONG_PRESS_TIMEOUT_MS = 500L
    const val DOUBLE_PRESS_TIMEOUT_MS = 350L

    /**
     * Whether a fresh long-press timer should be started for this DOWN event.
     *
     * While a key is held, Android delivers ACTION_DOWN with a non-zero
     * repeatCount. Restarting the timer on each repeat would prevent it from
     * ever reaching its timeout, so repeats are ignored.
     */
    @JvmStatic
    fun shouldStartLongPress(
        repeatCount: Int,
        gesturesEnabled: Boolean,
        longPressAlreadyFired: Boolean
    ): Boolean = gesturesEnabled && repeatCount == 0 && !longPressAlreadyFired

    /** Whether this tap completes a double press within [timeoutMs]. */
    @JvmStatic
    fun isDoublePress(nowMs: Long, lastTapMs: Long, timeoutMs: Long): Boolean =
        lastTapMs > 0 && nowMs >= lastTapMs && nowMs - lastTapMs < timeoutMs

    /**
     * Volume keys are never consumed. This is deliberately a function (rather
     * than a bare `return false`) so a unit test locks the invariant down and
     * a future refactor cannot silently reintroduce the bug.
     */
    @JvmStatic
    fun shouldConsumeVolumeKeyEvent(): Boolean = false
}
