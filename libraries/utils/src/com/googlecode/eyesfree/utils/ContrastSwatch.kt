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

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.Parcel
import android.os.Parcelable
import java.util.Collections
import java.util.HashMap
import java.util.LinkedList

/**
 * Represents a section of a screenshot bitmap and its associated color and
 * contrast data.
 *
 * @author caseyburkhardt@google.com (Casey Burkhardt)
 */
class ContrastSwatch : Parcelable {
    private var mImage: Bitmap? = null

    private val mName: String?

    private val mLuminanceMap = HashMap<Int, Double>()

    private val mLuminanceHistogram = HashMap<Double, Int>()

    private val mBackgroundColors = LinkedList<Int>()

    private val mForegroundColors = LinkedList<Int>()

    private var mBackgroundLuminance = 0.0

    private var mForegroundLuminance = 0.0

    private val mScreenBounds: Rect

    private var mContrastRatio = 0.0

    /**
     * Constructs a ContrastSwatch, also extracting certain properties from the
     * bitmap related to contrast and luminance.
     *
     * NOTE: Invoking this constructor performs image processing tasks, which
     * are relatively heavyweight. Callers should create these objects off the
     * UI thread.
     *
     * @param image [Bitmap] of the area of an image to be evaluated
     * @param screenBounds The bounds in screen coordinates of the image being
     *            evaluated
     * @param name Optional name identifying the image being evaluated
     */
    constructor(image: Bitmap, screenBounds: Rect, name: String) {
        mImage = image
        mScreenBounds = screenBounds
        mName = name
        processSwatch()
    }

    private constructor(source: Parcel) {
        mName = source.readString()
        source.readMap(mLuminanceMap, null)
        source.readMap(mLuminanceHistogram, null)
        source.readList(mBackgroundColors, null)
        source.readList(mForegroundColors, null)
        mBackgroundLuminance = source.readDouble()
        mForegroundLuminance = source.readDouble()
        mScreenBounds = source.readValue(Rect::class.java.classLoader) as Rect
        mContrastRatio = source.readDouble()
    }

    fun recycle() {
        mImage?.recycle()
    }

    private fun processSwatch() {
        processLuminanceData()
        extractFgBgData()

        // Two-decimal digits of precision for the contrast ratio
        mContrastRatio = Math.round(
            ContrastUtils.calculateContrastRatio(mBackgroundLuminance, mForegroundLuminance) *
                100.0) / 100.0
    }

    private fun processLuminanceData() {
        val image = mImage ?: return
        for (x in 0 until image.width) {
            for (y in 0 until image.height) {
                val color = image.getPixel(x, y)
                val luminance = ContrastUtils.calculateLuminance(color)
                if (!mLuminanceMap.containsKey(color)) {
                    mLuminanceMap[color] = luminance
                }
                if (!mLuminanceHistogram.containsKey(luminance)) {
                    mLuminanceHistogram[luminance] = 0
                }
                mLuminanceHistogram[luminance] = mLuminanceHistogram[luminance]!! + 1
            }
        }
    }

