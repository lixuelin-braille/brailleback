/*
 * Copyright (C) 2023 Google Inc.
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

package com.google.android.accessibility.brailleime.keyboardview

import com.google.android.accessibility.utils.BuildVersionUtils


import android.content.Context
import android.graphics.Region
import android.hardware.display.DisplayManager
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.Build.VERSION_CODES
import android.util.Size
import android.view.ContextThemeWrapper
import android.view.Surface
import android.view.View
import android.view.ViewTreeObserver.OnGlobalLayoutListener
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.annotation.NonNull
import androidx.core.graphics.Insets
import androidx.core.view.OnApplyWindowInsetsListener
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.accessibility.braille.common.BrailleUserPreferences
import com.google.android.accessibility.braille.common.TouchDots
import com.google.android.accessibility.brailleime.BrailleIme
import com.google.android.accessibility.brailleime.BrailleImeLog
import com.google.android.accessibility.brailleime.BrailleInputOptions
import com.google.android.accessibility.brailleime.CustomGestureView
import com.google.android.accessibility.brailleime.CustomGestureView.CustomGestureCallback
import com.google.android.accessibility.brailleime.R
import com.google.android.accessibility.brailleime.Utils
import com.google.android.accessibility.brailleime.dialog.ViewAttachedDialog
import com.google.android.accessibility.brailleime.input.BrailleDisplayImeStripView
import com.google.android.accessibility.brailleime.input.BrailleInputView
import com.google.android.accessibility.brailleime.tutorial.TutorialView
import com.google.android.accessibility.brailleime.tutorial.TutorialView.TutorialCallback
import com.google.android.accessibility.brailleime.tutorial.TutorialView.TutorialState.State
import com.google.common.annotations.VisibleForTesting
import java.util.Optional

/**
 * Manages the IME's input view and the code branching caused by strategy differences in Q and R.
 *
 * <p>First note that any Android Braille IME implementation faces a major challenge: it wants
 * ExploreByTouch to be on (because it will presumably be used by blind/low-vision customers for
 * whom ExploreByTouch is an important feature), but it needs ExploreByTouch to be off because
 * having it off allows the delivery of raw MotionEvents to the IME, which is crucial for
 * performance reasons. In other words, a Braille IME needs ExploreByTouch to be disabled in the
 * region where braille input (tapping or swiping on braille dots) is occurring, but it would like
 * to have ExploreByTouch enabled for the rest of the view graph, including the underlying Activity,
 * any chrome around the input area (such as a system navigation bar), and any dialogs that pop-up
 * over the IME.
 *
 * <p>In Q-and-below, this problem is solved quite hackily by a combination of
 *
 * <ol>
 *   <li>An additional Window of type ACCESSIBILITY_OVERLAY in which the braille dots are placed
 *   <li>The temporary disablement of ExploreByTouch while the IME is up and no IME-related dialog
 *       is up
 *   <li>A fully-immersive View so that the system nav bar cannot be accidentally touched. This is a
 *       major deviation from the IME pattern encouraged by the framework; the input Window provided
 *       by the IME framework is filled with a 0-size unused View and all (non-dialog-related) input
 *       occurs in the overlay Window.
 * </ol>
 *
 * <p>In R-and-later, with the introduction of setTouchExplorationPassthroughRegion API, a simpler
 * approach is used, which is more aligned with the pattern encouraged by the IME framework. Now,
 * the braille dot Views get placed into the IME's input View (instead of introducing an overlay
 * Window) and ExploreByTouch is not disabled; instead the Region of the screen corresponding to the
 * input View is marked as being a touch exploration passthrough region, which allows raw
 * MotionEvents to be received by BrailleIme.
 *
 * <p>In support of these two strategies, this class manages two Views: imeInputView and
 * viewContainer, the roles of which are described below.
 *
 * <p>The imeInputView gets passed to the {@link android.inputmethodservice.InputMethodService} and
 * is the framework's notion of IME input View. It might be empty or it might contain usable Views,
 * depending on the strategy. It also acts as a dialog anchor which works even if TalkBack is turned
 * off (edge case) and the braille input dots cannot be shown.
 *
 * <p>The viewContainer on the other hand always holds the interact-able Views (such as braille dots
 * or tutorial-related text and images) regardless of the strategy employed. In the Q-and-below
 * strategy, it gets placed inside of the overlay Window; in the R-and-later strategy, it serves as
 * the imeInputView - in that case both imeInputView and viewContainer refer to the same object.
 */
