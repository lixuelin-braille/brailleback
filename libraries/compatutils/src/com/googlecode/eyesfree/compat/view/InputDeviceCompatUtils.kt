/*
 * Copyright (C) 2013 Google Inc.
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

package com.googlecode.eyesfree.compat.view

object InputDeviceCompatUtils {
    /**
     * The input source is a pointing device associated with a display.
     * Examples: [SOURCE_TOUCHSCREEN], [SOURCE_MOUSE]. A
     * [android.view.MotionEvent] should be interpreted as absolute coordinates
     * in display units according to the [android.view.View] hierarchy. Pointer
     * down/up indicated when the finger touches the display or when the
     * selection button is pressed/released.
     */
    const val SOURCE_CLASS_POINTER = 0x00000002

    /**
     * The input source is a mouse pointing device. This code is also used for
     * other mouse-like pointing devices such as trackpads and trackpoints.
     *
     * @see #SOURCE_CLASS_POINTER
     */
    const val SOURCE_MOUSE = 0x00002002

    /**
     * The input source is a touch screen pointing device.
     *
     * @see #SOURCE_CLASS_POINTER
     */
    const val SOURCE_TOUCHSCREEN = 0x00001002

    /**
     * The input source is unknown.
     */
    const val SOURCE_UNKNOWN = 0x00000000
}
