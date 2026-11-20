package com.googlecode.eyesfree.brailleback

/**
 * Pure decision logic for the volume-key gestures. Kept free of Android types
 * so the whole state machine can be covered by JVM unit tests.
 *
 * ## Design
 *
 * Android requires [android.accessibilityservice.AccessibilityService.onKeyEvent]
 * to decide **synchronously** whether an event is consumed; there is no way to
 * "observe first, consume later". So to stop a long press or a fast double
 * press from also changing the system volume, the service has to consume every
 * volume key while gestures are enabled and re-apply the single volume step
 * itself for an ordinary press.
 *
 * Each press therefore lands in exactly one of three mutually exclusive
 * gestures:
 *
 * | Gesture | Effect |
 * |---|---|
 * | Long press (held >= [LONG_PRESS_TIMEOUT_MS]) | run the configured action, **no volume change** |
 * | Fast double press of the same key (< [DOUBLE_PRESS_TIMEOUT_MS]) | run the configured action, **no volume change** |
 * | Any other ("slow") press | adjust the system volume by one step |
 *
 * A slot whose action is set to `none` deliberately falls back to the slow
 * press branch, so a key press is never silently swallowed. When gestures are
 * disabled (or no action is configured at all) events are passed straight to
 * the system and everything behaves exactly as stock Android, including
 * long-press auto-repeat.
 *
 * The unavoidable cost of isolating the gestures is that a slow press waits
 * for the double-press window before its volume step is applied. When the
 * double-press action is disabled no wait is needed.
 */
object VolumeKeyGesturePolicy {

    const val LONG_PRESS_TIMEOUT_MS = 500L
    const val DOUBLE_PRESS_TIMEOUT_MS = 300L

    /** What the service must do when a volume key is released. */
    enum class TapOutcome {
        /** Double-press action is off: adjust one step immediately, no delay. */
        ADJUST_VOLUME_NOW,

        /** Wait out the double-press window, then adjust one step. */
        SCHEDULE_SLOW_PRESS,

        /** Second tap arrived in time: run the action, cancel the pending step. */
        RUN_DOUBLE_ACTION,

        /** Long press already ran its action: this release must not change volume. */
        SUPPRESS_AFTER_LONG_PRESS,

        /** Gestures are off and the event was passed through to the system. */
        PASSTHROUGH,
    }

    /** Approximation of the stream the platform would have adjusted. */
    enum class VolumeStream { VOICE_CALL, MUSIC }

    /**
     * Whether [android.accessibilityservice.AccessibilityService.onKeyEvent]
     * should consume a volume key. Consuming is only safe while gestures are
     * enabled, because the service then re-applies the slow-press volume step
     * itself. When disabled the key must reach the system untouched.
     */
    @JvmStatic
    fun shouldConsumeVolumeKeyEvent(gesturesEnabled: Boolean): Boolean = gesturesEnabled

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

    /** Whether this tap completes a double press of the same key in [timeoutMs]. */
    @JvmStatic
    fun isDoublePress(
        nowMs: Long,
        lastTapMs: Long,
        timeoutMs: Long,
        sameKey: Boolean
    ): Boolean = sameKey && lastTapMs > 0 && nowMs >= lastTapMs
            && nowMs - lastTapMs < timeoutMs

    /**
     * Single decision point for a key release. Keeping this pure is what makes
     * the "gestures never change the volume" invariant testable without a
     * device or Robolectric.
     */
    @JvmStatic
    fun classifyTap(
        gesturesEnabled: Boolean,
        longPressFired: Boolean,
        doubleActionEnabled: Boolean,
        nowMs: Long,
        lastTapMs: Long,
        sameKey: Boolean,
        timeoutMs: Long
    ): TapOutcome = when {
        !gesturesEnabled -> TapOutcome.PASSTHROUGH
        longPressFired -> TapOutcome.SUPPRESS_AFTER_LONG_PRESS
        !doubleActionEnabled -> TapOutcome.ADJUST_VOLUME_NOW
        isDoublePress(nowMs, lastTapMs, timeoutMs, sameKey) ->
            TapOutcome.RUN_DOUBLE_ACTION
        else -> TapOutcome.SCHEDULE_SLOW_PRESS
    }

    /**
     * During a call the platform routes volume keys to the voice-call stream;
     * otherwise media is the best available approximation. The service maps
     * this to the corresponding [android.media.AudioManager] constants.
     */
    @JvmStatic
    fun volumeStream(inCall: Boolean): VolumeStream =
        if (inCall) VolumeStream.VOICE_CALL else VolumeStream.MUSIC
}
