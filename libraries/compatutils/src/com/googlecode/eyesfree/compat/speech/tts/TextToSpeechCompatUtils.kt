/*
 * Copyright (C) 2011 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.googlecode.eyesfree.compat.speech.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import com.googlecode.eyesfree.compat.CompatUtils
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.util.Collections
import java.util.Locale

object TextToSpeechCompatUtils {
    private val CONSTRUCTOR_LLS: Constructor<*>? = CompatUtils.getConstructor(
        TextToSpeech::class.java, Context::class.java,
        TextToSpeech.OnInitListener::class.java, String::class.java
    )
    private val METHOD_setEngineByPackageName: Method? = CompatUtils.getMethod(
        TextToSpeech::class.java, "setEngineByPackageName", String::class.java
    )
    private val METHOD_getFeatures: Method? = CompatUtils.getMethod(
        TextToSpeech::class.java, "getFeatures", Locale::class.java
    )
    private val METHOD_getCurrentEngine: Method? = CompatUtils.getMethod(
        TextToSpeech::class.java, "getCurrentEngine"
    )

    @Suppress("UNCHECKED_CAST")
    @JvmStatic
    fun getFeatures(tts: TextToSpeech, locale: Locale): Set<String> {
        val result = CompatUtils.invoke(tts, null, METHOD_getFeatures, locale) ?: return Collections.emptySet()
        return result as Set<String>
    }

    @JvmStatic
    fun newTextToSpeech(
        context: Context, listener: TextToSpeech.OnInitListener, engine: String
    ): TextToSpeech {
        val result = CompatUtils.newInstance(CONSTRUCTOR_LLS, context, listener, engine) as TextToSpeech?
        return result ?: TextToSpeech(context, listener)
    }

    @JvmStatic
    fun setEngineByPackageName(receiver: TextToSpeech, enginePackageName: String): Int =
        (CompatUtils.invoke(
            receiver, TextToSpeech.ERROR, METHOD_setEngineByPackageName, enginePackageName
        ) as Int)

    @JvmStatic
    fun getCurrentEngine(receiver: TextToSpeech): String =
        (CompatUtils.invoke(receiver, null, METHOD_getCurrentEngine) as String)

    object EngineCompatUtils {
        const val INTENT_ACTION_TTS_SERVICE: String = "android.intent.action.TTS_SERVICE"
        const val KEY_PARAM_VOLUME: String = "volume"
        const val KEY_PARAM_PAN: String = "pan"
    }
}
