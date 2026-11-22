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

package com.google.android.accessibility.braille.translate.liblouis

import android.content.Context
import androidx.annotation.Nullable
import com.google.android.accessibility.braille.interfaces.BrailleCharacter
import com.google.android.accessibility.braille.interfaces.BrailleWord
import com.google.android.accessibility.braille.translate.BrailleTranslator
import com.google.android.accessibility.braille.translate.TranslationResult
import java.util.Objects

/** A LibLouis translator based on the {@link LibLouisTranslator#tableName}. */
open class LibLouisTranslator(context: Context, private val tableName: String) : BrailleTranslator {
  private val bypassMap: MutableMap<BrailleWord, String> = LinkedHashMap()
  private val commutativityMap: MutableMap<BrailleCharacter, String> = LinkedHashMap()
  private val loader: LiblouisLoader = LiblouisLoader.getInstance(context)

  /**
   * Returns a map that allows the backing translator to be bypassed, in case the {@link
   * BrailleWord} is present as a key in the map, in which case the corresponding String value is
   * used as the translation result.
   */
  protected fun getBypassMap(): MutableMap<BrailleWord, String> {
    return bypassMap
  }

  /**
   * Returns a map that allows the backing translator's translation of certain {@link
   * BrailleCharacter} to be overridden.
   *
   * <p>For example, for Spanish, LibLouis wrongly translates "26" as the number 5, when it actually
   * should be translated as a question mark. To fix this mis-translation, fill the commutativity
   * map with the key-value pair ("26", "?").
   *
   * <p>Note that overriding the translation in this manner can only occur when the concatenation
   * and translation operators commute for the given input phrase.
   */
  protected fun getCommutativityMap(): MutableMap<BrailleCharacter, String> {
    return commutativityMap
  }

  /** Modifies the result from the backing translator, post-translation. */
  protected open fun transformPostTranslation(translation: String): String {
    return translation
  }

  override fun translateToPrint(brailleWord: BrailleWord): String {
    if (!loader.isExtracted()) {
      return ""
    }
    return translateToPrintInternal(brailleWord, /* partial= */ false)
  }

  override fun translateToPrintPartial(brailleWord: BrailleWord): String {
    if (!loader.isExtracted()) {
      return ""
    }
    return translateToPrintInternal(brailleWord, /* partial= */ true)
  }

  override fun translate(text: CharSequence, cursorPosition: Int): TranslationResult {
    if (!loader.isExtracted()) {
      return TranslationResult.createUnknown(text, cursorPosition)
    }
    return LouisTranslation.translate(text, tableName, cursorPosition)
  }

  override fun equals(@Nullable o: Any?): Boolean {
    if (this === o) {
      return true
    }
    if (o !is LibLouisTranslator) {
      return false
    }
    return tableName == o.tableName
  }

  override fun hashCode(): Int {
    return Objects.hashCode(tableName)
  }

  private fun translateToPrintInternal(brailleWord: BrailleWord, partial: Boolean): String {
    val bypassValueOrNull = getBypassMap()[brailleWord]
    if (bypassValueOrNull != null) {
      return bypassValueOrNull
    }

    var translation = translateToPrintDirect(brailleWord, partial)
    translation = transformUsingCommutativityMap(brailleWord, translation)
    translation = transformPostTranslation(translation)
    return translation
  }

  private fun translateToPrintDirect(brailleWord: BrailleWord, partial: Boolean): String {
    var flags =
        LouisTranslation.TranslationMode.NO_UNDEFINED_DOTS or
            LouisTranslation.TranslationMode.DOTS_IO
    if (partial) {
      flags = flags or LouisTranslation.TranslationMode.PARTIAL_TRANSLATE
    }
    val result = LouisTranslation.backTranslate(brailleWord.toByteArray(), tableName, flags)
    // 诊断：把"盲文点 → 文字"这一步的原始结果打出来（tag=BBTranslate）。
    // 若这里输出的就是数字，说明是翻译表本身反译成数字，与键盘点阵无关。
    android.util.Log.d(
        "BBTranslate",
        "table=" + tableName
            + " extracted=" + loader.isExtracted()
            + " partial=" + partial
            + " dots=" + brailleWord
            + " -> [" + result + "]"
            + " codes=" + toCodePoints(result))
    return result
  }

  private fun transformUsingCommutativityMap(
      brailleWord: BrailleWord, translationRaw: String): String {
    if (!brailleWord.containsAny(commutativityMap.keys)) {
      return translationRaw
    }
    val tokens: List<BrailleWord> = brailleWord.tokenize(commutativityMap.keys)
    val translateThenConcatenate =
        tokens
            .map { brailleWord1 -> translateToPrintDirect(brailleWord1, false) }
            .joinToString(separator = "")
    if (translationRaw != translateThenConcatenate) {
      return translationRaw
    }
    // Hooray - the translate and concatenate operations commute.  That means that we can
    // translate token-by-token, which allows keys in the commutativityMap to be translated
    // to their corresponding commutativityMap values.
    val translationSB = StringBuilder()
    for (token in tokens) {
      val tokenTranslation =
          if (token.size() == 1 && commutativityMap.containsKey(token.get(0))) {
            commutativityMap.get(token.get(0))
          } else {
            translateToPrintDirect(token, false)
          }
      translationSB.append(tokenTranslation)
    }
    return translationSB.toString()
  }

  companion object {
    private fun toCodePoints(s: String): String {
      if (s == null || s.isEmpty()) {
        return "(empty)"
      }
      val sb = StringBuilder()
      for (i in 0 until s.length) {
        if (i > 0) {
          sb.append(' ')
        }
        sb.append(String.format("U+%04X", s[i].code))
      }
      return sb.toString()
    }
  }
}
