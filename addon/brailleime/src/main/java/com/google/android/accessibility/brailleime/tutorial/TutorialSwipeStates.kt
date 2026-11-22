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

import com.google.android.accessibility.braille.common.FeedbackManager
import com.google.android.accessibility.braille.interfaces.BrailleCharacter
import com.google.android.accessibility.brailleime.BrailleImeVibrator
import com.google.android.accessibility.brailleime.BrailleImeVibrator.VibrationType
import com.google.android.accessibility.brailleime.OrientationMonitor
import com.google.android.accessibility.brailleime.OrientationMonitor.Orientation
import com.google.android.accessibility.brailleime.R
import com.google.android.accessibility.brailleime.input.Swipe
import com.google.android.accessibility.brailleime.input.Swipe.Direction
import com.google.android.accessibility.brailleime.tutorial.TutorialAnimationView.SwipeAnimation.Direction.BOTTOM_TO_TOP
import com.google.android.accessibility.brailleime.tutorial.TutorialAnimationView.SwipeAnimation.Direction.LEFT_TO_RIGHT
import com.google.android.accessibility.brailleime.tutorial.TutorialAnimationView.SwipeAnimation.Direction.RIGHT_TO_LEFT
import com.google.android.accessibility.brailleime.tutorial.TutorialAnimationView.SwipeAnimation.Direction.TOP_TO_BOTTOM
import com.google.android.accessibility.brailleime.tutorial.TutorialView.TutorialState.State

/**
 * 手势教学步骤：从 [TutorialView] 拆出的滑动状态。
 *
 * 依次教单指左右上下、双指下、三指下、三指上；每步校验手势手指数与方向，
 * 命中后停掉滑动动画、播读结果并进入下一步。
 */

class SwipeLeft(private val host: TutorialView) : TutorialView.TutorialState {
  private var actionCompleted: Boolean = false

  override fun getCurrentState(): State {
    return State.SWIPE_LEFT
  }

  override fun nextState(): TutorialView.TutorialState {
    return host.swipeRight
  }

  override fun loadView() {
    host.addView(host.inputView)
    host.tutorialAnimationView.reset()
    host.tutorialAnimationView.startSwipeAnimation(1, LEFT_TO_RIGHT)
    host.tutorialAnimationView.startHintToastAnimation(host.state.hintText())
    host.addView(host.tutorialAnimationView)
    host.speakAnnouncement(audialAnnouncement(), /* delayMs= */ 0)
  }

  override fun audialAnnouncement(): String {
    return host.resources.getString(R.string.swipe_left_announcement)
  }

  override fun audialAnnouncementRepeated(): String {
    return host.resources.getString(R.string.swipe_left_announcement)
  }

  override fun hintText(): String {
    return host.resources.getString(R.string.instruction_swipe_left)
  }

  override fun resultText(): String {
    return host.resources.getString(R.string.result_swipe_left)
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
    // Read out the corresponding announcement.
    host.speakBrailleCharacter(brailleCharacter, /* delayMs= */ 0)
  }

  override fun onSwipeProduced(swipe: Swipe) {
    if (actionCompleted) {
      return
    }

    val touchCount = swipe.touchCount
    val direction = swipe.direction
    if (direction == Direction.LEFT && touchCount == 1) {
      host.tutorialAnimationView.stopSwipeAnimation()
      host.tutorialAnimationView.startActionResultAnimation(resultText())
      host.tutorialCallback.onPlaySound(FeedbackManager.Type.BEEP)
      // Wait a second for playing sound and then speak the post-action announcement.
      host.speakAnnouncement(
        host.resources.getString(R.string.swipe_left_action_result_announcement),
        TutorialView.BEEP_DELAY_MS,
      )
      actionCompleted = true
    } else {
      // Read out the corresponding announcement.
      host.speakSwipeEvent(swipe)
    }
    host.vibrateForSwipeGestures(swipe)
  }
}

class SwipeRight(private val host: TutorialView) : TutorialView.TutorialState {
  private var actionCompleted: Boolean = false

  override fun getCurrentState(): State {
    return State.SWIPE_RIGHT
  }

  override fun nextState(): TutorialView.TutorialState {
    return host.swipeUp
  }

