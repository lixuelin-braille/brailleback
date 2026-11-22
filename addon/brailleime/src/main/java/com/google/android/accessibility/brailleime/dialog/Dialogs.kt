/*
 * Copyright 2019 Google Inc.
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

package com.google.android.accessibility.brailleime.dialog

import android.app.Dialog
import android.content.Context
import android.view.ContextThemeWrapper
import android.view.View
import android.view.WindowManager
import android.view.WindowManager.BadTokenException
import android.view.WindowManager.LayoutParams
import com.google.android.accessibility.brailleime.BrailleImeLog
import com.google.android.accessibility.brailleime.R

/** Helps configure dialogs. */
class Dialogs private constructor() {
  companion object {
    private const val TAG = "Dialogs"

    /** Returns {@link Context} which adopts our desired theme. */
    @JvmStatic
    fun getDialogContext(context: Context): Context =
        ContextThemeWrapper(context, R.style.DialogTheme)

    /** Configures the {@link WindowManager.LayoutParams} for our dialogs. */
    @JvmStatic
    fun configureAndShowAttachedDialog(dialog: Dialog?, windowTokenProvidingView: View?) {
      val layoutParams: LayoutParams = dialog!!.window!!.attributes
      layoutParams.type = LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG
      layoutParams.token = windowTokenProvidingView!!.windowToken
      dialog!!.window!!.attributes = layoutParams
      try {
        dialog!!.show()
      } catch (e: BadTokenException) {
        BrailleImeLog.e(TAG, "Dialog not shown. Ignoring thrown BadTokenException.", e)
      }
    }
  }
}
