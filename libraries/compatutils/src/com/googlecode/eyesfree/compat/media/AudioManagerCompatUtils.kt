/*
 * Copyright (C) 2012 Google Inc.
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

package com.googlecode.eyesfree.compat.media

import android.media.AudioManager
import com.googlecode.eyesfree.compat.CompatUtils
import java.lang.reflect.Method

object AudioManagerCompatUtils {
    private val CLASS_OnAudioFocusChangeListener: Class<*> =
        CompatUtils.getClass("android.media.AudioManager\$OnAudioFocusChangeListener")!!

    private val METHOD_requestAudioFocus: Method? = CompatUtils.getMethod(
        AudioManager::class.java, "requestAudioFocus",
        CLASS_OnAudioFocusChangeListener, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType
    )
    private val METHOD_abandonAudioFocus: Method? = CompatUtils.getMethod(
        AudioManager::class.java, "abandonAudioFocus", CLASS_OnAudioFocusChangeListener
    )
    private val METHOD_forceVolumeControlStream: Method? = CompatUtils.getMethod(
        AudioManager::class.java, "forceVolumeControlStream", Int::class.javaPrimitiveType
    )

    /** The audio stream for DTMF Tones */
    const val STREAM_DTMF: Int = 8

    const val VOLUME_CHANGED_ACTION: String = "android.media.VOLUME_CHANGED_ACTION"
    const val MASTER_VOLUME_CHANGED_ACTION: String = "android.media.MASTER_VOLUME_CHANGED_ACTION"
    const val EXTRA_VOLUME_STREAM_TYPE: String = "android.media.EXTRA_VOLUME_STREAM_TYPE"
    const val EXTRA_VOLUME_STREAM_VALUE: String = "android.media.EXTRA_VOLUME_STREAM_VALUE"
    const val EXTRA_PREV_VOLUME_STREAM_VALUE: String = "android.media.EXTRA_PREV_VOLUME_STREAM_VALUE"
    const val EXTRA_MASTER_VOLUME_VALUE: String = "android.media.EXTRA_MASTER_VOLUME_VALUE"
    const val EXTRA_PREV_MASTER_VOLUME_VALUE: String = "android.media.EXTRA_PREV_MASTER_VOLUME_VALUE"

    const val AUDIOFOCUS_GAIN: Int = 1
    const val AUDIOFOCUS_GAIN_TRANSIENT: Int = 2
    const val AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK: Int = 3
    const val AUDIOFOCUS_REQUEST_FAILED: Int = 0
    const val AUDIOFOCUS_REQUEST_GRANTED: Int = 1

    @JvmStatic
    fun forceVolumeControlStream(receiver: AudioManager, streamType: Int) {
        CompatUtils.invoke(receiver, null, METHOD_forceVolumeControlStream, streamType)
    }

    @JvmStatic
    fun requestAudioFocus(
        receiver: AudioManager, l: Any, streamType: Int, durationHint: Int
    ): Int = (CompatUtils.invoke(
        receiver, AUDIOFOCUS_REQUEST_FAILED,
        METHOD_requestAudioFocus, l, streamType, durationHint
    ) as Int)

    @JvmStatic
    fun abandonAudioFocus(receiver: AudioManager, l: Any): Int = (CompatUtils.invoke(
        receiver, AUDIOFOCUS_REQUEST_FAILED, METHOD_abandonAudioFocus, l
    ) as Int)
}
