/*
 * Copyright (C) 2014 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.googlecode.eyesfree.utils

import android.graphics.Color

/**
 * Utilities for dealing with colors and evaluation of their relative contrast.
 *
 * @author caseyburkhardt@google.com (Casey Burkhardt)
 */
object ContrastUtils {
    /**
     * The minimum text size considered large for contrast checking purposes, as
     * taken from the WCAG standards at
     * http://www.w3.org/TR/UNDERSTANDING-WCAG20/visual-audio-contrast-contrast.html
     */
    const val WCAG_LARGE_TEXT_MIN_SIZE = 18

    /**
     * The minimum text size for bold text to be considered large for contrast
     * checking purposes, as taken from the WCAG standards at
     * http://www.w3.org/TR/UNDERSTANDING-WCAG20/visual-audio-contrast-contrast.html
     */
    const val WCAG_LARGE_BOLD_TEXT_MIN_SIZE = 14

    const val CONTRAST_RATIO_WCAG_NORMAL_TEXT = 4.5

    const val CONTRAST_RATIO_WCAG_LARGE_TEXT = 3.0

    /**
     * Calculates the luminance value of a given [Int] representation of [Color].
     *
     * Derived from formula at http://gmazzocato.altervista.org/colorwheel/algo.php
     *
     * @param color The [Color] to evaluate
     * @return the luminance value of the given color
     */
    @JvmStatic
    fun calculateLuminance(color: Int): Double {
        val sRGB = doubleArrayOf(
            Color.red(color) / 255.0,
            Color.green(color) / 255.0,
            Color.blue(color) / 255.0
        )

        val lumRGB = DoubleArray(3)
        for (i in sRGB.indices) {
            lumRGB[i] = if (sRGB[i] <= 0.03928) {
                sRGB[i] / 12.92
            } else {
                Math.pow((sRGB[i] + 0.055) / 1.055, 2.4)
            }
        }

        return 0.2126 * lumRGB[0] + 0.7152 * lumRGB[1] + 0.0722 * lumRGB[2]
    }

    /**
     * Calculates the contrast ratio of two order-independent luminance values.
     *
     * Derived from formula at http://gmazzocato.altervista.org/colorwheel/algo.php
     *
     * @param lum1 The first luminance value
     * @param lum2 The second luminance value
     * @return The contrast ratio of the luminance values
     * @throws IllegalArgumentException if luminance values are < 0
     */
    @JvmStatic
    fun calculateContrastRatio(lum1: Double, lum2: Double): Double {
        require(lum1 >= 0.0 && lum2 >= 0.0) {
            "Luminance values may not be negative."
        }
        return (Math.max(lum1, lum2) + 0.05) / (Math.min(lum1, lum2) + 0.05)
    }

    /**
     * Converts a collection of [Int] representations of colors to a string
     * representation of those colors in hex format.
     *
     * @param colors Collection of [Color] values to convert
     * @return The hex string representation of the colors
     */
    @JvmStatic
    fun colorsToHexString(colors: Iterable<Int>): CharSequence {
        val colorStr = StringBuilder(7)
        for (color in colors) {
            if (colorStr.length != 0) {
                colorStr.append(", ")
            }
            colorStr.append(colorToHexString(color))
        }
        return colorStr
    }

    /**
     * Converts an [Int] representation of a [Color] to a hex string.
     *
     * @param color The [Color] value to convert
     * @return The hex string representation of the color
     */
    @JvmStatic
    fun colorToHexString(color: Int): CharSequence {
        return String.format("#%06X", (0xFFFFFF and color))
    }
}