abstract class KeyboardView {
  companion object {
    private const val TAG = "KeyboardView"
  }

  /** A callback to notify clients of state changes. */
  interface KeyboardViewCallback {
    fun onViewReady()

    fun onViewUpdated()

    fun onViewCleared()

    fun isHideScreenMode(): Boolean
  }

  private var displayManager: DisplayManager? = null
  private var brailleInputView: BrailleInputView? = null
  private var stripView: BrailleDisplayImeStripView? = null
  private var tutorialView: TutorialView? = null
  private var customGestureView: CustomGestureView? = null
  protected val context: Context
  protected val keyboardViewCallback: KeyboardViewCallback
  @JvmField
  protected var windowManager: WindowManager? = null
  protected var imeInputView: View? = null
  protected var viewContainer: ViewContainer? = null

  protected constructor(context: Context, keyboardViewCallback: KeyboardViewCallback) {
    this.context = ContextThemeWrapper(context, R.style.BrailleImeTheme)
    this.keyboardViewCallback = keyboardViewCallback
  }

  /** Creates and returns the IME inputView. */
  fun createImeInputView(): View {
    init()
    imeInputView = createImeInputViewInternal()
    return imeInputView!!
  }

  /** Updates the view that attached to IME window. */
  fun updateImeView() {
    // To avoid affecting other builds, apply the fix only to the affected Android versions.
    if (Build.VERSION.SDK_INT >= VERSION_CODES.VANILLA_ICE_CREAM) {
      imeInputView!!.setBackgroundColor(
        if (this is AccessibilityOverlayKeyboardView) context.getColor(R.color.scrim)
        else context.getColor(R.color.braille_keyboard_background))
      ViewCompat.setOnApplyWindowInsetsListener(
        imeInputView!!,
        object : OnApplyWindowInsetsListener {
          @NonNull
          override fun onApplyWindowInsets(
            @NonNull v: View,
            @NonNull windowInsets: WindowInsetsCompat,
          ): WindowInsetsCompat {
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, insets.bottom)
            return WindowInsetsCompat.CONSUMED
          }
        })
    }
  }

  /** Creates and returns ViewContainer. */
  fun createViewContainer(): ViewContainer {
    if (isViewContainerCreated()) {
      return viewContainer!!
    }
    init()
    viewContainer = createViewContainerInternal()
    updateViewContainerInternal()
    return viewContainer!!
  }

  private fun init() {
    if (displayManager == null) {
      displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
      displayManager!!.registerDisplayListener(displayListener, /* handler= */ null)
    }
  }

  /** Saves input view points. */
  fun saveInputViewPoints() {
    if (brailleInputView != null) {
      brailleInputView!!.savePoints()
    }
  }

  /** Returns current tutorial status. */
  fun getTutorialStatus(): State {
    return if (isTutorialViewCreated()) tutorialView!!.getTutorialState() else State.NONE
  }

  fun isViewContainerCreated(): Boolean {
    return viewContainer != null
  }

  /**
   * 【移植改造】返回承载键盘内容的视图容器，供宿主把播报内容以无障碍公告形式交给读屏朗读。
   * 容器尚未创建时返回 null。
   */
  fun getViewContainer(): View? {
    return viewContainer
  }

  fun isInputViewCreated(): Boolean {
    return brailleInputView != null
  }

  fun isTutorialViewCreated(): Boolean {
    return tutorialView != null
  }

  fun isTutorialShown(): Boolean {
    return tutorialView != null && tutorialView!!.isShown()
  }

  fun isTouchInteracting(): Boolean {
    return isInputViewCreated() && brailleInputView!!.isTouchInteracting()
  }

  /** Creates {@link BrailleInputView} and adds it into {@link ViewContainer}. */
  open fun createAndAddInputView(
    inputPlaneCallback: BrailleInputView.Callback,
    tableTopMode: Boolean,
  ) {
    if (isInputViewAttached()) {
      keyboardViewCallback.onViewReady()
      return
    }
    runWhenViewContainerIsReady(
      Runnable {
        brailleInputView =
          BrailleInputView(
            context,
            inputPlaneCallback,
            getScreenSize(),
            obtainBrailleInputOptions(),
            BrailleUserPreferences.readLayoutMode(context) == TouchDots.TABLETOP
              || (BrailleUserPreferences.readLayoutMode(context) == TouchDots.AUTO_DETECT
                && tableTopMode))
        brailleInputView!!.setAccumulationMode(BrailleUserPreferences.readAccumulateMode(context))
        viewContainer!!.addView(brailleInputView!!, keyboardViewCallback::onViewReady)
      })
  }

  fun refreshInputView() {
    if (brailleInputView != null) {
      brailleInputView!!.setOptions(obtainBrailleInputOptions())
    }
  }

  fun createAndAddStripView(callback: BrailleDisplayImeStripView.CallBack?) {
    if (isStripViewAttached()) {
      keyboardViewCallback.onViewReady()
      return
    }
    runWhenViewContainerIsReady(
      Runnable {
        stripView = BrailleDisplayImeStripView(context)
        stripView!!.setCallBack(callback)
        viewContainer!!.addView(stripView!!, keyboardViewCallback::onViewReady)
      })
  }

  /** Creates {@link TutorialView} and adds it into {@link ViewContainer}. */
  open fun createAndAddTutorialView(
    tutorialState: State,
    tutorialCallback: TutorialCallback,
  ) {
    if (isTutorialViewAttached()) {
      // On foldables, from unfold to fold, sometimes there is no onFinishInputView so view is still
      // attached.
      keyboardViewCallback.onViewReady()
      tutorialView!!.switchState(tutorialState, /* delay= */ 0L)
      return
    }
    runWhenViewContainerIsReady(
      Runnable {
        tutorialView = TutorialView(context, tutorialCallback, getScreenSize())
        tutorialView!!.switchState(tutorialState, /* delay= */ 0L)
        viewContainer!!.addView(tutorialView!!, keyboardViewCallback::onViewReady)
      })
  }

  /** Creates {@link CustomGestureView} and adds it into {@link ViewContainer}. */
  fun createAndAddCustomGestureView(customGestureCallback: CustomGestureCallback) {
    if (isCustomGestureViewAttached()) {
      // On foldables, from unfold to fold, sometimes there is no onFinishInputView so view is still
      // attached.
      keyboardViewCallback.onViewReady()
      return
    }
    runWhenViewContainerIsReady(
      Runnable {
        customGestureView =
          CustomGestureView(context, customGestureCallback, getScreenSize())
        viewContainer!!.addView(customGestureView!!, keyboardViewCallback::onViewReady)
      })
  }

  fun setWindowManager(windowManager: WindowManager) {
    this.windowManager = windowManager
  }

  /** Shows the given {@link ViewAttachedDialog} and attach it on the created view. */
  fun showViewAttachedDialog(viewAttachedDialog: ViewAttachedDialog) {
    if (viewContainer != null) {
      // Invoke show() when view is ready to prevents WindowManager$BadTokenException.
      runWhenViewContainerIsReady { viewAttachedDialog.show(viewContainer) }
    } else if (imeInputView != null) {
      // Invoke show() when view is ready to prevents WindowManager$BadTokenException.
      runWhenImeInputViewIsReady { viewAttachedDialog.show(imeInputView) }
    } else {
      throw IllegalArgumentException("No available view to attach.")
    }
  }

  /** Sets keyboard to table layout. */
  fun setTableMode(enabled: Boolean) {
    if (brailleInputView != null) {
      brailleInputView!!.setTabletopMode(enabled)
    } else if (customGestureView != null) {
      customGestureView!!.setTableTopMode(enabled)
    }
  }

  /** Gets braille input view's dot count. */
  fun getBrailleInputViewDotCount(): Int {
    if (brailleInputView != null) {
      return brailleInputView!!.getBrailleDotCount()
    }
    return -1
  }

  /** Returns whether in two step calibration */
  fun inTwoStepCalibration(): Boolean {
    if (brailleInputView != null) {
      return brailleInputView!!.inTwoStepCalibration()
    }
    return false
  }

  /** Tear down keyboard view. */
  open fun tearDown() {
    BrailleImeLog.d(TAG, "tearDown")
    removeAllViews()
    tearDownInternal()
    if (viewContainer != null && viewContainer!!.childCount == 0) {
      viewContainer = null
    }
    windowManager = null
    keyboardViewCallback.onViewCleared()
    if (displayManager != null) {
      displayManager!!.unregisterDisplayListener(displayListener)
      displayManager = null
    }
  }

  /** Removes all views in keyboard view. */
  @VisibleForTesting
  internal fun removeAllViews() {
    if (viewContainer != null) {
      viewContainer!!.removeAllViews()
    }
    stripView = null
    brailleInputView = null
    tutorialView = null
  }

  /** Calibrates input view. */
  fun calibrateBrailleInputView() {
    if (brailleInputView == null) {
      return
    }
    brailleInputView!!.calibrateByTwoSteps()
  }

  /** Returns ime region size on the screen. */
  fun obtainImeViewRegion(): Optional<Region> {
    var view: View? = imeInputView
    if (isStripViewAttached()) {
      view = stripView
    }
    return Optional.ofNullable<View>(view)
      .map(
        { v: View ->
          val location = IntArray(2)
          v.getLocationInWindow(location)
          Region(
            location[0],
            location[1],
            location[0] + v.width,
            location[1] + v.height)
        })
  }

  /** Returns BrailleDisplayImeStripView. */
  fun getStripView(): BrailleDisplayImeStripView? {
    return stripView
  }

  /** Returns CustomGestureView. */
  fun getCustomGestureView(): CustomGestureView? {
    return customGestureView
  }

  private fun runWhenViewContainerIsReady(runnable: Runnable) {
    if (isViewContainerShown() || BuildVersionUtils.isRobolectric()) {
      runnable.run()
      return
    }
    viewContainer!!
      .viewTreeObserver
      .addOnGlobalLayoutListener(
        object : OnGlobalLayoutListener {
          override fun onGlobalLayout() {
            if (isViewContainerShown()) {
              viewContainer!!.viewTreeObserver.removeOnGlobalLayoutListener(this)
              runnable.run()
            }
          }
        })
  }

  private fun runWhenImeInputViewIsReady(runnable: Runnable) {
    if (isImeInputViewShown() || BuildVersionUtils.isRobolectric()) {
      runnable.run()
      return
    }
    imeInputView!!
      .viewTreeObserver
      .addOnGlobalLayoutListener(
        object : OnGlobalLayoutListener {
          override fun onGlobalLayout() {
            if (isImeInputViewShown()) {
              imeInputView!!.viewTreeObserver.removeOnGlobalLayoutListener(this)
              runnable.run()
            }
          }
        })
  }

  private fun obtainBrailleInputOptions(): BrailleInputOptions {
    return BrailleInputOptions.builder()
      .setReverseDots(BrailleUserPreferences.readReverseDotsMode(context))
      .setBrailleType(BrailleUserPreferences.getCurrentTypingLanguageType(context))
      .setTutorialMode(false)
      .build()
  }

  private fun isViewContainerShown(): Boolean {
    return isViewContainerCreated() && viewContainer!!.isShown()
  }

  private fun isImeInputViewShown(): Boolean {
    return imeInputView != null && imeInputView!!.isShown()
  }

  private fun isInputViewAttached(): Boolean {
    val v = brailleInputView ?: return false
    return viewContainer!!.isAttachedToWindow && viewContainer!!.contains(v)
  }

  private fun isTutorialViewAttached(): Boolean {
    val v = tutorialView ?: return false
    return viewContainer!!.isAttachedToWindow && viewContainer!!.contains(v)
  }

  private fun isStripViewAttached(): Boolean {
    return stripView != null && viewContainer != null && viewContainer!!.isAttachedToWindow
  }

  private fun isCustomGestureViewAttached(): Boolean {
    val v = customGestureView ?: return false
    return viewContainer!!.isAttachedToWindow && viewContainer!!.contains(v)
  }

  /** Creates and returns the ImeInputView. */
  protected abstract fun createImeInputViewInternal(): View

  /** Creates and returns the ViewContainer. */
  protected abstract fun createViewContainerInternal(): ViewContainer

  /** Updates ViewContainer's attributes. For example, height and width. */
  protected abstract fun updateViewContainerInternal()

  /** Gets the screen size of the device. */
  protected abstract fun getScreenSize(): Size

  protected abstract fun tearDownInternal()

  /**
   * Sets alpha of the view container depends on either hide screen or show screen is triggered by
   * Talkback.
   */
  abstract fun setKeyboardViewTransparent(isTransparent: Boolean)

  /** Signals that a orientation change has occurred. */
  fun onOrientationChanged(orientation: Int) {
    BrailleImeLog.d(TAG, "onOrientationChanged")
    if (brailleInputView != null) {
      brailleInputView!!.onOrientationChanged(orientation, getScreenSize())
    }
    if (stripView != null) {
      stripView!!.onOrientationChanged(orientation, getScreenSize())
    }
    if (customGestureView != null) {
      customGestureView!!.onOrientationChanged(orientation, getScreenSize())
    }
    if (tutorialView != null) {
      tutorialView!!.onOrientationChanged(orientation, getScreenSize())
    }
    if (viewContainer != null) {
      updateViewContainerInternal()
    }
  }

  private val displayListener =
    object : DisplayManager.DisplayListener {
      override fun onDisplayAdded(displayId: Int) {}

      override fun onDisplayChanged(displayId: Int) {
        if (viewContainer == null) {
          return
        }
        val rotation = Utils.getDisplayRotationDegrees(context)
        if (rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270) {
          updateViewContainerInternal()
        }
      }

      override fun onDisplayRemoved(displayId: Int) {}
    }

  @VisibleForTesting
  fun testing_getViewContainer(): ViewContainer? {
    return viewContainer
  }

  @VisibleForTesting
  fun testing_getBrailleInputView(): BrailleInputView? {
    return brailleInputView
  }

  @VisibleForTesting
  fun testing_getTutorialView(): TutorialView? {
    return tutorialView
  }

  /**
   * A container for whatever View should be shown in {@link BrailleIme}.
   *
   * <p>This layer must exist because {@link InputMethodService} subclass instances are not informed
   * of all configuration changes, but {@link View} subclass instances are informed of such changes;
   * so {@link BrailleIme} relies on this class to inform it of configuration changes.
   */
  class ViewContainer(context: Context) : FrameLayout(context) {

    /** A callback for notify clients view status changed. */
    fun interface ViewStatusCallback {
      fun onViewAdded()
    }

    override fun addView(child: View) {
      removeAllViews()
      super.addView(child)
    }

    fun addView(child: View, viewStatusCallback: ViewStatusCallback) {
      if (BuildVersionUtils.isRobolectric()) {
        viewStatusCallback.onViewAdded()
      } else {
        viewTreeObserver
          .addOnGlobalLayoutListener(
            object : OnGlobalLayoutListener {
              override fun onGlobalLayout() {
                viewTreeObserver.removeOnGlobalLayoutListener(this)
                viewStatusCallback.onViewAdded()
              }
            })
      }
      addView(child)
    }

    internal fun contains(view: View): Boolean {
      for (i in 0 until childCount) {
        if (getChildAt(i).equals(view)) {
          return true
        }
      }
      return false
    }
  }
}
