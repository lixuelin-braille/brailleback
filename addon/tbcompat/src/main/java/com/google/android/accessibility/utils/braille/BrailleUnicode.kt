/*
 * Copyright 2019 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.android.accessibility.utils.braille

import android.util.Range
import com.google.common.base.Preconditions.checkArgument
import java.util.BitSet

/** Utilities for Braille unicode characters, which are the 256 characters beginning at U+2800. */
class BrailleUnicode private constructor() {

  companion object {
    private const val DOT_COUNT = 8
    private val BRAILLE_CHAR_RANGE = Range('\u2800', '\u28FF')
    private val OFFSET_RANGE =
      Range(0, BRAILLE_CHAR_RANGE.upper.code - BRAILLE_CHAR_RANGE.lower.code)

    /**
     * Converts an offset into the braille unicode region into a specific Braille character.
     *
     * <p>For example, 15 in decimal (0b1111 in binary) converts to '⠏'.
     */
    @JvmStatic
    fun fromOffset(offset: Int): Char {
      checkArgument(
        OFFSET_RANGE.contains(offset),
        "offset %s out of range %s",
        offset,
        OFFSET_RANGE,
      )
      return BRAILLE_CHAR_RANGE.lower + offset
    }

    /** Returns {@code true} if {@code c} is in the range of Braille unicode symbols. */
    @JvmStatic
    fun isBraille(c: Char): Boolean = BRAILLE_CHAR_RANGE.contains(c)

    /**
     * Returns a list of the dot numbers.
     *
     * <p>The dot numbers are in the range 1 to 8.
     */
    @JvmStatic
    fun toDotNumbers(c: Char): List<Int> {
      val offset = c.code - BRAILLE_CHAR_RANGE.lower.code
      checkArgument(
        OFFSET_RANGE.contains(offset),
        "offset %s out of range %s",
        offset,
        OFFSET_RANGE,
      )
      return toDotNumbers(BitSet.valueOf(byteArrayOf(offset.toByte())))
    }

    /**
     * Returns a list of the dot numbers.
     *
     * <p>The dot numbers are in the range 1 to 8.
     */
    @JvmStatic
    fun toDotNumbers(dotNumbers: BitSet): List<Int> {
      checkArgument(
        dotNumbers.length() <= DOT_COUNT,
        "Bitset length %s out of range",
        dotNumbers.length(),
      )
      val dotNumberList = ArrayList<Int>()
      var index = dotNumbers.nextSetBit(0)
      while (index != -1) {
        dotNumberList.add(index + 1)
        index = dotNumbers.nextSetBit(index + 1)
      }
      return dotNumberList
    }

    /** Converts braille symbol to dot numbers string. */
    @JvmStatic
    fun toDotNumbersString(c: Char): String {
      val dotNumberList = toDotNumbers(c)
      val sb = StringBuilder()
      for (index in dotNumberList.indices) {
        sb.append(dotNumberList[index])
      }
      return sb.toString()
    }
  }
}
