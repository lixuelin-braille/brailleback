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
package com.google.android.accessibility.braille.common.translate

import android.content.Context
import android.content.res.Resources
import android.text.TextUtils
import androidx.annotation.VisibleForTesting
import com.google.android.accessibility.braille.common.BrailleCommonLog
import com.google.android.accessibility.braille.common.ImeConnection
import com.google.android.accessibility.braille.common.TalkBackSpeaker
import com.google.android.accessibility.braille.interfaces.BrailleCharacter
import com.google.android.accessibility.braille.interfaces.BrailleWord
import com.google.android.accessibility.braille.translate.BrailleTranslator
import com.google.android.accessibility.braille.translate.TranslationResult
import com.google.common.collect.ImmutableMap
import java.text.Normalizer
import java.text.Normalizer.Form.NFC
import java.text.Normalizer.Form.NFD
import java.util.Objects

/** Korean Braille Grade 2 EditBuffer. */
class EditBufferKorean2(
  context: Context,
  translator: BrailleTranslator,
  talkBack: TalkBackSpeaker,
) : EditBufferContracted(context, Korean2BrailleTranslator(translator), talkBack) {

  private val koreanContext: Context = context
  private val exceptionalCase = KoreanContractedExceptionalCase()
  private var appendBrailleState = false
  private var deleteWordState = false

  init {
    (this.translator as Korean2BrailleTranslator).setExceptionalCase(exceptionalCase)
    previousResultMap.clear()
    BrailleTranslateUtilsKorean.loadCompletedHangul(context)
  }

  override fun fillTranslatorMaps(
    initialCharacterTranslationMap: MutableMap<String, String>,
    nonInitialCharacterTranslationMap: MutableMap<String, String>,
  ) {
    // Do nothing.
  }

  override fun appendBraille(
    imeConnection: ImeConnection, brailleCharacter: BrailleCharacter
  ): String {
    appendBrailleState = true
    if (getNumeric()!!.equals(brailleCharacter)) {
      exceptionalCase.setNumericMode(true)
      BrailleCommonLog.d(TAG, "numericMode = true by getNumeric().equals(brailleCharacter)")
    }
    return super.appendBraille(imeConnection, brailleCharacter)!!
  }

  override fun deleteCharacterBackward(imeConnection: ImeConnection) {
    appendBrailleState = false
    super.deleteCharacterBackward(imeConnection)
  }

  override fun deleteWord(imeConnection: ImeConnection) {
    deleteWordState = true
    super.deleteWord(imeConnection)
    deleteWordState = false
  }

  override fun checkSpaceBetweenNumberAndChar(
    imeConnection: ImeConnection, appendedText: String
  ) {
    BrailleTranslateUtilsKorean.checkSpaceNumberAndCharBeforeCommit(imeConnection, appendedText)
  }

  override fun getAnnouncement(
    resources: Resources,
    translator: BrailleTranslator,
    brailleWord: BrailleWord,
    index: Int,
  ): String {
    if (deleteWordState) {
      return if (index == 0)
          BrailleTranslateUtilsKorean.generateToInputText(
              previousResultMap[brailleWord.toString()]!!)
        else ""
    }
    var result = getAppendResult(brailleWord, exceptionalCase)
    if (!TextUtils.isEmpty(result)) {
      previousResultMap[brailleWord.toString()] = result
    } else {
      val textToSpeak = getTextToSpeak(koreanContext.getResources(), brailleWord.get(index))
      if (!textToSpeak.isEmpty()) {
        return textToSpeak
      }
      return BrailleTranslateUtils.getDotsText(
          koreanContext.getResources(), brailleWord.get(index))
    }
    result = getAnnouncement(brailleWord, index, appendBrailleState)
    return BrailleTranslateUtilsKorean.generateToInputText(result)
  }

  private fun getAnnouncement(brailleWord: BrailleWord, index: Int, append: Boolean): String {
    val prevWord: BrailleWord? = if (index > 0) brailleWord.subword(0, index) else null
    val fullWord = brailleWord.subword(0, brailleWord.size())
    var prevStr = if (prevWord == null) "" else previousResultMap[prevWord.toString()]
    var fullStr = previousResultMap[fullWord.toString()]
    if (prevStr == null) {
      prevStr = ""
    }
    if (fullStr == null) {
      fullStr = ""
    }
    if (!append) {
      prevStr = Normalizer.normalize(prevStr, NFD)
      fullStr = Normalizer.normalize(fullStr, NFD)
    } else {
      return Normalizer.normalize(fullStr, NFC)
    }
    var result = findCommonString(prevStr!!, fullStr!!)
    if (TextUtils.isEmpty(result)) {
      result = BrailleTranslateUtils.getDotsText(
          koreanContext.getResources(), brailleWord.get(index))
    }
    return Normalizer.normalize(result, NFC)
  }

  override fun appendSpace(imeConnection: ImeConnection) {
    exceptionalCase.setNumericMode(false)
    BrailleCommonLog.d(TAG, "numericMode = false by appendSpace")
    super.appendSpace(imeConnection)
  }

  override fun appendNewline(imeConnection: ImeConnection) {
    (translator as Korean2BrailleTranslator)
        .appendedNewLine(imeConnection.editorInfo.inputType)
    super.appendNewline(imeConnection)
  }

  override fun isLetter(character: Char): Boolean {
    return false
  }

  override fun getCapitalize(): BrailleCharacter? {
    // TODO: May distinguish between this because cap indicator only appear after roman
    // indicator.
    return null
  }

  override fun getNumeric(): BrailleCharacter? {
    return BrailleTranslateUtils.DOTS3456
  }

  override fun forceInitialDefaultTranslation(dotsNumber: String): Boolean {
    return false
  }

  override fun forceNonInitialDefaultTranslation(dotsNumber: String): Boolean {
    return false
  }

  private fun findCommonString(prevString: String, fullString: String): String {
    val prev = StringBuilder(if (TextUtils.isEmpty(prevString)) "" else prevString)
    val full = StringBuilder(if (TextUtils.isEmpty(fullString)) "" else fullString)
    var index = 0
    var i = 0
    while (i < prev.length && i < full.length) {
      if (prev[i] != full[i]) {
        break
      }
      i++
      index++
    }
    return full.substring(index)
  }

  /** translateToPrint() is overridden only for Korean */
  @VisibleForTesting
  class Korean2BrailleTranslator(
    private val brailleTranslator: BrailleTranslator,
  ) : BrailleTranslator {
    private var appendNewLine = false
    private var exceptionalCase: KoreanContractedExceptionalCase? = null

    fun setExceptionalCase(exceptionalCase: KoreanContractedExceptionalCase) {
      this.exceptionalCase = exceptionalCase
    }

    fun appendedNewLine(inputType: Int) {
      if (EditBufferUtils.isMultiLineField(inputType)) {
        appendNewLine = true
      }
    }

    override fun translateToPrint(word: BrailleWord): String {
      if (word.isEmpty()) {
        return ""
      }
      if (appendNewLine) {
        checkLastDotForSymbol(word)
        appendNewLine = false
      }
      var result: String? = previousResultMap[word.toString()]
      if (TextUtils.isEmpty(result)) {
        result = getAppendResult(word, exceptionalCase!!)
      }
      BrailleCommonLog.d(TAG, "translateForCommit: $result")
      return result!!
    }

    override fun translateToPrintPartial(brailleWord: BrailleWord): String {
      return brailleTranslator.translateToPrintPartial(brailleWord)
    }

    override fun translate(text: CharSequence, cursorPosition: Int): TranslationResult {
      return brailleTranslator.translate(text, cursorPosition)
    }
  }

  class KoreanContractedExceptionalCase : KoreanExceptionalCase {
    private var numericMode = false

    fun setNumericMode(numericMode: Boolean) {
      this.numericMode = numericMode
    }

    fun isNumericMode(): Boolean {
      return numericMode
    }

    override fun getStringAtFirstTime(
      fullWord: BrailleWord, lastDot: String, inputDot: String
    ): String? {
      BrailleCommonLog.d(TAG, "getStringAtFirstTime")
      val result: String?
      if (numericMode && BrailleTranslateUtilsKorean.isNumberDot(inputDot)) {
        result = BrailleTranslateUtilsKorean.getNumber(inputDot)
      } else if (
        BrailleTranslateUtilsKorean.isCircleNumberDot(
            BrailleTranslateUtilsKorean.combineDots(lastDot, inputDot))) {
        result =
            BrailleTranslateUtilsKorean.getCircleNumber(
                BrailleTranslateUtilsKorean.combineDots(lastDot, inputDot))
        numericMode = false
      } else if (BrailleTranslateUtilsKorean.isDoubleFirstSymbol(lastDot, inputDot)) {
        result =
            BrailleTranslateUtilsKorean.getOpenSymbolTranslation(
                BrailleTranslateUtilsKorean.combineDots(lastDot, inputDot))
      } else if (isAbbreviationWord(fullWord, lastDot, inputDot)) {
        result =
            ABBREVIATION_WORD[BrailleTranslateUtilsKorean.combineDots(lastDot, inputDot)]
      } else if (BrailleTranslateUtilsKorean.isOpenSymbolTranslation(inputDot)) {
        result = BrailleTranslateUtilsKorean.getOpenSymbolTranslation(inputDot)
      } else if (isSpecialAbbreviationCharacters(inputDot)) {
        result = SPECIAL_ABBREVIATION_CHARS[inputDot]
      } else if (isSpecialAbbreviationVowels(inputDot)) {
        result = SPECIAL_ABBREVIATION_VOWELS[inputDot]
      } else if (BrailleTranslateUtilsKorean.isSpecialOTranslation(inputDot)) {
        result = BrailleTranslateUtilsKorean.getSpecialOTranslation(inputDot)
      } else {
        result = getSyllable("", "", inputDot)
      }
      BrailleCommonLog.d(
          TAG,
          "getStringAtFirstTime numericMode:"
              + numericMode
              + " by "
              + inputDot
              + " result-"
              + result)
      return result
    }

    override fun getSyllable(
      lastChar: String, lastDot: String, inputDot: String
    ): String? {
      BrailleCommonLog.d(
          TAG,
          "getSyllable lastChar:" + lastChar + ":lastDot:" + lastDot + ":inputDot:" + inputDot)
      if (TextUtils.isEmpty(inputDot)) {
        return " "; // appendSpace
      }
      var result = BrailleTranslateUtilsKorean.getInitialConsonants(inputDot)
      // We should check final at first than vowel, because of "ㅆ" and "ㅖ"
      if (TextUtils.isEmpty(result)) {
        result = BrailleTranslateUtilsKorean.getFinalConsonants(inputDot)
        // "ㅆ" cannot come with "ㄱ","ㅅ","ㄹ","ㅊ","ㅍ","ㅎ" so "ㅖ" is correct, it is not "ㅆ"
        if ((Objects.equals(lastDot, "4")
                || Objects.equals(lastDot, "6")
                || Objects.equals(lastDot, "5")
                || // 김정ㄹㅆ >> 김정례
                Objects.equals(lastDot, "56")
                || Objects.equals(lastDot, "145")
                || Objects.equals(lastDot, "245"))
            && Objects.equals(inputDot, "34")) {
          result = ""
        }
        // If final consonants is came after number or space, it is not confirmed
        if (TextUtils.isEmpty(lastChar) || TextUtils.isEmpty(lastDot) ||
            BrailleTranslateUtilsKorean.isNumber(lastChar)) {
          result = ""
        }
      }
      if (TextUtils.isEmpty(result)) {
        result = BrailleTranslateUtilsKorean.getVowelConsonants(inputDot)
      }
      val ret = if (TextUtils.isEmpty(result)) "" else result
      BrailleCommonLog.d(TAG, "getSyllable: $ret")
      return ret
    }

    override fun processExceptionalCase(
      current: StringBuilder,
      fullWord: BrailleWord,
      lastChar: String,
      lastDot: String,
      inputDot: String,
    ) {
      if (numericMode && BrailleTranslateUtilsKorean.isNumberDot(inputDot)) {
        appendNumber(current, lastDot, inputDot)
      } else if (
        BrailleTranslateUtilsKorean.isCircleNumberDot(
            BrailleTranslateUtilsKorean.combineDots(lastDot, inputDot))) {
        current.append(
            Normalizer.normalize(
                BrailleTranslateUtilsKorean.getCircleNumber(
                    BrailleTranslateUtilsKorean.combineDots(lastDot, inputDot)),
                NFD))
        numericMode = false
      } else if (isDoubleFinalWithAbbreviationVowel(current, lastChar, lastDot, inputDot)) {
        appendDoubleFinalWithAbbreviationVowel(current, lastChar, inputDot)
      } else if (isAbbreviationVowel(lastDot, inputDot) &&
          !BrailleTranslateUtilsKorean.isNumber(lastChar)) {
        appendAbbreviationVowel(current, lastChar, inputDot)
      } else if (isDoubleSpecialAbbreviationCharacters(lastDot, inputDot)) { // 까, 싸
        if (current.length > 0) {
          current.deleteCharAt(current.length - 1)
        }
        current.append(
            Normalizer.normalize(
                SPECIAL_ABBREVIATION_CHARS[
                    BrailleTranslateUtilsKorean.combineDots(lastDot, inputDot)],
                NFD))
      } else if (isSpecialAbbreviationCharacters(inputDot)) { // 가, 사
        if (BrailleTranslateUtilsKorean.isInitHangul(lastChar)) {
          current.append(Normalizer.normalize("ᅡ", NFD))
        }
        current.append(
            Normalizer.normalize(SPECIAL_ABBREVIATION_CHARS[inputDot], NFD))
      } else if (isAbbreviationChar(lastDot, inputDot) &&
          !BrailleTranslateUtilsKorean.isNumber(lastChar)) {
        if (BrailleTranslateUtilsKorean.isInitHangul(lastChar)) {
          current.append(Normalizer.normalize("ᅡ", NFD))
        }
        processExceptionalCase(current, fullWord, "ᅡ", "126", inputDot)
      } else if (isSpecialAbbreviationVowels(inputDot)) {
        current.append(
            Normalizer.normalize(SPECIAL_ABBREVIATION_VOWELS[inputDot], NFD))
      } else if (isAbbreviationWord(fullWord, lastDot, inputDot)) {
        appendAbbreviationWord(current, lastChar, lastDot, inputDot)
      } else if (
        BrailleTranslateUtilsKorean.isSpecialOWithTwoBrailles(current, lastDot, inputDot)) {
        appendDoubleSpecial(current, lastDot, inputDot)
      } else if (
        BrailleTranslateUtilsKorean.isDoubleVowelConsonant(current, lastDot, inputDot)) {
        if (current.length > 0) {
          current.deleteCharAt(current.length - 1)
        }
        current.append(
            Normalizer.normalize(
                BrailleTranslateUtilsKorean.getVowelConsonants(
                    BrailleTranslateUtilsKorean.combineDots(lastDot, inputDot)),
                NFD))
      } else if (
        BrailleTranslateUtilsKorean.isDoubleFinalConsonant(current, lastDot, inputDot)) {
        if (current.length > 0) {
          current.deleteCharAt(current.length - 1)
        }
        current.append(
            Normalizer.normalize(
                BrailleTranslateUtilsKorean.getFinalConsonants(
                    BrailleTranslateUtilsKorean.combineDots(lastDot, inputDot)),
                NFD))
      } else if (BrailleTranslateUtilsKorean.isDoubleInitConsonant(lastDot, inputDot)) {
        if (current.length > 0) {
          current.deleteCharAt(current.length - 1)
        }
        current.append(
            Normalizer.normalize(
                BrailleTranslateUtilsKorean.getInitialConsonants(
                    BrailleTranslateUtilsKorean.combineDots(lastDot, inputDot)),
                NFD))
      } else if (isSpecialOTrans(lastChar, lastDot, inputDot)) {
        current.append(
            Normalizer.normalize(
                BrailleTranslateUtilsKorean.getSpecialOTranslation(inputDot), NFD))
      } else {
        val syllable = getSyllable(lastChar, lastDot, inputDot)
        if (TextUtils.isEmpty(syllable)) {
          current.append(
              Normalizer.normalize(
                  BrailleTranslateUtilsKorean.appendSymbol(current, lastDot, inputDot),
                  NFD))
        } else {
          if (BrailleTranslateUtilsKorean.isFinalHangul(lastChar) &&
              BrailleTranslateUtilsKorean.isFinalHangul(syllable!!)) {
            if (current.length > 0) {
              current.deleteCharAt(current.length - 1)
            }
            current.append(Normalizer.normalize(lastChar + syllable, NFC))
          } else {
            appendSyllable(current, lastChar, lastDot, syllable!!)
          }
          BrailleTranslateUtilsKorean.convertToSymbol(current, syllable!!, lastDot, inputDot)
        }
      }
    }
  }

  companion object {
    const val TAG = "EditBufferKorean2"
    val previousResultMap = HashMap<String, String>()
    val ABBREVIATION_VOWELS: ImmutableMap<String, String> =
        ImmutableMap.builder<String, String>()
            .put("1456", "ᅥᆨ")
            .put("23456", "ᅥᆫ")
            .put("2345", "ᅥᆯ")
            .put("16", "ᅧᆫ")
            .put("1256", "ᅧᆯ")
            .put("12456", "ᅧᆼ")
            .put("1346", "ᅩᆨ")
            .put("12356", "ᅩᆫ")
            .put("123456", "ᅩᆼ")
            .put("1245", "ᅮᆫ")
            .put("12346", "ᅮᆯ")
            .put("1356", "ᅳᆫ")
            .put("2346", "ᅳᆯ")
            .put("12345", "ᅵᆫ")
            .buildOrThrow()
    val COMBINE_FINAL_MAP: ImmutableMap<String, String> =
        ImmutableMap.builder<String, String>()
            .put("ᆨᆨ", "ᆩ")
            .put("ᆨᆺ", "ᆪ")
            .put("ᆫᆽ", "ᆬ")
            .put("ᆫᇂ", "ᆭ")
            .put("ᆯᆨ", "ᆰ")
            .put("ᆯᆷ", "ᆱ")
            .put("ᆯᆸ", "ᆲ")
            .put("ᆯᆺ", "ᆳ")
            .put("ᆯᇀ", "ᆴ")
            .put("ᆯᇁ", "ᆵ")
            .put("ᆯᇂ", "ᆶ")
            .buildOrThrow()
    val SPECIAL_ABBREVIATION_VOWELS: ImmutableMap<String, String> =
        ImmutableMap.builder<String, String>()
            .put("1456", "억")
            .put("23456", "언")
            .put("2345", "얼")
            .put("16", "연")
            .put("1256", "열")
            .put("12456", "영")
            .put("1346", "옥")
            .put("12356", "온")
            .put("123456", "옹")
            .put("1245", "운")
            .put("12346", "울")
            .put("1356", "은")
            .put("2346", "을")
            .put("12345", "인")
            .buildOrThrow()
    val SPECIAL_ABBREVIATION_CHARS: ImmutableMap<String, String> =
        ImmutableMap.builder<String, String>()
            .put("1246", "가")
            .put("123", "사")
            .put("6-1246", "까")
            .put("6-123", "싸")
            .buildOrThrow()
    val ABBREVIATION_CHARACTERS: ImmutableMap<String, String> =
        ImmutableMap.builder<String, String>()
            .put("14", "나")
            .put("24", "다")
            .put("15", "마")
            .put("45", "바")
            .put("46", "자")
            .put("124", "카")
            .put("125", "타")
            .put("145", "파")
            .put("245", "하")
            .buildOrThrow()
    val ABBREVIATION_WORD: ImmutableMap<String, String> =
        ImmutableMap.builder<String, String>()
            .put("456-234", "것")
            .put("1-234", "그래서")
            .put("1-14", "그러나")
            .put("1-25", "그러면")
            .put("1-26", "그러므로")
            .put("1-1345", "그런데")
            .put("1-136", "그리고")
            .put("1-156", "그리하여")
            .buildOrThrow()

    fun checkLastDotForSymbol(word: BrailleWord) {
      if (word.isEmpty()) {
        return
      }
      val lastDot = word.get(word.size() - 1).toString()
      var prevString = previousResultMap[word.toString()]
      if (TextUtils.isEmpty(prevString)) {
        prevString = ""
      }
      val current = StringBuilder(Normalizer.normalize(prevString, NFD))
      if (BrailleTranslateUtilsKorean.shouldChangeToSymbol(current, lastDot, "")) {
        val result =
            BrailleTranslateUtilsKorean.changeToSymbolAboutLastChar(current, lastDot, "")
        current.append(result!!.replace(" ", ""))
        previousResultMap[word.toString()] = current.toString()
      }
    }

    fun getAppendResult(
      fullWord: BrailleWord, exceptionalCase: KoreanContractedExceptionalCase
    ): String {
      val prevWord: BrailleWord =
          if (fullWord.size() > 1) fullWord.subword(0, fullWord.size() - 1) else BrailleWord()
      val prevTrans: String? =
          if (prevWord.isEmpty()) "" else previousResultMap[prevWord.toString()]
      val lastDot =
          if (!fullWord.isEmpty() && !prevWord.isEmpty())
              fullWord.get(prevWord.size() - 1).toString()
          else ""
      val inputDot =
          if (!fullWord.isEmpty()) fullWord.get(fullWord.size() - 1).toString() else ""
      BrailleCommonLog.d(TAG, "prevTrans:$prevTrans / inputDot:$inputDot")
      // If "3" or "6" dot is inserted, this dots are not numeric. It is symbol or character
      // So we need to check this what is meaning
      if (exceptionalCase.isNumericMode() &&
          (inputDot.contains("3") || inputDot.contains("6"))) {
        exceptionalCase.setNumericMode(
            BrailleTranslateUtilsKorean.isAvailableSymbolWithNumeric(lastDot, inputDot))
      }
      var result: String? = ""
      if (TextUtils.isEmpty(prevTrans)) {
        previousResultMap.clear()
        result = exceptionalCase.getStringAtFirstTime(fullWord, lastDot, inputDot)
      } else {
        result =
            BrailleTranslateUtilsKorean.correctIfPossible(
                fullWord, prevTrans!!, lastDot, inputDot, exceptionalCase)
      }
      BrailleCommonLog.d(TAG, "result: $result")
      if (result == null) {
        result = ""
      }
      return Normalizer.normalize(result, NFC)
    }

    fun isSpecialAbbreviationCharacters(dot: String): Boolean {
      val result = SPECIAL_ABBREVIATION_CHARS.containsKey(dot)
      BrailleCommonLog.d(TAG, "isSpecialAbbreviationCharacters: $result")
      return result
    }

    // 억, 언, 얼, 연, 열, 영, 옥, 온, 옹, 운, 울, 은, 을, 인
    fun isSpecialAbbreviationVowels(dot: String): Boolean {
      val result = SPECIAL_ABBREVIATION_VOWELS.containsKey(dot)
      BrailleCommonLog.d(TAG, "isSpecialAbbreviationVowels by $dot: $result")
      return result
    }

    fun appendNumber(current: StringBuilder, lastDot: String, inputDot: String) {
      var lastChar = Normalizer.normalize(current, NFC)
      lastChar = lastChar.substring(lastChar.length - 1)
      if (Objects.equals(lastChar, "연") &&
          Objects.equals(lastDot, "16") &&
          Objects.equals(inputDot, "3456")) {
        // Remove "연"
        BrailleTranslateUtilsKorean.removeLastString(current)
        current.append(Normalizer.normalize("×", NFD))
      } else if (Objects.equals(lastChar, "옜") &&
          Objects.equals(lastDot, "34") &&
          Objects.equals(inputDot, "3456")) {
        // Remove "옜"
        BrailleTranslateUtilsKorean.removeLastString(current)
        current.append(Normalizer.normalize("÷", NFD))
      }
      current.append(
          Normalizer.normalize(BrailleTranslateUtilsKorean.getNumber(inputDot), NFD))
    }

    fun appendSyllable(
      current: StringBuilder, lastChar: String, lastDot: String, syllable: String
    ) {
      // This case: 10-3ㅏ >> 10-나
      if (BrailleTranslateUtilsKorean.isNumber(lastChar) &&
          ABBREVIATION_CHARACTERS.containsKey(lastDot) &&
          Objects.equals(syllable, "ᅡ")) {
        if (current.length > 0) {
          current.deleteCharAt(current.length - 1)
        }
        current.append(
            Normalizer.normalize(
                BrailleTranslateUtilsKorean.getInitialConsonants(lastDot), NFD))
      }
      current.append(Normalizer.normalize(syllable, NFD))
    }

    fun appendDoubleSpecial(current: StringBuilder, lastDot: String, inputDot: String) {
      BrailleTranslateUtilsKorean.convertStringToNfc(current)
      if (current.length > 0) {
        current.deleteCharAt(current.length - 1)
      }
      BrailleTranslateUtilsKorean.convertStringToNfd(current)
      current.append(
          Normalizer.normalize(
              BrailleTranslateUtilsKorean.getSpecialOTranslation(
                  BrailleTranslateUtilsKorean.combineDots(lastDot, inputDot)),
              NFD))
    }

    fun appendDoubleFinalWithAbbreviationVowel(
      current: StringBuilder, lastChar: String, inputDot: String
    ) {
      if (current.length > 0) {
        current.deleteCharAt(current.length - 1)
      }
      val finalCons = BrailleTranslateUtilsKorean.getFinalConsonants(inputDot)
      current.append(
          Normalizer.normalize(COMBINE_FINAL_MAP[lastChar + finalCons], NFD))
    }

    fun isDoubleFinalWithAbbreviationVowel(
      current: StringBuilder, lastChar: String, lastDot: String, inputDot: String
    ): Boolean {
      var result =
          ABBREVIATION_VOWELS.containsKey(lastDot) &&
              BrailleTranslateUtilsKorean.isFinalConsonants(inputDot) &&
              COMBINE_FINAL_MAP.containsKey(
                  lastChar + BrailleTranslateUtilsKorean.getFinalConsonants(inputDot))
      if (result) {
        val temp = StringBuilder(current)
        if (temp.length > 0) {
          temp.deleteCharAt(temp.length - 1)
        }
        val finalCons = BrailleTranslateUtilsKorean.getFinalConsonants(inputDot)
        temp.append(Normalizer.normalize(COMBINE_FINAL_MAP[lastChar + finalCons], NFD))
        val tempStr = Normalizer.normalize(temp.toString(), NFC)
        result = BrailleTranslateUtilsKorean.isCompletedHangul(
            tempStr.substring(tempStr.length - 1))
        BrailleCommonLog.d(
            TAG, "isDoubleFinalWithAbbreviationVowel double check tempStr:$tempStr $result")
      }
      BrailleCommonLog.d(
          TAG, "isDoubleFinalWithAbbreviationVowel by $inputDot $result")
      return result
    }

    fun appendAbbreviationWord(
      current: StringBuilder, lastChar: String, lastDot: String, inputDot: String
    ) {
      val whole = BrailleTranslateUtilsKorean.combineDots(lastDot, inputDot)
      var result = Normalizer.normalize(ABBREVIATION_WORD[whole], NFD)
      if (Objects.equals(lastDot, "456")) {
        BrailleCommonLog.d(TAG, "lastChar $lastChar")
        if (Objects.equals(lastChar, "ᄉ")) {
          if (current.length > 0) {
            current.deleteCharAt(current.length - 1)
          }
          result = Normalizer.normalize("껏", NFD)
        }
      } else if (Objects.equals(lastDot, "1")) {
        if (current.length > 0) {
          current.deleteCharAt(current.length - 1)
        }
      }
      current.append(result)
      BrailleCommonLog.d(TAG, "appendAbbreviationWord by $whole: $result")
    }

    fun appendAbbreviationVowel(
      current: StringBuilder, lastChar: String, inputDot: String
    ) {
      if ((Objects.equals(lastChar, "ᄉ") ||
              Objects.equals(lastChar, "ᄊ") ||
              Objects.equals(lastChar, "ᄌ") ||
              Objects.equals(lastChar, "ᄍ") ||
              Objects.equals(lastChar, "ᄎ")) &&
          Objects.equals(inputDot, "12456")) {
        current.append(Normalizer.normalize("ᅥᆼ", NFD))
      } else {
        current.append(
            Normalizer.normalize(ABBREVIATION_VOWELS[inputDot], NFD))
      }
    }

    fun isDoubleSpecialAbbreviationCharacters(lastDot: String, currentDot: String): Boolean {
      val whole = BrailleTranslateUtilsKorean.combineDots(lastDot, currentDot)
      val result = SPECIAL_ABBREVIATION_CHARS.containsKey(whole)
      BrailleCommonLog.d(
          TAG, "isDoubleSpecialAbbreviationCharacters by $whole: $result")
      return result
    }

    fun isSpecialOTrans(lastChar: String, lastDot: String, currentDot: String): Boolean {
      var result =
          BrailleTranslateUtilsKorean.isVowelConsonants(lastDot) ||
              Objects.equals(lastChar, "ᅡ") || // 회사ㅔ >> 회사에
              isSpecialAbbreviationVowels(lastDot) ||
              BrailleTranslateUtilsKorean.isFinalConsonants(lastDot) ||
              // 1. except for 10-3아
              // 2. 등자, 덩어리
              (BrailleTranslateUtilsKorean.isNumberDot(lastDot) &&
                  BrailleTranslateUtilsKorean.isNumber(lastChar) &&
                  !Objects.equals(currentDot, "126"))
      result = result && BrailleTranslateUtilsKorean.isVowelConsonants(currentDot)
      // vowel + "34" = vowel + "ㅆ"
      if ((BrailleTranslateUtilsKorean.isVowelHangul(lastChar) ||
              BrailleTranslateUtilsKorean.isVowelConsonants(lastDot)) &&
          Objects.equals(currentDot, "34")) {
        result = false // final "ㅆ", it is not "예"
      }
      if (Objects.equals(lastDot, "36") && Objects.equals(currentDot, "34")) {
        result = true // It is "예" after vowel
      }
      if (Objects.equals(lastDot, "356") && Objects.equals(currentDot, "1234")) { // case: 99.9%
        result = false // It is %, it is not "워"
      }
      BrailleCommonLog.d(
          TAG,
          "isSpecialOTrans lastChar:"
              + lastChar
              + " lastDot:"
              + lastDot
              + " currentDot:"
              + currentDot
              + " result:"
              + result)
      return result
    }

    fun isAbbreviationVowel(lastDot: String, currentDot: String): Boolean {
      val result =
          !Objects.equals(lastDot, "1245") && // 신문ㅡㄴ >> 신문은
              BrailleTranslateUtilsKorean.isInitialConsonants(lastDot) &&
              ABBREVIATION_VOWELS.containsKey(currentDot)
      BrailleCommonLog.d(
          TAG, "isAbbreviationVowel lastDot:$lastDot currentDot:$currentDot $result")
      return result
    }

    fun isAbbreviationWord(
      fullWord: BrailleWord, lastDot: String, currentDot: String
    ): Boolean {
      val whole = BrailleTranslateUtilsKorean.combineDots(lastDot, currentDot)
      var result = ABBREVIATION_WORD.containsKey(whole)
      if (result && Objects.equals(lastDot, "1")) {
        result = fullWord.size() == 2
      }
      BrailleCommonLog.d(TAG, "isAbbreviationWord by $whole: $result")
      return result
    }

    fun isAbbreviationChar(lastDot: String, currentDot: String): Boolean {
      var result = false
      if (ABBREVIATION_CHARACTERS.containsKey(lastDot) &&
          (TextUtils.isEmpty(currentDot) ||
              BrailleTranslateUtilsKorean.isFinalConsonants(currentDot) ||
              BrailleTranslateUtilsKorean.isInitialConsonants(currentDot) ||
              Objects.equals(currentDot, "36"))) { // 다예, 나예, 따예
        result = true
      }
      if (result && Objects.equals(currentDot, "34") &&
          (Objects.equals(lastDot, "145") || Objects.equals(lastDot, "245"))) {
        result = false
      }
      BrailleCommonLog.d(
          TAG, "isAbbreviationChar lastDot:$lastDot currentDot:$currentDot $result")
      return result
    }
  }
}