  override fun loadView() {
    host.addView(host.inputView)
    host.tutorialAnimationView.reset()
    host.tutorialAnimationView.startSwipeAnimation(1, RIGHT_TO_LEFT)
    host.tutorialAnimationView.startHintToastAnimation(host.state.hintText())
    host.addView(host.tutorialAnimationView)
    host.speakAnnouncement(audialAnnouncement(), /* delayMs= */ 0)
  }

  override fun audialAnnouncement(): String {
    return host.resources.getString(R.string.swipe_right_announcement)
  }

  override fun audialAnnouncementRepeated(): String {
    return host.resources.getString(R.string.swipe_right_announcement)
  }

  override fun hintText(): String {
    return host.resources.getString(R.string.instruction_swipe_right)
  }

  override fun resultText(): String {
    return host.resources.getString(R.string.result_swipe_right)
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
    // Read out the corresponding announcement.
    host.speakBrailleCharacter(brailleCharacter, /* delayMs= */ 0)
  }

  override fun onSwipeProduced(swipe: Swipe) {
    if (actionCompleted) {
      return
    }

    val touchCount = swipe.touchCount
    val direction = swipe.direction
    if (direction == Direction.RIGHT && touchCount == 1) {
      host.tutorialAnimationView.stopSwipeAnimation()
      host.tutorialAnimationView.startActionResultAnimation(resultText())
      host.tutorialCallback.onPlaySound(FeedbackManager.Type.BEEP)
      // Wait a second for playing sound and then speak the post-action announcement.
      host.speakAnnouncement(host.resources.getString(R.string.result_swipe_right), TutorialView.BEEP_DELAY_MS)
      actionCompleted = true
    } else {
      // Read out the corresponding announcement.
      host.speakSwipeEvent(swipe)
    }
    host.vibrateForSwipeGestures(swipe)
  }
}

class SwipeUp(private val host: TutorialView) : TutorialView.TutorialState {
  private var actionCompleted: Boolean = false

  override fun getCurrentState(): State {
    return State.SWIPE_UP
  }

  override fun nextState(): TutorialView.TutorialState {
    return host.swipeDown
  }

  override fun loadView() {
    host.addView(host.inputView)
    host.tutorialAnimationView.reset()
    host.tutorialAnimationView.startSwipeAnimation(1, BOTTOM_TO_TOP)
    host.tutorialAnimationView.startHintToastAnimation(host.state.hintText())
    host.addView(host.tutorialAnimationView)
    host.speakAnnouncement(audialAnnouncement(), /* delayMs= */ 0)
  }

  override fun audialAnnouncement(): String {
    return host.resources.getString(R.string.swipe_up_announcement)
  }

  override fun audialAnnouncementRepeated(): String {
    return host.resources.getString(R.string.swipe_up_announcement)
  }

  override fun hintText(): String {
    return host.resources.getString(R.string.instruction_swipe_up)
  }

  override fun resultText(): String {
    return host.resources.getString(R.string.result_swipe_up)
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
    host.speakBrailleCharacter(brailleCharacter, /* delayMs= */ 0)
  }

  override fun onSwipeProduced(swipe: Swipe) {
    if (actionCompleted) {
      return
    }

    val touchCount = swipe.touchCount
    val direction = swipe.direction
    if (direction == Direction.UP && touchCount == 1) {
      host.tutorialAnimationView.stopSwipeAnimation()
      host.tutorialAnimationView.startActionResultAnimation(resultText())
      host.tutorialCallback.onPlaySound(FeedbackManager.Type.BEEP)
      // Wait a second for playing sound and then speak the post-action announcement.
      host.speakAnnouncement(host.resources.getString(R.string.result_swipe_up), TutorialView.BEEP_DELAY_MS)
      actionCompleted = true
    } else {
      // Read out the corresponding announcement.
      host.speakSwipeEvent(swipe)
    }
    host.vibrateForSwipeGestures(swipe)
  }
}

class SwipeDown(private val host: TutorialView) : TutorialView.TutorialState {
  private var actionCompleted: Boolean = false

  override fun getCurrentState(): State {
    return State.SWIPE_DOWN
  }

  override fun nextState(): TutorialView.TutorialState {
    return host.swipeDown2Fingers
  }

  override fun loadView() {
    host.addView(host.inputView)
    host.tutorialAnimationView.reset()
    host.tutorialAnimationView.startSwipeAnimation(1, TOP_TO_BOTTOM)
    host.tutorialAnimationView.startHintToastAnimation(host.state.hintText())
    host.addView(host.tutorialAnimationView)
    host.speakAnnouncement(audialAnnouncement(), /* delayMs= */ 0)
  }

