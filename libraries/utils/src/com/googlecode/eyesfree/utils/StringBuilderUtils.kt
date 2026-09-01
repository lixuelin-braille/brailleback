/*
 * Copyright (C) 2015 The Android Open Source Project
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

import android.text.SpannableStringBuilder
import android.text.TextUtils

object StringBuilderUtils {
    /**
     * Breaking separator inserted between text, intended to make TTS pause an
     * appropriate amount. Using a period breaks pronunciation of street
     * abbreviations, and using a new line doesn't work in eSpeak.
     */
    @JvmField
    val DEFAULT_BREAKING_SEPARATOR = ", "

    /**
     * Non-breaking separator inserted between text. Used when text already ends
     * with some sort of breaking separator or non-alphanumeric character.
     */
    @JvmField
    val DEFAULT_SEPARATOR = " "

    /**
     * The hex alphabet.
     */
    private val HEX_ALPHABET = "0123456789abcdef".toCharArray()

    /**
     * Generates the aggregate text from a list of [CharSequence]s,
     * separating as necessary.
     *
     * @param textList The list of text to process.
     * @return The separated aggregate text, or null if no text was appended.
     */
    @JvmStatic
    fun getAggregateText(textList: List<CharSequence>?): CharSequence? {
        val aggregateText: CharSequence?
        if (textList == null || textList.isEmpty()) {
            aggregateText = null
        } else {
            val builder = SpannableStringBuilder()
            for (text in textList) {
                appendWithSeparator(builder, text)
            }
            aggregateText = builder
        }
        return aggregateText
    }

    /**
     * Appends CharSequence representations of the specified arguments to a
     * [SpannableStringBuilder], creating one if the supplied builder is
     * `null`.
     *
     * @param builder An existing [SpannableStringBuilder], or `null` to create one.
     * @param args The objects to append to the builder.
     * @return A builder with the specified objects appended.
     */
    @JvmStatic
    fun appendWithSeparator(
        builder: SpannableStringBuilder?,
        vararg args: CharSequence
    ): SpannableStringBuilder {
        var b = builder
        if (b == null) {
            b = SpannableStringBuilder()
        }
        for (arg in args) {
            if (arg == null) {
                continue
            }
            if (arg.toString().length == 0) {
                continue
            }
            if (b.length > 0) {
                if (needsBreakingSeparator(b)) {
                    b.append(DEFAULT_BREAKING_SEPARATOR)
                } else {
                    b.append(DEFAULT_SEPARATOR)
                }
            }
            b.append(arg)
        }
        return b
    }

    /**
     * Returns whether the text needs a breaking separator (e.g. a period
     * followed by a space) appended before more text is appended.
     *
     * If text ends with a letter or digit (according to the current locale)
     * then this method will return `true`.
     */
    private fun needsBreakingSeparator(text: CharSequence): Boolean {
        if (TextUtils.isEmpty(text)) {
            return false
        }
        return Character.isLetterOrDigit(text[text.length - 1])
    }

    /**
     * Convert a byte array to a hex-encoded string.
     *
     * @param bytes The byte array of data to convert
     * @return The hex encoding of `bytes`, or null if `bytes` was null
     */
    @JvmStatic
    fun bytesToHexString(bytes: ByteArray?): String? {
        if (bytes == null) {
            return null
        }
        val hex = StringBuilder(bytes.size * 2)
        var nibble1: Int
        var nibble2: Int
        for (b in bytes) {
            nibble1 = (b.toInt() ushr 4) and 0xf
            nibble2 = b.toInt() and 0xf
            hex.append(HEX_ALPHABET[nibble1])
            hex.append(HEX_ALPHABET[nibble2])
        }
        return hex.toString()
    }
}
