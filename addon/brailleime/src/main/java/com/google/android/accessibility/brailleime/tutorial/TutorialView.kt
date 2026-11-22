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

import com.google.android.accessibility.utils.BuildVersionUtils

import android.animation.Animator
import android.animation.Animator.AnimatorListener
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.drawable.AnimationDrawable
import android.os.Bundle
import android.os.Handler
import android.text.Spannable
import android.text.TextUtils
import android.text.style.BackgroundColorSpan
import android.util.Size
import android.view.GestureDetector
import android.view.GestureDetector.SimpleOnGestureListener
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.Nullable
import androidx.annotation.VisibleForTesting
import com.google.android.accessibility.braille.common.BrailleUserPreferences
import com.google.android.accessibility.braille.common.BrailleUtils
import com.google.android.accessibility.braille.common.Constants.BrailleType
import com.google.android.accessibility.braille.common.FeedbackManager
import com.google.android.accessibility.braille.common.TalkBackSpeaker
import com.google.android.accessibility.braille.common.translate.BrailleLanguages.Code
import com.google.android.accessibility.braille.common.translate.BrailleTranslateUtils
import com.google.android.accessibility.braille.common.translate.BrailleTranslateUtilsUeb
import com.google.android.accessibility.braille.interfaces.BrailleCharacter
import com.google.android.accessibility.braille.interfaces.BrailleWord
import com.google.android.accessibility.braille.translate.BrailleTranslator
import com.google.android.accessibility.brailleime.BrailleIme.OrientationSensitive
import com.google.android.accessibility.brailleime.BrailleImeLog
import com.google.android.accessibility.brailleime.BrailleImeVibrator
import com.google.android.accessibility.brailleime.BrailleImeVibrator.VibrationType
import com.google.android.accessibility.brailleime.BrailleInputOptions
import com.google.android.accessibility.brailleime.OrientationMonitor
import com.google.android.accessibility.brailleime.OrientationMonitor.Orientation
import com.google.android.accessibility.brailleime.R
import com.google.android.accessibility.brailleime.dialog.ContextMenuDialog
import com.google.android.accessibility.brailleime.input.BrailleInputPlane.DotTarget
import com.google.android.accessibility.brailleime.input.BrailleInputView
import com.google.android.accessibility.brailleime.input.BrailleInputView.CalibrationTriggeredType
import com.google.android.accessibility.brailleime.input.BrailleInputView.FingersPattern
import com.google.android.accessibility.brailleime.input.DotHoldSwipe
import com.google.android.accessibility.brailleime.input.Swipe
import com.google.android.accessibility.brailleime.input.Swipe.Direction
import com.google.android.accessibility.brailleime.tutorial.TutorialView.TutorialState.State
import com.google.android.accessibility.utils.output.SpeechController
import com.google.android.accessibility.utils.output.SpeechController.UtteranceCompleteRunnable
import com.google.common.base.Ascii
import java.util.concurrent.atomic.AtomicInteger
import android.view.accessibility.AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS
import com.google.android.accessibility.brailleime.BrailleImeVibrator.VibrationType.OTHER_GESTURES
import com.google.android.accessibility.brailleime.tutorial.TutorialAnimationView.SwipeAnimation.Direction.BOTTOM_TO_TOP
import com.google.android.accessibility.brailleime.tutorial.TutorialAnimationView.SwipeAnimation.Direction.LEFT_TO_RIGHT
import com.google.android.accessibility.brailleime.tutorial.TutorialAnimationView.SwipeAnimation.Direction.RIGHT_TO_LEFT
import com.google.android.accessibility.brailleime.tutorial.TutorialAnimationView.SwipeAnimation.Direction.TOP_TO_BOTTOM

