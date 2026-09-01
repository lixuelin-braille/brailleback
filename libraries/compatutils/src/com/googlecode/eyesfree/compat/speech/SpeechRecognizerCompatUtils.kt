/*
 * Copyright (C) Google Inc.
 * Licensed under the Apache License, Version 2.0
 */

package com.googlecode.eyesfree.compat.speech

import android.content.Context
import com.googlecode.eyesfree.compat.CompatUtils
import java.lang.reflect.Method

object SpeechRecognizerCompatUtils {
    private val CLASS_SpeechRecognizer: Class<*> =
        CompatUtils.getClass("android.speech.SpeechRecognizer")!!
    private val METHOD_isRecognitionAvailable: Method? =
        CompatUtils.getMethod(CLASS_SpeechRecognizer, "isRecognitionAvailable", Context::class.java)

    /**
     * Checks whether a speech recognition service is available on the system.
     * If this method returns `false`,
     * [android.speech.SpeechRecognizer.createSpeechRecognizer] will fail.
     *
     * @param context with which `SpeechRecognizer` will be created
     * @return `true` if recognition is available, `false` otherwise
     */
    @JvmStatic
    fun isRecognitionAvailable(context: Context): Boolean {
        return CompatUtils.invoke(null, false, METHOD_isRecognitionAvailable, context) as Boolean
    }
}
