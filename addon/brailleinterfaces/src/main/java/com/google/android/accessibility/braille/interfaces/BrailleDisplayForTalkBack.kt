/*
 * Copyright 2021 Google Inc.
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

import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/** Exposes some BrailleDisplay behavior to TalkBack. */
interface BrailleDisplayForTalkBack {
  /** Starts braille display. */
  fun start()

  /** Stops braille display. */
  fun stop()

  /** Notifies receiving accessibility event. */
  fun onAccessibilityEvent(accessibilityEvent: AccessibilityEvent)

  /** Notifies receiving key event. */
  fun onKeyEvent(keyEvent: KeyEvent): Boolean

  /** Notifies receiving reading control changed with overlay shown event. */
  fun onReadingControlSettingsChanged(readingControlDescription: CharSequence)

  /** Notifies receiving reading control value changed. */
  fun onReadingControlValueChanged()

  /** Switches braille display on or off. */
  fun switchBrailleDisplayOnOrOff()

  /** Toggles braille contracted mode. */
  fun toggleBrailleContractedMode()

  /** Toggles braille on screen overlay. */
  fun toggleBrailleOnScreenOverlay()
}
