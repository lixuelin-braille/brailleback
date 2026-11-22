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

package com.google.android.accessibility.brailleime.dialog

import android.app.Dialog
import android.content.Context
import androidx.appcompat.app.AlertDialog
import com.google.android.accessibility.brailleime.R
import com.google.android.accessibility.utils.KeyboardUtils
import com.google.android.accessibility.utils.material.MaterialComponentUtils

/** An error dialog which shows when the device supports too few touch points. */
class TooFewTouchPointsDialog(
  context: Context,
  private val callback: Callback,
) : ViewAttachedDialog() {

  /** A callback to notify {@link BrailleIme}. */
  interface Callback {
    fun onSwitchToNextIme()
  }

  private var dialog: Dialog? = null
  private val context: Context = context

  override fun makeDialog(): Dialog {
    val dialogBuilder: AlertDialog.Builder = MaterialComponentUtils.alertDialogBuilder(context)
    dialogBuilder
        .setTitle(context.getString(R.string.not_enough_touch_points_dialog_title))
        .setMessage(context.getString(R.string.not_enough_touch_points_dialog_message))
    if (KeyboardUtils.areMultipleImesEnabled(context)) {
      dialogBuilder.setPositiveButton(
          context.getString(R.string.next_keyboard),
          { _, _ -> callback.onSwitchToNextIme() })
    } else {
      dialogBuilder.setPositiveButton(
          context.getString(android.R.string.ok), { _, _ -> callback.onSwitchToNextIme() })
    }
    dialogBuilder.setOnCancelListener { _ -> callback.onSwitchToNextIme() }
    dialog = dialogBuilder.create()
    dialog!!.setCanceledOnTouchOutside(false)
    return dialog!!
  }
}