    private fun extractFgBgData() {
        if (mLuminanceMap.isEmpty()) {
            // An empty luminance map indicates we've encountered a 0px area
            // image. It has no luminance.
            mBackgroundLuminance = 0.0
            mForegroundLuminance = 0.0
            mBackgroundColors.add(Color.BLACK)
            mForegroundColors.add(Color.BLACK)
        } else if (mLuminanceMap.size == 1) {
            // Deal with views that only contain a single color
            mBackgroundLuminance = mLuminanceHistogram.keys.iterator().next()
            mForegroundLuminance = mBackgroundLuminance
            val singleColor = mLuminanceMap.keys.iterator().next()
            mForegroundColors.add(singleColor)
            mBackgroundColors.add(singleColor)
        } else {
            // Sort all luminance values seen from low to high
            val colorsByLuminance = mLuminanceMap.entries.toMutableList()
            colorsByLuminance.sortBy { it.value }

            // Sort luminance values seen by frequency in the image
            val luminanceByFrequency = mLuminanceHistogram.entries.toMutableList()
            luminanceByFrequency.sortBy { it.value }

            // Find the average luminance value within the set of luminances for
            // purposes of splitting luminance values into high-luminance and
            // low-luminance buckets. This is explicitly not a weighted average.
            var luminanceSum = 0.0
            for (luminanceCount in luminanceByFrequency) {
                luminanceSum += luminanceCount.key
            }

            val averageLuminance = luminanceSum / luminanceByFrequency.size

            // Select the highest and lowest luminance values that contribute to
            // most number of pixels in the image -- our background and
            // foreground colors.
            var lowLuminanceContributor = 0.0
            for (i in luminanceByFrequency.indices.reversed()) {
                val luminanceValue = luminanceByFrequency[i].key
                if (luminanceValue < averageLuminance) {
                    lowLuminanceContributor = luminanceValue
                    break
                }
            }

            var highLuminanceContributor = 1.0
            for (i in luminanceByFrequency.indices.reversed()) {
                val luminanceValue = luminanceByFrequency[i].key
                if (luminanceValue >= averageLuminance) {
                    highLuminanceContributor = luminanceValue
                    break
                }
            }

            // Background luminance is that which occurs more frequently
            if (mLuminanceHistogram[highLuminanceContributor]!!
                > mLuminanceHistogram[lowLuminanceContributor]!!
            ) {
                mBackgroundLuminance = highLuminanceContributor
                mForegroundLuminance = lowLuminanceContributor
            } else {
                mBackgroundLuminance = lowLuminanceContributor
                mForegroundLuminance = highLuminanceContributor
            }

            // Determine the contributing colors for those luminance values
            // TODO(caseyburkhardt): I know, this is gross to iterate through
            // the whole image again...
            for (colorLuminance in mLuminanceMap.entries) {
                if (colorLuminance.value == mBackgroundLuminance) {
                    mBackgroundColors.add(colorLuminance.key)
                }
                if (colorLuminance.value == mForegroundLuminance) {
                    mForegroundColors.add(colorLuminance.key)
                }
            }
        }
    }

    fun getImage(): Bitmap {
        return Bitmap.createBitmap(mImage!!)
    }

    fun setImage(image: Bitmap) {
        mImage = image
    }

    fun getName(): CharSequence? {
        return mName
    }

    fun getBackgroundColors(): List<Int> {
        return Collections.unmodifiableList(mBackgroundColors)
    }

    fun getForegroundColors(): List<Int> {
        return Collections.unmodifiableList(mForegroundColors)
    }

    fun getBackgroundLuminance(): Double {
        return mBackgroundLuminance
    }

    fun getForegroundLuminance(): Double {
        return mForegroundLuminance
    }

    fun getBounds(): Rect {
        return Rect(mScreenBounds)
    }

    fun getContrastRatio(): Double {
        return mContrastRatio
    }

    override fun describeContents(): Int {
        return 0
    }

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeString(mName)
        dest.writeMap(mLuminanceMap)
        dest.writeMap(mLuminanceHistogram)
        dest.writeList(mBackgroundColors)
        dest.writeList(mForegroundColors)
        dest.writeDouble(mBackgroundLuminance)
        dest.writeDouble(mForegroundLuminance)
        dest.writeValue(mScreenBounds)
        dest.writeDouble(mContrastRatio)
    }

    override fun toString(): String {
        return "{name:" + mName + ", contrast:1:" + mContrastRatio + ", background:" +
            ContrastUtils.colorsToHexString(mBackgroundColors) + ", foreground:" +
            ContrastUtils.colorsToHexString(mForegroundColors) + "}"
    }

    companion object {
        @JvmField
        val CREATOR: Parcelable.Creator<ContrastSwatch> =
            object : Parcelable.Creator<ContrastSwatch> {
                override fun createFromParcel(source: Parcel): ContrastSwatch {
                    return ContrastSwatch(source)
                }

                override fun newArray(size: Int): Array<ContrastSwatch?> {
                    return arrayOfNulls(size)
                }
            }
    }
}
