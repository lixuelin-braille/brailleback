/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 */

package com.googlecode.eyesfree.brailleback

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.googlecode.eyesfree.braille.display.BrailleInputEvent

/**
 * Helps coordinating between the accessibility service and input method. Among
 * other things, this class pops up the IMESetupWizardActivity if the user tries
 * to use the braille keyboard when the braille IME is not the current one.
 */
class IMEHelper(private val context: Context) {

    /**
     * Listens for text input keystrokes.  If the user tries to enter text and
     * the Braille IME is not the default on the system, takes the user through
     * the [IMESetupWizardActivity].
     */
    fun onInputEvent(event: BrailleInputEvent): Boolean {
        val cmd = event.getCommand()
        if (cmd == BrailleInputEvent.CMD_BRAILLE_KEY
            || cmd == BrailleInputEvent.CMD_KEY_DEL
            || cmd == BrailleInputEvent.CMD_KEY_FORWARD_DEL) {
            if (!isInputMethodDefault(context, BrailleIME::class.java)) {
                tryIMESwitch()
                return true
            }
        }
        return false
    }

    private fun tryIMESwitch() {
        val intent = Intent(context, IMESetupWizardActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    companion object {
        /**
         * Determines from system settings if `imeClass` is an enabled input
         * method.
         */
        @JvmStatic
        fun isInputMethodEnabled(contextArg: Context, imeClass: Class<*>): Boolean {
            val imeComponentName = ComponentName(contextArg, imeClass)
            val enabledIMEIds = Settings.Secure.getString(
                contextArg.contentResolver, Settings.Secure.ENABLED_INPUT_METHODS)
            if (enabledIMEIds == null) {
                return false
            }

            for (enabledIMEId in enabledIMEIds.split(":")) {
                if (imeComponentName == ComponentName.unflattenFromString(enabledIMEId)) {
                    return true
                }
            }
            return false
        }

        /**
         * Determines, from system settings, if `imeClass` is the default input
         * method.
         */
        @JvmStatic
        fun isInputMethodDefault(contextArg: Context, imeClass: Class<*>): Boolean {
            val imeComponentName = ComponentName(contextArg, imeClass)
            val defaultIMEId = Settings.Secure.getString(
                contextArg.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)

            return defaultIMEId != null
                && imeComponentName == ComponentName.unflattenFromString(defaultIMEId)
        }
    }
}
