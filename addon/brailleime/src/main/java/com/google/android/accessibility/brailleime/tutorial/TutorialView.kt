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

import android.content.Context
import android.os.Handler
import android.util.Size
import android.view.GestureDetector
import android.view.GestureDetector.SimpleOnGestureListener
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import androidx.annotation.Nullable
import androidx.annotation.VisibleForTesting
import com.google.android.accessibility.braille.common.BrailleUserPreferences
import com.google.android.accessibility.braille.common.BrailleUtils
import com.google.android.accessibility.braille.common.Constants.BrailleType
import com.google.android.accessibility.braille.common.FeedbackManager
import com.google.android.accessibility.braille.common.TalkBackSpeaker
import com.google.android.accessibility.braille.common.translate.BrailleLanguages.Code
import com.google.android.accessibility.braille.interfaces.BrailleCharacter
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
import com.google.android.accessibility.brailleime.input.BrailleInputView
import com.google.android.accessibility.brailleime.input.BrailleInputView.CalibrationTriggeredType
import com.google.android.accessibility.brailleime.input.BrailleInputView.FingersPattern
import com.google.android.accessibility.brailleime.input.DotHoldSwipe
import com.google.android.accessibility.brailleime.input.Swipe
import com.google.android.accessibility.brailleime.input.Swipe.Direction
import com.google.android.accessibility.brailleime.tutorial.TutorialView.TutorialState.State
import com.google.android.accessibility.utils.output.SpeechController.UtteranceCompleteRunnable
import java.util.concurrent.atomic.AtomicInteger

/** Views that displays the braille tutorial and there are several {@link State} in the tutorial. */
open class TutorialView(
  context: Context,
  internal val tutorialCallback: TutorialCallback,
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

  internal val context: Context
  internal val intro: Intro
  internal val rotateOrientation: RotateOrientation
  internal val rotateOrientationContinue: RotateOrientationContinue
  internal val typeLetterA: TypeLetterA
  internal val tapLetterBCD: TypeLetterBCD
  internal val swipeLeft: SwipeLeft
  internal val swipeRight: SwipeRight
  internal val swipeUp: SwipeUp
  internal val swipeDown: SwipeDown
  internal val swipeDown2Fingers: SwipeDown2Fingers
  internal val swipeDown3Fingers: SwipeDown3Fingers
  internal val swipeUp3Fingers: SwipeUp3Fingers
  internal val contextMenuOpened: ContextMenuOpened
  internal val tutorialFinished: TutorialFinished
  internal val holdSixFingers: HoldSixFingers

  internal var state: TutorialState
  internal var orientation: Int
  internal val inputView: BrailleInputView
  internal val dotBlockView: DotBlockView
  internal var dotsFlashingAnimationView: DotsFlashingAnimationView? = null
  internal lateinit var tutorialAnimationView: TutorialAnimationView
  private val gestureDetector: GestureDetector
  internal val contextMenuDialog: ContextMenuDialog

  internal val translator: BrailleTranslator
  internal val handler: Handler = Handler()

  // This is used to prevent handling utterance completed callback once ViewContainer is
  // deactivated.
  internal var isStopped: Boolean = false

  // Whether or not to play announcement of clicking Braille dot or swipe event. Exploration
  // feedbacks will be skipped if the instruction is speaking.
  internal var allowExploration: Boolean = false
  internal var isTabletop: Boolean = false
  internal var numberOfInteractionsPerState: Int = 0
  internal val instructionSpeechId: AtomicInteger = AtomicInteger()

  internal val inputPlaneCallback: BrailleInputView.Callback =
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
    intro = Intro(this)
    rotateOrientation = RotateOrientation(this)
    rotateOrientationContinue = RotateOrientationContinue(this)
    typeLetterA = TypeLetterA(this)
    tapLetterBCD = TypeLetterBCD(this)
    swipeLeft = SwipeLeft(this)
    swipeRight = SwipeRight(this)
    swipeUp = SwipeUp(this)
    swipeDown = SwipeDown(this)
    swipeDown2Fingers = SwipeDown2Fingers(this)
    swipeDown3Fingers = SwipeDown3Fingers(this)
    swipeUp3Fingers = SwipeUp3Fingers(this)
    contextMenuOpened = ContextMenuOpened(this)
    tutorialFinished = TutorialFinished()
    holdSixFingers = HoldSixFingers(this)
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

  internal fun switchState(state: TutorialState, delay: Long) {
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

    internal const val ANIMATION_DURATION_MS = 600
    internal const val INPUT_VIEW_ANIMATION_DURATION_MS = 500
    internal const val FLASHING_ANIMATION_DELAY_MS = 1000
    internal const val BEEP_DELAY_MS = 1000
    private val TAG: String = TutorialView::class.java.simpleName

    internal const val HIGHLIGHT_CHARACTER_ANIMATION_DURATION_MILLISECOND = 10
    internal const val ANIMATION_DELAY_MS = 3000
    internal const val TOTAL_LETTERS = 3
  }
}
