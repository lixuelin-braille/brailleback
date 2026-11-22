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
import android.util.Size
import android.view.View
import android.widget.FrameLayout
import com.google.android.accessibility.brailleime.Utils

/** A sub-class of {@link KeyboardView} which uses the standard Ime input view. */
class StandardKeyboardView(
  context: Context,
  keyboardViewCallback: KeyboardViewCallback,
  private val fullScreen: Boolean,
) : KeyboardView(context, keyboardViewCallback) {

  override fun createImeInputViewInternal(): View {
    if (imeInputView == null) {
      imeInputView = ViewContainer(context)
    }
    return imeInputView!!
  }

  override fun createViewContainerInternal(): ViewContainer {
    if (viewContainer == null) {
      if (imeInputView == null) {
        imeInputView = createImeInputViewInternal()
      }
      viewContainer = imeInputView as ViewContainer
    }
    return viewContainer!!
  }

  override fun updateViewContainerInternal() {
    if (fullScreen) {
      val viewSize = getScreenSize()
      val layoutParams =
        FrameLayout.LayoutParams(viewSize.width, viewSize.height)
      viewContainer!!.layoutParams = layoutParams
      viewContainer!!.post { keyboardViewCallback.onViewUpdated() }
    }
  }

  override fun setKeyboardViewTransparent(isTransparent: Boolean) {
    // Empty here.
  }

  override fun getScreenSize(): Size {
    return Utils.getVisibleDisplaySizeInPixels(viewContainer!!)
  }

  override fun tearDownInternal() {
    // Empty here.
  }
}
