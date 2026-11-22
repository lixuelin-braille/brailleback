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

import android.accessibilityservice.AccessibilityService
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.google.android.accessibility.braille.interfaces.ScreenReaderActionPerformer.ScreenReaderAction
import com.google.android.accessibility.utils.FocusFinder
import com.google.android.accessibility.utils.monitor.CollectionState
import com.google.errorprone.annotations.CanIgnoreReturnValue

/** Exposes some TalkBack behavior to BrailleDisplay. */
interface TalkBackForBrailleDisplay {
  /** Obtains the AccessibilityService. */
  fun getAccessibilityService(): AccessibilityService

  /** Performs specific actions for screen reader. */
  @CanIgnoreReturnValue
  fun performAction(action: ScreenReaderAction, vararg arg: Any?): Boolean

  /** Sets voice feedback state. */
  fun setVoiceFeedback(enabled: Boolean): Boolean

  /** Gets voice feedback enabled status. */
  fun getVoiceFeedbackEnabled(): Boolean

  /** Gets accessibility focus node. */
  fun getAccessibilityFocusNode(fallbackOnRoot: Boolean): AccessibilityNodeInfoCompat

  /** Creates {@link FocusFinder} instance. */
  fun createFocusFinder(): FocusFinder

  /** Shows custom label dialog for the Accessibility node to add or edit a label. */
  fun showLabelDialog(action: CustomLabelAction, node: AccessibilityNodeInfoCompat): Boolean

  /** Gets defined custom label. */
  fun getCustomLabelText(node: AccessibilityNodeInfoCompat): CharSequence

  /** Returns whether {@param AccessibilityNodeInfoCompat node} needs a label. */
  fun needsLabel(node: AccessibilityNodeInfoCompat): Boolean

  /** Returns whether a label can be added for this {@param AccessibilityNodeInfoCompat}. */
  fun supportsLabel(node: AccessibilityNodeInfoCompat): Boolean

  /** Returns keyboard status. */
  fun isOnscreenKeyboardActive(): Boolean

  /** Returns whether browse mode is enabled. */
  fun isBrowseMode(): Boolean

  /** Returns whether browse mode flag is enabled. */
  fun isBrowseModeFlagEnabled(): Boolean

  /** Launches TalkBack keyboard settings. */
  fun launchTalkBackKeyboardSettings()

  /** Returns whether table navigation is enabled. */
  fun isTableNavigationEnabled(): Boolean

  /**
   * Returns active onscreen keyboard window name.
   *
   * @return empty if window title is null.
   */
  fun getOnScreenKeyboardName(): CharSequence

  /** Custom label actions. */
  enum class CustomLabelAction {
    ADD_LABEL,
    EDIT_LABEL
  }

  /** Switches the input method to braille keyboard. */
  @CanIgnoreReturnValue
  fun switchInputMethodToBrailleKeyboard(): Boolean

  /** Switches to next input method. */
  fun switchToNextInputMethod(): Boolean

  /** Returns the collection state. */
  fun getCollectionState(): CollectionState
}
