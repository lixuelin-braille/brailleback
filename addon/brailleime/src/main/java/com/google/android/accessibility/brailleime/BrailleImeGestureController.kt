/*
 * Copyright 2023 Google Inc.
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

package com.google.android.accessibility.brailleime

import android.content.Context
import com.google.android.accessibility.braille.common.BrailleImeAction
import com.google.android.accessibility.braille.interfaces.BrailleCharacter
import com.google.android.accessibility.brailleime.BrailleImeVibrator.VibrationType
import com.google.android.accessibility.brailleime.analytics.BrailleImeAnalytics
import com.google.android.accessibility.brailleime.input.DotHoldSwipe
import com.google.android.accessibility.brailleime.input.Gesture
import com.google.android.accessibility.brailleime.input.Swipe
import com.google.common.annotations.VisibleForTesting
import com.google.errorprone.annotations.CanIgnoreReturnValue
import java.util.Optional

/** Controller to handle incoming gestures to BrailleIme. */
class BrailleImeGestureController(
  context: Context,
  brailleImeActor: BrailleImeActor?,
) {
  private val context: Context
  private val brailleImeActor: BrailleImeActor?
  private var brailleImeAnalytics: BrailleImeAnalytics

  init {
    this.context = context
    this.brailleImeActor = brailleImeActor
    brailleImeAnalytics = BrailleImeAnalytics.getInstance(context)
  }

  /** Receives gesture swipe to perform action. */
  @CanIgnoreReturnValue
  fun performSwipeAction(swipe: Swipe): Boolean {
    return performAction(swipe)
  }

  /** Receives gesture dot hold and swipe to perform action. */
  @CanIgnoreReturnValue
  fun performDotHoldAndSwipeAction(dotHoldSwipe: DotHoldSwipe): Boolean {
    return performAction(dotHoldSwipe)
  }

  /** Receives gesture hold to perform action. */
  @CanIgnoreReturnValue
  fun performDotHoldAction(pointersHeldCount: Int): Boolean {
    if (pointersHeldCount == 1 || pointersHeldCount == 2 || pointersHeldCount == 3) {
      BrailleImeVibrator.getInstance(context).vibrate(VibrationType.HOLD)
      return true
    }
    return false
  }

  fun performTyping(brailleChar: BrailleCharacter): String? {
    val result = brailleImeActor!!.performTyping(brailleChar)
    brailleImeAnalytics.logTotalBrailleCharCount(1)
    BrailleImeVibrator.getInstance(context).vibrate(VibrationType.BRAILLE_COMMISSION)
    return result
  }

  private fun performAction(gesture: Gesture): Boolean {
    val currentActions = BrailleImeGestureAction.getAction(context, gesture)
    val defaultActions = BrailleImeGestureAction.getDefaultAction(gesture)
    // Use the root one.
    val optionalBrailleImeAction: Optional<BrailleImeAction> =
      currentActions.stream().filter { action -> action == action.getRootAction() }.findFirst()
    if (optionalBrailleImeAction.isEmpty()) {
      BrailleImeLog.d(TAG, "No BrailleImeAction")
      return false
    }
    val action = optionalBrailleImeAction.get()
    val result = brailleImeActor!!.performAction(action)
    performFeedback(action)
    performLogging(action, currentActions == defaultActions)
    return result
  }

  private fun performFeedback(action: BrailleImeAction) {
    when (action) {
      BrailleImeAction.HIDE_KEYBOARD,
      BrailleImeAction.SWITCH_KEYBOARD,
      BrailleImeAction.HELP_AND_OTHER_ACTIONS,
      BrailleImeAction.SUBMIT_TEXT ->
        BrailleImeVibrator.getInstance(context).vibrate(VibrationType.OTHER_GESTURES)
      BrailleImeAction.NEXT_GRANULARITY,
      BrailleImeAction.PREVIOUS_GRANULARITY,
      BrailleImeAction.ADD_SPACE_OR_NEXT_ITEM,
      BrailleImeAction.DELETE_CHARACTER_OR_PREVIOUS_ITEM,
      BrailleImeAction.MOVE_CURSOR_FORWARD,
      BrailleImeAction.MOVE_CURSOR_BACKWARD ->
        BrailleImeVibrator.getInstance(context)
          .vibrate(VibrationType.SPACE_DELETE_OR_MOVE_CURSOR_OR_GRANULARITY)
      BrailleImeAction.ADD_NEWLINE, BrailleImeAction.DELETE_WORD ->
        BrailleImeVibrator.getInstance(context).vibrate(VibrationType.NEWLINE_OR_DELETE_WORD)
      else -> {
        // do nothing.
      }
    }
  }

  /**
   * Logs the gesture action.
   *
   * @param action the gesture action.
   * @param customized whether the gesture action is customized.
   */
  private fun performLogging(action: BrailleImeAction, customized: Boolean) {
    when (action) {
      BrailleImeAction.HIDE_KEYBOARD -> {
        brailleImeAnalytics.logGestureActionCloseKeyboard()
        brailleImeAnalytics.sendAllLogs()
      }
      BrailleImeAction.SWITCH_KEYBOARD -> brailleImeAnalytics.logGestureActionSwitchKeyboard()
      BrailleImeAction.NEXT_GRANULARITY ->
        brailleImeAnalytics.logGestureActionSwitchToNextEditingGranularity(customized)
      BrailleImeAction.PREVIOUS_GRANULARITY ->
        brailleImeAnalytics.logGestureActionSwitchToPreviousEditingGranularity(customized)
      BrailleImeAction.PASTE -> brailleImeAnalytics.logGestureActionPaste(customized)
      BrailleImeAction.CUT -> brailleImeAnalytics.logGestureActionCut(customized)
      BrailleImeAction.COPY -> brailleImeAnalytics.logGestureActionCopy(customized)
      BrailleImeAction.SUBMIT_TEXT -> {
        brailleImeAnalytics.logGestureActionSubmitText(customized)
        brailleImeAnalytics.collectSessionEvents()
      }
      BrailleImeAction.ADD_SPACE_OR_NEXT_ITEM ->
        brailleImeAnalytics.logGestureActionKeySpace(customized)
      BrailleImeAction.ADD_NEWLINE -> brailleImeAnalytics.logGestureActionKeyNewline(customized)
      BrailleImeAction.DELETE_CHARACTER_OR_PREVIOUS_ITEM ->
        brailleImeAnalytics.logGestureActionKeyDeleteCharacter(customized)
      BrailleImeAction.DELETE_WORD -> brailleImeAnalytics.logGestureActionKeyDeleteWord(customized)
      BrailleImeAction.MOVE_CURSOR_FORWARD ->
        brailleImeAnalytics.logGestureActionMoveCursorForward(customized)
      BrailleImeAction.MOVE_CURSOR_BACKWARD ->
        brailleImeAnalytics.logGestureActionMoveCursorBackward(customized)
      BrailleImeAction.NEXT_CHARACTER ->
        brailleImeAnalytics.logGestureActionMoveCursorForwardByCharacter(customized)
      BrailleImeAction.PREVIOUS_CHARACTER ->
        brailleImeAnalytics.logGestureActionMoveCursorBackwardByCharacter(customized)
      BrailleImeAction.NEXT_WORD ->
        brailleImeAnalytics.logGestureActionMoveCursorForwardByWord(customized)
      BrailleImeAction.PREVIOUS_WORD ->
        brailleImeAnalytics.logGestureActionMoveCursorBackwardByWord(customized)
      BrailleImeAction.NEXT_LINE ->
        brailleImeAnalytics.logGestureActionMoveCursorForwardByLine(customized)
      BrailleImeAction.PREVIOUS_LINE ->
        brailleImeAnalytics.logGestureActionMoveCursorBackwardByLine(customized)
      BrailleImeAction.BEGINNING_OF_PAGE ->
        brailleImeAnalytics.logGestureActionMoveCursorToBeginning(customized)
      BrailleImeAction.END_OF_PAGE ->
        brailleImeAnalytics.logGestureActionMoveCursorToEnd(customized)
      BrailleImeAction.SELECT_NEXT_CHARACTER ->
        brailleImeAnalytics.logGestureActionSelectNextCharacter(customized)
      BrailleImeAction.SELECT_PREVIOUS_CHARACTER ->
        brailleImeAnalytics.logGestureActionSelectPreviousCharacter(customized)
      BrailleImeAction.SELECT_NEXT_WORD ->
        brailleImeAnalytics.logGestureActionSelectNextWord(customized)
      BrailleImeAction.SELECT_PREVIOUS_WORD ->
        brailleImeAnalytics.logGestureActionSelectPreviousWord(customized)
      BrailleImeAction.SELECT_NEXT_LINE -> {
        // TODO: As the text selection for line granularity movement does not work, we
        // mask off the action of selecting text by line.
        // brailleImeAnalytics.logGestureActionSelectNextLine();
      }
      BrailleImeAction.SELECT_PREVIOUS_LINE -> {
        // TODO: As the text selection for line granularity movement does not work, we
        // mask off the action of selecting text by line.
        // brailleImeAnalytics.logGestureActionSelectPreviousLine();
      }
      BrailleImeAction.SELECT_ALL ->
        brailleImeAnalytics.logGestureActionSelectAllText(customized)
      BrailleImeAction.SELECT_CURRENT_TO_START ->
        brailleImeAnalytics.logGestureActionSelectCursorToStart(customized)
      BrailleImeAction.SELECT_CURRENT_TO_END ->
        brailleImeAnalytics.logGestureActionSelectCursorToEnd(customized)
      else -> {
        // do nothing.
      }
    }
  }

  @VisibleForTesting
  fun testing_setBrailleImeAnalytics(brailleImeAnalytics: BrailleImeAnalytics) {
    this.brailleImeAnalytics = brailleImeAnalytics
  }

  companion object {
    private const val TAG = "BrailleImeGestureController"
  }
}
