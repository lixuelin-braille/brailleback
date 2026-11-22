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

package com.google.android.accessibility.braille.interfaces

import android.icu.text.NumberFormat
import android.util.Range
import com.google.android.accessibility.utils.braille.BrailleUnicode
import java.util.BitSet
import java.util.Locale
import com.google.common.base.Preconditions.checkArgument

/**
 * An immutable configuration of braille dots, in a single braille cell, some of which are on
 * (raised).
 *
 * <p>There are 256 distinguishable {@link BrailleCharacter} objects, because there are 8 dots -
 * each of which can be on or off (raised or unraised).
 */
class BrailleCharacter {
  private val dotNumbers: BitSet

  /** Creates an empty braille character. */
  constructor() {
    this.dotNumbers = BitSet(DOT_COUNT)
  }

  /**
   * Creates a {@link BrailleCharacter} from a collection of dot numbers.
   *
   * <p>The dot numbers must be in the range 1 to 8, otherwise {@link IllegalArgumentException} is
   * thrown.
   */
  constructor(dotNumbers: Collection<Int>) : this() {
    for (dotNumber in dotNumbers) {
      checkArgument(DOT_RANGE.contains(dotNumber), "dot %s out of range %s", dotNumber, DOT_RANGE)
      // Maps dot number (1 to 8) to bit (0 to 7).
      this.dotNumbers.set(dotNumber - 1)
    }
  }

  /**
   * Creates a braille character from dot numbers string.
   *
   * <p>For example the input "12" creates a {@link BrailleCharacter} with dots 1 and 2.
   *
   * <p>Passing in the empty string returns an empty character.
   */
  constructor(dots: String) : this() {
    for (c in dots.toCharArray()) {
      val digit = Character.digit(c, DOT_COUNT + 1)
      checkArgument(digit != -1, "dot %s out of range", c)
      this.dotNumbers.set(digit - 1)
    }
  }

  /**
   * Creates a {@link BrailleCharacter} from a BitSet.
   *
   * <p>The length must be less than 8, otherwise {@link IllegalArgumentException} is thrown.
   */
  constructor(bitSet: BitSet) {
    checkArgument(bitSet.length() <= DOT_COUNT, "Bitset length %s out of range", bitSet.length())
    this.dotNumbers = bitSet
  }

  /**
   * Creates a {@link BrailleCharacter} from a collection of dot numbers.
   *
   * <p>The dot numbers must be in the range 1 to 8, otherwise {@link IllegalArgumentException} is
   * thrown.
   */
  constructor(vararg dotNumbers: Int?) : this(dotNumbers.map { it!! })

  /**
   * Creates a {@link BrailleCharacter} from a collection of dot numbers.
   *
   * <p>The bits map to the dot numbers.
   */
  constructor(b: Byte) {
    this.dotNumbers = BitSet.valueOf(byteArrayOf(b))
  }

  /** Intersects this {@link BrailleCharacter} with another, creating a new one. */
  fun intersect(arg: BrailleCharacter): BrailleCharacter {
    val dotNumbersCopy = dotNumbers.clone() as BitSet
    dotNumbersCopy.and(arg.dotNumbers)
    return BrailleCharacter(dotNumbersCopy)
  }

  /** Intersects this {@link BrailleCharacter} with another, mutating this. */
  fun intersectMutate(arg: BrailleCharacter) {
    dotNumbers.and(arg.dotNumbers)
  }

  /** Unions this {@link BrailleCharacter} with another, creating a new one. */
  fun union(arg: BrailleCharacter): BrailleCharacter {
    val dotNumbersCopy = dotNumbers.clone() as BitSet
    dotNumbersCopy.or(arg.dotNumbers)
    return BrailleCharacter(dotNumbersCopy)
  }

  /** Unions this {@link BrailleCharacter} with another, mutating this. */
  fun unionMutate(arg: BrailleCharacter) {
    dotNumbers.or(arg.dotNumbers)
  }

  /** Returns the number of on dots. */
  fun getOnCount(): Int {
    return dotNumbers.cardinality()
  }

