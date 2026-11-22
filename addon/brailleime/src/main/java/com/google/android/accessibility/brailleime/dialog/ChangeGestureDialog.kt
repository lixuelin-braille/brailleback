package com.google.android.accessibility.brailleime.dialog

import android.app.Dialog
import android.content.Context
import android.text.SpannableString
import android.view.LayoutInflater
import android.view.View
import android.widget.CheckBox
import android.widget.TextView
import com.google.android.accessibility.brailleime.R
import com.google.android.accessibility.brailleime.Utils
import com.google.android.accessibility.utils.material.A11yAlertDialogWrapper

/** A dialog to change the gesture. */
class ChangeGestureDialog(
  context: Context,
  private val callback: ButtonClickListener,
) : CommonDialog() {

  /** A callback to notify its client. */
  interface ButtonClickListener {
    fun onContinue()

    fun onNeverShown()
  }

  private var dialog: Dialog? = null
  private val context: Context = context

  override fun makeDialog(): Dialog {
    var builder: A11yAlertDialogWrapper.Builder =
        A11yAlertDialogWrapper.materialDialogBuilder(context)
    val view: View =
        LayoutInflater.from(context)
            .inflate(R.layout.braille_common_dialog_with_checkbox, /* root= */ null)
    val dontShowAgainCheckBox: CheckBox = view.findViewById(R.id.check_box)
    dontShowAgainCheckBox.setText(R.string.change_gestures_dialog_dont_show_checkbox)
    val messageTextView: TextView = view.findViewById(R.id.text_view)
    val spannableMessageString =
        SpannableString.valueOf(context.getString(R.string.change_gestures_dialog_message))
    val subString = context.getString(R.string.change_gestures_dialog_message_link)
    Utils.formatSubstringAsUrl(spannableMessageString, subString, BRAILLE_KEYBOARD_HELP_URI)
    messageTextView.setText(spannableMessageString)
    builder =
        builder
            .setTitle(R.string.change_gestures_dialog_message_title)
            .setView(view)
            .setPositiveButton(
                R.string.change_gestures_dialog_positive_button,
                { _, _ ->
                  dismiss()
                  callback.onContinue()
                  if (dontShowAgainCheckBox.isChecked) {
                    callback.onNeverShown()
                  }
                })
            .setNegativeButton(android.R.string.cancel, { _, _ -> dismiss() })
    dialog = builder.create().getDialog()
    dialog!!.setCanceledOnTouchOutside(false)
    return dialog!!
  }

  companion object {
    private const val BRAILLE_KEYBOARD_HELP_URI =
        "https://support.google.com/accessibility/android/answer/9728765"
  }
}
