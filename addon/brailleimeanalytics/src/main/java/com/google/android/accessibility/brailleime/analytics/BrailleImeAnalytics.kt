/*
 * Copyright (C) 2019 Google Inc.
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
package com.google.android.accessibility.brailleime.analytics

import android.content.Context

/** Stub implementation of analytics used by the open source variant. */
class BrailleImeAnalytics private constructor(context: Context) {

  /** Context menu selections. */
  enum class ContextMenuSelections {
    UNSPECIFIED_OPTION,
    SWITCH_CONTRACTED_STATUS,
    SEE_ALL_ACTIONS,
    CALIBRATION,
    TUTORIAL_OPEN,
    TUTORIAL_FINISH,
    GO_TO_SETTINGS,
    TYPING_LANGUAGE,
  }

  /** Calibration triggered type. */
  enum class CalibrationTriggeredType {
    UNSPECIFIED_FINGER,
    FIVE_FINGER,
    SIX_FINGER,
    SEVEN_FINGER,
    EIGHT_FINGER,
    MANUAL,
  }

  /** Stub implementation does nothing. */
  fun sendAllLogs() {}

  /** Stub implementation does nothing. */
  fun startSession() {}

  /** Stub implementation does nothing. */
  fun collectSessionEvents() {}

  /** Stub implementation does nothing. */
  fun logGestureActionKeySpace(customized: Boolean) {}

  /** Stub implementation does nothing. */
  fun logGestureActionKeyDeleteCharacter(customized: Boolean) {}

  /** Stub implementation does nothing. */
  fun logGestureActionKeyDeleteWord(customized: Boolean) {}

  /** Stub implementation does nothing. */
  fun logGestureActionKeyNewline(customized: Boolean) {}

  /** Stub implementation does nothing. */
  fun logGestureActionSubmitText(customized: Boolean) {}

  /** Stub implementation does nothing. */
  fun logGestureActionCloseKeyboard() {}

  /** Stub implementation does nothing. */
  fun logGestureActionSwitchKeyboard() {}

  /** Stub implementation does nothing. */
  fun logGestureActionOpenOptionsMenu() {}

  /** Stub implementation does nothing. */
  fun logGestureActionMoveCursorForward(customized: Boolean) {}

  /** Stub implementation does nothing. */
  fun logGestureActionMoveCursorBackward(customized: Boolean) {}

  /** Stub implementation does nothing. */
  fun logContractedToggle(contractedModeOn: Boolean) {}

  /** Stub implementation does nothing. */
  fun logTalkBackOffDialogDisplay() {}

  /** Stub implementation does nothing. */
  fun logFewTouchPointsDialogDisplay() {}

  /** Stub implementation does nothing. */
  fun logTotalBrailleCharCount(numOfChar: Int) {}

  /** Stub implementation does nothing. */
  fun logTutorialFinishedByTutorialCompleted() {}

  /** Stub implementation does nothing. */
  fun logTutorialFinishedByTalkbackStop() {}

  /** Stub implementation does nothing. */
  fun logTutorialFinishedByLaunchSettings() {}

  /** Stub implementation does nothing. */
  fun logTutorialFinishedBySwitchToNextInputMethod() {}

  /** Stub implementation does nothing. */
  fun logContextMenuOptionCount(contextMenuSelections: ContextMenuSelections) {}

  /** Logs for finishing calibration. */
  fun logCalibrationFinish(
    calibrationType: CalibrationTriggeredType,
    tabletop: Boolean,
    isEight: Boolean,
  ) {}

  /** Logs for calibration failure. */
  fun logCalibrationFailed(
    calibrationType: CalibrationTriggeredType,
    tabletop: Boolean,
    isEight: Boolean,
  ) {}

  /** Logs for starting calibration. */
  fun logCalibrationStarted(
    calibrationType: CalibrationTriggeredType,
    tabletop: Boolean,
    isEight: Boolean,
  ) {}

  /** Stub implementation does nothing. */
  fun logGestureActionMoveCursorForwardByCharacter(customized: Boolean) {}

  /** Stub implementation does nothing. */
  fun logGestureActionMoveCursorBackwardByCharacter(customized: Boolean) {}

  /** Stub implementation does nothing. */
  fun logGestureActionMoveCursorForwardByWord(customized: Boolean) {}

  /** Stub implementation does nothing. */
  fun logGestureActionMoveCursorBackwardByWord(customized: Boolean) {}

  /** Stub implementation does nothing. */
  fun logGestureActionMoveCursorForwardByLine(customized: Boolean) {}

  /** Stub implementation does nothing. */
  fun logGestureActionMoveCursorBackwardByLine(customized: Boolean) {}

  /** Stub implementation does nothing. */
  fun logGestureActionMoveCursorToBeginning(customized: Boolean) {}

  /** Stub implementation does nothing. */
  fun logGestureActionMoveCursorToEnd(customized: Boolean) {}

  /** Stub implementation does nothing. */
  fun logGestureActionSelectNextCharacter(customized: Boolean) {}

  /** Stub implementation does nothing. */
  fun logGestureActionSelectPreviousCharacter(customized: Boolean) {}

  /** Stub implementation does nothing. */
  fun logGestureActionSelectNextWord(customized: Boolean) {}

  /** Stub implementation does nothing. */
  fun logGestureActionSelectPreviousWord(customized: Boolean) {}

  /** Stub implementation does nothing. */
  fun logGestureActionSelectNextLine(customized: Boolean) {}

  /** Stub implementation does nothing. */
  fun logGestureActionSelectPreviousLine(customized: Boolean) {}

  /** Stub implementation does nothing. */
  fun logGestureActionSelectAllText(customized: Boolean) {}

  /** Stub implementation does nothing. */
  fun logGestureActionSelectCursorToStart(customized: Boolean) {}

  /** Stub implementation does nothing. */
  fun logGestureActionSelectCursorToEnd(customized: Boolean) {}

  /** Stub implementation does nothing. */
  fun logGestureActionCut(customized: Boolean) {}

  /** Stub implementation does nothing. */
  fun logGestureActionCopy(customized: Boolean) {}

  /** Stub implementation does nothing. */
  fun logGestureActionPaste(customized: Boolean) {}

  /** Stub implementation does nothing. */
  fun logGestureActionSwitchToNextEditingGranularity(customized: Boolean) {}

  /** Stub implementation does nothing. */
  fun logGestureActionSwitchToPreviousEditingGranularity(customized: Boolean) {}

  companion object {
    private var instance: BrailleImeAnalytics? = null

    @JvmStatic
    fun getInstance(context: Context): BrailleImeAnalytics {
      if (instance == null) {
        instance = BrailleImeAnalytics(context.applicationContext)
      }
      return instance!!
    }
  }
}