  /** Returns {@code true} if the character is empty (has zero on dots). */
  fun isEmpty(): Boolean {
    return dotNumbers.isEmpty()
  }

  /**
   * Returns a byte that represents this {@link BrailleCharacter} via binary mapping.
   *
   * <p>For example, the character with dots 1 and 3 raised results in {@code 0b101}, or {@code 5}.
   */
  fun toByte(): Byte {
    if (isEmpty()) {
      return 0
    }
    return dotNumbers.toByteArray()[0]
  }

  /**
   * Returns an int that represents this {@link BrailleCharacter} via binary mapping.
   *
   * <p>For example, the character with dots 1 and 3 raised results in {@code 0b101}, or {@code 5}.
   */
  fun toInt(): Int {
    return toByte().toInt() and 0xFF
  }

  /**
   * Returns a list of the dot numbers that represents this {@link BrailleCharacter}.
   *
   * <p>The dot numbers are in the range 1 to 8.
   */
  fun toDotNumbers(): List<Int> {
    return BrailleUnicode.toDotNumbers(dotNumbers)
  }

  /** Returns whether the given dot number is on. */
  fun isDotNumberOn(dotNumber: Int): Boolean {
    return dotNumbers.get(dotNumber - 1)
  }

  /** Swaps the dot values of 1<->4, 2<->5, 3<->6, 7<->8. */
  fun toMirror(): BrailleCharacter {
    val allDots = toByte()
    val dot123To456 = ((allDots.toInt() shl 3) and 0b00111000).toByte()
    val dot456To123 = ((allDots.toInt() shr 3) and 0b00000111).toByte()
    val dot7To8 = ((allDots.toInt() shl 1) and 0b10000000).toByte()
    val dot8To7 = ((allDots.toInt() shr 1) and 0b01000000).toByte()
    return BrailleCharacter(
      (dot123To456.toInt() or dot456To123.toInt() or dot7To8.toInt() or dot8To7.toInt()).toByte()
    )
  }

  override fun toString(): String {
    val sb = StringBuilder()
    for (bitIndex in 0 until dotNumbers.length()) {
      if (dotNumbers.get(bitIndex)) {
        // Maps bit 0 to dot number 1.
        sb.append(bitIndex + 1)
      }
    }
    return sb.toString()
  }

  /** Returns the number string using the current locale. */
  fun toLocaleString(): String {
    val sb = StringBuilder()
    val numberFormat = NumberFormat.getNumberInstance(Locale.getDefault())
    for (bitIndex in 0 until dotNumbers.length()) {
      if (dotNumbers.get(bitIndex)) {
        // Maps bit 0 to dot number 1.
        sb.append(numberFormat.format(bitIndex + 1))
      }
    }
    return sb.toString()
  }

  /** Returns the unicode represenation of this {@link BrailleCharacter}. */
  fun asUnicodeChar(): Char {
    return BrailleUnicode.fromOffset(toInt())
  }

  override fun equals(o: Any?): Boolean {
    if (o !is BrailleCharacter) {
      return false
    }
    val that = o
    return dotNumbers.equals(that.dotNumbers)
  }

  override fun hashCode(): Int {
    return dotNumbers.hashCode()
  }

  companion object {
    @JvmField val EMPTY_CELL = BrailleCharacter()
    @JvmField val FULL_CELL = BrailleCharacter("12345678")
    @JvmField val DOT1 = BrailleCharacter("1")
    @JvmField val DOT2 = BrailleCharacter("2")
    @JvmField val DOT3 = BrailleCharacter("3")
    @JvmField val DOT4 = BrailleCharacter("4")
    @JvmField val DOT5 = BrailleCharacter("5")
    @JvmField val DOT6 = BrailleCharacter("6")
    @JvmField val DOT7 = BrailleCharacter("7")
    @JvmField val DOT8 = BrailleCharacter("8")
    @JvmField val DOT12 = BrailleCharacter("12")

    const val DOT_COUNT = 8
    private val DOT_RANGE = Range<Int>(1, DOT_COUNT)
  }
}
