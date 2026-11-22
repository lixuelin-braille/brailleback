package com.google.android.accessibility.brailleime.dialog

import android.app.Dialog
import android.content.Context
import android.view.ContextThemeWrapper
import com.google.android.accessibility.brailleime.R
import com.google.android.accessibility.utils.material.A11yAlertDialogWrapper

/** A dialog to reset all gestures. */
class ResetAllGestureDialog(
  context: Context,
  private val callback: ButtonClickListener,
) : CommonDialog() {
  /** A callback to notify its client. */
  interface ButtonClickListener {
    fun onReset()
  }

  private var dialog: Dialog? = null
  private val context: Context = context

  override fun makeDialog(): Dialog {
    val dialogBuilder: A11yAlertDialogWrapper.Builder =
        A11yAlertDialogWrapper.materialDialogBuilder(
                ContextThemeWrapper(context, R.style.A11yAlertDialogCustomViewTheme))
            .setTitle(R.string.reset_gestures_dialog_title)
            .setMessage(R.string.reset_gestures_dialog_message)
            .setPositiveButton(
                R.string.reset_gestures_dialog_positive_button,
                { _, _ -> callback.onReset() })
            .setNegativeButton(android.R.string.cancel, null)
    dialog = dialogBuilder.create().getDialog()
    dialog!!.setCanceledOnTouchOutside(false)
    return dialog!!
  }
}
