/*
 * Licensed under the Apache License, Version 2.0 (the "License").
 */

package com.googlecode.eyesfree.compat.provider

import android.content.Context
import android.provider.Settings

object SettingsCompatUtils {
    object SecureCompatUtils {
        /**
         * Whether to speak passwords while in accessibility mode.
         */
        const val ACCESSIBILITY_SPEAK_PASSWORD = "speak_password"

        /**
         * Stores the default TTS locales on a per engine basis.
         */
        const val TTS_DEFAULT_LOCALE = "tts_default_locale"

        /**
         * Returns whether to speak passwords while in accessibility mode.
         */
        @JvmStatic
        fun shouldSpeakPasswords(context: Context): Boolean {
            return Settings.Secure.getInt(
                context.contentResolver, ACCESSIBILITY_SPEAK_PASSWORD, 0
            ) == 1
        }
    }
}
