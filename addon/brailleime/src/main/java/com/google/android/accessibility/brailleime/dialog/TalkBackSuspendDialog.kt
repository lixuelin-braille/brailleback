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

/**
 * An error dialog which shows when the keyboard is raised while TalkBack is off.
 */
class TalkBackSuspendDialog(
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
    // We will invoke switch to next keyboard in all of the ways leave the dialog even if user
    // click the "OK" button. In some applications, Chrome for example, the frameworks will focus
    // to editor again after we just close the dialog. It makes the dialog cannot dismiss forever.
    // It will need complicated logic to solve this problem, so we use this to simplify the code.
    val dialogBuilder: AlertDialog.Builder =
        MaterialComponentUtils.alertDialogBuilder(context)
            .setTitle(R.string.talkback_suspend_dialog_title)
            .setMessage(R.string.talkback_off_or_suspend_dialog_message)
            .setPositiveButton(
                if (KeyboardUtils.areMultipleImesEnabled(context)) R.string.next_keyboard
                else android.R.string.ok,
                { _, _ -> callback.onSwitchToNextIme() })
            .setOnCancelListener { _ -> callback.onSwitchToNextIme() }
    dialog = dialogBuilder.create()
    dialog!!.setCanceledOnTouchOutside(false)
    return dialog!!
  }
}
