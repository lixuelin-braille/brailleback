/*
 * Copyright (C) 2012 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.googlecode.eyesfree.brailleback;

import android.content.Context;
import com.googlecode.eyesfree.brailleback.sound.SoundEvent;
import com.googlecode.eyesfree.brailleback.sound.SoundSchemeManager;
import com.googlecode.eyesfree.utils.FeedbackController;

/**
 * Provides 'out of band feedback', that is feedback that is typically
 * not output on the braille display and that doesn't come from the UI itself.
 * Examples include error beeps and other earcons.
 *
 * Since 2026 the earcons are driven by the scheme-based
 * {@link SoundSchemeManager}, so users can switch sound schemes, adjust the
 * earcon volume and customize individual event sounds.
 *
 * Note that feedback that is not specific to braille navigation is
 * handled by other accessibility services.
 */
public class FeedbackManager {
    public static final int TYPE_NONE = -1;
    public static final int TYPE_NAVIGATE_OUT_OF_BOUNDS = 0;
    public static final int TYPE_NAVIGATE_INTO_HIERARCHY = 1;
    public static final int TYPE_NAVIGATE_OUT_OF_HIERARCHY = 2;
    public static final int TYPE_DISPLAY_CONNECTED = 3;
    public static final int TYPE_DISPLAY_DISCONNECTED = 4;
    public static final int TYPE_COMMAND_FAILED = 5;
    public static final int TYPE_UNKNOWN_COMMAND = 6;
    public static final int TYPE_GRADE_6_DOT = 7;
    public static final int TYPE_GRADE_8_DOT = 8;

    /** Legacy braille-specific sounds that are not part of the sound schemes. */
    private static final int[] LEGACY_RESOURCE_IDS = {
        -1, -1, -1,
        R.raw.display_connected,
        R.raw.display_disconnected,
        -1, -1,
        R.raw.grade_6_dot,
        R.raw.grade_8_dot,
    };

    /** Scheme events for the feedback types that participate in the schemes. */
    private static final SoundEvent[] TYPE_TO_EVENT = {
        SoundEvent.COMPLETE,            // NAVIGATE_OUT_OF_BOUNDS
        SoundEvent.CHIME_DOWN,          // NAVIGATE_INTO_HIERARCHY
        SoundEvent.CHIME_UP,            // NAVIGATE_OUT_OF_HIERARCHY
        null,                           // DISPLAY_CONNECTED (legacy)
        null,                           // DISPLAY_DISCONNECTED (legacy)
        SoundEvent.COMPLETE,            // COMMAND_FAILED
        SoundEvent.COMPLETE,            // UNKNOWN_COMMAND
        null,                           // GRADE_6_DOT (legacy)
        null,                           // GRADE_8_DOT (legacy)
    };

    private final FeedbackController mFeedbackController;
    private final SoundSchemeManager mSchemeManager;

    public FeedbackManager(Context context) {
        mFeedbackController = new FeedbackController(context);
        mSchemeManager = SoundSchemeManager.getInstance(context);
        applyGlobalSoundState();
        for (int resId : LEGACY_RESOURCE_IDS) {
            if (resId > 0) {
                mFeedbackController.preloadSound(resId);
            }
        }
    }

    /** Re-applies the master switch and volume to the underlying controller. */
    public void applyGlobalSoundState() {
        mFeedbackController.setAuditoryEnabled(mSchemeManager.isEnabled());
        mFeedbackController.setVolume(mSchemeManager.getVolume());
    }

    public void emitFeedback(int type) {
        if (type < 0 || TYPE_TO_EVENT.length <= type) {
            return;
        }
        // First sync the master switch/volume so user changes take effect.
        applyGlobalSoundState();
        SoundEvent event = TYPE_TO_EVENT[type];
        if (event != null) {
            mSchemeManager.play(mFeedbackController, event);
            return;
        }
        // Legacy braille-specific sounds bypass the schemes.
        int legacyResId = LEGACY_RESOURCE_IDS[type];
        if (legacyResId > 0 && mSchemeManager.isEnabled()) {
            mFeedbackController.setVolume(mSchemeManager.getVolume());
            mFeedbackController.playSound(legacyResId);
        }
    }

    /** Directly plays a scheme earcon (usable by other modules). */
    public void playEvent(SoundEvent event) {
        applyGlobalSoundState();
        mSchemeManager.play(mFeedbackController, event);
    }

    /**
     * Emits feedback if an operation fails ({@code result} is {@code false}),
     * and returns {@code true} always.  This is intended to
     * signal that an event was handled even if it fails, but instead
     * emit the appropriate feedback {@code type} to the user.
     */
    public boolean emitOnFailure(boolean result, int type) {
        if (!result) {
            emitFeedback(type);
        }
        return true;
    }

    public void shutdown() {
        if (mFeedbackController != null) {
            mFeedbackController.shutdown();
        }
    }
}
