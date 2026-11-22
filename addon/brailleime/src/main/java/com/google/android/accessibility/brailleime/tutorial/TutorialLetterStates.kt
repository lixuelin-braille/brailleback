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

import android.animation.Animator
import android.animation.Animator.AnimatorListener
import android.view.View
import com.google.android.accessibility.braille.common.FeedbackManager
import com.google.android.accessibility.braille.common.translate.BrailleTranslateUtils
import com.google.android.accessibility.braille.common.translate.BrailleTranslateUtilsUeb
import com.google.android.accessibility.braille.interfaces.BrailleCharacter
import com.google.android.accessibility.braille.interfaces.BrailleWord
import com.google.android.accessibility.brailleime.BrailleImeVibrator
import com.google.android.accessibility.brailleime.BrailleImeVibrator.VibrationType
import com.google.android.accessibility.brailleime.R
import com.google.android.accessibility.brailleime.input.BrailleInputPlane.DotTarget
import com.google.android.accessibility.brailleime.input.Swipe
import com.google.common.base.Ascii
import com.google.android.accessibility.brailleime.tutorial.TutorialView.TutorialState.State

/**
 * 字母点位教学步骤：从 [TutorialView] 拆出的打字段状态。
 *
 * 先打字母 A（点位 1），再依次打 B、C、D；每步校验上屏字符、播读结果并切到下一步。
 */

class TypeLetterA(private val host: TutorialView) : TutorialView.TutorialState {
  private var actionCompleted: Boolean = false

  override fun getCurrentState(): State {
    return State.TYPE_LETTER_A
  }

  override fun nextState(): TutorialView.TutorialState {
    return host.tapLetterBCD
  }

  override fun loadView() {
    host.dotBlockView.setAlpha(0f)
    host.dotBlockView.animate().alpha(1f).setDuration(TutorialView.ANIMATION_DURATION_MS.toLong())
    host.addView(host.dotBlockView)
    // Get dot 1 coordinate to show flashing animation.
    host.dotsFlashingAnimationView =
      DotsFlashingAnimationView(
        host.context,
        host.getDotTargetsForBrailleCharacter(BrailleTranslateUtilsUeb.LETTER_A),
        host.orientation,
        host.isTabletop,
      )
    host.dotsFlashingAnimationView!!.setVisibility(View.INVISIBLE)
    host.inputView.setAlpha(0f)
    host.inputView
      .animate()
      .alpha(1f)
      .setDuration(TutorialView.INPUT_VIEW_ANIMATION_DURATION_MS.toLong())
      .setStartDelay(TutorialView.ANIMATION_DURATION_MS.toLong())
      .setListener(
        object : AnimatorListener {
          override fun onAnimationStart(animation: Animator) {}

          override fun onAnimationEnd(animation: Animator) {
            host.postDelayed(
              {
                host.removeView(host.inputView)
                host.addView(host.inputView, 0)
                host.dotsFlashingAnimationView!!.setVisibility(View.VISIBLE)
                host.tutorialAnimationView.startHintToastAnimation(host.state.hintText())
              },
              TutorialView.FLASHING_ANIMATION_DELAY_MS.toLong(),
            )
          }

          override fun onAnimationCancel(animation: Animator) {}

          override fun onAnimationRepeat(animation: Animator) {}
        })
    host.addView(host.inputView)
    host.addView(host.dotsFlashingAnimationView!!)
    host.tutorialAnimationView.reset()
    host.addView(host.tutorialAnimationView)
    host.speakAnnouncement(audialAnnouncement(), /* delayMs= */ 0)
  }

  override fun audialAnnouncement(): String {
    return host.resources.getString(R.string.type_letter_a_announcement)
  }

  override fun audialAnnouncementRepeated(): String {
    return host.resources.getString(R.string.type_letter_a_inactive_announcement)
  }

  override fun hintText(): String {
    return host.resources.getString(R.string.instruction_type_letter_a)
  }

  override fun isActionCompleted(): Boolean {
    return actionCompleted
  }

  override fun onUtteranceCompleted() {
    if (actionCompleted) {
      actionCompleted = false
      host.switchState(nextState(), /* delay= */ 0)
    } else {
      host.speakAnnouncement(audialAnnouncementRepeated(), /* delayMs= */ 0)
    }
  }

  override fun onBrailleProduced(brailleCharacter: BrailleCharacter) {
    if (actionCompleted) {
      return
    }
    BrailleImeVibrator.getInstance(host.context).vibrate(VibrationType.BRAILLE_COMMISSION)
    // Dot 1 is clicked.
    if (brailleCharacter.toByte().toInt() == 0b01) {
      host.tutorialAnimationView.startActionResultAnimation(
        Ascii.toUpperCase(host.translator.translateToPrint(BrailleWord(brailleCharacter))),
      )
      host.tutorialCallback.onPlaySound(FeedbackManager.Type.BEEP)
      // Wait a second for playing sound and then speak the post-action announcement.
      val textToSpeak = host.translator.translateToPrint(BrailleWord(brailleCharacter))
      host.speakAnnouncement(textToSpeak, TutorialView.BEEP_DELAY_MS)
      actionCompleted = true
    } else {
      // Read out the corresponding announcement.
      host.speakBrailleCharacter(brailleCharacter, /* delayMs= */ 0)
    }
  }

