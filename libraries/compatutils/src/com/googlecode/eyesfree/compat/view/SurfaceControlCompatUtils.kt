/*
 * Copyright (C) Google Inc.
 * Licensed under the Apache License, Version 2.0
 */

package com.googlecode.eyesfree.compat.view

import android.graphics.Bitmap
import android.util.Log
import com.googlecode.eyesfree.compat.CompatUtils
import java.lang.reflect.Method

/**
 * Provides access to `android.view.SurfaceControl` methods.
 *
 * @author alanv@google.com (Alan Viverette)
 */
object SurfaceControlCompatUtils {
    private val TAG = SurfaceControlCompatUtils::class.java.simpleName

    private val CLASS_Surface_Control: Class<*> =
        CompatUtils.getClass("android.view.SurfaceControl")!!
    private val METHOD_screenshot_II: Method? = CompatUtils.getMethod(
        CLASS_Surface_Control, "screenshot",
        Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)

    /**
     * Copy the current screen contents into a bitmap and return it. Use width =
     * 0 and height = 0 to obtain an unscaled screenshot.
     *
     * @param width The desired width of the returned bitmap; the raw screen
     *            will be scaled down to this size.
     * @param height The desired height of the returned bitmap; the raw screen
     *            will be scaled down to this size.
     */
    @JvmStatic
    fun screenshot(width: Int, height: Int): Bitmap? {
        if (METHOD_screenshot_II == null) {
            Log.e(TAG, "screenshot method was not found.")
            return null
        }
        return CompatUtils.invoke(null, null, METHOD_screenshot_II, width, height) as Bitmap?
    }
}
