/*
 * Copyright 2019 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.android.accessibility.braille.interfaces

import android.graphics.Region
import android.view.WindowManager
import com.google.android.accessibility.braille.interfaces.ScreenReaderActionPerformer.ScreenReaderAction
import com.google.android.accessibility.utils.FocusFinder
import com.google.errorprone.annotations.CanIgnoreReturnValue

/** Exposes some TalkBack behavior to BrailleIme. */
interface TalkBackForBrailleIme {

  /** Returns the callback of BrailleIme to TalkBack. */
  fun interface BrailleImeForTalkBackProvider {
    /** A reference to the active Braille IME if any. */
    fun getBrailleImeForTalkBack(): BrailleImeForTalkBack?
  }

  /** TalkBack service status. */
  enum class ServiceStatus {
    ON,
    OFF,
    SUSPEND,
  }

  /** Performs specific actions for screen reader. */
  @CanIgnoreReturnValue
  fun performAction(action: ScreenReaderAction, vararg arg: Any?): Boolean

  /**
   * BrailleIme invokes this when it becomes active. When TalkBack gets this signal, it should enter
   * an IME-friendly mode (by disabling Explore-by-Touch, for example).
   */
  fun onBrailleImeActivated(
    disableEbt: Boolean,
    usePassThrough: Boolean,
    passThroughRegion: Region?
  )

  /**
   * BrailleIme invokes this when it becomes inactive. When TalkBack gets this signal, it should
   * restore its typical (non-IME-friendly) mode.
   */
  fun onBrailleImeInactivated(usePassThrough: Boolean)

  /** TalkBack provides the ability to enable BrailleIme. */
  fun setInputMethodEnabled(): Boolean

  /** TalkBack provides its privileged WindowManager to BrailleIme. */
  fun getWindowManager(): WindowManager

  /** TalkBack provides its active/suspended/inactive status to BrailleIme. */
  fun getServiceStatus(): ServiceStatus

  /** TalkBack provides the ability to interrupt all of the queuing announcement. */
  fun interruptSpeak()

  /** Disables proximity sensor to silence speech. */
  fun disableSilenceOnProximity()

  /** Restores the state of silence on proximity. */
  fun restoreSilenceOnProximity()

  /** Checks a user is using TalkBack context menu or not. */
  fun isContextMenuExist(): Boolean

  /** Checks vibration feedback is enabled. */
  fun isVibrationFeedbackEnabled(): Boolean

  /** Checks should braille keyboard announce character when on-screen mode. */
  fun shouldAnnounceCharacterForOnScreenKeyboard(): Boolean

  /** Checks should braille keyboard announce character when physical mode. */
  fun shouldAnnounceCharacterForPhysicalKeyboard(): Boolean

  /** Whether use character granularity to be the moving granularity. */
  fun shouldUseCharacterGranularity(): Boolean

  fun isCurrentGranularityTypoCorrection(): Boolean

  /** Checks the status of the screen. */
  fun isHideScreenMode(): Boolean

  /** Resets the granularity to CHARACTER. */
  fun resetGranularity()

  /** Provides BrailleIme callback provider to TalkBack. */
  fun getBrailleImeForTalkBackProvider(): BrailleImeForTalkBackProvider

  /** Sets BrailleIme callback provider. */
  fun setBrailleImeForTalkBack(brailleImeForTalkBack: BrailleImeForTalkBack?)

  /** Creates {@link FocusFinder} instance. */
  fun createFocusFinder(): FocusFinder
}