  override fun onSwipeProduced(swipe: Swipe) {
    if (actionCompleted) {
      return
    }

    // Read out the corresponding announcement and do nothing.
    host.speakSwipeEvent(swipe)
    host.vibrateForSwipeGestures(swipe)
  }
}

class TypeLetterBCD(private val host: TutorialView) : TutorialView.TutorialState {
  private var actionCompleted: Boolean = false
  private val remainLetters: MutableList<Char> =
    mutableListOf('B', 'C', 'D')

  override fun getCurrentState(): State {
    return State.TYPE_LETTER_BCD
  }

  override fun nextState(): TutorialView.TutorialState {
    if (remainLetters.isEmpty()) {
      return host.swipeLeft
    }
    return this
  }

  override fun loadView() {
    host.addView(host.inputView)
    val brailleCharacter: BrailleCharacter =
      when (remainLetters[0]) {
        'B' -> BrailleTranslateUtilsUeb.LETTER_B
        'C' -> BrailleTranslateUtilsUeb.LETTER_C
        'D' -> BrailleTranslateUtilsUeb.LETTER_D
        else -> throw IllegalStateException("Unexpected value: " + remainLetters[0])
      }
    val animationDots: List<DotTarget> = host.getDotTargetsForBrailleCharacter(brailleCharacter)
    host.dotsFlashingAnimationView =
      DotsFlashingAnimationView(host.context, animationDots, host.orientation, host.isTabletop)
    host.addView(host.dotsFlashingAnimationView!!)
    host.tutorialAnimationView.reset()
    host.tutorialAnimationView.startHintToastAnimation(host.state.hintText())
    host.addView(host.tutorialAnimationView)
    host.speakAnnouncement(audialAnnouncement(), /* delayMs= */ 0)
  }

  override fun audialAnnouncement(): String {
    if (remainLetters.size == TutorialView.TOTAL_LETTERS) {
      return host.resources.getString(R.string.instruction_type_letter_bcd)
    }
    return audialAnnouncementRepeated()
  }

  override fun audialAnnouncementRepeated(): String {
    return when (remainLetters[0]) {
      'B' ->
        host.resources
          .getString(
            R.string.type_letter_inactive_announcement,
            BrailleTranslateUtils.getDotsText(
              host.resources, BrailleTranslateUtilsUeb.LETTER_B,
            ),
            remainLetters[0],
          )
      'C' ->
        host.resources
          .getString(
            R.string.type_letter_inactive_announcement,
            BrailleTranslateUtils.getDotsText(
              host.resources, BrailleTranslateUtilsUeb.LETTER_C,
            ),
            remainLetters[0],
          )
      'D' ->
        host.resources
          .getString(
            R.string.type_letter_inactive_announcement,
            BrailleTranslateUtils.getDotsText(
              host.resources, BrailleTranslateUtilsUeb.LETTER_D,
            ),
            remainLetters[0],
          )
      else ->
        // Should not happen.
        host.resources
          .getString(
            R.string.type_letter_inactive_announcement,
            BrailleTranslateUtils.getDotsText(
              host.resources, BrailleTranslateUtilsUeb.LETTER_B,
            ),
            remainLetters[0],
          )
    }
  }

  override fun hintText(): String {
    return host.resources.getString(R.string.instruction_type_letter_bcd)
  }

  override fun isActionCompleted(): Boolean {
    return actionCompleted
  }

  override fun onUtteranceCompleted() {
    if (actionCompleted) {
      actionCompleted = false
      host.switchState(nextState(), /* delay= */ 0)
    } else {
      host.speakAnnouncement(audialAnnouncementRepeated(), /* delayMs= */ 0)
    }
  }

  override fun onBrailleProduced(brailleCharacter: BrailleCharacter) {
    if (actionCompleted) {
      return
    }
    BrailleImeVibrator.getInstance(host.context).vibrate(VibrationType.BRAILLE_COMMISSION)

    val result =
      Ascii.toUpperCase(host.translator.translateToPrint(BrailleWord(brailleCharacter)))
    var character: Char = Char.MAX_VALUE
    if (result.length > 0) {
      character = result[0]
    }

    if (remainLetters.contains(character)) {
      host.tutorialAnimationView.startActionResultAnimation(result)
      host.tutorialCallback.onPlaySound(FeedbackManager.Type.BEEP)
      // Wait a second for playing sound and then speak the post-action announcement.
      val textToSpeak = host.translator.translateToPrint(BrailleWord(brailleCharacter))
      host.speakAnnouncement(textToSpeak, TutorialView.BEEP_DELAY_MS)
      actionCompleted = true
      remainLetters.remove(character)
    } else {
      // Read out the corresponding announcement.
      host.speakBrailleCharacter(brailleCharacter, /* delayMs= */ 0)
    }
  }

  override fun onSwipeProduced(swipe: Swipe) {
    if (actionCompleted) {
      return
    }
    // Read out the corresponding announcement and do nothing.
    host.speakSwipeEvent(swipe)
    host.vibrateForSwipeGestures(swipe)
  }
}
