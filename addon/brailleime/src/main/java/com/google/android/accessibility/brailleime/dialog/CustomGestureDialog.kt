package com.google.android.accessibility.brailleime.dialog

import android.view.View.GONE
import android.view.View.VISIBLE

import android.app.Dialog
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.TextView
import com.google.android.accessibility.braille.common.BrailleImeAction
import com.google.android.accessibility.brailleime.BrailleImeGestureAction
import com.google.android.accessibility.brailleime.R
import com.google.android.accessibility.brailleime.Utils
import com.google.android.accessibility.utils.material.A11yAlertDialogWrapper

/** A dialog for showing custom gesture commands. */
class CustomGestureDialog(
  context: Context,
  private val callback: ButtonClickListener,
) : CommonDialog() {

  /** A callback to notify its client. */
  interface ButtonClickListener {
    fun onChangeGesture()

    fun onResetToDefault()

    fun onRemove()
  }

  private val context: Context = context
  private var action: BrailleImeAction? = null

  /** Sets the {@link BrailleImeAction}. */
  fun setAction(action: BrailleImeAction) {
    this.action = action
  }

  override fun makeDialog(): Dialog {
    val dialogView: View =
        LayoutInflater.from(context).inflate(R.layout.custom_gesture_dialog, /* root= */ null)
    val assignedAction: TextView = dialogView.findViewById(R.id.assigned_action)
    assignedAction.setText(
        Utils.getCombinedGestureDescription(
            context, BrailleImeGestureAction.getGesture(context, action)))
    val shareText: TextView = dialogView.findViewById(R.id.share_text)
    val sameAction: List<BrailleImeAction> = BrailleImeGestureAction.getActionWithSameRoot(action)
    if (sameAction.isNotEmpty()) {
      shareText.setVisibility(VISIBLE)
      shareText.setText(
          context.getString(
              R.string.custom_gestures_dialog_share_description,
              sameAction.get(0).getDescriptionRes(context.resources)))
    } else {
      shareText.setVisibility(GONE)
    }
    val changeGesture: Button = dialogView.findViewById(R.id.custom_gesture_button_change_gesture)
    changeGesture.setOnClickListener(
        { v ->
          dismiss()
          callback.onChangeGesture()
        })
    val restToDefault: Button =
        dialogView.findViewById(R.id.custom_gestures_button_reset_to_default)
    restToDefault.setOnClickListener(
        { v ->
          dismiss()
          callback.onResetToDefault()
        })
    val remove: Button = dialogView.findViewById(R.id.custom_gesture_button_remove)
    remove.setOnClickListener(
        { v ->
          dismiss()
          callback.onRemove()
        })
    val cancel: Button = dialogView.findViewById(R.id.custom_gesture_button_cancel)
    cancel.setOnClickListener { v -> dismiss() }
    val dialogBuilder: A11yAlertDialogWrapper.Builder =
        A11yAlertDialogWrapper.materialDialogBuilder(context)
            .setTitle(action!!.getDescriptionRes(context.resources))
            .setView(dialogView)
    return dialogBuilder.create().getDialog()
  }
}
