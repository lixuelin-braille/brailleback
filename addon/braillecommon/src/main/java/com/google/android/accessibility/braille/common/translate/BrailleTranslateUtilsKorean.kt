package com.google.android.accessibility.braille.common.translate

import java.text.Normalizer.Form.NFC
import java.text.Normalizer.Form.NFD

import android.content.Context
import android.content.res.AssetManager
import android.text.TextUtils
import android.view.KeyEvent
import com.google.android.accessibility.braille.common.BrailleCommonLog
import com.google.android.accessibility.braille.common.BrailleCommonUtils
import com.google.android.accessibility.braille.common.ImeConnection
import com.google.android.accessibility.braille.interfaces.BrailleWord
import com.google.common.collect.ImmutableMap
import com.google.common.collect.ImmutableSet
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.text.Normalizer
import java.util.Objects
import java.util.regex.Pattern

/** Utils for translation of Korean Braille. */
class BrailleTranslateUtilsKorean private constructor() {

  companion object {
    private const val TAG = "BrailleTranslateUtilsKorean"
    private const val HYPHEN = "-"
    private const val INIT_CONSONANT_START_CHARACTER: Char = 0x1100.toChar()
    private const val VOWEL_CONSONANT_START_CHARACTER: Char = 0x1161.toChar()
    private const val FINAL_CONSONANT_START_CHARACTER: Char = 0x11A8.toChar()
    private val completedHangul = ArrayList<String>()
    private val EXCEPT_FROM_COMPLETED_HANGUL: ImmutableSet<String> =
        ImmutableSet.of(
            "엌", "읔", "같", "곁", "깥", "꼍", "낱", "돝", "릍", "뭍", "샅", "앝", "얕", "옅", "읕", "짙", "홑", "흩",
            "갚", "겊", "깊", "높", "닢", "덮", "랖", "릎", "붚", "섶", "싶", "엎", "읖", "짚", "톺")
    // This map is for converting pronunciation from initial cons. to used alone
    private val INPUT_TEXT_MAP_FOR_SPEAKING: ImmutableMap<String, String> =
        ImmutableMap.builder<String, String>()
            .put("ᄀ", "ㄱ")
            .put("ᄁ", "ㄲ")
            .put("ᄂ", "ㄴ")
            .put("ᄃ", "ㄷ")
            .put("ᄄ", "ㄸ")
            .put("ᄅ", "ㄹ")
            .put("ᄆ", "ㅁ")
            .put("ᄇ", "ㅂ")
            .put("ᄈ", "ㅃ")
            .put("ᄉ", "ㅅ")
            .put("ᄊ", "ㅆ")
            .put("ᄋ", "ㅇ")
            .put("ᄌ", "ㅈ")
            .put("ᄍ", "ㅉ")
            .put("ᄎ", "ㅊ")
            .put("ᄏ", "ㅋ")
            .put("ᄐ", "ㅌ")
            .put("ᄑ", "ㅍ")
            .put("ᄒ", "ㅎ")
            .buildOrThrow()

    private val INITIAL_CONSONANTS_TRANSLATION_MAP: ImmutableMap<String, String> =
        ImmutableMap.builder<String, String>()
            .put("4", INIT_CONSONANT_START_CHARACTER.toString()) // ㄱ
            .put("6-4", (INIT_CONSONANT_START_CHARACTER + 1).toString()) // ㄲ
            .put("14", (INIT_CONSONANT_START_CHARACTER + 2).toString()) // ㄴ
            .put("24", (INIT_CONSONANT_START_CHARACTER + 3).toString()) // ㄷ
            .put("6-24", (INIT_CONSONANT_START_CHARACTER + 4).toString()) // ㄸ
            .put("5", (INIT_CONSONANT_START_CHARACTER + 5).toString()) // ㄹ
            .put("15", (INIT_CONSONANT_START_CHARACTER + 6).toString()) // ㅁ
            .put("45", (INIT_CONSONANT_START_CHARACTER + 7).toString()) // ㅂ
            .put("6-45", (INIT_CONSONANT_START_CHARACTER + 8).toString()) // ㅃ
            .put("6", (INIT_CONSONANT_START_CHARACTER + 9).toString()) // ㅅ
            .put("6-6", (INIT_CONSONANT_START_CHARACTER + 10).toString()) // ㅆ
            .put("1245", (INIT_CONSONANT_START_CHARACTER + 11).toString()) // ㅇ
            .put("46", (INIT_CONSONANT_START_CHARACTER + 12).toString()) // ㅈ
            .put("6-46", (INIT_CONSONANT_START_CHARACTER + 13).toString()) // ㅉ
            .put("56", (INIT_CONSONANT_START_CHARACTER + 14).toString()) // ㅊ
            .put("124", (INIT_CONSONANT_START_CHARACTER + 15).toString()) // ㅋ
            .put("125", (INIT_CONSONANT_START_CHARACTER + 16).toString()) // ㅌ
            .put("145", (INIT_CONSONANT_START_CHARACTER + 17).toString()) // ㅍ
            .put("245", (INIT_CONSONANT_START_CHARACTER + 18).toString()) // ㅎ
            .buildOrThrow()

    private val VOWEL_TRANSLATION_MAP: ImmutableMap<String, String> =
        ImmutableMap.builder<String, String>()
            .put("126", VOWEL_CONSONANT_START_CHARACTER.toString()) // ㅏ
            .put("1235", (VOWEL_CONSONANT_START_CHARACTER + 1).toString()) // ㅐ
            .put("345", (VOWEL_CONSONANT_START_CHARACTER + 2).toString()) // ㅑ
            .put("345-1235", (VOWEL_CONSONANT_START_CHARACTER + 3).toString()) // ㅒ
            .put("234", (VOWEL_CONSONANT_START_CHARACTER + 4).toString()) // ㅓ
            .put("1345", (VOWEL_CONSONANT_START_CHARACTER + 5).toString()) // ㅔ
            .put("156", (VOWEL_CONSONANT_START_CHARACTER + 6).toString()) // ㅕ
            .put("34", (VOWEL_CONSONANT_START_CHARACTER + 7).toString()) // ㅖ
            .put("136", (VOWEL_CONSONANT_START_CHARACTER + 8).toString()) // ㅗ
            .put("1236", (VOWEL_CONSONANT_START_CHARACTER + 9).toString()) // ㅘ
            .put("1236-1235", (VOWEL_CONSONANT_START_CHARACTER + 10).toString()) // ㅙ
            .put("13456", (VOWEL_CONSONANT_START_CHARACTER + 11).toString()) // ㅚ
            .put("346", (VOWEL_CONSONANT_START_CHARACTER + 12).toString()) // ㅛ
            .put("134", (VOWEL_CONSONANT_START_CHARACTER + 13).toString()) // ㅜ
            .put("1234", (VOWEL_CONSONANT_START_CHARACTER + 14).toString()) // ㅝ
            .put("1234-1235", (VOWEL_CONSONANT_START_CHARACTER + 15).toString()) // ㅞ
            .put("134-1235", (VOWEL_CONSONANT_START_CHARACTER + 16).toString()) // ㅟ
            .put("146", (VOWEL_CONSONANT_START_CHARACTER + 17).toString()) // ㅠ
            .put("246", (VOWEL_CONSONANT_START_CHARACTER + 18).toString()) // ㅡ
            .put("2456", (VOWEL_CONSONANT_START_CHARACTER + 19).toString()) // ㅢ
            .put("135", (VOWEL_CONSONANT_START_CHARACTER + 20).toString()) // ㅣ
            .buildOrThrow()
    private val FINAL_CONSONANTS_TRANSLATION_MAP: ImmutableMap<String, String> =
        ImmutableMap.builder<String, String>()
            .put("1", FINAL_CONSONANT_START_CHARACTER.toString()) // ㄱ
            .put("1-1", (FINAL_CONSONANT_START_CHARACTER + 1).toString()) // ㄲ
            .put("1-3", (FINAL_CONSONANT_START_CHARACTER + 2).toString()) // ㄳ
            .put("25", (FINAL_CONSONANT_START_CHARACTER + 3).toString()) // ㄴ
            .put("25-13", (FINAL_CONSONANT_START_CHARACTER + 4).toString()) // ㄵ
            .put("25-356", (FINAL_CONSONANT_START_CHARACTER + 5).toString()) // ㄶ
            .put("35", (FINAL_CONSONANT_START_CHARACTER + 6).toString()) // ㄷ
            .put("2", (FINAL_CONSONANT_START_CHARACTER + 7).toString()) // ㄹ
            .put("2-1", (FINAL_CONSONANT_START_CHARACTER + 8).toString()) // ㄺ
            .put("2-26", (FINAL_CONSONANT_START_CHARACTER + 9).toString()) // ㄻ
            .put("2-12", (FINAL_CONSONANT_START_CHARACTER + 10).toString()) // ㄼ
            .put("2-3", (FINAL_CONSONANT_START_CHARACTER + 11).toString()) // ㄽ
            .put("2-236", (FINAL_CONSONANT_START_CHARACTER + 12).toString()) // ㄾ
            .put("2-256", (FINAL_CONSONANT_START_CHARACTER + 13).toString()) // ㄿ
            .put("2-356", (FINAL_CONSONANT_START_CHARACTER + 14).toString()) // ㅀ
            .put("26", (FINAL_CONSONANT_START_CHARACTER + 15).toString()) // ㅁ
            .put("12", (FINAL_CONSONANT_START_CHARACTER + 16).toString()) // ㅂ
            .put("12-3", (FINAL_CONSONANT_START_CHARACTER + 17).toString()) // ㅄ
            .put("3", (FINAL_CONSONANT_START_CHARACTER + 18).toString()) // ㅅ
            .put("34", (FINAL_CONSONANT_START_CHARACTER + 19).toString()) // ㅆ
            .put("2356", (FINAL_CONSONANT_START_CHARACTER + 20).toString()) // ㅇ
            .put("13", (FINAL_CONSONANT_START_CHARACTER + 21).toString()) // ㅈ
            .put("23", (FINAL_CONSONANT_START_CHARACTER + 22).toString()) // ㅊ
            .put("235", (FINAL_CONSONANT_START_CHARACTER + 23).toString()) // ㅋ
            .put("236", (FINAL_CONSONANT_START_CHARACTER + 24).toString()) // ㅌ
            .put("256", (FINAL_CONSONANT_START_CHARACTER + 25).toString()) // ㅍ
            .put("356", (FINAL_CONSONANT_START_CHARACTER + 26).toString()) // ㅎ
            .buildOrThrow()
    // If vowel is coming at first, it should be added "ㅇ" consonant
    private val SPECIAL_O_TRANSLATION_MAP: ImmutableMap<String, String> =
        ImmutableMap.builder<String, String>()
            .put("126", "아")
            .put("345", "야")
            .put("234", "어")
            .put("156", "여")
            .put("136", "오")
            .put("346", "요")
            .put("134", "우")
            .put("146", "유")
            .put("246", "으")
            .put("135", "이")
            .put("36-1235", "애")
            .put("1235", "애")
            .put("1345", "에")
            .put("345-1235", "얘")
            .put("34", "예")
            .put("36-34", "예")
            .put("1236", "와")
            .put("1236-1235", "왜")
            .put("13456", "외")
            .put("2456", "의")
            .put("1234", "워")
            .put("1234-1235", "웨")
            .put("134-1235", "위")
            .buildOrThrow()
    private val OPEN_SYMBOL_TRANSLATION_MAP: ImmutableMap<String, String> =
        ImmutableMap.builder<String, String>()
            .put("236", "“")
            .put("6-236", "'")
            .put("236-3", "(")
            .put("236-2", "{")
            .put("236-23", "[")
            .put("5-236", "<")
            .put("56-236", "《")
            .put("456-236", "?")
            .put("456-34", "/")
            .put("4-2456", "￦")
            .put("4-145", "$")
            .put("26", "+")
            .put("35", "-")
            .put("25-25", "=")
            .put("36-36", "~")
            .put("36", "-")
            .put("235", "!")
            .put("256", ".")
            .buildOrThrow()
    private val CLOSE_SYMBOL_TRANSLATION_MAP: ImmutableMap<String, String> =
        ImmutableMap.builder<String, String>()
            .put("356", "”")
            .put("356-3", "'")
            .put("6-356", ")")
            .put("5-356", "}")
            .put("56-356", "]")
            .put("356-2", ">")
            .put("356-23", "》")
            .buildOrThrow()
    // For contracted Korean braille only.
    private val CHANGE_SYMBOL_TRANSLATION_MAP: ImmutableMap<String, String> =
        ImmutableMap.builder<String, String>()
            .put("256-", ". ")
            .put("236-", "? ")
            .put("235-", "!")
            .buildOrThrow()
    private val SYMBOL_TRANSLATION_MAP: ImmutableMap<String, String> =
        ImmutableMap.builder<String, String>()
            .put("356-1234", "%")
            .put("26", "+")
            .put("35", "-")
            .put("16", "×")
            .put("34-34", "÷")
            .put("25-25", "=")
            .put("36-36", "~")
            .put("36", "-")
            .put("6-6-6", "···")
            .put("235", "!")
            .put("256", ".")
            .put("5-23", "·")
            .put("2", ",")
            .put("5", ",")
            .put("5-", ", ")
            .put("236", "?")
            .put("456-236", "?")
            .put("5-2", ":")
            .put("56-23", ";")
            .put("35-35", "※")
            .put("456-34", "/")
            .buildOrThrow()
    private val NUMBER_TRANSLATION_MAP: ImmutableMap<String, String> =
        ImmutableMap.builder<String, String>()
            .put("3456", "")
            .put("1", "1")
            .put("12", "2")
            .put("14", "3")
            .put("145", "4")
            .put("15", "5")
            .put("124", "6")
            .put("1245", "7")
            .put("125", "8")
            .put("24", "9")
            .put("245", "0")
            .buildOrThrow()
    private val CIRCLE_NUMBER_TRANSLATION_MAP: ImmutableMap<String, String> =
        ImmutableMap.builder<String, String>()
            .put("3456-2", "①")
            .put("3456-23", "②")
            .put("3456-25", "③")
            .put("3456-256", "④")
            .put("3456-26", "⑤")
            .put("3456-235", "⑥")
            .put("3456-2356", "⑦")
            .put("3456-236", "⑧")
            .put("3456-35", "⑨")
            .buildOrThrow()

    @JvmStatic
    fun combineDots(firstDot: String, secondDot: String): String {
      return firstDot + HYPHEN + secondDot
    }

    @JvmStatic
    fun isAvailableSymbolWithNumeric(lastDot: String, inputDot: String): Boolean {
      var result = false
      if (!Objects.equals(lastDot, "36") && Objects.equals(inputDot, "36")) { // symbol "-" is keeping number, not "~"
        result = true
      } else if (Objects.equals(lastDot, "5") && Objects.equals(inputDot, "23")) { // symbol "·"
        result = true
      } else if (Objects.equals(inputDot, "256")) { // symbol "."
        result = true
      } else if (Objects.equals(inputDot, "3456")) { // "3456" as number start
        result = true
      } else if (Objects.equals(lastDot, "3456") && isNumberDot(inputDot)) { // This case is circle number
        result = true
      }
      BrailleCommonLog.d(
          TAG,
          "isAvailableSymbolWithNumeric by lastDot:"
              + lastDot
              + " inputDot:"
              + inputDot
              + " "
              + result)
      return result
    }

    @JvmStatic
    fun isNumberDot(dot: String): Boolean {
      return NUMBER_TRANSLATION_MAP.containsKey(dot)
    }

    @JvmStatic
    fun getNumber(dot: String): String? {
      return NUMBER_TRANSLATION_MAP[dot]
    }

    @JvmStatic
    fun isCircleNumberDot(dot: String): Boolean {
      return CIRCLE_NUMBER_TRANSLATION_MAP.containsKey(dot)
    }

    @JvmStatic
    fun getCircleNumber(dot: String): String? {
      return CIRCLE_NUMBER_TRANSLATION_MAP[dot]
    }

    @JvmStatic
    fun isInitialConsonants(dot: String): Boolean {
      return INITIAL_CONSONANTS_TRANSLATION_MAP.containsKey(dot)
    }

    @JvmStatic
    fun isVowelConsonants(dot: String): Boolean {
      return VOWEL_TRANSLATION_MAP.containsKey(dot)
    }

    @JvmStatic
    fun isFinalConsonants(dot: String): Boolean {
      return FINAL_CONSONANTS_TRANSLATION_MAP.containsKey(dot)
    }

    @JvmStatic
    fun isSpecialOTranslation(dot: String): Boolean {
      return SPECIAL_O_TRANSLATION_MAP.containsKey(dot)
    }

    @JvmStatic
    fun isOpenSymbolTranslation(dot: String): Boolean {
      return OPEN_SYMBOL_TRANSLATION_MAP.containsKey(dot)
    }

    @JvmStatic
    fun getSpecialOTranslation(dot: String): String? {
      return SPECIAL_O_TRANSLATION_MAP[dot]
    }

    @JvmStatic
    fun getOpenSymbolTranslation(dot: String): String? {
      return OPEN_SYMBOL_TRANSLATION_MAP[dot]
    }

    @JvmStatic
    fun getInitialConsonants(dot: String): String? {
      return INITIAL_CONSONANTS_TRANSLATION_MAP[dot]
    }

    @JvmStatic
    fun getVowelConsonants(dot: String): String? {
      return VOWEL_TRANSLATION_MAP[dot]
    }

    @JvmStatic
    fun getFinalConsonants(dot: String): String? {
      return FINAL_CONSONANTS_TRANSLATION_MAP[dot]
    }

    @JvmStatic
    fun loadCompletedHangul(context: Context) {
      completedHangul.clear()
      val assetManager: AssetManager = context.assets
      try {
        val inputStream: InputStream =
            assetManager.open("completed_hangul.txt", AssetManager.ACCESS_BUFFER)
        val bufferedReader = BufferedReader(InputStreamReader(inputStream))
        var line = bufferedReader.readLine()
        while (line != null) {
          completedHangul.add(line)
          line = bufferedReader.readLine()
        }
      } catch (e: IOException) {
        BrailleCommonLog.e(TAG, "IOException: $e")
      }
    }

    @JvmStatic
    fun isNumber(lastInput: String?): Boolean {
      val ret: Boolean
      if (TextUtils.isEmpty(lastInput)) {
        ret = false
      } else {
        ret = Pattern.matches("^[#0-9]*$", lastInput!!)
      }
      BrailleCommonLog.d(TAG, "isNumber by $lastInput: $ret")
      return ret
    }

    /** Converts arg to input text for input. */
    @JvmStatic
    fun generateToInputText(result: String): String {
      if (TextUtils.isEmpty(result)) {
        return ""
      }
      val input = StringBuilder()
      var temp: String?
      for (i in 0 until result.length) {
        val ch = result[i]
        // This is first consonant range - 0x1100 : "ᄀ" , 0x115E: "ᄒ"
        temp =
            if (ch.code in 0x1100..0x115E) INPUT_TEXT_MAP_FOR_SPEAKING[ch.toString()]
            else ch.toString()
        input.append(temp)
      }
      return input.toString()
    }

    @JvmStatic
    fun isDuplicatedDot(currentDot: String): Boolean {
      val result =
          Objects.equals(currentDot, "235")
              || // ㅋ or !
              Objects.equals(currentDot, "236")
              || // ㅌ or ?
              Objects.equals(currentDot, "256")
              || // ㅍ or .
              Objects.equals(currentDot, "356") // ㅎ or "
      BrailleCommonLog.d(TAG, "isDuplicatedDot by $currentDot: $result")
      return result
    }

    @JvmStatic
    fun appendSymbol(current: StringBuilder, lastDot: String, currentDot: String): String? {
      var result: String? = ""
      if (shouldChangeToSymbol(current, lastDot, currentDot)) {
        result = changeToSymbolAboutLastChar(current, lastDot, currentDot)
      } else if (isDoubleFirstSymbol(lastDot, currentDot)) {
        result = appendDoubleFirstSymbol(current, lastDot, currentDot)
      } else if (isDoubleMiddleSymbol(lastDot, currentDot)) {
        result = appendDoubleMiddleSymbol(current, lastDot, currentDot)
      } else if (isDoubleFinalSymbol(lastDot, currentDot)) {
        if (current.length > 0) {
          current.deleteCharAt(current.length - 1)
        }
        result = CLOSE_SYMBOL_TRANSLATION_MAP[combineDots(lastDot, currentDot)]
      } else if (SYMBOL_TRANSLATION_MAP.containsKey(currentDot)) {
        result = SYMBOL_TRANSLATION_MAP[currentDot]
      } else if (CLOSE_SYMBOL_TRANSLATION_MAP.containsKey(currentDot)) {
        result = CLOSE_SYMBOL_TRANSLATION_MAP[currentDot]
      }
      if (Objects.equals(Normalizer.normalize(current.toString(), NFC), "연")
          && Objects.equals(lastDot, "16")
          && TextUtils.isEmpty(currentDot)) {
        // Replace "연" to "×" among characters
        removeLastString(current)
        current.append(Normalizer.normalize("×", NFD))
      } else if (Objects.equals(Normalizer.normalize(current.toString(), NFC), "옜")
          && Objects.equals(lastDot, "34")
          && TextUtils.isEmpty(currentDot)) {
        // Replace "옜" to "÷" among characters
        removeLastString(current)
        current.append(Normalizer.normalize("÷", NFD))
      }
      BrailleCommonLog.d(
          TAG,
          "getSymbol:"
              + result
              + "current:"
              + current
              + "lastDot:"
              + lastDot
              + "curDot:"
              + currentDot)
      return result
    }

    @JvmStatic
    fun changeToSymbolAboutLastChar(
        current: StringBuilder, lastDot: String, curDot: String
    ): String? {
      if (current.length > 0) {
        current.deleteCharAt(current.length - 1)
      }
      val whole = combineDots(lastDot, curDot)
      val result = CHANGE_SYMBOL_TRANSLATION_MAP[whole]
      BrailleCommonLog.d(TAG, "changeToSymbolAboutLastChar by $whole: $result")
      return result
    }

    @JvmStatic
    fun shouldChangeToSymbol(current: StringBuilder, lastDot: String, curDot: String): Boolean {
      if (TextUtils.isEmpty(current)) {
        return false
      }
      val temp = Normalizer.normalize(current.toString(), NFC)
      if (TextUtils.isEmpty(temp)) {
        return false
      }
      val lastChar = temp.substring(temp.length - 1)
      val whole = combineDots(lastDot, curDot)
      var result = false
      if (EXCEPT_FROM_COMPLETED_HANGUL.contains(lastChar)
          && CHANGE_SYMBOL_TRANSLATION_MAP.containsKey(whole)) {
        result = true
      }
      BrailleCommonLog.d(TAG, "shouldBeChangedToSymbol:$lastChar:$result")
      return result
    }

    @JvmStatic
    fun removeLastString(current: StringBuilder) {
      if (current.length == 0) {
        return
      }
      val temp = Normalizer.normalize(current, NFC)
      val length = current.length
      current.delete(0, length)
      current.append(Normalizer.normalize(temp.substring(0, temp.length - 1), NFD))
    }

    @JvmStatic
    fun isDoubleFinalSymbol(lastDot: String, currentDot: String): Boolean {
      val whole = combineDots(lastDot, currentDot)
      val result = CLOSE_SYMBOL_TRANSLATION_MAP.containsKey(whole)
      BrailleCommonLog.d(TAG, "isDoubleFinalSymbol by $whole: $result")
      return result
    }

    @JvmStatic
    fun isDoubleMiddleSymbol(lastDot: String, currentDot: String): Boolean {
      val whole = combineDots(lastDot, currentDot)
      val result = SYMBOL_TRANSLATION_MAP.containsKey(whole)
      BrailleCommonLog.d(TAG, "isDoubleMiddleSymbol by $whole: $result")
      return result
    }

    @JvmStatic
    fun isDoubleFirstSymbol(lastDot: String, currentDot: String): Boolean {
      val whole = combineDots(lastDot, currentDot)
      val result = OPEN_SYMBOL_TRANSLATION_MAP.containsKey(whole)
      BrailleCommonLog.d(TAG, "isDoubleFirstSymbol by $whole: $result")
      return result
    }

    @JvmStatic
    fun isSpecialOWithTwoBrailles(
        current: StringBuilder, lastDot: String, currentDot: String
    ): Boolean {
      val whole = combineDots(lastDot, currentDot)
      var result = SPECIAL_O_TRANSLATION_MAP.containsKey(whole)
      var prevLastChar = ""
      if (current.length > 1) {
        prevLastChar = current.substring(current.length - 2, current.length - 1)
      }
      if (isInitHangul(prevLastChar)) {
        result = false
      }
      if (Objects.equals(lastDot, "36") && Objects.equals(currentDot, "34")) {
        result = true // It is "ㅖ" after vowel
      }
      BrailleCommonLog.d(TAG, "isSpecialOWithTwoBrailles by $whole: $result")
      return result
    }

    @JvmStatic
    fun isDoubleVowelConsonant(
        current: StringBuilder, lastDot: String, currentDot: String
    ): Boolean {
      val whole = combineDots(lastDot, currentDot)
      val result = VOWEL_TRANSLATION_MAP.containsKey(whole)
      BrailleCommonLog.d(TAG, "isDoubleVowelConsonant by $whole: $result")
      return result
    }

    @JvmStatic
    fun isDoubleFinalConsonant(
        current: StringBuilder, lastDot: String, currentDot: String
    ): Boolean {
      val whole = combineDots(lastDot, currentDot)
      var result = FINAL_CONSONANTS_TRANSLATION_MAP.containsKey(whole)
      if (result) {
        val temp = StringBuilder(current)
        if (temp.length > 0) {
          temp.deleteCharAt(temp.length - 1)
        }
        temp.append(Normalizer.normalize(getFinalConsonants(combineDots(lastDot, currentDot)), NFD))
        val tempStr = Normalizer.normalize(temp.toString(), NFC)
        result = isCompletedHangul(tempStr.substring(tempStr.length - 1))
        BrailleCommonLog.d(
            TAG, "isDoubleFinalConsonant double check tempStr:$tempStr $result")
      }
      BrailleCommonLog.d(TAG, "isDoubleFinalConsonant by $whole: $result")
      return result
    }

    @JvmStatic
    fun isDoubleInitConsonant(lastDot: String, currentDot: String): Boolean {
      var result: Boolean
      val whole = combineDots(lastDot, currentDot)
      if (!Objects.equals(lastDot, "6")) {
        result = false
      } else {
        result = INITIAL_CONSONANTS_TRANSLATION_MAP.containsKey(whole)
      }
      BrailleCommonLog.d(TAG, "isDoubleInitConsonant by $whole: $result")
      return result
    }

    @JvmStatic
    fun isAllHangul(all: String?): Boolean {
      if (TextUtils.isEmpty(all)) {
        return false
      }
      val result = Pattern.matches("^[ᄀ-ᄒ가-힣]*$", all)
      BrailleCommonLog.d(TAG, "isAllHangul $all : $result")
      return result
    }

    @JvmStatic
    fun isCompletedHangul(all: String?): Boolean {
      if (TextUtils.isEmpty(all)) {
        return false
      }
      val result = completedHangul.contains(all)
      BrailleCommonLog.d(TAG, "isCompletedHangul $all : $result")
      return result
    }

    @JvmStatic
    fun isVowelHangul(all: String?): Boolean {
      if (TextUtils.isEmpty(all)) {
        return false
      }
      val result = Pattern.matches("^[ᅡ-ᅵㅏ-ㅣ]*$", all)
      BrailleCommonLog.d(TAG, "isVowelHangul $all : $result")
      return result
    }

    @JvmStatic
    fun isInitHangul(all: String?): Boolean {
      if (TextUtils.isEmpty(all)) {
        return false
      }
      val result = Pattern.matches("^[ᄀ-ᄒ]*$", all)
      BrailleCommonLog.d(TAG, "isInitHangul $all : $result")
      return result
    }

    @JvmStatic
    fun isFinalHangul(all: String?): Boolean {
      if (TextUtils.isEmpty(all)) {
        return false
      }
      val result = Pattern.matches("^[ᆨ-ᇂ]*$", all)
      BrailleCommonLog.d(TAG, "isFinalHangul $all : $result")
      return result
    }

    @JvmStatic
    fun convertStringToNfc(current: StringBuilder) {
      val nfcString = Normalizer.normalize(current.toString(), NFC)
      current.delete(0, current.length)
      current.append(nfcString)
    }

    @JvmStatic
    fun convertStringToNfd(current: StringBuilder) {
      val nfdString = Normalizer.normalize(current.toString(), NFD)
      current.delete(0, current.length)
      current.append(nfdString)
    }

    @JvmStatic
    fun convertToSymbol(
        current: StringBuilder, syllable: String, lastDot: String, curDot: String
    ) {
      val currentStr = Normalizer.normalize(current.toString(), NFC)
      val lastSyllable = currentStr.substring(currentStr.length - 1)
      if (!isAllHangul(lastSyllable)
          || (isDuplicatedDot(curDot) && !isCompletedHangul(lastSyllable))
          || isWonDollarSymbol(currentStr, lastDot, curDot)) {
        current.deleteCharAt(current.length - 1)
        val symbol = appendSymbol(current, lastDot, curDot)
        if (TextUtils.isEmpty(symbol)) {
          current.append(Normalizer.normalize(syllable, NFD))
        } else {
          current.append(Normalizer.normalize(symbol, NFD))
        }
      }
    }

    @JvmStatic
    fun correctIfPossible(
        fullWord: BrailleWord,
        prevTrans: String,
        lastDot: String,
        inputDot: String,
        exceptionalCase: KoreanExceptionalCase,
    ): String {
      val prevResult = Normalizer.normalize(prevTrans, NFD)
      val result = StringBuilder(if (TextUtils.isEmpty(prevResult)) "" else prevResult)
      getHangulIfPossible(result, fullWord, lastDot, inputDot, exceptionalCase)
      BrailleCommonLog.d(TAG, "correctIfPossible: $result")
      return result.toString()
    }

    // Check sentence is number + " " + character, if it is true, we will remove space.
    @JvmStatic
    fun checkSpaceNumberAndCharBeforeCommit(
        imeConnection: ImeConnection, appendedText: String
    ) {
      var index = EditBufferUtils.getCursorPosition(imeConnection.inputConnection)
      // If index is less than 2, text is one character. We don't need to check this case.
      if (index < 2) {
        return
      }
      BrailleCommonLog.d(
          TAG, "full:" + EditBufferUtils.getTextFieldText(imeConnection.inputConnection) + ":")
      val before: CharSequence? = imeConnection.inputConnection.getTextBeforeCursor(2, index)
      BrailleCommonLog.d(
          TAG, "index:$index:before:$before:appendedText:$appendedText:")
      if (TextUtils.isEmpty(before) || before!!.length < 2) {
        return
      }
      if (TextUtils.isEmpty(appendedText)) {
        return
      }
      val beforeChar = before.toString().substring(0, 1)
      val beforeLast = before.toString().substring(1, 2)
      val afterChar = Normalizer.normalize(appendedText, NFD).substring(0, 1)
      val afterStr = appendedText.substring(0, 1)
      BrailleCommonLog.d(
          TAG,
          "index:"
              + index
              + ":beforeChar:"
              + beforeChar
              + ":afterChar:"
              + afterChar
              + ":afterStr:"
              + afterStr
              + ":")
      if (isNumber(beforeChar)
          && Objects.equals(beforeLast, " ")
          && (Objects.equals(afterChar, "ᄂ")
              || Objects.equals(afterChar, "ᄃ")
              || Objects.equals(afterChar, "ᄆ")
              || Objects.equals(afterChar, "ᄏ")
              || Objects.equals(afterChar, "ᄐ")
              || Objects.equals(afterChar, "ᄑ")
              || Objects.equals(afterChar, "ᄒ")
              || Objects.equals(afterStr, "운"))) {
        BrailleCommonUtils.performKeyAction(imeConnection.inputConnection, KeyEvent.KEYCODE_DEL)
      }
    }

    private fun appendDoubleFirstSymbol(
        current: StringBuilder, lastDot: String, currentDot: String
    ): String? {
      // if last dot is "456", it is not removed previous character
      if (current.length > 0
          && !(Objects.equals(lastDot, "456")
              ||
              // "=" should be not removing about previous character
              (Objects.equals(lastDot, "25") && Objects.equals(currentDot, "25")))) {
        current.deleteCharAt(current.length - 1)
      } else if (current.length > 0
          && !(Objects.equals(lastDot, "25") && Objects.equals(currentDot, "25"))) {
        // if last dot "25-25", it is not removed previous character

        current.deleteCharAt(current.length - 1)
      }
      val whole = combineDots(lastDot, currentDot)
      val result = OPEN_SYMBOL_TRANSLATION_MAP[whole]
      BrailleCommonLog.d(TAG, "appendDoubleFirstSymbol by $whole: $result")
      return result
    }

    private fun appendDoubleMiddleSymbol(
        current: StringBuilder, lastDot: String, curDot: String
    ): String? {
      // if last dot is "456", it is not removed previous character
      if (current.length > 0 && !Objects.equals(lastDot, "456")) {
        current.deleteCharAt(current.length - 1)
      }
      val whole = combineDots(lastDot, curDot)
      val result = SYMBOL_TRANSLATION_MAP[whole]
      BrailleCommonLog.d(TAG, "appendDoubleMiddleSymbol by $whole: $result")
      return result
    }

    private fun isWonDollarSymbol(currentStr: String, lastDot: String, curDot: String): Boolean {
      var result = false
      // ￦ symbol or 긔
      if (currentStr.length < 2 && Objects.equals(lastDot, "4") && Objects.equals(curDot, "2456")) {
        result = true
      } else if (currentStr.length < 3
          && Objects.equals(lastDot, "4")
          && Objects.equals(curDot, "145")) {
        // $ symbol or ㄱㅍ
        result = true
      }
      BrailleCommonLog.d(TAG, "isWonDallarSymbol $currentStr : $result")
      return result
    }

    private fun getHangulIfPossible(
        current: StringBuilder,
        fullWord: BrailleWord,
        lastDot: String,
        inputDot: String,
        exceptionalCase: KoreanExceptionalCase,
    ) {
      val lastChar = if (current.length > 0) current.substring(current.length - 1) else ""
      BrailleCommonLog.d(
          TAG,
          "getHangulIfPossible"
              + " fullWord: "
              + fullWord
              + " current: "
              + current
              + " lastDot: "
              + lastDot
              + " inputDot: "
              + inputDot
              + " lastChar: "
              + lastChar)
      exceptionalCase.processExceptionalCase(current, fullWord, lastChar, lastDot, inputDot)
    }
  }
}
