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

package com.googlecode.eyesfree.compat.view.accessibility

import android.os.Parcel
import android.view.accessibility.AccessibilityEvent
import com.googlecode.eyesfree.compat.CompatUtils
import java.lang.reflect.Method

object AccessibilityEventCompatUtils {
    private val CLASS_AccessibilityEvent: Class<*> = AccessibilityEvent::class.java
    private val CLASS_AccessibilityRecord: Class<*>? =
        CompatUtils.getClass("android.view.accessibility.AccessibilityRecord")
    private val METHOD_getToIndex: Method? =
        CompatUtils.getMethod(CLASS_AccessibilityRecord, "getToIndex")
    private val METHOD_getMovementGranularity: Method? =
        CompatUtils.getMethod(CLASS_AccessibilityEvent, "getMovementGranularity")
    private val METHOD_getAction: Method? =
        CompatUtils.getMethod(CLASS_AccessibilityEvent, "getAction")

    @JvmStatic
    fun obtain(event: AccessibilityEvent): AccessibilityEvent {
        val parcel = Parcel.obtain()
        event.writeToParcel(parcel, 0)
        parcel.setDataPosition(0)
        val clone = AccessibilityEvent.CREATOR.createFromParcel(parcel)
        parcel.recycle()
        return clone
    }

    @JvmStatic
    fun getToIndex(event: AccessibilityEvent): Int =
        (CompatUtils.invoke(event, -1, METHOD_getToIndex) as Int)

    @JvmStatic
    fun getMovementGranularity(event: AccessibilityEvent): Int =
        (CompatUtils.invoke(event, -1, METHOD_getMovementGranularity) as Int)

    @JvmStatic
    fun getAction(event: AccessibilityEvent): Int =
        (CompatUtils.invoke(event, -1, METHOD_getAction) as Int)
}
