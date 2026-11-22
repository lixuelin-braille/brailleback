/*
 * Copyright 2020 Google Inc.
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
import android.os.Handler
import androidx.annotation.VisibleForTesting
import com.google.android.accessibility.braille.common.BrailleUserPreferences
import com.google.android.accessibility.utils.output.SpeechController
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Tell user how to escape braille keyboard. The reminder will announce when no any braille
 * characters are produced or any legitimate gestures occur in 15 seconds. Also, the reminder
 * happens only in following cases: 1. option dialog shows less than 3 times. 2. escape keyboard
 * less than 5 times. After the first reminder announces, the second reminder will happen 30 seconds
 * later. We announce at most three times.
 */
class EscapeReminder(context: Context, private val callback: Callback) {
  interface Callback {
    fun onRemind(utteranceCompleteRunnable: SpeechController.UtteranceCompleteRunnable)

    fun shouldAnnounce(): Boolean
  }

  private val context: Context
  private val handler: Handler
  private var reminderCount = 0
  private var optionsDialogCounter: Int
  private var exitKeyboardCounter: Int
  private val finishSpeaking = AtomicBoolean(true)

  @VisibleForTesting
  var utteranceCompleteRunnable: SpeechController.UtteranceCompleteRunnable =
    object : SpeechController.UtteranceCompleteRunnable {
      override fun run(status: Int) {
        finishSpeaking.set(true)
        // The announce thrown before keyboard turns off will still play, don't start timer here
        // so it won't repeat.
        if (callback.shouldAnnounce()) {
          startTimer()
        }
      }
    }

  init {
    this.context = context
    this.handler =
      Handler(context.mainLooper) { msg ->
        if (msg.what == MSG_LEAVE_REMINDER) {
          finishSpeaking.set(false)
          callback.onRemind(utteranceCompleteRunnable)
          reminderCount++
          true
        } else {
          false
        }
      }
    this.optionsDialogCounter = BrailleUserPreferences.readShowOptionDialogCount(context)
    this.exitKeyboardCounter = BrailleUserPreferences.readExitKeyboardCount(context)
  }

  /** Starts countdown. */
  fun startTimer() {
    // There is a condition that startTimer() is called between the announce task sent to TalkBack
    // but it hasn't been spoken. Introduce finishSpeaking to stop timer starting here.
    if (!handler.hasMessages(MSG_LEAVE_REMINDER) && finishSpeaking.get()) {
      if (shouldAnnounceReminder()) {
        handler.sendEmptyMessageDelayed(
          MSG_LEAVE_REMINDER, (IDLE_THRESHOLD_DURATION_MS * reminderCount).toLong())
      }
    }
  }

  /** Cancels countdown. */
  fun cancelTimer() {
    handler.removeCallbacksAndMessages(null)
  }

  /** Restarts countdown. */
  fun restartTimer() {
    cancelTimer()
    startTimer()
  }

  /** Increase show option dialog counter to decide whether to announce reminder. */
  fun increaseOptionDialogCounter() {
    optionsDialogCounter++
    if (optionsDialogCounter <= MAX_SHOW_OPTION_DIALOG_COUNT) {
      BrailleUserPreferences.writeShowOptionDialogCount(context, optionsDialogCounter)
    }
  }

  /** Increase exit keyboard counter to decide whether to announce reminder. */
  fun increaseExitKeyboardCounter() {
    exitKeyboardCounter++
    if (optionsDialogCounter <= MAX_EXIT_KEYBOARD_COUNT) {
      BrailleUserPreferences.writeExitKeyboardCount(context, exitKeyboardCounter)
    }
  }

  /**
   * Announce every time keyboard is first displayed; otherwise, restrict the announcement to when
   * show context menu counts and exit keyboard counts exceeds constraints.
   */
  private fun shouldAnnounceReminder(): Boolean {
    return reminderCount == 0 ||
      (reminderCount < MAX_REMINDER_COUNT &&
        (optionsDialogCounter < MAX_SHOW_OPTION_DIALOG_COUNT ||
          exitKeyboardCounter < MAX_EXIT_KEYBOARD_COUNT))
  }

  companion object {
    @VisibleForTesting const val IDLE_THRESHOLD_DURATION_MS = 15000
    private const val MSG_LEAVE_REMINDER = 0
    private const val MAX_REMINDER_COUNT = 3
    private const val MAX_SHOW_OPTION_DIALOG_COUNT = 3
    private const val MAX_EXIT_KEYBOARD_COUNT = 5
  }
}
