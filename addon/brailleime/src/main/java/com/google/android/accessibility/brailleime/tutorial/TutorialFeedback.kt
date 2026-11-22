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

package com.google.android.accessibility.brailleime.tutorial

import android.text.TextUtils
import com.google.android.accessibility.braille.common.TalkBackSpeaker
import com.google.android.accessibility.braille.common.translate.BrailleTranslateUtils
import com.google.android.accessibility.braille.common.translate.BrailleTranslateUtilsUeb
import com.google.android.accessibility.braille.interfaces.BrailleCharacter
import com.google.android.accessibility.braille.interfaces.BrailleWord
import com.google.android.accessibility.brailleime.BrailleImeVibrator
import com.google.android.accessibility.brailleime.BrailleImeVibrator.VibrationType
import com.google.android.accessibility.brailleime.OrientationMonitor
import com.google.android.accessibility.brailleime.OrientationMonitor.Orientation
import com.google.android.accessibility.brailleime.R
import com.google.android.accessibility.brailleime.input.BrailleInputPlane.DotTarget
import com.google.android.accessibility.brailleime.input.Swipe
import com.google.android.accessibility.brailleime.input.Swipe.Direction
import com.google.android.accessibility.utils.output.SpeechController
import com.google.android.accessibility.utils.output.SpeechController.UtteranceCompleteRunnable

/**
 * 教程视图的播报与触感反馈：从 [TutorialView] 拆出的同包扩展函数。
 *
 * 这里只负责「把点位/手势事件读出来、按空闲次数决定是否重播指令、按手势给震动」，
 * 不持有教程步骤状态；步骤流程见 TutorialEntryStates / TutorialLetterStates /
 * TutorialSwipeStates / TutorialTerminalStates。
 */

/**
 * Speaks the events of Braille dots and swipe gestures. If the user has tried a fixed number of
 * interactions, speak the instruction.
 */
internal fun TutorialView.speakUserEventAnnouncement(textToSpeak: String, delayMs: Int) {
  if (!allowExploration) {
    return
  }
  if (numberOfInteractionsPerState >= TutorialView.FREE_INTERACTIONS_MAX) {
    speakAnnouncement(
      context.getString(
        R.string.continue_tutorial_announcement, state.audialAnnouncementRepeated(),
      ),
      /* delayMs= */ 0,
    )
  } else {
    tutorialCallback.onAudialAnnounce(
      textToSpeak,
      delayMs,
      TalkBackSpeaker.AnnounceType.INTERRUPT,
      /* utteranceCompleteRunnable= */ null,
    )
    numberOfInteractionsPerState++
  }
}

/**
 * Speaks the instructions. Repeat the instruction or go to next step once callback is invoked.
 */
internal fun TutorialView.speakAnnouncement(textToSpeak: String, delayMs: Int) {
  val host: TutorialView = this
  allowExploration = false
  handler.removeCallbacksAndMessages(/* token= */ null)
  val speechId = instructionSpeechId.incrementAndGet()
  tutorialCallback.onAudialAnnounce(
    textToSpeak,
    delayMs,
    TalkBackSpeaker.AnnounceType.INTERRUPT,
    object : UtteranceCompleteRunnable {
      override fun run(status: Int) {
        // When incoming speech uses {@link SpeechController.QUEUE_MODE_INTERRUPT}, the
        // sequence is
        // 1. second speech onUtteranceStarted
        // 2. first speech onUtteranceCompleted
        // 3. second speech is playing. A few seconds later.
        // 4. second speech onUtteranceCompleted.
        //
        // Put a condition here so that when first speech onUtteranceCompleted, we don't start
        // countdown. Otherwise, it will report onIdle() but actually second speech is playing.
        if (status == SpeechController.STATUS_INTERRUPTED) {
          if (speechId == instructionSpeechId.get()) {
            // Announcement is interrupted by non-tutorial speech. Go to next step immediately if
            // action is completed. Otherwise, repeat the instruction in a certain time.
            val postDelayedTime: Int =
              if (state.isActionCompleted()) 0
              else TutorialView.AUDIAL_ANNOUNCEMENT_IDLE_FOR_EXTERNAL_SPEECH_MS
            handler.postDelayed(host::onUtteranceCompleted, postDelayedTime.toLong())
          }
          return
        }
        onUtteranceCompleted()
      }
    },
  )
}

