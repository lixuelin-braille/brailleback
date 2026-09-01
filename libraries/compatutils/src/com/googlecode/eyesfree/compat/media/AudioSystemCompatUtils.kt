/*
 * Copyright (C) Google Inc.
 * Licensed under the Apache License, Version 2.0
 */

package com.googlecode.eyesfree.compat.media

import android.os.Build
import com.googlecode.eyesfree.compat.CompatUtils
import java.lang.reflect.Method

object AudioSystemCompatUtils {
    private val CLASS_AudioSystem: Class<*> =
        CompatUtils.getClass("android.media.AudioSystem")!!
    private val METHOD_isStreamActive: Method? = CompatUtils.getMethod(
        CLASS_AudioSystem, "isStreamActive",
        Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
    private val METHOD_LEGACY_isStreamActive: Method? = CompatUtils.getMethod(
        CLASS_AudioSystem, "isStreamActive", Int::class.javaPrimitiveType)
    private val METHOD_isSourceActive: Method? = CompatUtils.getMethod(
        CLASS_AudioSystem, "isSourceActive", Int::class.javaPrimitiveType)

    /**
     * Calls into AudioSystem to check the current status of audio streams.
     *
     * The inPastMs value is only used by the system on API 11+.
     *
     * @param stream The stream ID to query
     * @param inPastMs The time interval allowance, in milliseconds, for checking
     *            active streams
     * @return `true` if the stream is or was open in the past `inPastMs`
     *         milliseconds, `false` otherwise
     */
    @JvmStatic
    fun isStreamActive(stream: Int, inPastMs: Int): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) {
            CompatUtils.invoke(null, false, METHOD_isStreamActive, stream, inPastMs) as Boolean
        } else {
            CompatUtils.invoke(null, false, METHOD_LEGACY_isStreamActive, stream) as Boolean
        }
    }

    /**
     * Calls into AudioSystem to check the current status of an input source.
     *
     * This is only available on API 17+ and will always return false if invoked
     * on earlier platforms.
     *
     * @param source The source ID to query. Expects constants from
     *            `MediaRecorder.AudioSource`
     * @return `true` if the input source is active, `false` otherwise.
     */
    @JvmStatic
    fun isSourceActive(source: Int): Boolean {
        return CompatUtils.invoke(null, false, METHOD_isSourceActive, source) as Boolean
    }
}
