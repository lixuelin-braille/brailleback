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

package com.googlecode.eyesfree.compat.os

import android.os.Vibrator
import com.googlecode.eyesfree.compat.CompatUtils
import java.lang.reflect.Method

object VibratorCompatUtils {
    private val METHOD_hasVibrator: Method? =
        CompatUtils.getMethod(Vibrator::class.java, "hasVibrator")

    /**
     * Check whether the hardware has a vibrator. Returns true if a vibrator
     * exists, else false.
     */
    @JvmStatic
    fun hasVibrator(receiver: Vibrator): Boolean {
        return CompatUtils.invoke(receiver, true, METHOD_hasVibrator) as Boolean
    }
}
