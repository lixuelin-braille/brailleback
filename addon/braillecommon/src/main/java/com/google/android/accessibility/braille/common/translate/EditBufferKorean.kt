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
import android.text.TextUtils
import android.view.KeyEvent
import com.google.android.accessibility.braille.common.BrailleCommonLog
import com.google.android.accessibility.braille.common.BrailleCommonUtils
import com.google.android.accessibility.braille.common.ImeConnection
import com.google.android.accessibility.braille.common.R
import com.google.android.accessibility.braille.common.TalkBackSpeaker
import com.google.android.accessibility.braille.interfaces.BrailleCharacter
import com.google.android.accessibility.braille.interfaces.BrailleWord
import com.google.android.accessibility.braille.translate.BrailleTranslator
import com.google.errorprone.annotations.CanIgnoreReturnValue
import java.text.Normalizer
import java.text.Normalizer.Form.NFC
import java.text.Normalizer.Form.NFD
import java.util.Objects

/** An EditBuffer for Korean Braille. */
class EditBufferKorean(
  private val context: Context,
  brailleTranslator: BrailleTranslator,
  private val talkBack: TalkBackSpeaker,
) : EditBufferUnContracted(context, brailleTranslator, talkBack) {

  private val previousResultMap = HashMap<String, String>()
  private var numericMode = false

  init {
    BrailleTranslateUtilsKorean.loadCompletedHangul(context)
  }

  @CanIgnoreReturnValue
  override fun appendBraille(
    imeConnection: ImeConnection, brailleCharacter: BrailleCharacter
  ): String {
    holdings.append(brailleCharacter)
    if (getNumeric()!!.equals(brailleCharacter)) {
      numericMode = true
      BrailleCommonLog.d(TAG, "numericMode = true by getNumeric().equals(brailleCharacter)")
    }
    val speak = getSpeakableAnnouncement(brailleCharacter)
    talkBack.speak(speak, TalkBackSpeaker.AnnounceType.INTERRUPT_AND_UNINTERRUPTIBLE_BY_NEW_SPEECH)
    val result = getAppendResult(brailleCharacter)
    var input = ""
    if (!TextUtils.isEmpty(result)) {
      BrailleTranslateUtilsKorean.checkSpaceNumberAndCharBeforeCommit(imeConnection, result)
      input = BrailleTranslateUtilsKorean.generateToInputText(result)
      imeConnection.inputConnection.setComposingText(input, input.length)
      previousResultMap.put(holdings.toString(), result)
    }
    lastCommitIndexOfHoldings = holdings.size()
    return input
  }

  override fun deleteCharacterBackward(imeConnection: ImeConnection) {
    if (holdings.isEmpty()) {
      BrailleCommonUtils.performKeyAction(imeConnection.inputConnection, KeyEvent.KEYCODE_DEL)
      return
    }
    val deleted = holdings.get(holdings.size() - 1)
    holdings.remove(holdings.size() - 1)
    val speak = getSpeakableAnnouncement(deleted)
    if (!TextUtils.isEmpty(speak)) {
      talkBack.speak(
          context.getString(R.string.read_out_deleted, speak),
          TalkBackSpeaker.AnnounceType.INTERRUPT_AND_UNINTERRUPTIBLE_BY_NEW_SPEECH)
    }
    if (!holdings.isEmpty()) {
      val b = holdings.get(holdings.size() - 1)
      val result = getAppendResult(b)
      if (result != null) {
        val input = BrailleTranslateUtilsKorean.generateToInputText(result)
        imeConnection.inputConnection.setComposingText(input, input.length)
      }
    } else {
      BrailleCommonUtils.performKeyAction(imeConnection.inputConnection, KeyEvent.KEYCODE_DEL)
    }
    lastCommitIndexOfHoldings = holdings.size()
  }

  private fun getAppendResult(brailleCharacter: BrailleCharacter): String {
    val fullWord: BrailleWord = holdings
    val prevWord: BrailleWord =
        if (fullWord.size() > 1) fullWord.subword(0, fullWord.size() - 1) else BrailleWord()
    val prevTrans: String? =
        if (prevWord.isEmpty()) "" else previousResultMap[prevWord.toString()]
    val lastDot =
        if (!fullWord.isEmpty() && !prevWord.isEmpty())
            fullWord.get(prevWord.size() - 1).toString()
        else ""
    val inputDot = brailleCharacter.toString()
    BrailleCommonLog.d(TAG, "prevTrans:$prevTrans / inputDot:$inputDot")
    if (numericMode && (inputDot.contains("3") || inputDot.contains("6"))) {
      numericMode = BrailleTranslateUtilsKorean.isAvailableSymbolWithNumeric(lastDot, inputDot)
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

  private fun appendSyllable(
    current: StringBuilder, lastChar: String, lastDot: String, syllable: String
  ) {
    // This case: 10-3ㅏ >> 10-나
    if (BrailleTranslateUtilsKorean.isNumber(lastChar) &&
        BrailleTranslateUtilsKorean.isInitialConsonants(lastDot) &&
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

  private fun appendDoubleSpecial(current: StringBuilder, lastDot: String, inputDot: String) {
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

  private fun isSpecialOTrans(lastChar: String, lastDot: String, currentDot: String): Boolean {
    var result =
        BrailleTranslateUtilsKorean.isVowelConsonants(lastDot)
            || (BrailleTranslateUtilsKorean.isFinalConsonants(lastDot) &&
                BrailleTranslateUtilsKorean.isFinalHangul(lastChar))
            || // case: 99.9%
            // 1. except for 10-3아
            // 2. 등자, 덩어리
            (BrailleTranslateUtilsKorean.isNumberDot(lastDot) &&
                BrailleTranslateUtilsKorean.isNumber(lastChar) &&
                !Objects.equals(currentDot, "126"))
    result = result && BrailleTranslateUtilsKorean.isVowelConsonants(currentDot)
    if (BrailleTranslateUtilsKorean.isVowelConsonants(lastDot) &&
        Objects.equals(currentDot, "34")) {
      result = false; // final "ㅆ", it is not "예"
    }
    if (Objects.equals(lastDot, "36") && Objects.equals(currentDot, "34")) {
      result = true; // It is "예" after vowel
    }
    if (Objects.equals(lastDot, "356") && Objects.equals(currentDot, "1234")) { // case: 99.9%
      result = false; // It is %, it is not "워"
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

  override fun appendSpace(imeConnection: ImeConnection) {
    numericMode = false
    BrailleCommonLog.d(TAG, "numericMode = false by appendSpace")
    checkLastDotForCommaSymbol(imeConnection)
    checkLastDotForSymbol(imeConnection)
    super.appendSpace(imeConnection)
  }

  override fun appendNewline(imeConnection: ImeConnection) {
    if (EditBufferUtils.isMultiLineField(imeConnection.editorInfo.inputType)) {
      checkLastDotForSymbol(imeConnection)
    }
    super.appendNewline(imeConnection)
  }

  private fun checkLastDotForCommaSymbol(imeConnection: ImeConnection) {
    if (holdings.isEmpty()) {
      return
    }
    val lastDot = holdings.get(holdings.size() - 1).toString()
    if (Objects.equals(lastDot, "5")) {
      var prevString = previousResultMap[holdings.toString()]
      if (!TextUtils.isEmpty(prevString)) {
        prevString =
            prevString!!.replace(BrailleTranslateUtilsKorean.getInitialConsonants("5")!!, ",")
        imeConnection.inputConnection.setComposingText(prevString!!, prevString!!.length)
        previousResultMap.put(holdings.toString(), prevString!!)
      }
    }
  }

  private fun checkLastDotForSymbol(imeConnection: ImeConnection) {
    if (holdings.isEmpty()) {
      return
    }
    val lastDot = holdings.get(holdings.size() - 1).toString()
    var prevString = previousResultMap[holdings.toString()]
    if (TextUtils.isEmpty(prevString)) {
      prevString = ""
    }
    val current = StringBuilder(Normalizer.normalize(prevString, NFD))
    if (BrailleTranslateUtilsKorean.shouldChangeToSymbol(current, lastDot, "")) {
      val result =
          BrailleTranslateUtilsKorean.changeToSymbolAboutLastChar(current, lastDot, "")
      current.append(result!!.replace(" ", ""))
      imeConnection.inputConnection.setComposingText(current.toString(), current.length)
      previousResultMap.put(holdings.toString(), current.toString())
    }
  }

  fun getSpeakableAnnouncement(brailleCharacter: BrailleCharacter): String? {
    if (BrailleTranslateUtilsKorean.isInitialConsonants(brailleCharacter.toString()) ||
        BrailleTranslateUtilsKorean.isVowelConsonants(brailleCharacter.toString()) ||
        BrailleTranslateUtilsKorean.isFinalConsonants(brailleCharacter.toString())) {
      return ""
    }
    var textToSpeak =
        getAppendBrailleTextToSpeak(context.getResources(), brailleCharacter).orElse(null)
    if (TextUtils.isEmpty(textToSpeak)) {
      textToSpeak = BrailleTranslateUtils.getDotsText(context.getResources(), brailleCharacter)
    }
    return textToSpeak
  }

  private val exceptionalCase =
      object : KoreanExceptionalCase {
        override fun getStringAtFirstTime(
          fullWord: BrailleWord, lastDot: String, inputDot: String
        ): String? {
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
          } else if (BrailleTranslateUtilsKorean.isOpenSymbolTranslation(inputDot)) {
            result = BrailleTranslateUtilsKorean.getOpenSymbolTranslation(inputDot)
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
          BrailleCommonLog.d(TAG, "getSyllable inputDot: $inputDot")
          if (TextUtils.isEmpty(inputDot)) {
            return " "; // appendSpace
          }
          var result = BrailleTranslateUtilsKorean.getInitialConsonants(inputDot)
          // We should check final at first than vowel, because of "ㅆ" and "ㅖ"
          if (TextUtils.isEmpty(result)) {
            result = BrailleTranslateUtilsKorean.getFinalConsonants(inputDot)
            // "ㅆ" cannot come with "ㄱ","ㅅ","ㄹ","ㅊ", so "ㅖ" is correct, it is not "ㅆ"
            if ((Objects.equals(lastDot, "4") ||
                    Objects.equals(lastDot, "6") ||
                    Objects.equals(lastDot, "5") || // 김정ㄹㅆ >> 김정례
                    Objects.equals(lastDot, "56")) &&
                Objects.equals(inputDot, "34")) {
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
            current.append(
                Normalizer.normalize(BrailleTranslateUtilsKorean.getNumber(inputDot), NFD))
          } else if (
            BrailleTranslateUtilsKorean.isCircleNumberDot(
                BrailleTranslateUtilsKorean.combineDots(lastDot, inputDot))) {
            current.append(
                Normalizer.normalize(
                    BrailleTranslateUtilsKorean.getCircleNumber(
                        BrailleTranslateUtilsKorean.combineDots(lastDot, inputDot)),
                    NFD))
            numericMode = false
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
          } else if (
            BrailleTranslateUtilsKorean.isDoubleInitConsonant(lastDot, inputDot)) {
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
                      BrailleTranslateUtilsKorean.appendSymbol(current, lastDot, inputDot), NFD))
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
    private const val TAG = "EditBufferKorean"
  }
}
