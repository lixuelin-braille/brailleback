package com.google.android.accessibility.brailleime.dialog

import android.app.Dialog
import android.content.Context
import android.view.ContextThemeWrapper
import com.google.android.accessibility.brailleime.R
import com.google.android.accessibility.utils.material.A11yAlertDialogWrapper

/** Custom braille keyboard when TalkBack is off dialog. */
class CustomGestureTalkBackOffDialog(
  context: Context,
  private val callback: ButtonClickListener,
) : CommonDialog() {

  /** A callback to notify its client. */
  interface ButtonClickListener {
    fun onLaunchSettings()
  }

  private val context: Context = context

  override fun makeDialog(): Dialog {
    val dialogBuilder: A11yAlertDialogWrapper.Builder =
        A11yAlertDialogWrapper.materialDialogBuilder(
                ContextThemeWrapper(context, R.style.A11yAlertDialogCustomViewTheme))
            .setTitle(R.string.talkback_off_custom_gestures_dialog_title)
            .setMessage(R.string.talkback_off_custom_gestures_dialog_message)
            .setPositiveButton(
                R.string.talkback_off_custom_gestures_dialog_positive_button,
                { _, _ -> callback.onLaunchSettings() })
            .setNegativeButton(android.R.string.cancel, null)
    return dialogBuilder.create().getDialog()
  }
}