internal fun TutorialView.speakSwipeEvent(swipe: Swipe) {
  var action: String? = null
  val touchCount = swipe.touchCount
  val direction = swipe.direction
  if (direction == Direction.DOWN && touchCount == 2) {
    action = resources.getString(R.string.perform_hide_keyboard_announcement)

  } else if (direction == Direction.DOWN && touchCount == 3) {
    action = resources.getString(R.string.perform_switch_keyboard_announcement)

  } else if (direction == Direction.UP && touchCount == 2) {
    action = resources.getString(R.string.perform_submit_text_announcement)

  } else if (direction == Direction.RIGHT && touchCount == 1) {
    action = resources.getString(R.string.perform_add_space_announcement)

  } else if (direction == Direction.RIGHT && touchCount == 2) {
    action = resources.getString(R.string.perform_add_new_line_announcement)

  } else if (direction == Direction.LEFT && touchCount == 1) {
    action = resources.getString(R.string.perform_delete_letter_announcement)

  } else if (direction == Direction.LEFT && touchCount == 2) {
    action = resources.getString(R.string.perform_delete_word_announcement)
  }

  if (action != null) {
    speakUserEventAnnouncement(action, /* delayMs= */ 0)
  }
}

internal fun TutorialView.vibrateForSwipeGestures(swipe: Swipe) {
  val touchCount = swipe.touchCount
  val direction = swipe.direction
  if (direction == Direction.UP && touchCount == 1) {
    // Move text cursor backward.
    BrailleImeVibrator.getInstance(context)
      .vibrate(VibrationType.SPACE_DELETE_OR_MOVE_CURSOR_OR_GRANULARITY)
  } else if (direction == Direction.DOWN && touchCount == 1) {
    // Move text cursor forward.
    BrailleImeVibrator.getInstance(context)
      .vibrate(VibrationType.SPACE_DELETE_OR_MOVE_CURSOR_OR_GRANULARITY)
  } else if (direction == Direction.DOWN && touchCount == 2) {
    // Close keyboard.
    BrailleImeVibrator.getInstance(context).vibrate(VibrationType.OTHER_GESTURES)
  } else if (direction == Direction.DOWN && touchCount == 3) {
    // Switch keyboard.
    BrailleImeVibrator.getInstance(context).vibrate(VibrationType.OTHER_GESTURES)
  } else if (direction == Direction.UP && touchCount == 2) {
    // Submit.
    BrailleImeVibrator.getInstance(context).vibrate(VibrationType.OTHER_GESTURES)
  } else if (direction == Direction.RIGHT && touchCount == 1) {
    // Space.
    BrailleImeVibrator.getInstance(context)
      .vibrate(VibrationType.SPACE_DELETE_OR_MOVE_CURSOR_OR_GRANULARITY)
  } else if (direction == Direction.RIGHT && touchCount == 2) {
    // Newline.
    BrailleImeVibrator.getInstance(context).vibrate(VibrationType.NEWLINE_OR_DELETE_WORD)
  } else if (direction == Direction.LEFT && touchCount == 1) {
    // Delete.
    BrailleImeVibrator.getInstance(context)
      .vibrate(VibrationType.SPACE_DELETE_OR_MOVE_CURSOR_OR_GRANULARITY)
  } else if (direction == Direction.LEFT && touchCount == 2) {
    // Delete word.
    BrailleImeVibrator.getInstance(context).vibrate(VibrationType.NEWLINE_OR_DELETE_WORD)
  } else if (swipe.direction == Direction.UP && swipe.touchCount == 3) {
    // Open context menu.
    BrailleImeVibrator.getInstance(context).vibrate(VibrationType.OTHER_GESTURES)
  } else if (swipe.direction == Direction.LEFT && swipe.touchCount == 3) {
    // Open context menu in portrait. Braille keyboard view is forced to be in landscape. When
    // device is portrait and user swipes upward, for keyboard view, it's swipe rightward.
    if (OrientationMonitor.getInstance().currentOrientation == Orientation.PORTRAIT) {
      BrailleImeVibrator.getInstance(context).vibrate(VibrationType.OTHER_GESTURES)
    }
  }
}

internal fun TutorialView.speakBrailleCharacter(brailleCharacter: BrailleCharacter, delayMs: Int) {
  var textToSpeak: String =
    BrailleTranslateUtilsUeb.getTextToSpeak(resources, brailleCharacter)
  if (TextUtils.isEmpty(textToSpeak)) {
    textToSpeak = translator.translateToPrint(BrailleWord(brailleCharacter))
  }
  if (TextUtils.isEmpty(textToSpeak) || !BrailleTranslateUtils.isPronounceable(textToSpeak)) {
    textToSpeak = BrailleTranslateUtils.getDotsText(resources, brailleCharacter)
  }
  speakUserEventAnnouncement(textToSpeak, delayMs)
}

internal fun TutorialView.openContextMenu() {
  contextMenuDialog.show(this)
  speakAnnouncement(
    resources.getString(R.string.open_context_menu_announcement), /* delayMs= */ 0,
  )
}

internal fun TutorialView.getDotTargetsForBrailleCharacter(
  brailleCharacter: BrailleCharacter,
): List<DotTarget> =
  inputView.getDotTargets().filter { dotTarget ->
    brailleCharacter.isDotNumberOn(dotTarget.getDotNumber())
  }
