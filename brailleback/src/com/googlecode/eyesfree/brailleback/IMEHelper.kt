/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 */

package com.googlecode.eyesfree.brailleback

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.inputmethod.InputMethodManager
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
        val cmd = event.command
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
        private fun inputMethodManager(contextArg: Context): InputMethodManager =
            contextArg.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager

        /**
         * Determines from system settings if `imeClass` is an enabled input
         * method.
         *
         * 注意：不能用 Settings.Secure.ENABLED_INPUT_METHODS——targetSdk 34+
         * 在 Android 14+ 上读取该键直接抛 SecurityException（向导启动即崩），
         * 必须走公开 API InputMethodManager.enabledInputMethodList。
         */
        @JvmStatic
        fun isInputMethodEnabled(contextArg: Context, imeClass: Class<*>): Boolean {
            val imeComponentName = ComponentName(contextArg, imeClass)
            return inputMethodManager(contextArg).enabledInputMethodList.any {
                imeComponentName == ComponentName.unflattenFromString(it.id)
            }
        }

        /**
         * Determines, from system settings, if `imeClass` is the default input
         * method（同理走公开 API，见 [isInputMethodEnabled] 注释）。
         */
        @JvmStatic
        fun isInputMethodDefault(contextArg: Context, imeClass: Class<*>): Boolean {
            val imeComponentName = ComponentName(contextArg, imeClass)
            val current = inputMethodManager(contextArg).currentInputMethodInfo
                ?: return false
            return imeComponentName == current.component
        }
    }
}
