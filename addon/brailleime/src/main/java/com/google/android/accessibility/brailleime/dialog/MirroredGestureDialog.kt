package com.google.android.accessibility.brailleime.dialog

import android.app.Dialog
import android.content.Context
import android.view.ContextThemeWrapper
import com.google.android.accessibility.braille.common.BrailleImeAction
import com.google.android.accessibility.brailleime.BrailleImeGestureAction
import com.google.android.accessibility.brailleime.R
import com.google.android.accessibility.brailleime.input.Gesture
import com.google.android.accessibility.utils.material.A11yAlertDialogWrapper
import java.util.Optional

/** A dialog to notify the user that the gesture is mirrored. */
class MirroredGestureDialog(
  context: Context,
  private val callback: ButtonClickListener,
) : CommonDialog() {
  /** A callback to notify its client. */
  interface ButtonClickListener {
    fun onMirrored(gesture: Gesture?)
  }

  private var dialog: Dialog? = null
  private val context: Context = context
  private var gesture: Gesture? = null
  private var action: BrailleImeAction? = null

  /** Sets the gesture and action. */
  fun setGestureAction(action: BrailleImeAction, gesture: Gesture) {
    this.gesture = gesture
    this.action = action
  }

  override fun makeDialog(): Dialog {
    val dialogBuilder: A11yAlertDialogWrapper.Builder =
        A11yAlertDialogWrapper.materialDialogBuilder(
                ContextThemeWrapper(context, R.style.A11yAlertDialogCustomViewTheme))
            .setTitle(R.string.mirrored_gesture_dialog_title)
            .setMessage(getMessage())
            .setPositiveButton(
                R.string.mirrored_gesture_dialog_positive_button,
                { _, _ -> callback.onMirrored(gesture) })
            .setNegativeButton(
                if (hasConflict()) R.string.mirrored_gesture_dialog_conflict_negative_button
                else R.string.mirrored_gesture_dialog_negative_button,
                { _, _ -> dismiss() })
    dialog = dialogBuilder.create().getDialog()
    dialog!!.setCanceledOnTouchOutside(false)
    return dialog!!
  }

  private fun getMessage(): String {
    val mirroredGestureStr = gesture!!.mirrorDots()!!.getDescription(context.resources)
    val actionStr = action!!.getDescriptionRes(context.resources)
    var message =
        context.getString(R.string.mirrored_gesture_dialog_message, actionStr, mirroredGestureStr)
    if (hasConflict()) {
      val optionalBrailleImeAction: Optional<BrailleImeAction> =
          BrailleImeGestureAction.getAction(context, gesture!!.mirrorDots()).stream()
              .filter { brailleImeAction -> action != brailleImeAction }
              .findFirst()
      val oldAction =
          optionalBrailleImeAction
              .map { brailleImeAction -> brailleImeAction.getDescriptionRes(context.resources) }
              .orElse("")
      message =
          context.getString(
              R.string.mirrored_gesture_dialog_conflict_message,
              mirroredGestureStr,
              actionStr,
              oldAction)
    }
    return message
  }

  private fun hasConflict(): Boolean =
      gesture != gesture!!.mirrorDots() &&
          BrailleImeGestureAction.getAction(context, gesture!!.mirrorDots()).isNotEmpty()
}
