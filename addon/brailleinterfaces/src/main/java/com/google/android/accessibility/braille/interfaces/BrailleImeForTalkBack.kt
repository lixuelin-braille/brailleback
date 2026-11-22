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

import com.google.android.accessibility.utils.input.CursorGranularity

/** Allows TalkBack to signal to BrailleIme. */
interface BrailleImeForTalkBack {
  /** Callbacks when TalkBack state changes from active to suspended or to inactive. */
  fun onTalkBackSuspended()

  /** Callbacks when TalkBack state changes from inactive or suspended to active. */
  fun onTalkBackResumed()

  /** Returns whether braille keyboard is in full screen and touch interacting. */
  fun isTouchInteracting(): Boolean

  /** Returns the callback of BrailleIme to BrailleDisplay. */
  fun getBrailleImeForBrailleDisplay(): BrailleImeForBrailleDisplay

  /** Callbacks when screen dimmed. */
  fun onScreenDim()

  /** Callback when screen undimmed. */
  fun onScreenBright()

  /** Returns whether the granularity is valid for editing. */
  fun isGranularityValid(cursorGranularity: CursorGranularity): Boolean

  /** Returns whether braille keyboard is activated. */
  fun isBrailleKeyboardActivated(): Boolean
}
