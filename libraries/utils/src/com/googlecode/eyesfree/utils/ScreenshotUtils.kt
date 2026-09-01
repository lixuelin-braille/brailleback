/*
 * Copyright (C) 2014 Google Inc.
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
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Bitmap.CompressFormat
import android.graphics.Canvas
import android.graphics.Rect
import android.util.Log
import android.view.Surface
import android.view.WindowManager
import com.googlecode.eyesfree.compat.view.SurfaceControlCompatUtils
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * A utility class for taking screenshots.
 *
 * @author caseyburkhardt@google.com (Casey Burkhardt)
 */
object ScreenshotUtils {
    @JvmStatic
    fun hasScreenshotPermission(context: Context): Boolean {
        return context.checkCallingOrSelfPermission("android.permission.READ_FRAME_BUFFER") ==
            PackageManager.PERMISSION_GRANTED
    }

    /**
     * Returns a screenshot with the contents of the current display that
     * matches the current display rotation.
     *
     * @param context The current context.
     * @return A bitmap of the screenshot.
     */
    @JvmStatic
    fun createScreenshot(context: Context): Bitmap? {
        if (!hasScreenshotPermission(context)) {
            LogUtils.log(ScreenshotUtils::class.java, Log.ERROR, "Screenshot permission denied.")
            return null
        }

        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val bitmap = SurfaceControlCompatUtils.screenshot(0, 0)

        // Bail if we couldn't take the screenshot.
        if (bitmap == null) {
            LogUtils.log(ScreenshotUtils::class.java, Log.ERROR, "Failed to take screenshot.")
            return null
        }

        val width = bitmap.width
        val height = bitmap.height
        @Suppress("DEPRECATION")
        val rotation = windowManager.defaultDisplay.rotation

        val outWidth: Int
        val outHeight: Int
        val rotationDegrees: Float

        when (rotation) {
            Surface.ROTATION_90 -> {
                outWidth = height
                outHeight = width
                rotationDegrees = 90f
            }
            Surface.ROTATION_180 -> {
                outWidth = width
                outHeight = height
                rotationDegrees = 180f
            }
            Surface.ROTATION_270 -> {
                outWidth = height
                outHeight = width
                rotationDegrees = 270f
            }
            else -> return bitmap
        }

        // Rotate the screenshot to match the screen orientation.
        val rotatedBitmap =
            Bitmap.createBitmap(outWidth, outHeight, Bitmap.Config.RGB_565)
        val c = Canvas(rotatedBitmap)

        c.translate(outWidth / 2.0f, outHeight / 2.0f)
        c.rotate(-rotationDegrees)
        c.translate(-width / 2.0f, -height / 2.0f)
        c.drawBitmap(bitmap, 0f, 0f, null)

        bitmap.recycle()

        return rotatedBitmap
    }

    /**
     * Creates a new [Bitmap] object with a rectangular region of pixels from
     * the source bitmap.
     *
     * The source bitmap is unaffected by this operation.
     *
     * @param sourceBitmap The source bitmap to crop.
     * @param bounds The rectangular bounds to keep when cropping.
     * @return A new bitmap of the cropped area, or `null` if the source was
     *         `null` or the crop parameters were out of bounds.
     */
    @JvmStatic
    fun cropBitmap(sourceBitmap: Bitmap?, bounds: Rect): Bitmap? {
        if (sourceBitmap == null) {
            return null
        }
        return try {
            Bitmap.createBitmap(
                sourceBitmap, bounds.left, bounds.top, bounds.width(), bounds.height())
        } catch (ex: IllegalArgumentException) {
            // Can throw exception if cropping arguments are out of bounds.
            LogUtils.log(ScreenshotUtils::class.java, Log.ERROR, Log.getStackTraceString(ex))
            null
        }
    }

    /**
     * Writes a [Bitmap] object to a file in the current context's files
     * directory.
     *
     * @param context The current context.
     * @param bitmap The bitmap object to output.
     * @param dir The output directory name within the files directory.
     * @param filename The name of the file to output.
     * @return A file where the Bitmap was stored, or `null` if the write
     *         operation failed.
     */
    @JvmStatic
    fun writeBitmap(context: Context, bitmap: Bitmap?, dir: String, filename: String): File? {
        if (bitmap == null) {
            return null
        }

        val dirFile = File(context.filesDir, dir)
        if (!dirFile.exists() && !dirFile.mkdirs()) {
            LogUtils.log(
                ScreenshotUtils::class.java, Log.WARN,
                "Directory %s does not exist and could not be created.",
                dirFile.absolutePath)
            return null
        }

        val outFile = File(dirFile, filename)
        if (outFile.exists()) {
            LogUtils.log(
                ScreenshotUtils::class.java, Log.WARN,
                "Tried to write a bitmap to a file that already exists.")
            return null
        }

        var outStream: FileOutputStream? = null
        try {
            outStream = FileOutputStream(outFile)
            val compressSuccess = bitmap.compress(
                CompressFormat.PNG, 0 /* quality, ignored for PNG */, outStream)

            if (compressSuccess) {
                LogUtils.log(
                    ScreenshotUtils::class.java, Log.VERBOSE,
                    "Wrote bitmap to %s.", outFile.absolutePath)
                return outFile
            } else {
                LogUtils.log(
                    ScreenshotUtils::class.java, Log.WARN,
                    "Bitmap failed to compress to file %s.", outFile.absolutePath)
                return null
            }
        } catch (e: IOException) {
            LogUtils.log(
                ScreenshotUtils::class.java, Log.WARN,
                "Could not output bitmap file to %s.", outFile.absolutePath)
            return null
        } finally {
            if (outStream != null) {
                try {
                    outStream.close()
                } catch (e: IOException) {
                    LogUtils.log(ScreenshotUtils::class.java, Log.WARN, "Unable to close resource.")
                }
            }
        }
    }
}
