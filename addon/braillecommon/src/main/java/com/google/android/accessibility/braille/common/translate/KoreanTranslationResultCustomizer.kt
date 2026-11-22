/*
 * Copyright 2024 Google Inc.
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

import android.content.Context
import android.text.TextUtils
import androidx.annotation.Nullable
import com.google.android.accessibility.braille.common.BrailleCommonLog
import com.google.android.accessibility.braille.interfaces.BrailleCharacter
import com.google.android.accessibility.braille.interfaces.BrailleWord
import com.google.android.accessibility.braille.translate.BrailleTranslator
import com.google.android.accessibility.braille.translate.TranslationResult
import java.util.Objects
import java.util.regex.Matcher
import java.util.regex.Pattern

/** Customizer for Korean braille translation. */
class KoreanTranslationResultCustomizer(context: Context) :
    GoogleTranslationResultCustomizer(context) {

  @Nullable
  override fun customize(
      result: TranslationResult?,
      translator: BrailleTranslator,
  ): TranslationResult? {
    var ret = super.customize(result, translator) ?: return null
    val text = ret.text()
    val original = ret.cells().toString()
    ret = customizeAlphabet(ret)
    ret = customizeFullDot(ret)
    ret = customizeConnect(ret)
    ret = customizeConnect2(ret)
    ret = customizeDoubleFinal(ret)
    ret = customizeWord(ret)
    ret = customizeSymbol(ret)
    ret = customizeExceptionalSymbol(ret)
    BrailleCommonLog.d(
        TAG, "text: " + text + " original: " + original + " customize: " + ret.cells())
    textToBrailleLog(ret)
    brailleToTextLog(ret)
    return ret
  }

  private fun customizeExceptionalSymbol(ret: TranslationResult): TranslationResult {
    val original = ret.cells().toString()
    var ret2 = replaceWonSymbol(ret)
    ret2 = replaceCommaSymbol(ret2)
    BrailleCommonLog.d(
        TAG, "customizeExceptionalSymbol:" + !original.equals(ret2.cells().toString()))
    return ret2
  }

  private fun replaceWonSymbol(ret: TranslationResult): TranslationResult {
    var result = ret
    val text = result.text().toString()
    var index = text.indexOf(CODE_POINT_OF_WON.toChar())
    while (-1 < index && index < text.length) {
      val brailleWord = BrailleWord()
      brailleWord.append(BrailleWord(WON))
      result = TranslationResult.correctTranslation(result, brailleWord, index, index + 1)
      index = text.indexOf(CODE_POINT_OF_WON.toChar(), index + 1)
    }
    return result
  }

  private fun replaceCommaSymbol(ret: TranslationResult): TranslationResult {
    var result = ret
    val text = result.text().toString()
    var index = text.indexOf(",")
    while (0 < index && index < text.length - 1) {
      // 1,234,567 > comma is "2" at this case
      if (Pattern.matches("^[0-9]*$", text.substring(index - 1, index)) &&
          Pattern.matches("^[0-9]*$", text.substring(index + 1, index + 2))) {
        val brailleWord = BrailleWord()
        brailleWord.append(BrailleWord(COMMA_SYMBOL))
        result = TranslationResult.correctTranslation(result, brailleWord, index, index + 1)
      }
      index = text.indexOf(",", index + 1)
    }
    return result
  }

  private fun customizeSymbol(ret: TranslationResult): TranslationResult {
    val original = ret.cells().toString()
    var result = replaceSymbol(ret, "", "'")
    result = replaceSymbol(result, "", "\"")
    result = replaceSymbol(result, "236-3", "\\(")
    result = replaceSymbol(result, "6-356", "\\)")
    result = replaceSymbol(result, "236-2", "\\{")
    result = replaceSymbol(result, "5-356", "\\}")
    result = replaceSymbol(result, "236-23", "\\[")
    result = replaceSymbol(result, "56-356", "\\]")
    result = replaceSymbol(result, "5-236", "\\<")
    result = replaceSymbol(result, "356-2", "\\>")
    result = replaceSymbol(result, "26", "\\+")
    result = replaceHypon(result, "\\-")
    result = replaceSlash(result, "/")
    result = replaceSymbol(result, "5-23", "·") // matching for center dot
    result = replaceSymbol(result, "356-4-1-256", "@")
    result = replaceSymbol(result, "356-456-1456-256", "#")
    result = replaceSymbol(result, "356-4-26-256", "\\^")
    result = replaceSymbol(result, "356-4-12346-256", "&")
    BrailleCommonLog.d(TAG, "customizeSymbol:" + !original.equals(result.cells().toString()))
    return result
  }

  private fun replaceSlash(ret: TranslationResult, compare: String): TranslationResult {
    var result = ret
    val regexPattern = Pattern.compile(compare)
    val p: Matcher = regexPattern.matcher(result.text())
    while (p.find()) {
      val replacement = p.group()
      if (p.start() - 1 < 0) {
        continue
      }
      if (p.end() + 1 > result.text().length) {
        continue
      }
      val prevText = result.text().toString().substring(p.start() - 1, p.start())
      val nextText = result.text().toString().substring(p.end(), p.end() + 1)
      BrailleCommonLog.d(
          TAG,
          "replaceSlash prev:"
              + prevText
              + " next:"
              + nextText
              + " s:"
              + p.start()
              + " e:"
              + p.end())
      if (BrailleTranslateUtilsKorean.isNumber(prevText) &&
          BrailleTranslateUtilsKorean.isNumber(nextText)) {
        val brailleWord = BrailleWord()
        appendBrailleWord(result, brailleWord, p.start(), p.end())
        brailleWord.append(BrailleCharacter(SLASH))
        result = TranslationResult.correctTranslation(result, brailleWord, p.start(), p.end())
        BrailleCommonLog.d(
            TAG,
            "replacement:"
                + replacement
                + " brailleWord:"
                + brailleWord
                + " start:"
                + p.start()
                + " end:"
                + p.end())
      }
    }
    return result
  }

  private fun replaceHypon(ret: TranslationResult, compare: String): TranslationResult {
    var result = ret
    val regexPattern = Pattern.compile(compare)
    val p: Matcher = regexPattern.matcher(result.text())
    while (p.find()) {
      val brailleWord = BrailleWord()
      result = appendHypon(result, brailleWord, p)
    }
    return result
  }

  private fun appendHypon(
      ret: TranslationResult, word: BrailleWord, p: Matcher
  ): TranslationResult {
    val textIdx = p.start()
    var start = ret.textToBraillePositions().get(p.start())
    var end = ret.textToBraillePositions().get(p.end())
    var i = start
    while (true) {
      val compare = ret.brailleToTextPositions().get(i)
      if (compare > textIdx) {
        break
      }
      end = i + 1
      i++
    }
    if (end > ret.cells().size()) {
      return ret
    }
    val sub = ret.cells().subword(start, end)
    BrailleCommonLog.d(TAG, "checkHypon:" + sub + ":" + Objects.equals(sub.toString(), TILDE))
    word.append(BrailleWord(HYPHEN))
    BrailleCommonLog.d(
        TAG,
        "replacement:" + p.group() + " ret:" + ret.cells() + " start:" + p.start() + " end:" + end)
    return TranslationResult.correctTranslation(
        ret, word, p.start(), ret.brailleToTextPositions().get(end))
  }

  private fun replaceSymbol(ret: TranslationResult, dots: String, compare: String): TranslationResult {
    var result = ret
    val regexPattern = Pattern.compile(compare)
    val p: Matcher = regexPattern.matcher(result.text())
    var openAndCloseFlagForSymbol = 1
    while (p.find()) {
      val replacement = p.group()
      val brailleWord = BrailleWord()
      if (Objects.equals(replacement, "'")) {
        if ((openAndCloseFlagForSymbol % 2) == 1) {
          brailleWord.append(BrailleWord(LEFT_SINGLE_QUOTE))
        } else {
          brailleWord.append(BrailleWord(RIGHT_SINGLE_QUOTE))
        }
        openAndCloseFlagForSymbol++
      } else if ("\"".contentEquals(replacement)) {
        if ((openAndCloseFlagForSymbol % 2) == 1) {
          brailleWord.append(BrailleWord(LEFT_DOUBLE_QUOTE))
        } else {
          brailleWord.append(BrailleWord(RIGHT_DOUBLE_QUOTE))
        }
        openAndCloseFlagForSymbol++
      } else {
        brailleWord.append(BrailleWord(dots))
      }
      BrailleCommonLog.d(
          TAG,
          "replacement:"
              + replacement
              + " brailleWord:"
              + brailleWord
              + " start:"
              + p.start()
              + " end:"
              + p.end())
      result = TranslationResult.correctTranslation(result, brailleWord, p.start(), p.end())
    }
    return result
  }

  private fun customizeWord(ret: TranslationResult): TranslationResult {
    val original = ret.cells().toString()
    var result = replaceWord(ret, "그래서", "4-246-5-1235-6-234", "1-234")
    result = replaceWord(result, "그러나", "4-246-5-234-14", "1-14")
    result = replaceWord(result, "그러면", "4-246-5-234-15-16", "1-25")
    result = replaceWord(result, "그러므로", "4-246-5-234-15-246-5-136", "1-26")
    result = replaceWord(result, "그런데", "4-246-5-23456-24-1345", "1-1345")
    result = replaceWord(result, "그리고", "4-246-5-135-4-136", "1-136")
    result = replaceWord(result, "그리하여", "4-246-5-135-245-126-156", "1-156")
    BrailleCommonLog.d(TAG, "customizeWord:" + !original.equals(result.cells().toString()))
    return result
  }

  private fun replaceWord(
      ret: TranslationResult, compare: String, normalDots: String, contractedDots: String
  ): TranslationResult {
    var result = ret
    val regexPattern = Pattern.compile(compare)
    val p: Matcher = regexPattern.matcher(result.text())
    while (p.find()) {
      val replacement = p.group()
      val brailleWord = BrailleWord()
      if (shouldContracted(result, p.start())) {
        brailleWord.append(BrailleWord(contractedDots))
      } else {
        brailleWord.append(BrailleWord(normalDots))
      }
      BrailleCommonLog.d(
          TAG,
          "replacement:"
              + replacement
              + " brailleWord:"
              + brailleWord
              + " start:"
              + p.start()
              + " end:"
              + p.end())
      result = TranslationResult.correctTranslation(result, brailleWord, p.start(), p.end())
    }
    return result
  }

  private fun shouldContracted(ret: TranslationResult, start: Int): Boolean {
    val result =
        (start == 0) || " ".contentEquals(ret.text().subSequence(start - 1, start))
    BrailleCommonLog.d(TAG, "shouldContracted:" + result)
    return result
  }

  private fun textToBrailleLog(ret: TranslationResult) {
    val temp = StringBuilder()
    for (position in ret.textToBraillePositions()) {
      temp.append(position).append("-")
    }
    BrailleCommonLog.d(TAG, "tTob:" + temp)
  }

  private fun brailleToTextLog(ret: TranslationResult) {
    val temp2 = StringBuilder()
    for (position in ret.brailleToTextPositions()) {
      temp2.append(position).append("-")
    }
    BrailleCommonLog.d(TAG, "bTot:" + temp2)
  }

  private fun customizeDoubleFinal(ret: TranslationResult): TranslationResult {
    var result = ret
    val original = result.cells().toString()
    val regexPattern = Pattern.compile("[겄껐팠]")
    val p: Matcher = regexPattern.matcher(result.text())
    while (p.find()) {
      val replacement = p.group()
      BrailleCommonLog.d(TAG, "replacement:" + replacement)
      val brailleWord = BrailleWord()
      if ("겄".contentEquals(replacement)) {
        brailleWord.append(BrailleWord(GEOSS))
      } else if ("껐".contentEquals(replacement)) {
        brailleWord.append(BrailleWord(IR_DAN))
      } else {
        brailleWord.append(BrailleWord("145-126-34"))
      }
      BrailleCommonLog.d(TAG, "customizeDoubleFinal:" + !original.equals(result.cells().toString()))
      result = TranslationResult.correctTranslation(result, brailleWord, p.start(), p.end())
    }
    return result
  }

  // This api is for "ㅑ,ㅘ,ㅜ,ㅝ"+"36"+"애"
  // When "ㅑ,ㅘ,ㅜ,ㅝ" is followed by ‘애’, it is indicated by putting "36" in between it.
  private fun customizeConnect2(ret: TranslationResult): TranslationResult {
    var result = ret
    val original = result.cells().toString()
    val regexPattern = Pattern.compile("[애]")
    val ye: Matcher = regexPattern.matcher(result.text())
    while (ye.find()) {
      val replacement = ye.group()
      BrailleCommonLog.d(TAG, "replacement:" + replacement)
      if (!needConnect2(result, ye.start())) {
        continue
      }
      val brailleWord = BrailleWord()
      brailleWord.append(BrailleCharacter(HYPHEN))
      appendBrailleWord(result, brailleWord, ye.start(), ye.end())
      result = TranslationResult.correctTranslation(result, brailleWord, ye.start(), ye.end())
    }
    BrailleCommonLog.d(TAG, "customizeConnect:" + !original.equals(result.cells().toString()))
    return result
  }

  private fun needConnect2(ret: TranslationResult, start: Int): Boolean {
    var brailleStart = 0
    var brailleEnd = 0
    var index = 0
    for (idx in ret.textToBraillePositions()) {
      if (index + 1 == start) {
        brailleStart = idx
      } else if (index == start) {
        brailleEnd = idx
        break
      }
      index++
    }
    BrailleCommonLog.d(TAG, "brailleStart:" + brailleStart + " brailleEnd:" + brailleEnd)
    if (brailleStart >= brailleEnd) {
      BrailleCommonLog.d(TAG, "needConnect2:" + false)
      return false
    }
    val word = BrailleWord(ret.cells().subword(brailleStart, brailleEnd))
    BrailleCommonLog.d(TAG, "word:" + word)
    val pos = word.size()
    val result = pos > 0 && isSpecialConsonants(word.get(pos - 1).toString())
    BrailleCommonLog.d(TAG, "needConnect2:" + result)
    return result
  }

  private fun isSpecialConsonants(dots: String): Boolean {
    return Objects.equals(dots, "345") // ㅝ
        || Objects.equals(dots, "1236") // ㅑ
        || Objects.equals(dots, "134") // ㅘ
        || Objects.equals(dots, "1234") // ㅜ
  }

  // This api is for 모음+"36"+"예"
  // When a vowel letter is followed by ‘예’, it is indicated by putting "36" in between it.
  private fun customizeConnect(ret: TranslationResult): TranslationResult {
    var result = ret
    val original = result.cells().toString()
    val regexPattern = Pattern.compile("[예]")
    val ye: Matcher = regexPattern.matcher(result.text())
    while (ye.find()) {
      val replacement = ye.group()
      BrailleCommonLog.d(TAG, "replacement:" + replacement)
      if (!needConnect(result, ye.start())) {
        continue
      }
      val brailleWord = BrailleWord()
      brailleWord.append(BrailleCharacter(HYPHEN))
      appendBrailleWord(result, brailleWord, ye.start(), ye.end())
      result = TranslationResult.correctTranslation(result, brailleWord, ye.start(), ye.end())
    }
    BrailleCommonLog.d(TAG, "customizeConnect:" + !original.equals(result.cells().toString()))
    return result
  }

  private fun needConnect(ret: TranslationResult, start: Int): Boolean {
    var brailleStart = 0
    var brailleEnd = 0
    var index = 0
    for (idx in ret.textToBraillePositions()) {
      if (index + 1 == start) {
        brailleStart = idx
      } else if (index == start) {
        brailleEnd = idx
        break
      }
      index++
    }
    BrailleCommonLog.d(TAG, "brailleStart:" + brailleStart + " brailleEnd:" + brailleEnd)
    if (brailleStart >= brailleEnd) {
      return false
    }
    val word = BrailleWord(ret.cells().subword(brailleStart, brailleEnd))
    BrailleCommonLog.d(TAG, "word:" + word)
    val pos = word.size()
    val result =
        pos > 0 && BrailleTranslateUtilsKorean.isVowelConsonants(word.get(pos - 1).toString())
    BrailleCommonLog.d(TAG, "needConnect:" + result)
    return result
  }

  private fun customizeFullDot(ret: TranslationResult): TranslationResult {
    var result = ret
    val original = result.cells().toString()
    val regexPattern = Pattern.compile("[ㄱ-ㅎㅏ-ㅣ]")
    val jamo: Matcher = regexPattern.matcher(result.text())
    while (jamo.find()) {
      val replacement = jamo.group()
      BrailleCommonLog.d(TAG, "replacement:" + replacement)
      val brailleWord = BrailleWord()
      brailleWord.append(BrailleCharacter(ONG))
      appendBrailleWord(result, brailleWord, jamo.start(), jamo.end())
      if (needSpace(result, replacement, jamo.end())) {
        BrailleCommonLog.d(TAG, "append empty")
        brailleWord.append(BrailleCharacter())
      }
      BrailleCommonLog.d(TAG, "customizeFullDot:" + !original.equals(result.cells().toString()))
      result = TranslationResult.correctTranslation(result, brailleWord, jamo.start(), jamo.end())
    }
    return result
  }

  private fun needSpace(ret: TranslationResult, replacement: CharSequence, end: Int): Boolean {
    if (BrailleTranslateUtilsKorean.isVowelHangul(replacement.toString())) {
      return false
    }
    val text = ret.text()
    if (end + 1 > text.length) {
      return false
    }
    val sub = text.subSequence(end, end + 1)
    if (TextUtils.isEmpty(sub)) {
      return false
    }
    BrailleCommonLog.d(
        TAG, "sub:" + sub + ":" + " ".contentEquals(sub.toString()) + ":" + sub.length)
    return !Objects.equals(sub.toString(), " ")
  }

  private fun customizeAlphabet(ret: TranslationResult): TranslationResult {
    var result = ret
    val original = result.cells().toString()
    val alphabetMap = findAlphabet(result.text())
    for (start in alphabetMap.keys) {
      val end = alphabetMap[start]!!
      val replacement: CharSequence = result.text().subSequence(start, end)
      BrailleCommonLog.d(TAG, "replacement:" + replacement)
      val brailleWord = BrailleWord()
      if (needStartSymbol(result, start)) {
        brailleWord.append(BrailleCharacter(RIGHT_DOUBLE_QUOTE))
      }
      appendBrailleWord(result, brailleWord, start, end)
      if (needEndSymbol(result, end)) {
        brailleWord.append(BrailleCharacter(PERIOD))
      }
      BrailleCommonLog.d(TAG, "customizeAlphabet:" + !original.equals(result.cells().toString()))
      result = TranslationResult.correctTranslation(result, brailleWord, start, end)
    }
    return result
  }

  private fun needStartSymbol(ret: TranslationResult, start: Int): Boolean {
    var idx = start - 1
    var subtext = ""
    while (idx > 0) {
      subtext = ret.text().toString().substring(idx, idx + 1)
      if (!Objects.equals(subtext, " ")) {
        break
      }
      BrailleCommonLog.d(TAG, "sub:" + subtext)
      idx--
    }
    if (Pattern.matches("[a-zA-Z]", subtext)) {
      BrailleCommonLog.d(TAG, "needEndSymbol false sub:" + subtext)
      return false
    }
    val pos = ret.textToBraillePositions().get(start)
    val result = !Objects.equals(ret.cells().get(pos).toString(), CH)
    BrailleCommonLog.d(TAG, "needStartSymbol:" + result)
    return result
  }

  private fun needEndSymbol(ret: TranslationResult, end: Int): Boolean {
    val text = ret.text()
    var idx = end + 1
    var subtext = ""
    while (idx < text.length) {
      subtext = text.toString().substring(idx, idx + 1)
      if (!Objects.equals(subtext, " ")) {
        break
      }
      idx++
    }
    if (Pattern.matches("[a-zA-Z]", subtext)) {
      BrailleCommonLog.d(TAG, "needEndSymbol false sub:" + subtext)
      return false
    }
    var result = false
    if (end < text.length) {
      result = !Pattern.matches("^[0-9]*$", text.subSequence(end, end + 1).toString())
    }
    BrailleCommonLog.d(TAG, "needEndSymbol:" + result)
    return result
  }

  private fun appendBrailleWord(
      ret: TranslationResult, newWord: BrailleWord, start: Int, end: Int
  ) {
    val word = BrailleWord(ret.cells())
    if (ret.text().length == end) {
      newWord.append(word)
    } else {
      val brailleStart = ret.textToBraillePositions().get(start)
      val brailleEnd = ret.textToBraillePositions().get(end)
      newWord.append(word.subword(brailleStart, brailleEnd))
    }
    BrailleCommonLog.d(TAG, "newWord:" + newWord)
  }

  private fun findAlphabet(ret: CharSequence): Map<Int, Int> {
    val map = HashMap<Int, Int>()
    if (TextUtils.isEmpty(ret)) {
      return map
    }
    val text = ret.toString()
    var firstIdx = -1
    for (i in 0 until text.length) {
      val c = text[i]
      if (('A' <= c && c <= 'Z') || ('a' <= c && c <= 'z')) {
        if (firstIdx == -1) {
          firstIdx = i
        }
      } else {
        if (firstIdx > -1) {
          BrailleCommonLog.d(TAG, "firstIdx:" + firstIdx + " end:" + i)
          map[firstIdx] = i
          firstIdx = -1
        }
      }
    }
    return map
  }

  companion object {
    private const val TAG = "KoreanTranslationResultCustomizer"
    private const val CODE_POINT_OF_WON = 8361
    private const val WON = "4-2456"
    private const val COMMA_SYMBOL = "2"
    private const val SLASH = "3456"
    private const val HYPHEN = "36"
    private const val TILDE = "36-36"
    private const val LEFT_SINGLE_QUOTE = "6-236"
    private const val RIGHT_SINGLE_QUOTE = "356-3"
    private const val LEFT_DOUBLE_QUOTE = "236"
    private const val RIGHT_DOUBLE_QUOTE = "356"
    private const val GEOSS = "4-234-34" // 겄
    private const val IR_DAN = "6-4-234-34" // 껐
    private const val CH = "56" // ㅊ
    private const val ONG = "123456" // 옹
    private const val PERIOD = "256"
  }
}
