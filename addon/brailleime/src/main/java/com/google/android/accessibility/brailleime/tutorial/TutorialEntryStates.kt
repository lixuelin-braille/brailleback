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
import android.animation.ValueAnimator
import android.graphics.drawable.AnimationDrawable
import android.os.Bundle
import android.text.Spannable
import android.text.style.BackgroundColorSpan
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.VisibleForTesting
import com.google.android.accessibility.braille.common.FeedbackManager
import com.google.android.accessibility.brailleime.BrailleImeVibrator
import com.google.android.accessibility.brailleime.BrailleImeVibrator.VibrationType.OTHER_GESTURES
import com.google.android.accessibility.brailleime.OrientationMonitor
import com.google.android.accessibility.brailleime.OrientationMonitor.Orientation
import com.google.android.accessibility.brailleime.R
import com.google.android.accessibility.brailleime.input.BrailleInputView.CalibrationTriggeredType
import com.google.android.accessibility.brailleime.input.BrailleInputView.FingersPattern
import com.google.android.accessibility.brailleime.input.Swipe
import com.google.android.accessibility.brailleime.input.Swipe.Direction
import com.google.android.accessibility.utils.BuildVersionUtils
import com.google.android.accessibility.brailleime.tutorial.TutorialView.TutorialState.State

/**
 * 教程开场与校准步骤：从 [TutorialView] 拆出的前半段状态。
 *
 * 包含进入页、横竖屏引导（含续步）、六指握姿校准；字母点位与手势步骤见
 * [TutorialLetterStates] / [TutorialSwipeStates]，菜单与收尾见 [TutorialTerminalStates]。
 * 每个状态只通过 [TutorialView] 暴露的内部成员读写视图与播报，不直接持有宿主字段。
 */

class Intro(private val host: TutorialView) : TutorialView.TutorialState {

  override fun getCurrentState(): State {
    return State.INTRO
  }

  override fun nextState(): TutorialView.TutorialState {
    return if (host.isTabletop) host.holdSixFingers else host.rotateOrientation
  }

  override fun loadView() {
    host.tutorialCallback.onBrailleImeInactivated()
    View.inflate(host.context, R.layout.tutorial_intro, host)
    val animationView = host.findViewById<ImageView>(R.id.tutorial_animation)
    (animationView.drawable as AnimationDrawable).start()
    host.findViewById<View>(R.id.next_button)
      .setOnClickListener(
        { view ->
          host.tutorialCallback.onBrailleImeActivated()
          host.switchState(nextState(), 0)
        })
    host.findViewById<View>(R.id.leave_keyboard_button)
      .setOnClickListener(
        { view ->
          // For users who got in accidentally, switch to the next keyboard.
          host.tutorialCallback.onSwitchToNextInputMethod()
          host.switchState(State.NONE, /* delay= */ 0)
        })

    // To make TalkBack focus on tutorial view. This is not a good solution, try to find better
    // one later.
    host.postDelayed(
      {
        host.findViewById<View>(R.id.tutorial_title)
          .performAccessibilityAction(ACTION_ACCESSIBILITY_FOCUS, Bundle())
      },
      /* delayMillis= */ 1000,
    )
  }
}

class RotateOrientation(private val host: TutorialView) : TutorialView.TutorialState {
  private var actionCompleted: Boolean = false

  override fun getCurrentState(): State {
    return State.ROTATE_ORIENTATION
  }

  override fun nextState(): TutorialView.TutorialState {
    val orientation: OrientationMonitor.Orientation =
      OrientationMonitor.getInstance().currentOrientation
    if (orientation == OrientationMonitor.Orientation.PORTRAIT ||
      orientation == OrientationMonitor.Orientation.REVERSE_PORTRAIT
    ) {
      // Guide user to rotate their phone to landscape.
      return host.rotateOrientationContinue
    }
    return host.typeLetterA
  }

  override fun loadView() {
    // This view is used to detect swipe gesture and set as transparent to not seen by user.
    host.inputView.setAlpha(0f)
    host.addView(host.inputView)

    View.inflate(host.context, R.layout.tutorial_rotate_orientation, host)
    host.findViewById<View>(R.id.tap_to_continue).setBackground(TapMeAnimationDrawable(host.context))
    host.speakAnnouncement(audialAnnouncement(), /* delayMs= */ 0)
  }

