package com.google.android.accessibility.brailleime

import android.content.Context
import android.content.Intent
import android.os.Bundle
import com.google.android.accessibility.braille.common.lib.ActionReceiver

/** A receiver that receives the ACTION_CLOSE_SYSTEM_DIALOGS intent to close the system dialogs. */
class CloseSystemDialogReceiver(
  context: Context,
  callback: Callback,
) : ActionReceiver<CloseSystemDialogReceiver, CloseSystemDialogReceiver.Callback>(context, callback) {

  /** The callback associated with the actions of this receiver. */
  interface Callback {
    fun onSystemDialogClosed()
  }

  override fun onReceive(callback: Callback, action: String?, extras: Bundle?) {
    if (action == Intent.ACTION_CLOSE_SYSTEM_DIALOGS) {
      val reason = extras?.getString(SYSTEM_DIALOG_REASON_KEY)
      if (reason != null) {
        if (reason == SYSTEM_DIALOG_REASON_HOME_KEY ||
            reason == SYSTEM_DIALOG_REASON_RECENT_APPS ||
            reason == SYSTEM_DIALOG_REASON_VOICE_INTERACTION) {
          callback.onSystemDialogClosed()
        }
      }
    }
  }

  override fun getActionsList(): Array<String> {
    return arrayOf(Intent.ACTION_CLOSE_SYSTEM_DIALOGS)
  }

  companion object {
    private const val SYSTEM_DIALOG_REASON_KEY = "reason"
    private const val SYSTEM_DIALOG_REASON_RECENT_APPS = "recentapps"
    private const val SYSTEM_DIALOG_REASON_HOME_KEY = "homekey"
    private const val SYSTEM_DIALOG_REASON_VOICE_INTERACTION = "voiceinteraction"
  }
}
