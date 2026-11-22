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

package com.google.android.accessibility.braille.common

import android.os.Handler
import androidx.annotation.IntegerRes
import com.google.android.accessibility.utils.Performance
import com.google.android.accessibility.utils.output.FeedbackController

/**
 * Provides 'out of band feedback', that is feedback that is typically not output on the braille
 * display and that doesn't come from the UI itself. Examples include error beeps and other earcons.
 * Note that feedback that is not specific to braille navigation is handled by other accessibility
 * services.
 */
// TODO: Add haptic feedback.
class FeedbackManager(private val feedbackController: FeedbackController) {

  /** * An enumeration of types. */
  enum class Type(@IntegerRes val resId: Int) {
    NAVIGATE_OUT_OF_BOUNDS(R.raw.complete),
    NAVIGATE_INTO_HIERARCHY(R.raw.chime_down),
    NAVIGATE_OUT_OF_HIERARCHY(R.raw.chime_up),
    DISPLAY_CONNECTED(R.raw.display_connected),
    DISPLAY_DISCONNECTED(R.raw.display_disconnected),
    COMMAND_FAILED(R.raw.double_beep),
    UNKNOWN_COMMAND(R.raw.double_beep),
    AUTO_SCROLL_START(R.raw.turn_on),
    AUTO_SCROLL_STOP(R.raw.turn_off),
    CALIBRATION(R.raw.calibration_done),
    BEEP(R.raw.volume_beep),
    ;
  }

  /** Emits feedback with {@code Type}. */
  fun emitFeedback(type: Type) {
    feedbackController.playAuditory(type.resId, Performance.EVENT_ID_UNTRACKED)
  }

  /** Emits feedback with delay. */
  fun emitFeedback(type: Type, delayMs: Int) {
    Handler()
      .postDelayed(
        { feedbackController.playAuditory(type.resId, Performance.EVENT_ID_UNTRACKED) },
        delayMs.toLong(),
      )
  }

  /**
   * Emits feedback if an operation fails ({@code result} is {@code false}), and returns {@code
   * true} always. This is intended to signal that an event was handled even if it fails, but
   * instead emit the appropriate feedback {@code type} to the user.
   */
  fun emitOnFailure(result: Boolean, type: Type): Boolean {
    if (!result) {
      emitFeedback(type)
    }
    return true
  }
}
