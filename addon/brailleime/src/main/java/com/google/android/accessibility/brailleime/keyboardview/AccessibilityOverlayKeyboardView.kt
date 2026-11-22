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

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Build.VERSION_CODES
import android.util.Size
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.WindowManager.LayoutParams
import android.widget.FrameLayout
import com.google.android.accessibility.braille.common.lib.ScreenOnOffReceiver
import com.google.android.accessibility.brailleime.BrailleImeLog
import com.google.android.accessibility.brailleime.CloseSystemDialogReceiver
import com.google.android.accessibility.brailleime.Utils
import com.google.android.accessibility.brailleime.input.BrailleInputView
import com.google.android.accessibility.brailleime.tutorial.TutorialView.TutorialCallback
import com.google.android.accessibility.brailleime.tutorial.TutorialView.TutorialState.State

/**
 * A sub-class of {@link KeyboardView} which uses an Accessibility Overlay for taking touch input.
 */
class AccessibilityOverlayKeyboardView(
  context: Context,
  keyboardViewCallback: KeyboardViewCallback,
) : KeyboardView(context, keyboardViewCallback) {

  private val screenOnOffReceiver =
    ScreenOnOffReceiver(
      context,
      object : ScreenOnOffReceiver.Callback {
        override fun onScreenOn() {
          BrailleImeLog.d(TAG, "onScreenOn")
        }

        override fun onScreenOff() {
          BrailleImeLog.d(TAG, "onScreenOff")
          tearDown()
        }
      })

  private val closeSystemDialogReceiver =
    CloseSystemDialogReceiver(
      context,
      object : CloseSystemDialogReceiver.Callback {
        override fun onSystemDialogClosed() {
          BrailleImeLog.d(TAG, "onSystemDialogClosed")
          tearDown()
        }
      })

  override fun createAndAddInputView(
    inputPlaneCallback: BrailleInputView.Callback,
    tableTopMode: Boolean,
  ) {
    super.createAndAddInputView(inputPlaneCallback, tableTopMode)
    registerListener()
  }

  override fun createAndAddTutorialView(tutorialState: State, tutorialCallback: TutorialCallback) {
    super.createAndAddTutorialView(tutorialState, tutorialCallback)
    registerListener()
  }

  override fun createImeInputViewInternal(): View {
    if (imeInputView == null) {
      if (Build.VERSION.SDK_INT == Build.VERSION_CODES.P) {
        val fullScreenView = View(context)
        val layoutParams =
          LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
        fullScreenView.layoutParams = layoutParams
        imeInputView = fullScreenView
      } else {
        imeInputView = FrameLayout(context)
      }
      // Ensure non-zero height to make sure framework considers the inputView significant enough to
      // include in the info sent to TalkBack.
      imeInputView!!.setMinimumHeight(1)
    }
    return imeInputView!!
  }

  override fun createViewContainerInternal(): ViewContainer {
    if (viewContainer == null) {
      viewContainer = ViewContainer(context)
    }
    if (keyboardViewCallback.isHideScreenMode()) {
      viewContainer!!.alpha = SCREEN_HIDE_ALPHA
    }

    windowManager!!.addView(viewContainer!!, getWindowsLayoutParams())
    return viewContainer!!
  }

  override fun updateViewContainerInternal() {
    windowManager!!.updateViewLayout(viewContainer!!, getWindowsLayoutParams())
  }

  override fun getScreenSize(): Size {
    return Utils.getDisplaySizeInPixels(context)
  }

  override fun tearDownInternal() {
    if (windowManager != null) {
      windowManager!!.removeViewImmediate(viewContainer!!)
    }
  }

  override fun setKeyboardViewTransparent(isTransparent: Boolean) {
    if (viewContainer != null) {
      val alpha = if (isTransparent) SCREEN_HIDE_ALPHA else SCREEN_SHOW_ALPHA
      viewContainer!!.alpha = alpha
    }
  }

  private fun registerListener() {
    screenOnOffReceiver.registerSelf()
    closeSystemDialogReceiver.registerSelf()
  }

  override fun tearDown() {
    super.tearDown()
    screenOnOffReceiver.unregisterSelf()
    closeSystemDialogReceiver.unregisterSelf()
  }

  private fun getWindowsLayoutParams(): WindowManager.LayoutParams {
    val params = WindowManager.LayoutParams()
    params.format = PixelFormat.TRANSLUCENT
    params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
    params.flags = params.flags or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
    params.flags = params.flags or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
    // A workaround for b/258382964, which only occurs in T.
    if (Build.VERSION.SDK_INT == VERSION_CODES.TIRAMISU) {
      params.flags = params.flags or WindowManager.LayoutParams.FLAG_SPLIT_TOUCH
    }
    params.type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
    params.gravity = Gravity.TOP
    params.gravity = params.gravity or
      (if (Utils.isNavigationBarLeftLocated(context)) Gravity.RIGHT else Gravity.LEFT)
    val size: Size = Utils.getDisplaySizeInPixels(context)
    params.height = size.height
    params.width = size.width

    return params
  }

  companion object {
    private const val TAG = "AccessibilityOverlayKeyboardView"

    @JvmField
    protected val SCREEN_HIDE_ALPHA: Float = 0.001f

    @JvmField
    protected val SCREEN_SHOW_ALPHA: Float = 1f
  }
}
