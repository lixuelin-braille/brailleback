package com.google.android.accessibility.brailleime.dialog

import android.app.Dialog

/** Common dialog for Braille IME. */
abstract class CommonDialog {
  private var dialog: Dialog? = null

  /** Shows the dialog attach on the specific View. */
  fun show() {
    dialog = makeDialog()
    dialog!!.show()
  }

  /** Gets the dialog is showing or not. */
  fun isShowing(): Boolean = dialog != null && dialog!!.isShowing()

  /** Dismisses the dialog. */
  fun dismiss() {
    dialog?.dismiss()
  }

  protected abstract fun makeDialog(): Dialog
}