  override fun onUtteranceCompleted() {
    host.speakAnnouncement(audialAnnouncementRepeated(), /* delayMs= */ 0)
  }

  override fun audialAnnouncement(): String {
    return host.resources.getString(
      R.string.rotate_orientation_announcement,
      host.resources.getString(R.string.rotate_orientation_inactive_announcement),
    )
  }

  override fun audialAnnouncementRepeated(): String {
    return host.resources.getString(R.string.rotate_orientation_inactive_announcement)
  }

  override fun onDoubleTap() {
    if (!actionCompleted) {
      actionCompleted = true
      // onDoubleTap callback comes twice, delay 500ms to consume this event.
      host.switchState(nextState(), /* delay= */ 500)
    }
  }

  override fun onSwipeProduced(swipe: Swipe) {
    val touchCount = swipe.touchCount
    val direction = swipe.direction
    if (direction == Direction.DOWN && touchCount == 3) {
      // Exit Braille keyboard.
      host.tutorialCallback.onSwitchToNextInputMethod()
    } else if (direction == Direction.RIGHT && touchCount == 3) {
      // Braille keyboard view is forced to be in landscape. When device is portrait and user
      // swipes left, for keyboard view, it's swipe down.
      if (OrientationMonitor.getInstance().currentOrientation == Orientation.PORTRAIT) {
        // Exit Braille keyboard.
        host.tutorialCallback.onSwitchToNextInputMethod()
      }
    }
    host.vibrateForSwipeGestures(swipe)
  }
}

