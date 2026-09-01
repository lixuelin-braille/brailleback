/*
 * Copyright (C) 2011 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.googlecode.eyesfree.compat.view

import android.os.SystemClock
import android.view.MotionEvent
import com.googlecode.eyesfree.compat.CompatUtils
import java.lang.reflect.Method

object MotionEventCompatUtils {
    private val CLASS_MotionEvent: Class<*> = MotionEvent::class.java
    private val METHOD_getSource: Method? = CompatUtils.getMethod(CLASS_MotionEvent, "getSource")
    private val METHOD_setSource: Method? =
        CompatUtils.getMethod(CLASS_MotionEvent, "setSource", Int::class.javaPrimitiveType)
    private val METHOD_setDownTime: Method? =
        CompatUtils.getMethod(CLASS_MotionEvent, "setDownTime", Long::class.javaPrimitiveType)

    const val ACTION_HOVER_MOVE: Int = 0x7
    const val ACTION_HOVER_ENTER: Int = 0x9
    const val ACTION_HOVER_EXIT: Int = 0xA

    private var sPreviousDownTime: Long = 0

    @JvmStatic
    fun getSource(event: MotionEvent): Int =
        (CompatUtils.invoke(event, InputDeviceCompatUtils.SOURCE_UNKNOWN, METHOD_getSource) as Int)

    @JvmStatic
    fun setSource(event: MotionEvent, source: Int) {
        CompatUtils.invoke(event, null, METHOD_setSource, source)
    }

    @JvmStatic
    fun setDownTime(event: MotionEvent, downTime: Long) {
        CompatUtils.invoke(event, null, METHOD_setDownTime, downTime)
    }

    @JvmStatic
    fun convertHoverToTouch(hoverEvent: MotionEvent): MotionEvent {
        val touchEvent = MotionEvent.obtain(hoverEvent)
        var downTime = sPreviousDownTime

        when (touchEvent.action) {
            ACTION_HOVER_ENTER -> {
                touchEvent.action = MotionEvent.ACTION_DOWN
                sPreviousDownTime = SystemClock.uptimeMillis()
                downTime = sPreviousDownTime
            }
            ACTION_HOVER_MOVE -> touchEvent.action = MotionEvent.ACTION_MOVE
            ACTION_HOVER_EXIT -> {
                touchEvent.action = MotionEvent.ACTION_UP
                sPreviousDownTime = 0
            }
            else -> downTime = touchEvent.downTime
        }

        setSource(touchEvent, InputDeviceCompatUtils.SOURCE_MOUSE)
        setDownTime(touchEvent, downTime)

        return touchEvent
    }
}
