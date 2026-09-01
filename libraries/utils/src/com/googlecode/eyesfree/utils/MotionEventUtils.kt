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

package com.googlecode.eyesfree.utils

import android.view.MotionEvent
import com.googlecode.eyesfree.compat.view.InputDeviceCompatUtils
import com.googlecode.eyesfree.compat.view.MotionEventCompatUtils

object MotionEventUtils {
    /**
     * Converts a hover [MotionEvent] to touch event by changing its action and
     * source. Returns an modified clone of the original event.
     *
     * The following types are affected:
     * - [MotionEventCompatUtils.ACTION_HOVER_ENTER]
     * - [MotionEventCompatUtils.ACTION_HOVER_MOVE]
     * - [MotionEventCompatUtils.ACTION_HOVER_EXIT]
     *
     * @param hoverEvent The hover event to convert.
     * @return a touch event
     */
    @JvmStatic
    fun convertHoverToTouch(hoverEvent: MotionEvent): MotionEvent {
        val touchEvent = MotionEvent.obtain(hoverEvent)
        MotionEventCompatUtils.setSource(hoverEvent, InputDeviceCompatUtils.SOURCE_TOUCHSCREEN)

        when (hoverEvent.action) {
            MotionEventCompatUtils.ACTION_HOVER_ENTER ->
                touchEvent.setAction(MotionEvent.ACTION_DOWN)
            MotionEventCompatUtils.ACTION_HOVER_MOVE ->
                touchEvent.setAction(MotionEvent.ACTION_MOVE)
            MotionEventCompatUtils.ACTION_HOVER_EXIT ->
                touchEvent.setAction(MotionEvent.ACTION_UP)
        }

        return touchEvent
    }
}