class RotateOrientationContinue(private val host: TutorialView) :
  TutorialView.TutorialState {
  private var animator: ValueAnimator? = null
  private var actionCompleted: Boolean = false

  @VisibleForTesting
  val orientationMonitorCallBack: OrientationMonitor.Callback =
    object : OrientationMonitor.Callback {
      override fun onOrientationChanged(orientation: OrientationMonitor.Orientation) {
        if (orientation == OrientationMonitor.Orientation.LANDSCAPE ||
          orientation == OrientationMonitor.Orientation.REVERSE_LANDSCAPE
        ) {
          host.tutorialCallback.onPlaySound(FeedbackManager.Type.BEEP)
          BrailleImeVibrator.getInstance(host.context).vibrate(OTHER_GESTURES)
          // Save the state only because TutorialView will recreate if orientation is changed.
          host.state = host.rotateOrientation
          host.tutorialCallback.unregisterOrientationChange()
          host.tutorialCallback.onRestartTutorial()
        }
      }
    }

  override fun getCurrentState(): State {
    return State.ROTATE_ORIENTATION_CONTINUE
  }

  override fun nextState(): TutorialView.TutorialState {
    return host.typeLetterA
  }

  override fun loadView() {
    host.tutorialCallback.registerOrientationChange(orientationMonitorCallBack)
    // Use to detect swipe gesture and set as transparent.
    host.inputView.setAlpha(0f)
    host.addView(host.inputView)

    View.inflate(host.context, R.layout.tutorial_rotate_orientation, host)
    host.findViewById<View>(R.id.tap_to_continue).setBackground(TapMeAnimationDrawable(host.context))
    startHighlightTextAnimation()
    host.speakAnnouncement(audialAnnouncement(), /* delayMs= */ 0)
  }

  override fun audialAnnouncement(): String {
    return host.resources.getString(R.string.rotate_orientation_continue_announcement)
  }

  override fun audialAnnouncementRepeated(): String {
    return host.resources.getString(R.string.rotate_orientation_continue_announcement)
  }

  override fun onUtteranceCompleted() {
    host.speakAnnouncement(audialAnnouncementRepeated(), /* delayMs= */ 0)
  }

  override fun onDoubleTap() {
    if (!actionCompleted) {
      actionCompleted = true
      host.tutorialCallback.unregisterOrientationChange()
      host.switchState(nextState(), /* delay= */ 0)
    }
  }

  private fun startHighlightTextAnimation() {
    val highlightTextView = host.findViewById<TextView>(R.id.highlight_description)
    val highlightText: CharSequence = highlightTextView.text
    val highlightColor: Int =
      host.resources.getColor(R.color.text_highlight_color, /* theme= */ null)
    animator = ValueAnimator.ofInt(1, highlightText.length)
    animator!!.setDuration(
      highlightText.length * TutorialView.HIGHLIGHT_CHARACTER_ANIMATION_DURATION_MILLISECOND.toLong(),
    )
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
          animator!!.setStartDelay(TutorialView.ANIMATION_DELAY_MS.toLong())
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

class HoldSixFingers(private val host: TutorialView) : TutorialView.TutorialState {
  private var actionCompleted: Boolean = false
  private var fingersPattern: FingersPattern? = null

  override fun getCurrentState(): State {
    return State.HOLD_6_FINGERS
  }

  override fun nextState(): TutorialView.TutorialState {
    return host.typeLetterA
  }

  override fun audialAnnouncement(): String {
    return host.resources
      .getString(
        R.string.hold_six_fingers_announcement,
        host.resources.getString(R.string.hold_six_fingers_description),
      )
  }

  override fun audialAnnouncementRepeated(): String {
    if (fingersPattern == FingersPattern.FIVE_FINGERS) {
      return host.resources
        .getString(
          R.string.calibration_hold_left_or_right_three_finger_announcement,
          host.resources.getString(R.string.left_hand),
        )
    } else if (fingersPattern == FingersPattern.FIRST_THREE_FINGERS) {
      return host.resources
        .getString(
          R.string.calibration_step2_hold_left_or_right_finger_announcement,
          host.resources.getString(R.string.right_hand),
        )
    }
    return host.resources.getString(R.string.hold_six_fingers_inactive_announcement)
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
      host.tutorialCallback.onPlaySound(FeedbackManager.Type.BEEP)
      // Wait a second for playing sound and then speak the post-action announcement.
      host.speakAnnouncement(
        host.resources.getString(R.string.calibration_finish_announcement), TutorialView.BEEP_DELAY_MS,
      )
      actionCompleted = true
    } else if (fingersPattern == FingersPattern.FIVE_FINGERS) {
      val sb = StringBuilder()
      sb.append(
        host.resources
          .getString(
            R.string.calibration_step1_hold_left_or_right_finger_announcement,
            host.resources.getString(R.string.left_hand),
          ),
      )
        .append(" ")
        .append(
          host.resources
            .getString(
              R.string.calibration_hold_left_or_right_three_finger_announcement,
              host.resources.getString(R.string.left_hand),
            ),
        )
      host.speakAnnouncement(sb.toString(), /* delayMs= */ 0)
    } else if (fingersPattern == FingersPattern.FIRST_THREE_FINGERS) {
      this.fingersPattern = FingersPattern.FIRST_THREE_FINGERS
      val sb = StringBuilder()
      sb.append(
        host.resources
          .getString(
            R.string.calibration_step2_hold_left_or_right_finger_announcement,
            host.resources.getString(R.string.right_hand),
          ),
      )
        .append(" ")
        .append(
          host.resources
            .getString(
              R.string.calibration_hold_left_or_right_three_finger_announcement,
              host.resources.getString(R.string.right_hand),
            ),
        )
      host.speakAnnouncement(sb.toString(), /* delayMs= */ 0)
    }
  }

  override fun onTwoStepCalibrationRetry(isFirstStep: Boolean) {
    val announcement =
      host.resources
        .getString(
          R.string.calibration_hold_left_or_right_three_finger_announcement,
          host.resources.getString(R.string.left_hand),
        )
    host.speakAnnouncement(announcement, /* delayMs= */ 0)
  }

  override fun onUtteranceCompleted() {
    if (actionCompleted) {
      actionCompleted = false
      host.switchState(nextState(), /* delay= */ 0)
    } else {
      host.speakAnnouncement(audialAnnouncementRepeated(), /* delayMs= */ 0)
    }
  }

  override fun loadView() {
    // This view is used to detect swipe gesture and set as transparent to not seen by user.
    host.inputView.setAlpha(0f)
    host.addView(host.inputView)

    View.inflate(host.context, R.layout.tutorial_hold_six_fingers, host)
    val animationView = host.findViewById<ImageView>(R.id.tutorial_animation)
    (animationView.drawable as AnimationDrawable).start()
    host.speakAnnouncement(audialAnnouncement(), /* delayMs= */ 0)
  }
}
