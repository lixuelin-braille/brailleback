/*
 * Copyright (C) 2015 Google Inc.
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

import android.content.Context
import android.content.res.Resources

/**
 * Utility methods for retrieving information about the dimensions of the screen.
 *
 * WARNING: THIS IS NOT GUARANTEED TO WORK ON ALL DEVICES
 */
object ScreenDimensionUtils {
    /**
     * Get the current nav bar height.
     *
     * @param context
     * @return The current nav bar height in dps
     */
    @JvmStatic
    fun getNavBarHeight(context: Context): Int {
        return try {
            val resources = context.resources
            resources.getDimensionPixelSize(
                resources.getIdentifier("navigation_bar_height", "dimen", "android"))
        } catch (e: Resources.NotFoundException) {
            0
        }
    }

    /**
     * Get the current status bar height.
     *
     * @param context
     * @return The current status bar height in dps
     */
    @JvmStatic
    fun getStatusBarHeight(context: Context): Int {
        return try {
            val resources = context.resources
            resources.getDimensionPixelSize(
                resources.getIdentifier("status_bar_height", "dimen", "android"))
        } catch (e: Resources.NotFoundException) {
            0
        }
    }
}
