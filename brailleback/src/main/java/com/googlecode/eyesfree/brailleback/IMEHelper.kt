/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 */

package com.googlecode.eyesfree.brailleback

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
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
        private fun inputMethodManager(contextArg: Context): InputMethodManager? =
            contextArg.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager

        /**
         * Returns the currently selected input method.
         *
         * `InputMethodManager.currentInputMethodInfo` was added in API 34.
         * Calling it directly on older releases throws NoSuchMethodError, even
         * though the code is targeting current Android versions. The secure
         * setting remains readable below API 34 (the Android 14 restriction
         * only applies when the new API is available).
         */
        private fun currentInputMethod(contextArg: Context): ComponentName? {
            val manager = inputMethodManager(contextArg) ?: return null
            return try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    manager.currentInputMethodInfo?.component
                } else {
                    @Suppress("DEPRECATION")
                    val id = Settings.Secure.getString(
                        contextArg.contentResolver,
                        Settings.Secure.DEFAULT_INPUT_METHOD
                    )
                    id?.let(ComponentName::unflattenFromString)
                }
            } catch (ex: SecurityException) {
                null
            }
        }

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
            val manager = inputMethodManager(contextArg) ?: return false
            return try {
                manager.enabledInputMethodList.orEmpty().any {
                    imeComponentName == ComponentName.unflattenFromString(it.id)
                }
            } catch (ex: SecurityException) {
                false
            }
        }

        /**
         * Determines, from system settings, if `imeClass` is the default input
         * method（同理走公开 API，见 [isInputMethodEnabled] 注释）。
         */
        @JvmStatic
        fun isInputMethodDefault(contextArg: Context, imeClass: Class<*>): Boolean {
            val imeComponentName = ComponentName(contextArg, imeClass)
            return imeComponentName == currentInputMethod(contextArg)
        }
    }
}
