/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 */

package com.googlecode.eyesfree.brailleback

import android.content.Context
import com.googlecode.eyesfree.brailleback.sound.SoundEvent
import com.googlecode.eyesfree.brailleback.sound.SoundSchemeManager
import com.googlecode.eyesfree.utils.FeedbackController

/**
 * Provides 'out of band feedback', that is feedback that is typically
 * not output on the braille display and that doesn't come from the UI itself.
 * Examples include error beeps and other earcons (Kotlin 版).
 *
 * Earcons are driven by the built-in [SoundSchemeManager], so users can
 * enable/disable earcons and adjust the earcon volume. (Custom schemes were
 * removed.)
 *
 * Note that feedback that is not specific to braille navigation is handled by
 * other accessibility services.
 */
class FeedbackManager(context: Context) {

    private val mFeedbackController: FeedbackController
    private val mSchemeManager: SoundSchemeManager

    init {
        mFeedbackController = FeedbackController(context)
        mSchemeManager = SoundSchemeManager.getInstance(context)
        applyGlobalSoundState()
        for (resId in LEGACY_RESOURCE_IDS) {
            if (resId > 0) {
                mFeedbackController.preloadSound(resId)
            }
        }
    }

    /** Re-applies the master switch and volume to the underlying controller. */
    fun applyGlobalSoundState() {
        mFeedbackController.setAuditoryEnabled(mSchemeManager.isEnabled())
        mFeedbackController.setVolume(mSchemeManager.getVolume())
    }

    fun emitFeedback(type: Int) {
        if (type < 0 || TYPE_TO_EVENT.size <= type) {
            return
        }
        // First sync the master switch/volume so user changes take effect.
        applyGlobalSoundState()
        val event = TYPE_TO_EVENT[type]
        if (event != null) {
            mSchemeManager.play(mFeedbackController, event)
            return
        }
        // Legacy braille-specific sounds bypass the schemes.
        val legacyResId = LEGACY_RESOURCE_IDS[type]
        if (legacyResId > 0 && mSchemeManager.isEnabled()) {
            mFeedbackController.setVolume(mSchemeManager.getVolume())
            mFeedbackController.playSound(legacyResId)
        }
    }

    /** Directly plays a scheme earcon (usable by other modules). */
    fun playEvent(event: SoundEvent) {
        applyGlobalSoundState()
        mSchemeManager.play(mFeedbackController, event)
    }

    /**
     * Emits feedback if an operation fails (`result` is `false`), and returns
     * `true` always. This is intended to signal that an event was handled even
     * if it fails, but instead emit the appropriate feedback `type`.
     */
    fun emitOnFailure(result: Boolean, type: Int): Boolean {
        if (!result) {
            emitFeedback(type)
        }
        return true
    }

    fun shutdown() {
        mFeedbackController.shutdown()
    }

    companion object {
        const val TYPE_NONE = -1
        const val TYPE_NAVIGATE_OUT_OF_BOUNDS = 0
        const val TYPE_NAVIGATE_INTO_HIERARCHY = 1
        const val TYPE_NAVIGATE_OUT_OF_HIERARCHY = 2
        const val TYPE_DISPLAY_CONNECTED = 3
        const val TYPE_DISPLAY_DISCONNECTED = 4
        const val TYPE_COMMAND_FAILED = 5
        const val TYPE_UNKNOWN_COMMAND = 6
        const val TYPE_GRADE_6_DOT = 7
        const val TYPE_GRADE_8_DOT = 8

        /** Legacy braille-specific sounds that are not part of the sound schemes. */
        private val LEGACY_RESOURCE_IDS = intArrayOf(
            -1, -1, -1,
            R.raw.display_connected,
            R.raw.display_disconnected,
            -1, -1,
            R.raw.grade_6_dot,
            R.raw.grade_8_dot
        )

        /** Scheme events for the feedback types that participate in the schemes. */
        private val TYPE_TO_EVENT = arrayOfNulls<SoundEvent>(9).apply {
            this[TYPE_NAVIGATE_OUT_OF_BOUNDS] = SoundEvent.COMPLETE
            this[TYPE_NAVIGATE_INTO_HIERARCHY] = SoundEvent.CHIME_DOWN
            this[TYPE_NAVIGATE_OUT_OF_HIERARCHY] = SoundEvent.CHIME_UP
            // DISPLAY_CONNECTED / DISPLAY_DISCONNECTED: legacy (null)
            this[TYPE_COMMAND_FAILED] = SoundEvent.COMPLETE
            this[TYPE_UNKNOWN_COMMAND] = SoundEvent.COMPLETE
            // GRADE_6_DOT / GRADE_8_DOT: legacy (null)
        }
    }
}