  override fun audialAnnouncement(): String {
    return host.resources.getString(R.string.swipe_down_announcement)
  }

  override fun audialAnnouncementRepeated(): String {
    return host.resources.getString(R.string.swipe_down_announcement)
  }

  override fun hintText(): String {
    return host.resources.getString(R.string.instruction_swipe_down)
  }

  override fun resultText(): String {
    return host.resources.getString(R.string.result_swipe_down)
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
    host.speakBrailleCharacter(brailleCharacter, /* delayMs= */ 0)
  }

  override fun onSwipeProduced(swipe: Swipe) {
    if (actionCompleted) {
      return
    }

    val touchCount = swipe.touchCount
    val direction = swipe.direction
    if (direction == Direction.DOWN && touchCount == 1) {
      host.tutorialAnimationView.stopSwipeAnimation()
      host.tutorialAnimationView.startActionResultAnimation(resultText())
      host.tutorialCallback.onPlaySound(FeedbackManager.Type.BEEP)
      // Wait a second for playing sound and then speak the post-action announcement.
      host.speakAnnouncement(host.resources.getString(R.string.result_swipe_down), TutorialView.BEEP_DELAY_MS)
      actionCompleted = true
    } else {
      // Read out the corresponding announcement.
      host.speakSwipeEvent(swipe)
    }
    host.vibrateForSwipeGestures(swipe)
  }
}

class SwipeDown2Fingers(private val host: TutorialView) : TutorialView.TutorialState {
  private var actionCompleted: Boolean = false

  override fun getCurrentState(): State {
    return State.SWIPE_DOWN_2_FINGERS
  }

  override fun nextState(): TutorialView.TutorialState {
    return host.swipeDown3Fingers
  }

  override fun loadView() {
    host.addView(host.inputView)
    host.tutorialAnimationView.reset()
    host.tutorialAnimationView.startSwipeAnimation(2, TOP_TO_BOTTOM)
    host.tutorialAnimationView.startHintToastAnimation(host.state.hintText())
    host.addView(host.tutorialAnimationView)
    host.speakAnnouncement(audialAnnouncement(), /* delayMs= */ 0)
  }

  override fun audialAnnouncement(): String {
    return host.resources.getString(R.string.swipe_down_2_fingers_announcement)
  }

  override fun audialAnnouncementRepeated(): String {
    return host.resources.getString(R.string.swipe_down_2_fingers_announcement)
  }

  override fun hintText(): String {
    return host.resources.getString(R.string.instruction_swipe_down_2_fingers)
  }

  override fun resultText(): String {
    return host.resources.getString(R.string.result_swipe_down_2_fingers)
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
    // Read out the corresponding announcement.
    host.speakBrailleCharacter(brailleCharacter, /* delayMs= */ 0)
  }

  override fun onSwipeProduced(swipe: Swipe) {
    if (actionCompleted) {
      return
    }

    val touchCount = swipe.touchCount
    val direction = swipe.direction
    if (direction == Direction.DOWN && touchCount == 2) {
      host.tutorialAnimationView.stopSwipeAnimation()
      host.tutorialAnimationView.startActionResultAnimation(resultText())
      host.tutorialCallback.onPlaySound(FeedbackManager.Type.BEEP)
      // Wait a second for playing sound and then speak the post-action announcement.
      host.speakAnnouncement(
        host.resources.getString(R.string.swipe_down_2_fingers_completed_announcement),
        TutorialView.BEEP_DELAY_MS,
      )
      actionCompleted = true
    } else {
      // Read out the corresponding announcement.
      host.speakSwipeEvent(swipe)
    }
    host.vibrateForSwipeGestures(swipe)
  }
}

class SwipeDown3Fingers(private val host: TutorialView) : TutorialView.TutorialState {
  private var actionCompleted: Boolean = false

  override fun getCurrentState(): State {
    return State.SWIPE_DOWN_3_FINGERS
  }

  override fun nextState(): TutorialView.TutorialState {
    return host.swipeUp3Fingers
  }

