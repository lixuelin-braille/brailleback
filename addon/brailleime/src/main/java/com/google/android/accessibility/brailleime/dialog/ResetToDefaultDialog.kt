package com.google.android.accessibility.brailleime.dialog

import android.app.Dialog
import android.content.Context
import android.view.ContextThemeWrapper
import com.google.android.accessibility.braille.common.BrailleImeAction
import com.google.android.accessibility.brailleime.BrailleImeGestureAction
import com.google.android.accessibility.brailleime.R
import com.google.android.accessibility.brailleime.Utils
import com.google.android.accessibility.utils.material.A11yAlertDialogWrapper

/** A dialog to reset an action to its default gesture. */
class ResetToDefaultDialog(
  context: Context,
  private val callback: ButtonClickListener,
) : CommonDialog() {
  private val context: Context = context
  private var action: BrailleImeAction? = null

  /** A callback to notify its client. */
  interface ButtonClickListener {
    fun onReset()
  }

  /** Sets the {@link BrailleImeAction}. */
  fun setAction(action: BrailleImeAction) {
    this.action = action
  }

  override fun makeDialog(): Dialog {
    val dialogBuilder: A11yAlertDialogWrapper.Builder =
        A11yAlertDialogWrapper.materialDialogBuilder(
                ContextThemeWrapper(context, R.style.A11yAlertDialogCustomViewTheme))
            .setTitle(R.string.reset_to_default_dialog_title)
            .setMessage(
                context.getString(
                    R.string.reset_to_default_dialog_message,
                    Utils.getCombinedGestureDescription(
                        context, BrailleImeGestureAction.getDefaultGesture(action))))
            .setPositiveButton(
                R.string.reset_to_default_dialog_positive_button,
                { _, _ -> callback.onReset() })
            .setNegativeButton(android.R.string.cancel, null)
    return dialogBuilder.create().getDialog()
  }
}
