/*
 * Copyright 2020 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.google.android.accessibility.braille.common.translate

import android.content.res.Resources
import android.text.TextUtils
import com.google.android.accessibility.braille.common.R
import com.google.android.accessibility.braille.interfaces.BrailleCharacter
import com.google.common.collect.ImmutableList
import java.lang.Character.PRIVATE_USE
import java.util.regex.Pattern

/** Utils for translation of Braille. */
class BrailleTranslateUtils private constructor() {

    companion object {
        @JvmField val DOTS3456 = BrailleCharacter(3, 4, 5, 6)
        @JvmField val DOTS45 = BrailleCharacter(4, 5)
        @JvmField val DOTS46 = BrailleCharacter(4, 6)
        @JvmField val DOTS456 = BrailleCharacter(4, 5, 6)
        @JvmField val DOT6 = BrailleCharacter(*arrayOf(6))

        const val PASSWORD_BULLET = "•"

        /**
         * Returns a String representation of [BrailleCharacter] with human understanding text.
         * Example: "⠏" -> "dots 1 2 3 4".
         */
        @JvmStatic
        fun getDotsText(resources: Resources, brailleCharacter: BrailleCharacter): String {
            val dotsString = insertSpacesInto(brailleCharacter.toString())
            return resources.getQuantityString(
                R.plurals.braille_dots, brailleCharacter.getOnCount(), dotsString
            )
        }

        /**
         * Returns a String representation of [BrailleCharacter] with human understanding the
         * sentence. Example: "⠏" -> "dots 1, 2, 3 and 4".
         */
        @JvmStatic
        fun getDotsDescription(resources: Resources, brailleCharacter: BrailleCharacter): String {
            val dotsString =
                changeToSentence(
                    resources,
                    ImmutableList.copyOf(
                        Pattern.compile("").split(brailleCharacter.toLocaleString())
                    ),
                )
            return resources.getQuantityString(
                R.plurals.braille_dots, brailleCharacter.getOnCount(), dotsString
            )
        }

        /** Compose words to a sentence. Example: "abcd" -> "a, b, c and d". */
        @JvmStatic
        fun changeToSentence(resources: Resources, words: List<String>): String {
            if (!words.isEmpty()) {
                val sentence = StringBuilder()
                sentence.append(words.get(0))
                for (i in 1 until words.size) {
                    sentence.append(resources.getString(R.string.split_comma, words.get(i)))
                }
                return sentence.toString()
            } else {
                return TextUtils.join("", words)
            }
        }

        /** Inserts space between characters. Example: "abc" -> "a b c". */
        @JvmStatic
        fun insertSpacesInto(arg: String): String {
            return arg.replace("", " ").trim()
        }

        /**
         * A translator will sometimes return a String result that is unpronounceable (instead of
         * returning an empty string). An example is the translation of 23456, which the LibLouis UEB
         * table translates as \uF501.
         */
        @JvmStatic
        fun isPronounceable(text: String): Boolean {
            // We only attempt to recognize length-1 cases of this sort for now.
            if (text.length == 1) {
                if (Character.getType(text[0]) == PRIVATE_USE.toInt()) {
                    return false
                }
            }
            return true
        }
    }
}