/** Views that displays the braille tutorial and there are several {@link State} in the tutorial. */
open class TutorialView(
  context: Context,
  private val tutorialCallback: TutorialCallback,
  screenSize: Size,
) : FrameLayout(context), OrientationSensitive {

  /** An interface of tutorial state machine. */
  interface TutorialState {

    /** The stage in which the tutorial is. */
    enum class State {
      NONE,
      INTRO,
      ROTATE_ORIENTATION,
      ROTATE_ORIENTATION_CONTINUE,
      TYPE_LETTER_A,
      TYPE_LETTER_BCD,
      SWIPE_LEFT,
      SWIPE_RIGHT,
      SWIPE_UP,
      SWIPE_DOWN,
      SWIPE_DOWN_2_FINGERS,
      SWIPE_DOWN_3_FINGERS,
      SWIPE_UP_3_FINGERS,
      CONTEXT_MENU_OPENED,
      HOLD_6_FINGERS,
    }

    fun getCurrentState(): State

    fun nextState(): TutorialState

    fun loadView()

    fun hintText(): String = ""

    fun resultText(): String = ""

    fun audialAnnouncement(): String = ""

    fun audialAnnouncementRepeated(): String = ""

    /** Whether user has completed the guide in the current tutorial step. */
    fun isActionCompleted(): Boolean = false

    /**
     * Invokes when speech announcement is finished. In each tutorial step it should repeat the
     * audio announcement or go to next step once post-action announcement is finished.
     */
    fun onUtteranceCompleted() {}

    fun onDoubleTap() {}

    fun onBrailleProduced(brailleCharacter: BrailleCharacter) {}

    fun onSwipeProduced(swipe: Swipe) {}

    fun onCalibration(type: CalibrationTriggeredType, fingersPattern: FingersPattern) {}

    fun onTwoStepCalibrationRetry(isFirstStep: Boolean) {}
  }

  inner class Intro : TutorialState {
    override fun getCurrentState(): State {
      return State.INTRO
    }

    override fun nextState(): TutorialState {
      return if (isTabletop) holdSixFingers else rotateOrientation
    }

    override fun loadView() {
      tutorialCallback.onBrailleImeInactivated()
      View.inflate(context, R.layout.tutorial_intro, this@TutorialView)
      val animationView = findViewById<ImageView>(R.id.tutorial_animation)
      (animationView.drawable as AnimationDrawable).start()
      findViewById<View>(R.id.next_button)
        .setOnClickListener(
          { view ->
            tutorialCallback.onBrailleImeActivated()
            switchState(nextState(), 0)
          })
      findViewById<View>(R.id.leave_keyboard_button)
        .setOnClickListener(
          { view ->
            // For users who got in accidentally, switch to the next keyboard.
            tutorialCallback.onSwitchToNextInputMethod()
            switchState(State.NONE, /* delay= */ 0)
          })

      // To make TalkBack focus on tutorial view. This is not a good solution, try to find better
      // one later.
      postDelayed(
        {
          findViewById<View>(R.id.tutorial_title)
            .performAccessibilityAction(ACTION_ACCESSIBILITY_FOCUS, Bundle())
        },
        /* delayMillis= */ 1000,
      )
    }
  }

  inner class RotateOrientation : TutorialState {
    private var actionCompleted: Boolean = false

    override fun getCurrentState(): State {
      return State.ROTATE_ORIENTATION
    }

    override fun nextState(): TutorialState {
      val orientation: OrientationMonitor.Orientation =
        OrientationMonitor.getInstance().currentOrientation
      if (orientation == OrientationMonitor.Orientation.PORTRAIT ||
        orientation == OrientationMonitor.Orientation.REVERSE_PORTRAIT
      ) {
        // Guide user to rotate their phone to landscape.
        return rotateOrientationContinue
      }
      return typeLetterA
    }

    override fun loadView() {
      // This view is used to detect swipe gesture and set as transparent to not seen by user.
      inputView.setAlpha(0f)
      addView(inputView)

      View.inflate(context, R.layout.tutorial_rotate_orientation, this@TutorialView)
      findViewById<View>(R.id.tap_to_continue).setBackground(TapMeAnimationDrawable(context))
      speakAnnouncement(audialAnnouncement(), /* delayMs= */ 0)
    }

    override fun onUtteranceCompleted() {
      speakAnnouncement(audialAnnouncementRepeated(), /* delayMs= */ 0)
    }

    override fun audialAnnouncement(): String {
      return resources.getString(
        R.string.rotate_orientation_announcement,
        resources.getString(R.string.rotate_orientation_inactive_announcement),
      )
    }

    override fun audialAnnouncementRepeated(): String {
      return resources.getString(R.string.rotate_orientation_inactive_announcement)
    }

    override fun onDoubleTap() {
      if (!actionCompleted) {
        actionCompleted = true
        // onDoubleTap callback comes twice, delay 500ms to consume this event.
        switchState(nextState(), /* delay= */ 500)
      }
    }

    override fun onSwipeProduced(swipe: Swipe) {
      val touchCount = swipe.touchCount
      val direction = swipe.direction
      if (direction == Direction.DOWN && touchCount == 3) {
        // Exit Braille keyboard.
        tutorialCallback.onSwitchToNextInputMethod()
      } else if (direction == Direction.RIGHT && touchCount == 3) {
        // Braille keyboard view is forced to be in landscape. When device is portrait and user
        // swipes left, for keyboard view, it's swipe down.
        if (OrientationMonitor.getInstance().currentOrientation == Orientation.PORTRAIT) {
          // Exit Braille keyboard.
          tutorialCallback.onSwitchToNextInputMethod()
        }
      }
      vibrateForSwipeGestures(swipe)
    }
  }

  inner class RotateOrientationContinue : TutorialState {
    private var animator: ValueAnimator? = null
    private var actionCompleted: Boolean = false

    @VisibleForTesting
    val orientationMonitorCallBack: OrientationMonitor.Callback =
      object : OrientationMonitor.Callback {
        override fun onOrientationChanged(orientation: OrientationMonitor.Orientation) {
          if (orientation == OrientationMonitor.Orientation.LANDSCAPE ||
            orientation == OrientationMonitor.Orientation.REVERSE_LANDSCAPE
          ) {
            tutorialCallback.onPlaySound(FeedbackManager.Type.BEEP)
            BrailleImeVibrator.getInstance(context).vibrate(OTHER_GESTURES)
            // Save the state only because TutorialView will recreate if orientation is changed.
            this@TutorialView.state = rotateOrientation
            tutorialCallback.unregisterOrientationChange()
            tutorialCallback.onRestartTutorial()
          }
        }
      }

    override fun getCurrentState(): State {
      return State.ROTATE_ORIENTATION_CONTINUE
    }

    override fun nextState(): TutorialState {
      return typeLetterA
    }

    override fun loadView() {
      tutorialCallback.registerOrientationChange(orientationMonitorCallBack)
      // Use to detect swipe gesture and set as transparent.
      inputView.setAlpha(0f)
      addView(inputView)

      View.inflate(context, R.layout.tutorial_rotate_orientation, this@TutorialView)
      findViewById<View>(R.id.tap_to_continue).setBackground(TapMeAnimationDrawable(context))
      startHighlightTextAnimation()
      speakAnnouncement(audialAnnouncement(), /* delayMs= */ 0)
    }

    override fun audialAnnouncement(): String {
      return resources.getString(R.string.rotate_orientation_continue_announcement)
    }

    override fun audialAnnouncementRepeated(): String {
      return resources.getString(R.string.rotate_orientation_continue_announcement)
    }

    override fun onUtteranceCompleted() {
      speakAnnouncement(audialAnnouncementRepeated(), /* delayMs= */ 0)
    }

    override fun onDoubleTap() {
      if (!actionCompleted) {
        actionCompleted = true
        tutorialCallback.unregisterOrientationChange()
        switchState(nextState(), /* delay= */ 0)
      }
    }

    private fun startHighlightTextAnimation() {
      val highlightTextView = findViewById<TextView>(R.id.highlight_description)
      val highlightText: CharSequence = highlightTextView.text
      val highlightColor: Int =
        resources.getColor(R.color.text_highlight_color, /* theme= */ null)
      animator = ValueAnimator.ofInt(1, highlightText.length)
      animator!!.setDuration(highlightText.length * HIGHLIGHT_CHARACTER_ANIMATION_DURATION_MILLISECOND.toLong())
      animator!!.start()
      animator!!.addUpdateListener(
        { animation ->
          val spanText: Spannable = Spannable.Factory.getInstance().newSpannable(highlightText)
          spanText.setSpan(
            BackgroundColorSpan(highlightColor),
            /* start= */ 0,
            animation.animatedValue as Int,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
          )
          highlightTextView.setText(spanText)
          highlightTextView.invalidate()
        })
      animator!!.addListener(
        object : AnimatorListener {
          override fun onAnimationStart(animation: Animator) {}

          override fun onAnimationEnd(animation: Animator) {
            animator!!.setStartDelay(ANIMATION_DELAY_MS.toLong())
            // This might not be a good solution but it works, do not start animator because
            // Robolectric tests run on main thread also, otherwise animator will repeat in
            // endless loop and cause the test timeout.
            if (!BuildVersionUtils.isRobolectric()) {
              animator!!.start()
            }
          }

          override fun onAnimationCancel(animation: Animator) {}

          override fun onAnimationRepeat(animation: Animator) {}
        })
    }
  }

  inner class HoldSixFingers : TutorialState {
    private var actionCompleted: Boolean = false
    private var fingersPattern: FingersPattern? = null

    override fun getCurrentState(): State {
      return State.HOLD_6_FINGERS
    }

    override fun nextState(): TutorialState {
      return typeLetterA
    }

    override fun audialAnnouncement(): String {
      return resources
        .getString(
          R.string.hold_six_fingers_announcement,
          resources.getString(R.string.hold_six_fingers_description),
        )
    }

    override fun audialAnnouncementRepeated(): String {
      if (fingersPattern == FingersPattern.FIVE_FINGERS) {
        return resources
          .getString(
            R.string.calibration_hold_left_or_right_three_finger_announcement,
            resources.getString(R.string.left_hand),
          )
      } else if (fingersPattern == FingersPattern.FIRST_THREE_FINGERS) {
        return resources
          .getString(
            R.string.calibration_step2_hold_left_or_right_finger_announcement,
            resources.getString(R.string.right_hand),
          )
      }
      return resources.getString(R.string.hold_six_fingers_inactive_announcement)
    }

    override fun isActionCompleted(): Boolean {
      return actionCompleted
    }

    override fun onCalibration(type: CalibrationTriggeredType, fingersPattern: FingersPattern) {
      this.fingersPattern = fingersPattern
      // Only six-dot braille in the tutorial.
      if (fingersPattern == FingersPattern.SIX_FINGERS ||
        fingersPattern == FingersPattern.REMAINING_THREE_FINGERS
      ) {
        if (actionCompleted) {
          return
        }
        tutorialCallback.onPlaySound(FeedbackManager.Type.BEEP)
        // Wait a second for playing sound and then speak the post-action announcement.
        speakAnnouncement(
          resources.getString(R.string.calibration_finish_announcement), BEEP_DELAY_MS,
        )
        actionCompleted = true
      } else if (fingersPattern == FingersPattern.FIVE_FINGERS) {
        val sb = StringBuilder()
        sb.append(
            resources
              .getString(
                R.string.calibration_step1_hold_left_or_right_finger_announcement,
                resources.getString(R.string.left_hand),
              ),
          )
          .append(" ")
          .append(
            resources
              .getString(
                R.string.calibration_hold_left_or_right_three_finger_announcement,
                resources.getString(R.string.left_hand),
              ),
          )
        speakAnnouncement(sb.toString(), /* delayMs= */ 0)
      } else if (fingersPattern == FingersPattern.FIRST_THREE_FINGERS) {
        this.fingersPattern = FingersPattern.FIRST_THREE_FINGERS
        val sb = StringBuilder()
        sb.append(
            resources
              .getString(
                R.string.calibration_step2_hold_left_or_right_finger_announcement,
                resources.getString(R.string.right_hand),
              ),
          )
          .append(" ")
          .append(
            resources
              .getString(
                R.string.calibration_hold_left_or_right_three_finger_announcement,
                resources.getString(R.string.right_hand),
              ),
          )
        speakAnnouncement(sb.toString(), /* delayMs= */ 0)
      }
    }

    override fun onTwoStepCalibrationRetry(isFirstStep: Boolean) {
      val announcement =
        resources
          .getString(
            R.string.calibration_hold_left_or_right_three_finger_announcement,
            resources.getString(R.string.left_hand),
          )
      speakAnnouncement(announcement, /* delayMs= */ 0)
    }

    override fun onUtteranceCompleted() {
      if (actionCompleted) {
        actionCompleted = false
        switchState(nextState(), /* delay= */ 0)
      } else {
        speakAnnouncement(audialAnnouncementRepeated(), /* delayMs= */ 0)
      }
    }

    override fun loadView() {
      // This view is used to detect swipe gesture and set as transparent to not seen by user.
      inputView.setAlpha(0f)
      addView(inputView)

      View.inflate(context, R.layout.tutorial_hold_six_fingers, this@TutorialView)
      val animationView = findViewById<ImageView>(R.id.tutorial_animation)
      (animationView.drawable as AnimationDrawable).start()
      speakAnnouncement(audialAnnouncement(), /* delayMs= */ 0)
    }
  }

  inner class TypeLetterA : TutorialState {
    private var actionCompleted: Boolean = false

    override fun getCurrentState(): State {
      return State.TYPE_LETTER_A
    }

    override fun nextState(): TutorialState {
      return tapLetterBCD
    }

    override fun loadView() {
      dotBlockView.setAlpha(0f)
      dotBlockView.animate().alpha(1f).setDuration(ANIMATION_DURATION_MS.toLong())
      addView(dotBlockView)
      // Get dot 1 coordinate to show flashing animation.
      dotsFlashingAnimationView =
        DotsFlashingAnimationView(
          context,
          getDotTargetsForBrailleCharacter(BrailleTranslateUtilsUeb.LETTER_A),
          orientation,
          isTabletop,
        )
      dotsFlashingAnimationView!!.setVisibility(View.INVISIBLE)
      inputView.setAlpha(0f)
      inputView
        .animate()
        .alpha(1f)
        .setDuration(INPUT_VIEW_ANIMATION_DURATION_MS.toLong())
        .setStartDelay(ANIMATION_DURATION_MS.toLong())
        .setListener(
          object : AnimatorListener {
            override fun onAnimationStart(animation: Animator) {}

            override fun onAnimationEnd(animation: Animator) {
              postDelayed(
                {
                  removeView(inputView)
                  addView(inputView, 0)
                  dotsFlashingAnimationView!!.setVisibility(View.VISIBLE)
                  tutorialAnimationView.startHintToastAnimation(state.hintText())
                },
                FLASHING_ANIMATION_DELAY_MS.toLong(),
              )
            }

            override fun onAnimationCancel(animation: Animator) {}

            override fun onAnimationRepeat(animation: Animator) {}
          })
      addView(inputView)
      addView(dotsFlashingAnimationView!!)
      tutorialAnimationView.reset()
      addView(tutorialAnimationView)
      speakAnnouncement(audialAnnouncement(), /* delayMs= */ 0)
    }

    override fun audialAnnouncement(): String {
      return resources.getString(R.string.type_letter_a_announcement)
    }

    override fun audialAnnouncementRepeated(): String {
      return resources.getString(R.string.type_letter_a_inactive_announcement)
    }

    override fun hintText(): String {
      return resources.getString(R.string.instruction_type_letter_a)
    }

    override fun isActionCompleted(): Boolean {
      return actionCompleted
    }

    override fun onUtteranceCompleted() {
      if (actionCompleted) {
        actionCompleted = false
        switchState(nextState(), /* delay= */ 0)
      } else {
        speakAnnouncement(audialAnnouncementRepeated(), /* delayMs= */ 0)
      }
    }

    override fun onBrailleProduced(brailleCharacter: BrailleCharacter) {
      if (actionCompleted) {
        return
      }
      BrailleImeVibrator.getInstance(context).vibrate(VibrationType.BRAILLE_COMMISSION)
      // Dot 1 is clicked.
      if (brailleCharacter.toByte().toInt() == 0b01) {
        tutorialAnimationView.startActionResultAnimation(
          Ascii.toUpperCase(translator.translateToPrint(BrailleWord(brailleCharacter))),
        )
        tutorialCallback.onPlaySound(FeedbackManager.Type.BEEP)
        // Wait a second for playing sound and then speak the post-action announcement.
        val textToSpeak = translator.translateToPrint(BrailleWord(brailleCharacter))
        speakAnnouncement(textToSpeak, BEEP_DELAY_MS)
        actionCompleted = true
      } else {
        // Read out the corresponding announcement.
        speakBrailleCharacter(brailleCharacter, /* delayMs= */ 0)
      }
    }

    override fun onSwipeProduced(swipe: Swipe) {
      if (actionCompleted) {
        return
      }

      // Read out the corresponding announcement and do nothing.
      speakSwipeEvent(swipe)
      vibrateForSwipeGestures(swipe)
    }
  }

  inner class TypeLetterBCD : TutorialState {
    private var actionCompleted: Boolean = false
    private val remainLetters: MutableList<Char> =
      mutableListOf('B', 'C', 'D')

    override fun getCurrentState(): State {
      return State.TYPE_LETTER_BCD
    }

    override fun nextState(): TutorialState {
      if (remainLetters.isEmpty()) {
        return swipeLeft
      }
      return this
    }

    override fun loadView() {
      addView(inputView)
      val brailleCharacter: BrailleCharacter =
        when (remainLetters[0]) {
          'B' -> BrailleTranslateUtilsUeb.LETTER_B
          'C' -> BrailleTranslateUtilsUeb.LETTER_C
          'D' -> BrailleTranslateUtilsUeb.LETTER_D
          else -> throw IllegalStateException("Unexpected value: " + remainLetters[0])
        }
      val animationDots: List<DotTarget> = getDotTargetsForBrailleCharacter(brailleCharacter)
      dotsFlashingAnimationView =
        DotsFlashingAnimationView(context, animationDots, orientation, isTabletop)
      addView(dotsFlashingAnimationView!!)
      tutorialAnimationView.reset()
      tutorialAnimationView.startHintToastAnimation(state.hintText())
      addView(tutorialAnimationView)
      speakAnnouncement(audialAnnouncement(), /* delayMs= */ 0)
    }

    override fun audialAnnouncement(): String {
      if (remainLetters.size == TOTAL_LETTERS) {
        return resources.getString(R.string.instruction_type_letter_bcd)
      }
      return audialAnnouncementRepeated()
    }

    override fun audialAnnouncementRepeated(): String {
      return when (remainLetters[0]) {
        'B' ->
          resources
            .getString(
              R.string.type_letter_inactive_announcement,
              BrailleTranslateUtils.getDotsText(
                resources, BrailleTranslateUtilsUeb.LETTER_B,
              ),
              remainLetters[0],
            )
        'C' ->
          resources
            .getString(
              R.string.type_letter_inactive_announcement,
              BrailleTranslateUtils.getDotsText(
                resources, BrailleTranslateUtilsUeb.LETTER_C,
              ),
              remainLetters[0],
            )
        'D' ->
          resources
            .getString(
              R.string.type_letter_inactive_announcement,
              BrailleTranslateUtils.getDotsText(
                resources, BrailleTranslateUtilsUeb.LETTER_D,
              ),
              remainLetters[0],
            )
        else ->
          // Should not happen.
          resources
            .getString(
              R.string.type_letter_inactive_announcement,
              BrailleTranslateUtils.getDotsText(
                resources, BrailleTranslateUtilsUeb.LETTER_B,
              ),
              remainLetters[0],
            )
      }
    }

    override fun hintText(): String {
      return resources.getString(R.string.instruction_type_letter_bcd)
    }

    override fun isActionCompleted(): Boolean {
      return actionCompleted
    }

    override fun onUtteranceCompleted() {
      if (actionCompleted) {
        actionCompleted = false
        switchState(nextState(), /* delay= */ 0)
      } else {
        speakAnnouncement(audialAnnouncementRepeated(), /* delayMs= */ 0)
      }
    }

    override fun onBrailleProduced(brailleCharacter: BrailleCharacter) {
      if (actionCompleted) {
        return
      }
      BrailleImeVibrator.getInstance(context).vibrate(VibrationType.BRAILLE_COMMISSION)

      val result =
        Ascii.toUpperCase(translator.translateToPrint(BrailleWord(brailleCharacter)))
      var character: Char = Char.MAX_VALUE
      if (result.length > 0) {
        character = result[0]
      }

      if (remainLetters.contains(character)) {
        tutorialAnimationView.startActionResultAnimation(result)
        tutorialCallback.onPlaySound(FeedbackManager.Type.BEEP)
        // Wait a second for playing sound and then speak the post-action announcement.
        val textToSpeak = translator.translateToPrint(BrailleWord(brailleCharacter))
        speakAnnouncement(textToSpeak, BEEP_DELAY_MS)
        actionCompleted = true
        remainLetters.remove(character)
      } else {
        // Read out the corresponding announcement.
        speakBrailleCharacter(brailleCharacter, /* delayMs= */ 0)
      }
    }

    override fun onSwipeProduced(swipe: Swipe) {
      if (actionCompleted) {
        return
      }
      // Read out the corresponding announcement and do nothing.
      speakSwipeEvent(swipe)
      vibrateForSwipeGestures(swipe)
    }
  }

  inner class SwipeLeft : TutorialState {
    private var actionCompleted: Boolean = false

    override fun getCurrentState(): State {
      return State.SWIPE_LEFT
    }

    override fun nextState(): TutorialState {
      return swipeRight
    }

    override fun loadView() {
      addView(inputView)
      tutorialAnimationView.reset()
      tutorialAnimationView.startSwipeAnimation(1, LEFT_TO_RIGHT)
      tutorialAnimationView.startHintToastAnimation(state.hintText())
      addView(tutorialAnimationView)
      speakAnnouncement(audialAnnouncement(), /* delayMs= */ 0)
    }

    override fun audialAnnouncement(): String {
      return resources.getString(R.string.swipe_left_announcement)
    }

    override fun audialAnnouncementRepeated(): String {
      return resources.getString(R.string.swipe_left_announcement)
    }

    override fun hintText(): String {
      return resources.getString(R.string.instruction_swipe_left)
    }

    override fun resultText(): String {
      return resources.getString(R.string.result_swipe_left)
    }

    override fun isActionCompleted(): Boolean {
      return actionCompleted
    }

    override fun onUtteranceCompleted() {
      if (actionCompleted) {
        actionCompleted = false
        switchState(nextState(), /* delay= */ 0)
      } else {
        speakAnnouncement(audialAnnouncementRepeated(), /* delayMs= */ 0)
      }
    }

    override fun onBrailleProduced(brailleCharacter: BrailleCharacter) {
      if (actionCompleted) {
        return
      }

      BrailleImeVibrator.getInstance(context).vibrate(VibrationType.BRAILLE_COMMISSION)
      // Read out the corresponding announcement.
      speakBrailleCharacter(brailleCharacter, /* delayMs= */ 0)
    }

    override fun onSwipeProduced(swipe: Swipe) {
      if (actionCompleted) {
        return
      }

      val touchCount = swipe.touchCount
      val direction = swipe.direction
      if (direction == Direction.LEFT && touchCount == 1) {
        tutorialAnimationView.stopSwipeAnimation()
        tutorialAnimationView.startActionResultAnimation(resultText())
        tutorialCallback.onPlaySound(FeedbackManager.Type.BEEP)
        // Wait a second for playing sound and then speak the post-action announcement.
        speakAnnouncement(
          resources.getString(R.string.swipe_left_action_result_announcement),
          BEEP_DELAY_MS,
        )
        actionCompleted = true
      } else {
        // Read out the corresponding announcement.
        speakSwipeEvent(swipe)
      }
      vibrateForSwipeGestures(swipe)
    }
  }

  inner class SwipeRight : TutorialState {
    private var actionCompleted: Boolean = false

    override fun getCurrentState(): State {
      return State.SWIPE_RIGHT
    }

    override fun nextState(): TutorialState {
      return swipeUp
    }

    override fun loadView() {
      addView(inputView)
      tutorialAnimationView.reset()
      tutorialAnimationView.startSwipeAnimation(1, RIGHT_TO_LEFT)
      tutorialAnimationView.startHintToastAnimation(state.hintText())
      addView(tutorialAnimationView)
      speakAnnouncement(audialAnnouncement(), /* delayMs= */ 0)
    }

    override fun audialAnnouncement(): String {
      return resources.getString(R.string.swipe_right_announcement)
    }

    override fun audialAnnouncementRepeated(): String {
      return resources.getString(R.string.swipe_right_announcement)
    }

    override fun hintText(): String {
      return resources.getString(R.string.instruction_swipe_right)
    }

    override fun resultText(): String {
      return resources.getString(R.string.result_swipe_right)
    }

    override fun isActionCompleted(): Boolean {
      return actionCompleted
    }

    override fun onUtteranceCompleted() {
      if (actionCompleted) {
        actionCompleted = false
        switchState(nextState(), /* delay= */ 0)
      } else {
        speakAnnouncement(audialAnnouncementRepeated(), /* delayMs= */ 0)
      }
    }

    override fun onBrailleProduced(brailleCharacter: BrailleCharacter) {
      if (actionCompleted) {
        return
      }

      BrailleImeVibrator.getInstance(context).vibrate(VibrationType.BRAILLE_COMMISSION)
      // Read out the corresponding announcement.
      speakBrailleCharacter(brailleCharacter, /* delayMs= */ 0)
    }

    override fun onSwipeProduced(swipe: Swipe) {
      if (actionCompleted) {
        return
      }

      val touchCount = swipe.touchCount
      val direction = swipe.direction
      if (direction == Direction.RIGHT && touchCount == 1) {
        tutorialAnimationView.stopSwipeAnimation()
        tutorialAnimationView.startActionResultAnimation(resultText())
        tutorialCallback.onPlaySound(FeedbackManager.Type.BEEP)
        // Wait a second for playing sound and then speak the post-action announcement.
        speakAnnouncement(resources.getString(R.string.result_swipe_right), BEEP_DELAY_MS)
        actionCompleted = true
      } else {
        // Read out the corresponding announcement.
        speakSwipeEvent(swipe)
      }
      vibrateForSwipeGestures(swipe)
    }
  }

  inner class SwipeUp : TutorialState {
    private var actionCompleted: Boolean = false

    override fun getCurrentState(): State {
      return State.SWIPE_UP
    }

    override fun nextState(): TutorialState {
      return swipeDown
    }

    override fun loadView() {
      addView(inputView)
      tutorialAnimationView.reset()
      tutorialAnimationView.startSwipeAnimation(1, BOTTOM_TO_TOP)
      tutorialAnimationView.startHintToastAnimation(state.hintText())
      addView(tutorialAnimationView)
      speakAnnouncement(audialAnnouncement(), /* delayMs= */ 0)
    }

    override fun audialAnnouncement(): String {
      return resources.getString(R.string.swipe_up_announcement)
    }

    override fun audialAnnouncementRepeated(): String {
      return resources.getString(R.string.swipe_up_announcement)
    }

    override fun hintText(): String {
      return resources.getString(R.string.instruction_swipe_up)
    }

    override fun resultText(): String {
      return resources.getString(R.string.result_swipe_up)
    }

    override fun isActionCompleted(): Boolean {
      return actionCompleted
    }

    override fun onUtteranceCompleted() {
      if (actionCompleted) {
        actionCompleted = false
        switchState(nextState(), /* delay= */ 0)
      } else {
        speakAnnouncement(audialAnnouncementRepeated(), /* delayMs= */ 0)
      }
    }

    override fun onBrailleProduced(brailleCharacter: BrailleCharacter) {
      if (actionCompleted) {
        return
      }

      BrailleImeVibrator.getInstance(context).vibrate(VibrationType.BRAILLE_COMMISSION)
      speakBrailleCharacter(brailleCharacter, /* delayMs= */ 0)
    }

    override fun onSwipeProduced(swipe: Swipe) {
      if (actionCompleted) {
        return
      }

      val touchCount = swipe.touchCount
      val direction = swipe.direction
      if (direction == Direction.UP && touchCount == 1) {
        tutorialAnimationView.stopSwipeAnimation()
        tutorialAnimationView.startActionResultAnimation(resultText())
        tutorialCallback.onPlaySound(FeedbackManager.Type.BEEP)
        // Wait a second for playing sound and then speak the post-action announcement.
        speakAnnouncement(resources.getString(R.string.result_swipe_up), BEEP_DELAY_MS)
        actionCompleted = true
      } else {
        // Read out the corresponding announcement.
        speakSwipeEvent(swipe)
      }
      vibrateForSwipeGestures(swipe)
    }
  }

  inner class SwipeDown : TutorialState {
    private var actionCompleted: Boolean = false

    override fun getCurrentState(): State {
      return State.SWIPE_DOWN
    }

    override fun nextState(): TutorialState {
      return swipeDown2Fingers
    }

    override fun loadView() {
      addView(inputView)
      tutorialAnimationView.reset()
      tutorialAnimationView.startSwipeAnimation(1, TOP_TO_BOTTOM)
      tutorialAnimationView.startHintToastAnimation(state.hintText())
      addView(tutorialAnimationView)
      speakAnnouncement(audialAnnouncement(), /* delayMs= */ 0)
    }

    override fun audialAnnouncement(): String {
      return resources.getString(R.string.swipe_down_announcement)
    }

    override fun audialAnnouncementRepeated(): String {
      return resources.getString(R.string.swipe_down_announcement)
    }

    override fun hintText(): String {
      return resources.getString(R.string.instruction_swipe_down)
    }

    override fun resultText(): String {
      return resources.getString(R.string.result_swipe_down)
    }

    override fun isActionCompleted(): Boolean {
      return actionCompleted
    }

    override fun onUtteranceCompleted() {
      if (actionCompleted) {
        actionCompleted = false
        switchState(nextState(), /* delay= */ 0)
      } else {
        speakAnnouncement(audialAnnouncementRepeated(), /* delayMs= */ 0)
      }
    }

    override fun onBrailleProduced(brailleCharacter: BrailleCharacter) {
      if (actionCompleted) {
        return
      }

      BrailleImeVibrator.getInstance(context).vibrate(VibrationType.BRAILLE_COMMISSION)
      speakBrailleCharacter(brailleCharacter, /* delayMs= */ 0)
    }

    override fun onSwipeProduced(swipe: Swipe) {
      if (actionCompleted) {
        return
      }

      val touchCount = swipe.touchCount
      val direction = swipe.direction
      if (direction == Direction.DOWN && touchCount == 1) {
        tutorialAnimationView.stopSwipeAnimation()
        tutorialAnimationView.startActionResultAnimation(resultText())
        tutorialCallback.onPlaySound(FeedbackManager.Type.BEEP)
        // Wait a second for playing sound and then speak the post-action announcement.
        speakAnnouncement(resources.getString(R.string.result_swipe_down), BEEP_DELAY_MS)
        actionCompleted = true
      } else {
        // Read out the corresponding announcement.
        speakSwipeEvent(swipe)
      }
      vibrateForSwipeGestures(swipe)
    }
  }

  inner class SwipeDown2Fingers : TutorialState {
    private var actionCompleted: Boolean = false

    override fun getCurrentState(): State {
      return State.SWIPE_DOWN_2_FINGERS
    }

    override fun nextState(): TutorialState {
      return swipeDown3Fingers
    }

    override fun loadView() {
      addView(inputView)
      tutorialAnimationView.reset()
      tutorialAnimationView.startSwipeAnimation(2, TOP_TO_BOTTOM)
      tutorialAnimationView.startHintToastAnimation(state.hintText())
      addView(tutorialAnimationView)
      speakAnnouncement(audialAnnouncement(), /* delayMs= */ 0)
    }

    override fun audialAnnouncement(): String {
      return resources.getString(R.string.swipe_down_2_fingers_announcement)
    }

    override fun audialAnnouncementRepeated(): String {
      return resources.getString(R.string.swipe_down_2_fingers_announcement)
    }

    override fun hintText(): String {
      return resources.getString(R.string.instruction_swipe_down_2_fingers)
    }

    override fun resultText(): String {
      return resources.getString(R.string.result_swipe_down_2_fingers)
    }

    override fun isActionCompleted(): Boolean {
      return actionCompleted
    }

    override fun onUtteranceCompleted() {
      if (actionCompleted) {
        actionCompleted = false
        switchState(nextState(), /* delay= */ 0)
      } else {
        speakAnnouncement(audialAnnouncementRepeated(), /* delayMs= */ 0)
      }
    }

    override fun onBrailleProduced(brailleCharacter: BrailleCharacter) {
      if (actionCompleted) {
        return
      }

      BrailleImeVibrator.getInstance(context).vibrate(VibrationType.BRAILLE_COMMISSION)
      // Read out the corresponding announcement.
      speakBrailleCharacter(brailleCharacter, /* delayMs= */ 0)
    }

    override fun onSwipeProduced(swipe: Swipe) {
      if (actionCompleted) {
        return
      }

      val touchCount = swipe.touchCount
      val direction = swipe.direction
      if (direction == Direction.DOWN && touchCount == 2) {
        tutorialAnimationView.stopSwipeAnimation()
        tutorialAnimationView.startActionResultAnimation(resultText())
        tutorialCallback.onPlaySound(FeedbackManager.Type.BEEP)
        // Wait a second for playing sound and then speak the post-action announcement.
        speakAnnouncement(
          resources.getString(R.string.swipe_down_2_fingers_completed_announcement),
          BEEP_DELAY_MS,
        )
        actionCompleted = true
      } else {
        // Read out the corresponding announcement.
        speakSwipeEvent(swipe)
      }
      vibrateForSwipeGestures(swipe)
    }
  }

  inner class SwipeDown3Fingers : TutorialState {
    private var actionCompleted: Boolean = false

    override fun getCurrentState(): State {
      return State.SWIPE_DOWN_3_FINGERS
    }

    override fun nextState(): TutorialState {
      return swipeUp3Fingers
    }

    override fun loadView() {
      addView(inputView)
      tutorialAnimationView.reset()
      tutorialAnimationView.startSwipeAnimation(3, TOP_TO_BOTTOM)
      tutorialAnimationView.startHintToastAnimation(state.hintText())
      addView(tutorialAnimationView)
      speakAnnouncement(audialAnnouncement(), /* delayMs= */ 0)
    }

    override fun audialAnnouncement(): String {
      return resources.getString(R.string.swipe_down_3_fingers_announcement)
    }

    override fun audialAnnouncementRepeated(): String {
      return resources.getString(R.string.swipe_down_3_fingers_announcement)
    }

    override fun hintText(): String {
      return resources.getString(R.string.instruction_swipe_down_3_fingers)
    }

    override fun resultText(): String {
      return resources.getString(R.string.result_swipe_down_3_fingers)
    }

    override fun isActionCompleted(): Boolean {
      return actionCompleted
    }

    override fun onUtteranceCompleted() {
      if (actionCompleted) {
        actionCompleted = false
        switchState(nextState(), /* delay= */ 0)
      } else {
        speakAnnouncement(audialAnnouncementRepeated(), /* delayMs= */ 0)
      }
    }

    override fun onBrailleProduced(brailleCharacter: BrailleCharacter) {
      if (actionCompleted) {
        return
      }

      BrailleImeVibrator.getInstance(context).vibrate(VibrationType.BRAILLE_COMMISSION)
      speakBrailleCharacter(brailleCharacter, /* delayMs= */ 0)
    }

    override fun onSwipeProduced(swipe: Swipe) {
      if (actionCompleted) {
        return
      }

      val touchCount = swipe.touchCount
      val direction = swipe.direction
      if (direction == Direction.DOWN && touchCount == 3) {
        tutorialAnimationView.stopSwipeAnimation()
        tutorialAnimationView.startActionResultAnimation(resultText())
        tutorialCallback.onPlaySound(FeedbackManager.Type.BEEP)
        // Wait a second for playing sound and then speak the post-action announcement.
        speakAnnouncement(
          resources.getString(R.string.swipe_down_3_fingers_completed_announcement),
          BEEP_DELAY_MS,
        )
        actionCompleted = true
      } else {
        speakSwipeEvent(swipe)
      }
      vibrateForSwipeGestures(swipe)
    }
  }

  inner class SwipeUp3Fingers : TutorialState {
    private var actionCompleted: Boolean = false

    override fun getCurrentState(): State {
      return State.SWIPE_UP_3_FINGERS
    }

    override fun nextState(): TutorialState {
      return contextMenuOpened
    }

    override fun loadView() {
      actionCompleted = false
      addView(inputView)
      tutorialAnimationView.reset()
      tutorialAnimationView.startSwipeAnimation(3, BOTTOM_TO_TOP)
      tutorialAnimationView.startHintToastAnimation(state.hintText())
      addView(tutorialAnimationView)
      speakAnnouncement(audialAnnouncement(), /* delayMs= */ 0)
    }

    override fun audialAnnouncement(): String {
      return resources.getString(R.string.swipe_up_3_fingers_announcement)
    }

    override fun audialAnnouncementRepeated(): String {
      return resources.getString(R.string.swipe_up_3_fingers_announcement)
    }

    override fun hintText(): String {
      return resources.getString(R.string.instruction_swipe_up_3_fingers)
    }

    override fun onUtteranceCompleted() {
      if (!actionCompleted) {
        speakAnnouncement(audialAnnouncementRepeated(), /* delayMs= */ 0)
      }
    }

    override fun onBrailleProduced(brailleCharacter: BrailleCharacter) {
      if (actionCompleted) {
        return
      }

      BrailleImeVibrator.getInstance(context).vibrate(VibrationType.BRAILLE_COMMISSION)
      speakBrailleCharacter(brailleCharacter, /* delayMs= */ 0)
    }

    override fun onSwipeProduced(swipe: Swipe) {
      if (actionCompleted) {
        return
      }

      val touchCount = swipe.touchCount
      val direction = swipe.direction
      if (direction == Direction.UP && touchCount == 3) {
        switchState(nextState(), /* delay= */ 0)
        actionCompleted = true
      } else if (direction == Direction.LEFT && touchCount == 3) {
        // Braille keyboard view is forced to be in landscape. When device is portrait and user
        // swipes upward, for keyboard view, it's swipe rightward.
        if (OrientationMonitor.getInstance().currentOrientation == Orientation.PORTRAIT) {
          switchState(nextState(), /* delay= */ 0)
          actionCompleted = true
        }
      } else {
        // Read out the corresponding announcement.
        speakSwipeEvent(swipe)
      }
      vibrateForSwipeGestures(swipe)
    }
  }

  inner class ContextMenuOpened : TutorialState {

    override fun getCurrentState(): State {
      return State.CONTEXT_MENU_OPENED
    }

    override fun nextState(): TutorialState {
      return tutorialFinished
    }

    override fun audialAnnouncement(): String {
      return resources.getString(R.string.open_context_menu_announcement)
    }

    override fun audialAnnouncementRepeated(): String {
      return resources.getString(R.string.open_context_menu_announcement)
    }

    override fun onUtteranceCompleted() {
      speakAnnouncement(audialAnnouncementRepeated(), /* delayMs= */ 0)
    }

    override fun loadView() {
      addView(inputView)
      if (!contextMenuDialog.isShowing()) {
        openContextMenu()
      }
    }
  }

  inner class TutorialFinished : TutorialState {
    override fun getCurrentState(): State {
      return State.NONE
    }

    override fun nextState(): TutorialState {
      return this
    }

    override fun loadView() {}
  }

  /** A callback for Tutorial event. */
  interface TutorialCallback {
    fun onBrailleImeActivated()

    fun onBrailleImeInactivated()

    fun onAudialAnnounce(
      announcement: String,
      delayMs: Int,
      announceType: TalkBackSpeaker.AnnounceType,
      utteranceCompleteRunnable: UtteranceCompleteRunnable?,
    )

    fun onPlaySound(type: FeedbackManager.Type)

    fun onSwitchToNextInputMethod()

    fun onLaunchSettings()

    fun onTutorialFinished()

    fun onRestartTutorial()

    fun registerOrientationChange(callBack: OrientationMonitor.Callback)

    fun unregisterOrientationChange()
  }

  private val context: Context
  private val intro: Intro
  private val rotateOrientation: RotateOrientation
  private val rotateOrientationContinue: RotateOrientationContinue
  private val typeLetterA: TypeLetterA
  private val tapLetterBCD: TypeLetterBCD
  private val swipeLeft: SwipeLeft
  private val swipeRight: SwipeRight
  private val swipeUp: SwipeUp
  private val swipeDown: SwipeDown
  private val swipeDown2Fingers: SwipeDown2Fingers
  private val swipeDown3Fingers: SwipeDown3Fingers
  private val swipeUp3Fingers: SwipeUp3Fingers
  private val contextMenuOpened: ContextMenuOpened
  private val tutorialFinished: TutorialFinished
  private val holdSixFingers: HoldSixFingers

  private var state: TutorialState
  private var orientation: Int
  private val inputView: BrailleInputView
  private val dotBlockView: DotBlockView
  private var dotsFlashingAnimationView: DotsFlashingAnimationView? = null
  private lateinit var tutorialAnimationView: TutorialAnimationView
  private val gestureDetector: GestureDetector
  private val contextMenuDialog: ContextMenuDialog

  private val translator: BrailleTranslator
  private val handler: Handler = Handler()

  // This is used to prevent handling utterance completed callback once ViewContainer is
  // deactivated.
  private var isStopped: Boolean = false

  // Whether or not to play announcement of clicking Braille dot or swipe event. Exploration
  // feedbacks will be skipped if the instruction is speaking.
  private var allowExploration: Boolean = false
  private var isTabletop: Boolean = false
  private var numberOfInteractionsPerState: Int = 0
  private val instructionSpeechId: AtomicInteger = AtomicInteger()

  private val inputPlaneCallback: BrailleInputView.Callback =
    object : BrailleInputView.Callback {
      override fun onSwipeProduced(swipe: Swipe): Boolean {
        var result = false
        if (swipe.direction == Direction.UP && swipe.touchCount == 3) {
          openContextMenu()
          result = true
        } else if (swipe.direction == Direction.LEFT && swipe.touchCount == 3) {
          // Braille keyboard view is forced to be in landscape. When device is portrait and user
          // swipes upward, for keyboard view, it's swipe rightward.
          if (OrientationMonitor.getInstance().currentOrientation == Orientation.PORTRAIT) {
            openContextMenu()
            result = true
          }
        }
        vibrateForSwipeGestures(swipe)
        state.onSwipeProduced(swipe)
        return result
      }

      override fun onDotHoldAndDotSwipe(dotHoldSwipe: DotHoldSwipe): Boolean {
        return false
      }

      override fun isCalibrationHoldRecognized(
        inTwoStepCalibration: Boolean,
        pointersHeldCount: Int,
      ): Boolean {
        // For calibration.
        return pointersHeldCount >= 5 || (inTwoStepCalibration && pointersHeldCount == 3)
      }

      override fun onHoldProduced(pointersHeldCount: Int): Boolean {
        return false
      }

      @Nullable
      override fun onBrailleProduced(brailleCharacter: BrailleCharacter): String? {
        BrailleImeVibrator.getInstance(context).vibrate(VibrationType.BRAILLE_COMMISSION)
        state.onBrailleProduced(brailleCharacter)
        return null
      }

      override fun onCalibration(
        type: CalibrationTriggeredType,
        fingersPattern: FingersPattern,
      ): Boolean {
        state.onCalibration(type, fingersPattern)
        return true
      }

      override fun onCalibrationFailed(type: CalibrationTriggeredType) {
        speakAnnouncement(
          resources.getString(R.string.calibration_fail_announcement), /* delayMs= */ 0,
        )
      }

      override fun onTwoStepCalibrationRetry(isFirstStep: Boolean) {
        state.onTwoStepCalibrationRetry(isFirstStep)
      }
    }

  private val contextMenuDialogCallback: ContextMenuDialog.Callback =
    object : ContextMenuDialog.Callback {
      override fun onDialogHidden() {
        tutorialCallback.onBrailleImeActivated()
        if (state == contextMenuOpened) {
          switchState(swipeUp3Fingers, /* delay= */ 0)
        } else {
          // Repeat audial announcement when back to tutorial.
          state.onUtteranceCompleted()
        }
      }

      override fun onDialogShown() {
        tutorialCallback.onBrailleImeInactivated()
      }

      override fun onLaunchSettings() {
        tutorialCallback.onLaunchSettings()
        switchState(tutorialFinished, /* delay= */ 0)
        tutorialCallback.onTutorialFinished()
      }

      override fun onTutorialOpen() {}

      override fun onTutorialClosed() {
        switchState(tutorialFinished, /* delay= */ 0)
        tutorialCallback.onAudialAnnounce(
          resources.getString(R.string.finish_tutorial_announcement),
          0,
          TalkBackSpeaker.AnnounceType.INTERRUPT_AND_UNINTERRUPTIBLE_BY_NEW_SPEECH,
          null,
        )
        tutorialCallback.onTutorialFinished()
      }

      override fun onCalibration() {}
    }

  private val doubleTapDetector: SimpleOnGestureListener =
    object : SimpleOnGestureListener() {
      override fun onDoubleTapEvent(e: MotionEvent): Boolean {
        state.onDoubleTap()
        return false
      }
    }

  init {
    this.context = context
    orientation = resources.configuration.orientation
    intro = Intro()
    rotateOrientation = RotateOrientation()
    rotateOrientationContinue = RotateOrientationContinue()
    typeLetterA = TypeLetterA()
    tapLetterBCD = TypeLetterBCD()
    swipeLeft = SwipeLeft()
    swipeRight = SwipeRight()
    swipeUp = SwipeUp()
    swipeDown = SwipeDown()
    swipeDown2Fingers = SwipeDown2Fingers()
    swipeDown3Fingers = SwipeDown3Fingers()
    swipeUp3Fingers = SwipeUp3Fingers()
    contextMenuOpened = ContextMenuOpened()
    tutorialFinished = TutorialFinished()
    holdSixFingers = HoldSixFingers()
    state = tutorialFinished

    gestureDetector = GestureDetector(context, doubleTapDetector)
    val options: BrailleInputOptions =
      BrailleInputOptions.builder()
        .setTutorialMode(true)
        .setBrailleType(BrailleType.SIX_DOT)
        .build()
    inputView =
      BrailleInputView(
        context, inputPlaneCallback, screenSize, options, /* tabletopMode= */ false,
      )
    isTabletop = !BrailleUtils.isPhoneSizedDevice(context.resources)
    inputView.setTabletopMode(isTabletop)
    dotBlockView =
      DotBlockView(context, orientation, isTabletop, BrailleInputOptions.builder().build())
    tutorialAnimationView = TutorialAnimationView(context, orientation, screenSize, isTabletop)
    contextMenuDialog = ContextMenuDialog(context, contextMenuDialogCallback)
    contextMenuDialog.setTutorialMode(true)

    // Force use UEB grade 1 in tutorial to make sure the instructions are always correct.
    val code: Code = Code.UEB
    translator =
      BrailleUserPreferences.readTranslatorFactory(context)
        .create(context, code.name, /* contractedMode= */ false)
  }

  @VisibleForTesting
  fun setTableMode(isTabletop: Boolean) {
    this.isTabletop = isTabletop
  }

  override fun onOrientationChanged(orientation: Int, screenSize: Size) {
    this.orientation = orientation
    tutorialAnimationView = TutorialAnimationView(context, orientation, screenSize, isTabletop)
    inputView.onOrientationChanged(orientation, screenSize)
    dotBlockView.onOrientationChanged(orientation, screenSize)
    if (dotsFlashingAnimationView != null) {
      dotsFlashingAnimationView!!.updateDotTarget(
        getDotTargetsForBrailleCharacter(dotsFlashingAnimationView!!.getFlashingCharacter()),
      )
      dotsFlashingAnimationView!!.onOrientationChanged(orientation, screenSize)
    }
    tutorialAnimationView.onOrientationChanged(orientation, screenSize)
    if (contextMenuDialog.isShowing()) {
      tutorialCallback.onBrailleImeInactivated()
    }
    if (state.isActionCompleted()) {
      // Wait for utterance to complete, which will trigger next action.
      return
    }
    reloadView()
  }

  override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
    gestureDetector.onTouchEvent(event)
    return false
  }

  fun getTutorialState(): State {
    return state.getCurrentState()
  }

  fun setTutorialState(state: State) {
    when (state) {
      State.NONE -> {
        // Should not happen.
      }
      State.INTRO -> this.state = intro
      State.ROTATE_ORIENTATION -> this.state = rotateOrientation
      State.ROTATE_ORIENTATION_CONTINUE -> this.state = rotateOrientationContinue
      State.TYPE_LETTER_A -> this.state = typeLetterA
      State.TYPE_LETTER_BCD -> this.state = tapLetterBCD
      State.SWIPE_LEFT -> this.state = swipeLeft
      State.SWIPE_RIGHT -> this.state = swipeRight
      State.SWIPE_UP -> this.state = swipeUp
      State.SWIPE_DOWN -> this.state = swipeDown
      State.SWIPE_DOWN_2_FINGERS -> this.state = swipeDown2Fingers
      State.SWIPE_DOWN_3_FINGERS -> this.state = swipeDown3Fingers
      State.SWIPE_UP_3_FINGERS -> this.state = swipeUp3Fingers
      State.CONTEXT_MENU_OPENED -> this.state = contextMenuOpened
      State.HOLD_6_FINGERS -> this.state = holdSixFingers
    }
  }

  fun switchState(state: State, delay: Long) {
    val before: TutorialState = this.state
    setTutorialState(state)
    if (this.state == before) {
      return
    }
    switchState(this.state, delay)
  }

  private fun switchState(state: TutorialState, delay: Long) {
    postDelayed(
      {
        BrailleImeLog.d(TAG, "Switch to " + state.getCurrentState().name)
        handler.removeCallbacksAndMessages(/* token= */ null)
        this.state = state
        numberOfInteractionsPerState = 0
        reloadView()
      },
      delay,
    )
  }

  override fun onDetachedFromWindow() {
    super.onDetachedFromWindow()
    isStopped = true
    handler.removeCallbacksAndMessages(/* token= */ null)
    contextMenuDialog.dismiss()
  }

  @VisibleForTesting
  fun onUtteranceCompleted() {
    if (isStopped) {
      return
    }
    handler.removeCallbacksAndMessages(/* token= */ null)
    if (state.isActionCompleted()) {
      state.onUtteranceCompleted()
    } else {
      // Allow exploration feedback when the instruction is idle.
      allowExploration = true
      handler.postDelayed({ state.onUtteranceCompleted() }, AUDIAL_ANNOUNCEMENT_IDLE_MS.toLong())
    }
  }

  private fun reloadView() {
    removeAllViews()
    state.loadView()
  }

  /**
   * Speaks the events of Braille dots and swipe gestures. If the user has tried a fixed number of
   * interactions, speak the instruction.
   */
  private fun speakUserEventAnnouncement(textToSpeak: String, delayMs: Int) {
    if (!allowExploration) {
      return
    }
    if (numberOfInteractionsPerState >= FREE_INTERACTIONS_MAX) {
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
  private fun speakAnnouncement(textToSpeak: String, delayMs: Int) {
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
                else AUDIAL_ANNOUNCEMENT_IDLE_FOR_EXTERNAL_SPEECH_MS
              handler.postDelayed(this@TutorialView::onUtteranceCompleted, postDelayedTime.toLong())
            }
            return
          }
          onUtteranceCompleted()
        }
      },
    )
  }

  private fun speakSwipeEvent(swipe: Swipe) {
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

  private fun vibrateForSwipeGestures(swipe: Swipe) {
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

  private fun speakBrailleCharacter(brailleCharacter: BrailleCharacter, delayMs: Int) {
    var textToSpeak: String =
      BrailleTranslateUtilsUeb.getTextToSpeak(this@TutorialView.resources, brailleCharacter)
    if (TextUtils.isEmpty(textToSpeak)) {
      textToSpeak = translator.translateToPrint(BrailleWord(brailleCharacter))
    }
    if (TextUtils.isEmpty(textToSpeak) || !BrailleTranslateUtils.isPronounceable(textToSpeak)) {
      textToSpeak = BrailleTranslateUtils.getDotsText(resources, brailleCharacter)
    }
    speakUserEventAnnouncement(textToSpeak, delayMs)
  }

  private fun openContextMenu() {
    contextMenuDialog.show(this@TutorialView)
    speakAnnouncement(
      resources.getString(R.string.open_context_menu_announcement), /* delayMs= */ 0,
    )
  }

  private fun getDotTargetsForBrailleCharacter(
    brailleCharacter: BrailleCharacter,
  ): List<DotTarget> =
    inputView.getDotTargets().filter { dotTarget ->
      brailleCharacter.isDotNumberOn(dotTarget.getDotNumber())
    }

  @VisibleForTesting
  fun testing_setTutorialState(state: TutorialState) {
    this.state = state
  }

  @VisibleForTesting
  fun testing_getTutorialState(): TutorialState {
    return state
  }

  @VisibleForTesting
  fun testing_getInputView(): BrailleInputView {
    return inputView
  }

  @VisibleForTesting
  fun testing_getBrailleInputViewCallback(): BrailleInputView.Callback {
    return inputPlaneCallback
  }

  @VisibleForTesting
  fun testing_getRotateOrientationContinue(): RotateOrientationContinue {
    return rotateOrientationContinue
  }

  companion object {
    @JvmField
    val ROTATION_270 = 270
    @JvmField
    val ROTATION_90 = 90
    @JvmField
    val ROTATION_180 = 180
    @JvmField
    val ROTATION_0 = 0
    // Exceeding this number of free interactions triggers a replay of the current tutorial
    // instruction upon subsequent interactions.
    @JvmField
    val FREE_INTERACTIONS_MAX = 5

    // Wait this amount of time before re-announcing instructions for a task for the user (such as
    // swipe-leftward) that has not yet been completed.
    @JvmField
    val AUDIAL_ANNOUNCEMENT_IDLE_MS = 5000

    // Wait this amount of time (estimated) for external speech gets completed before re-announcing
    // instructions for a task for the user (such as swipe-leftward) that has not yet been completed.
    @JvmField
    val AUDIAL_ANNOUNCEMENT_IDLE_FOR_EXTERNAL_SPEECH_MS = 5000

    private const val ANIMATION_DURATION_MS = 600
    private const val INPUT_VIEW_ANIMATION_DURATION_MS = 500
    private const val FLASHING_ANIMATION_DELAY_MS = 1000
    private const val BEEP_DELAY_MS = 1000
    private val TAG: String = TutorialView::class.java.simpleName

    private const val HIGHLIGHT_CHARACTER_ANIMATION_DURATION_MILLISECOND = 10
    private const val ANIMATION_DELAY_MS = 3000
    private const val TOTAL_LETTERS = 3
  }
}