  override fun loadView() {
    host.addView(host.inputView)
    host.tutorialAnimationView.reset()
    host.tutorialAnimationView.startSwipeAnimation(3, TOP_TO_BOTTOM)
    host.tutorialAnimationView.startHintToastAnimation(host.state.hintText())
    host.addView(host.tutorialAnimationView)
    host.speakAnnouncement(audialAnnouncement(), /* delayMs= */ 0)
  }

  override fun audialAnnouncement(): String {
    return host.resources.getString(R.string.swipe_down_3_fingers_announcement)
  }

  override fun audialAnnouncementRepeated(): String {
    return host.resources.getString(R.string.swipe_down_3_fingers_announcement)
  }

  override fun hintText(): String {
    return host.resources.getString(R.string.instruction_swipe_down_3_fingers)
  }

  override fun resultText(): String {
    return host.resources.getString(R.string.result_swipe_down_3_fingers)
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
    host.speakBrailleCharacter(brailleCharacter, /* delayMs= */ 0)
  }

  override fun onSwipeProduced(swipe: Swipe) {
    if (actionCompleted) {
      return
    }

    val touchCount = swipe.touchCount
    val direction = swipe.direction
    if (direction == Direction.DOWN && touchCount == 3) {
      host.tutorialAnimationView.stopSwipeAnimation()
      host.tutorialAnimationView.startActionResultAnimation(resultText())
      host.tutorialCallback.onPlaySound(FeedbackManager.Type.BEEP)
      // Wait a second for playing sound and then speak the post-action announcement.
      host.speakAnnouncement(
        host.resources.getString(R.string.swipe_down_3_fingers_completed_announcement),
        TutorialView.BEEP_DELAY_MS,
      )
      actionCompleted = true
    } else {
      host.speakSwipeEvent(swipe)
    }
    host.vibrateForSwipeGestures(swipe)
  }
}

class SwipeUp3Fingers(private val host: TutorialView) : TutorialView.TutorialState {
  private var actionCompleted: Boolean = false

  override fun getCurrentState(): State {
    return State.SWIPE_UP_3_FINGERS
  }

  override fun nextState(): TutorialView.TutorialState {
    return host.contextMenuOpened
  }

  override fun loadView() {
    actionCompleted = false
    host.addView(host.inputView)
    host.tutorialAnimationView.reset()
    host.tutorialAnimationView.startSwipeAnimation(3, BOTTOM_TO_TOP)
    host.tutorialAnimationView.startHintToastAnimation(host.state.hintText())
    host.addView(host.tutorialAnimationView)
    host.speakAnnouncement(audialAnnouncement(), /* delayMs= */ 0)
  }

  override fun audialAnnouncement(): String {
    return host.resources.getString(R.string.swipe_up_3_fingers_announcement)
  }

  override fun audialAnnouncementRepeated(): String {
    return host.resources.getString(R.string.swipe_up_3_fingers_announcement)
  }

  override fun hintText(): String {
    return host.resources.getString(R.string.instruction_swipe_up_3_fingers)
  }

  override fun onUtteranceCompleted() {
    if (!actionCompleted) {
      host.speakAnnouncement(audialAnnouncementRepeated(), /* delayMs= */ 0)
    }
  }

  override fun onBrailleProduced(brailleCharacter: BrailleCharacter) {
    if (actionCompleted) {
      return
    }

    BrailleImeVibrator.getInstance(host.context).vibrate(VibrationType.BRAILLE_COMMISSION)
    host.speakBrailleCharacter(brailleCharacter, /* delayMs= */ 0)
  }

  override fun onSwipeProduced(swipe: Swipe) {
    if (actionCompleted) {
      return
    }

    val touchCount = swipe.touchCount
    val direction = swipe.direction
    if (direction == Direction.UP && touchCount == 3) {
      host.switchState(nextState(), /* delay= */ 0)
      actionCompleted = true
    } else if (direction == Direction.LEFT && touchCount == 3) {
      // Braille keyboard view is forced to be in landscape. When device is portrait and user
      // swipes upward, for keyboard view, it's swipe rightward.
      if (OrientationMonitor.getInstance().currentOrientation == Orientation.PORTRAIT) {
        host.switchState(nextState(), /* delay= */ 0)
        actionCompleted = true
      }
    } else {
      // Read out the corresponding announcement.
      host.speakSwipeEvent(swipe)
    }
    host.vibrateForSwipeGestures(swipe)
  }
}
