/*
 * Copyright 2020 Google Inc.
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

import com.google.common.base.Splitter
import java.util.Collections
import java.util.Objects

/** A sequence of {@link BrailleCharacter}. */
class BrailleWord {

  companion object {
    @JvmField val NEW_LINE = BrailleWord("1246-123")

    @JvmStatic
    fun create(dashDelimited: String): BrailleWord {
      return BrailleWord(dashDelimited)
    }
  }

  private val list: MutableList<BrailleCharacter> = ArrayList()

  /** Creates an empty word. */
  constructor()

  /** Creates a new {@link BrailleWord}. */
  constructor(brailleWord: BrailleWord) : this() {
    append(brailleWord)
  }

  /** Creates a new {@link BrailleWord} with repeated times. */
  constructor(brailleWord: BrailleWord, times: Int) : this() {
    for (i in 0 until times) {
      append(brailleWord)
    }
  }

  /** Creates a word from a collection of {@link BrailleCharacter}. */
  constructor(characters: Collection<BrailleCharacter>) : this() {
    list.addAll(characters)
  }

  /**
   * Creates a word from a dash-delimited list of dot numbers.
   *
   * <p>For example the input "6-12" creates a word of two {@link BrailleCharacter} - the first is
   * the character corresponding with dot 6 and the second is the character corresponding with dots
   * 1 and 2.
   *
   * <p>Passing in the empty string returns an empty word.
   */
  constructor(dashDelimited: String) : this() {
    for (token in Splitter.on('-').omitEmptyStrings().split(dashDelimited)) {
      list.add(BrailleCharacter(token))
    }
  }

  /** Creates a word from a var-args list of {@link BrailleCharacter}. */
  constructor(vararg brailleCharacters: BrailleCharacter) : this(brailleCharacters.asList())

  /** Creates a word from byte array. */
  constructor(brailleByteArray: ByteArray) : this() {
    for (b in brailleByteArray) {
      list.add(BrailleCharacter(b))
    }
  }

  /** Appends a {@link BrailleCharacter} to the end of the word. */
  fun append(brailleCharacter: BrailleCharacter) {
    list.add(brailleCharacter)
  }

  /** Appends a {@link BrailleWord} to the end of the word. */
  fun append(brailleWord: BrailleWord) {
    list.addAll(brailleWord.list)
  }

  /** Inserts a {@link BrailleCharacter} to the specified position of the word. */
  fun insert(index: Int, brailleCharacter: BrailleCharacter) {
    list.add(index, brailleCharacter)
  }

  /** Returns the starting position of the first occurrence of the word. */
  fun indexOf(brailleWord: BrailleWord): Int {
    return Collections.indexOfSubList(list, brailleWord.list)
  }

  /**
   * Replaces {@link BrailleCharacter} at the specified position with the specified {@link
   * BrailleCharacter}.
   */
  fun set(index: Int, brailleCharacter: BrailleCharacter) {
    list[index] = brailleCharacter
  }

  /**
   * Replaces {@link BrailleCharacter} at the specified position with the specified {@link
   * BrailleWord}.
   */
  fun set(index: Int, brailleWord: BrailleWord) {
    list.removeAt(index)
    list.addAll(index, brailleWord.list)
  }

  /**
   * Removes and returns the {@link BrailleCharacter} found at the specified index or throws {@link
   * IndexOutOfBoundsException} if {@code index < 0} or {@code index >= size()}.
   */
  fun remove(index: Int): BrailleCharacter {
    return list.removeAt(index)
  }

  /**
   * Gets the {@link BrailleCharacter} at the specified {@code index}, or throws {@link
   * IndexOutOfBoundsException} if {@code index < 0} or {@code index >= size()}.
   */
  fun get(index: Int): BrailleCharacter {
    return list[index]
  }

  /** Returns the size of the word, which is the number of {@link BrailleCharacter} it contains. */
  fun size(): Int {
    return list.size
  }

  /** Returns {@code true} if the word is empty. */
  fun isEmpty(): Boolean {
    return list.isEmpty()
  }

  /** Clears the contents of word, making it empty. */
  fun clear() {
    list.clear()
  }

  /**
   * Returns an array of bytes representing this list, where each byte value comes from {@link
   * BrailleCharacter#toByte()}.
   */
  fun toByteArray(): ByteArray {
    val array = ByteArray(list.size)
    for (i in 0 until list.size) {
      val brailleCharacter = list[i]
      array[i] = brailleCharacter.toByte()
    }
    return array
  }

  /**
   * Returns a String representation of a list of {@link BrailleCharacter}.
   *
   * <p>The result is a dash-delimited concatenation of the result of invoking {@code toString} on
   * the individual {@link BrailleCharacter} elements.
   */
  override fun toString(): String {
    val sb = StringBuilder()
    for (i in 0 until list.size) {
      val brailleCharacter = list[i]
      sb.append(brailleCharacter)
      if (i < list.size - 1) {
        sb.append('-')
      }
    }
    return sb.toString()
  }

  override fun equals(o: Any?): Boolean {
    if (this === o) {
      return true
    }
    if (o !is BrailleWord) {
      return false
    }
    val that: BrailleWord = o
    return list.equals(that.list)
  }

  override fun hashCode(): Int {
    return Objects.hashCode(list)
  }

  fun toList(): List<BrailleCharacter> {
    return ArrayList(list)
  }

  /**
   * Returns a subword from the range [start, end), or throws {@link IndexOutOfBoundsException} if
   * the passed-in range is out of bounds.
   */
  fun subword(start: Int, end: Int): BrailleWord {
    return BrailleWord(list.subList(start, end))
  }

  /** Returns {@code true} if the word contains the passed-in {@link BrailleCharacter}. */
  fun contains(brailleCharacter: BrailleCharacter): Boolean {
    return list.contains(brailleCharacter)
  }

  /**
   * Returns {@code true} if the word contains any of the {@link BrailleCharacter} contained in the
   * passed-in collection.
   */
  fun containsAny(collection: Collection<BrailleCharacter>): Boolean {
    return !Collections.disjoint(list, collection)
  }

  /**
   * Tokenizes a word into subwords, where subword boundaries are defined by the {@code delimiters}.
   *
   * <p>The resulting list of words includes delimiters, as well as non-delimiters, and no non-empty
   * subwords are discarded by the tokenization.
   */
  fun tokenize(delimiters: Collection<BrailleCharacter>): List<BrailleWord> {
    val fragments: MutableList<BrailleWord> = ArrayList()
    var nonPoleFragmentAnchor = -1
    for (i in 0 until size()) {
      val iCharacter = get(i)
      if (delimiters.contains(iCharacter)) {
        if (nonPoleFragmentAnchor >= 0) {
          fragments.add(subword(nonPoleFragmentAnchor, i))
          nonPoleFragmentAnchor = -1
        }
        fragments.add(BrailleWord(iCharacter))
      } else {
        if (nonPoleFragmentAnchor < 0) {
          nonPoleFragmentAnchor = i
        }
      }
    }
    if (nonPoleFragmentAnchor >= 0) {
      fragments.add(subword(nonPoleFragmentAnchor, size()))
    }
    return fragments
  }
}
