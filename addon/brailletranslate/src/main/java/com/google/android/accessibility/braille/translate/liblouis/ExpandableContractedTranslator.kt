/*
 * Copyright (C) 2023 Google Inc.
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

package com.google.android.accessibility.braille.translate.liblouis

import android.text.TextUtils
import androidx.annotation.Nullable
import com.google.android.accessibility.braille.interfaces.BrailleWord
import com.google.android.accessibility.braille.translate.BrailleTranslator
import com.google.android.accessibility.braille.translate.TranslationResult
import com.google.common.base.Splitter
import com.google.common.collect.ImmutableList
import java.util.Objects

/**
 * A contracted translator translates the word the cursor point at in uncontracted.
 *
 * <ul>
 *   <li>If a text field has "a child|", it will be translated into 1-0-16-78.
 *   <li>If a text field has "a chil|d", it will be translated into 1-0-14-125-24-123-14578. The
 *       text will be cut into 3 parts: <br>
 *       1. the string before child "a " <br>
 *       2. the word "child" <br>
 *       3. string after child "" <br>
 *       The translator will translate 1 and 3 in grade 2 and translate 2 in grade 1 and combine all
 *       the result.
 *   <li>If a text field has "a |child", it will be translated into 1-0-1478-125-24-123-145.
 */
class ExpandableContractedTranslator(
  private val g1Translator: BrailleTranslator,
  private val g2Translator: BrailleTranslator,
) : BrailleTranslator {

  override fun translateToPrint(brailleWord: BrailleWord): String {
    return g2Translator.translateToPrint(brailleWord)
  }

  override fun translateToPrintPartial(brailleWord: BrailleWord): String {
    return g2Translator.translateToPrintPartial(brailleWord)
  }

  override fun translate(wholeText: CharSequence, cursorPosition: Int): TranslationResult {
    val words: Iterable<String> = Splitter.on(' ').split(wholeText)
    var lastSearchIndex = -1
    for (wordString in words) {
      if (TextUtils.isEmpty(wordString)) {
        continue
      }
      val startIndex =
          wholeText.toString().indexOf(wordString, /* fromIndex= */ lastSearchIndex + 1)
      val endIndex = startIndex + wordString.length
      lastSearchIndex = endIndex
      // Check if the cursor is at this word.
      if (startIndex <= cursorPosition && cursorPosition <= endIndex - 1) {
        return getTranslationResult(wholeText, startIndex, endIndex, cursorPosition)
      }
    }
    return g2Translator.translate(wholeText, cursorPosition)
  }

  private fun getTranslationResult(
    wholeText: CharSequence,
    startIndex: Int,
    endIndex: Int,
    cursorPosition: Int,
  ): TranslationResult {
    val beforeWord = wholeText.subSequence(0, startIndex)
    val expandableString = wholeText.subSequence(startIndex, endIndex)
    val afterWord = wholeText.subSequence(endIndex, wholeText.length)
    val beforeWordResult = g2Translator.translate(beforeWord, /* cursorPosition= */ -1)
    val expandableWordResult = g1Translator.translate(expandableString, /* cursorPosition= */ -1)
    val afterWordResult = g2Translator.translate(afterWord, /* cursorPosition= */ -1)
    val beforeWordWord = beforeWordResult.cells()
    val expandableWord = expandableWordResult.cells()
    val afterWordWord = afterWordResult.cells()
    val beforeWordTextToBraillePositions: ImmutableList<Int> =
        beforeWordResult.textToBraillePositions()
    val beforeWordBrailleToTextPositions: ImmutableList<Int> =
        beforeWordResult.brailleToTextPositions()
    val expandableWordTextToBraillePositions: ImmutableList<Int> =
        expandableWordResult.textToBraillePositions()
    val expandableWordBrailleToTextPositions: ImmutableList<Int> =
        expandableWordResult.brailleToTextPositions()
    val afterWordTextToBraillePositions: ImmutableList<Int> =
        afterWordResult.textToBraillePositions()
    val afterWordBrailleToTextPositions: ImmutableList<Int> =
        afterWordResult.brailleToTextPositions()
    val all = BrailleWord()
    all.append(beforeWordWord)
    all.append(expandableWord)
    all.append(afterWordWord)
    val textToBraille: MutableList<Int> = ArrayList(wholeText.length)
    val brailleToText: MutableList<Int> = ArrayList(all.size())
    // Assign the position of braille byte array to each character in text.
    for (i in 0 until wholeText.length) {
      if (i < beforeWord.length) {
        textToBraille.add(beforeWordTextToBraillePositions[i])
      } else if (i < beforeWord.length + expandableString.length) {
        textToBraille.add(
            beforeWordWord.size() +
                expandableWordTextToBraillePositions[i - beforeWord.length])
      } else if (i < wholeText.length) {
        textToBraille.add(
            beforeWordWord.size() +
                expandableWord.size() +
                afterWordTextToBraillePositions[
                    i - beforeWord.length - expandableString.length])
      }
    }
    // Assign the position of character in text to each byte in braille byte array.
    for (i in 0 until all.size()) {
      if (i < beforeWordWord.size()) {
        brailleToText.add(beforeWordBrailleToTextPositions[i])
      } else if (i < beforeWordWord.size() + expandableWord.size()) {
        brailleToText.add(
            beforeWord.length +
                expandableWordBrailleToTextPositions[i - beforeWordWord.size()])
      } else if (i < all.size()) {
        brailleToText.add(
            beforeWord.length +
                expandableString.length +
                afterWordBrailleToTextPositions[
                    i - beforeWordWord.size() - expandableWord.size()])
      }
    }
    return TranslationResult.builder()
        .setText(wholeText)
        .setCells(all)
        .setTextToBraillePositions(textToBraille)
        .setBrailleToTextPositions(brailleToText)
        .setCursorBytePosition(cursorPosition)
        .build()
  }

  override fun equals(@Nullable o: Any?): Boolean {
    if (this === o) {
      return true
    }
    if (o !is ExpandableContractedTranslator) {
      return false
    }
    return g1Translator.equals(o.g1Translator) && g2Translator.equals(o.g2Translator)
  }

  override fun hashCode(): Int {
    return Objects.hash(g1Translator, g2Translator)
  }
}
