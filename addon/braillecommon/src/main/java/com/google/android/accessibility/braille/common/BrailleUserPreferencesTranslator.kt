/*
 * Copyright (C) 2021 Google Inc.
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

package com.google.android.accessibility.braille.common

import android.content.Context
import android.content.SharedPreferences
import com.google.android.accessibility.braille.common.Constants.BrailleType
import com.google.android.accessibility.braille.common.translate.BrailleLanguages
import com.google.android.accessibility.braille.common.translate.BrailleLanguages.Code
import com.google.android.accessibility.braille.common.translate.GoogleTranslationResultCustomizer
import com.google.android.accessibility.braille.common.translate.KoreanTranslationResultCustomizer
import com.google.android.accessibility.braille.translate.GoogleBrailleTranslatorFactory
import com.google.android.accessibility.braille.translate.TranslationResultCustomizer
import com.google.android.accessibility.braille.translate.TranslatorFactory
import com.google.android.accessibility.braille.translate.liblouis.LibLouis
import com.google.common.collect.ImmutableList
import com.google.common.collect.ImmutableSet

/** 输入/输出盲文码、首选语言列表、 contracted 模式与翻译工厂相关偏好。 */
class BrailleUserPreferencesTranslator private constructor() {

  companion object {
    private const val CONTRACTED_MODE_DEFAULT = false

    private const val BRAILLE_SHARED_PREFS_FILENAME =
      BrailleUserPreferencesMigration.BRAILLE_SHARED_PREFS_FILENAME

    private fun getSharedPreferences(context: Context, fileName: String): SharedPreferences =
      BrailleUserPreferencesMigration.getSharedPreferences(context, fileName)

    // Prefs that are common to BK and BD
    /** Writes current using input {@link Code}. */
    @JvmStatic
    fun writeCurrentActiveInputCode(context: Context, code: Code) {
      getSharedPreferences(context, BRAILLE_SHARED_PREFS_FILENAME)
        .edit()
        .putString(context.getString(R.string.pref_brailleime_translator_code), code.name)
        .apply()
    }

    /** Whether current active typing language is eight dot braille. */
    @JvmStatic
    fun isCurrentActiveInputCodeEightDot(context: Context): Boolean {
      return readCurrentActiveInputCodeAndCorrect(context).isEightDot()
    }

    /** Whether contracted enabled. */
    @JvmStatic
    fun readContractedMode(context: Context): Boolean {
      return getSharedPreferences(context, BRAILLE_SHARED_PREFS_FILENAME)
        .getBoolean(
          context.getString(R.string.pref_braille_contracted_mode),
          CONTRACTED_MODE_DEFAULT,
        )
    }

    /** Writes contracted enabled status. */
    @JvmStatic
    fun writeContractedMode(context: Context, contractedMode: Boolean) {
      getSharedPreferences(context, BRAILLE_SHARED_PREFS_FILENAME)
        .edit()
        .putBoolean(context.getString(R.string.pref_braille_contracted_mode), contractedMode)
        .apply()
    }

    /** Returns braille type of current typing language. */
    @JvmStatic
    fun getCurrentTypingLanguageType(context: Context): BrailleType {
      return if (isCurrentActiveInputCodeEightDot(context)) {
        BrailleType.EIGHT_DOT
      } else {
        BrailleType.SIX_DOT
      }
    }

    /** Retrieves a {@link List<Code>} contains all user preferred and available {@link Code}. */
    @JvmStatic
    fun readAvailablePreferredCodes(context: Context): List<Code> {
      return BrailleLanguages.extractAvailableCodes(context, readPreferredCodes(context))
    }

    /** Writes user preferred {@link List<Code>}. */
    @JvmStatic
    fun writePreferredCodes(context: Context, preferredCodes: List<Code>) {
      val selectedCodesInStringSet = mutableSetOf<String>()
      for (code in preferredCodes) {
        selectedCodesInStringSet.add(code.name)
      }
      getSharedPreferences(context, BRAILLE_SHARED_PREFS_FILENAME)
        .edit()
        .putStringSet(
          context.getString(R.string.pref_brailleime_translator_codes_preferred),
          selectedCodesInStringSet,
        )
        .apply()
    }

    /**
     * 首次运行时把推荐语言播种进"语言"列表，只做一次。
     *
     * <p>原版 TalkBack 的"语言"列表默认是空的，此时 {@link #readPreferredCodes} 会退化成"只有系统
     * 默认的一种语言"，于是"首选输入语言"下拉里只有一项，用户会以为"切了没反应"。这里播种一份
     * 常用组合，让用户能真正切换。
     *
     * <p><b>顺序必须与原版一致：把 {@link BrailleLanguages#getDefaultCode} 放在最前面，并直接写成
     * 当前输入码。</b>原版的默认输入码就是"系统语言对应的盲文码"——中文系统下是中文现行盲文（三格
     * 一字）。如果这里把英文一级表排在首位，中文系统首次启动就会落在英文表上，单格只会打出英文字母，
     * 永远打不出汉字。
     */
    @JvmStatic
    fun ensureRecommendedPreferredCodes(context: Context) {
      val prefs: SharedPreferences = getSharedPreferences(context, BRAILLE_SHARED_PREFS_FILENAME)
      val seededKey = "pref_brailleime_recommended_codes_seeded"
      if (prefs.getBoolean(seededKey, false)) {
        return
      }
      // 默认码按系统语言推导，与原版保持一致。
      val defaultCode = BrailleLanguages.getDefaultCode(context)
      val codesKey = context.getString(R.string.pref_brailleime_translator_codes_preferred)
      if (prefs.getStringSet(codesKey, ImmutableSet.of())!!.isEmpty()) {
        val recommended = mutableListOf<Code>()
        recommended.add(defaultCode)
        for (code in
          ImmutableList.of(
            Code.CHINESE_CHINA_CURRENT_WITH_TONES,
            Code.CHINESE_CHINA_COMMON,
            Code.EN_US_EBAE,
          )) {
          if (!recommended.contains(code) && code.isAvailable(context)) {
            recommended.add(code)
          }
        }
        writePreferredCodes(context, recommended)
      }
      // 目前没有存过输入码时，落成系统默认码，不要留给"列表第一项"这种不确定行为。
      val currentCodeKey = context.getString(R.string.pref_brailleime_translator_code)
      if (!prefs.contains(currentCodeKey)) {
        writeCurrentActiveInputCode(context, defaultCode)
      }
      prefs.edit().putBoolean(seededKey, true).apply()
    }

    /** Removes {@link Code} from preferred list. */
    @JvmStatic
    fun removePreferredCode(context: Context, code: Code) {
      val preferredCodes = readPreferredCodes(context).toMutableList()
      if (preferredCodes.remove(code)) {
        writePreferredCodes(context, preferredCodes)
      }
    }

    /**
     * Retrieves current active input {@link Code}. If it's not in the preferred codes, returns the
     * first preferred code.
     */
    @JvmStatic
    fun readCurrentActiveInputCodeAndCorrect(context: Context): Code {
      val storedCode = readCurrentActiveInputCode(context)
      val selectedAvailableCodes = readAvailablePreferredCodes(context)
      if (selectedAvailableCodes.contains(storedCode)) {
        return storedCode
      }
      val firstPreferredCode = selectedAvailableCodes[0]
      // Update current active input code preference.
      writeCurrentActiveInputCode(context, firstPreferredCode)
      return firstPreferredCode
    }

    /** Gets the next preferred output {@link Code}. */
    @JvmStatic
    fun getNextOutputCode(context: Context): Code {
      val preferredCodes = readAvailablePreferredCodes(context)
      val currentCode = readCurrentActiveOutputCodeAndCorrect(context)
      var currentCodeIndex = preferredCodes.indexOf(currentCode) + 1
      if (currentCodeIndex >= preferredCodes.size) {
        currentCodeIndex = 0
      }
      return preferredCodes[currentCodeIndex]
    }

    /** Gets the next preferred input {@link Code}. */
    @JvmStatic
    fun getNextInputCode(context: Context): Code {
      val preferredCodes = readAvailablePreferredCodes(context)
      val currentCode = readCurrentActiveInputCodeAndCorrect(context)
      var currentCodeIndex = preferredCodes.indexOf(currentCode) + 1
      if (currentCodeIndex >= preferredCodes.size) {
        currentCodeIndex = 0
      }
      return preferredCodes[currentCodeIndex]
    }

    /** Reads translator factory. */
    @JvmStatic
    fun readTranslatorFactory(context: Context): TranslatorFactory {
      val translatorFactorClass: Class<out TranslatorFactory> = LibLouis::class.java
      return GoogleBrailleTranslatorFactory(
        TranslatorFactory.forName(TranslatorFactory.getNameFromClass(translatorFactorClass)),
        getTranslationResultCustomizer(context),
      )
    }

    private fun getTranslationResultCustomizer(context: Context): TranslationResultCustomizer {
      val code = readCurrentActiveOutputCodeAndCorrect(context)
      if (code == Code.KOREAN_2006) {
        return KoreanTranslationResultCustomizer(context)
      }
      return GoogleTranslationResultCustomizer(context)
    }

    /** Reads current using input {@link Code}. */
    private fun readCurrentActiveInputCode(context: Context): Code {
      val codeName =
        getSharedPreferences(context, BRAILLE_SHARED_PREFS_FILENAME)
          .getString(
            context.getString(R.string.pref_brailleime_translator_code),
            BrailleLanguages.getDefaultCode(context).name,
          )
      return BrailleCommonUtils.valueOfSafe(codeName!!, BrailleLanguages.getDefaultCode(context))
    }

    /** Reads user preferred {@link List<Code>}. */
    private fun readPreferredCodes(context: Context): List<Code> {
      val preferredCodesInStringSet: Set<String> =
        getSharedPreferences(context, BRAILLE_SHARED_PREFS_FILENAME)
          .getStringSet(
            context.getString(R.string.pref_brailleime_translator_codes_preferred),
            ImmutableSet.of(),
          )!!
      if (preferredCodesInStringSet.isEmpty()) {
        return ImmutableList.of(BrailleLanguages.getDefaultCode(context))
      }
      val preferredCodesInList: List<Code> = extractValidCodes(preferredCodesInStringSet)
      return if (preferredCodesInList.isEmpty()) {
        ImmutableList.of(BrailleLanguages.getDefaultCode(context))
      } else {
        preferredCodesInList
      }
    }

    /** Extracts the valid {@link Code} in String Set. */
    @JvmStatic
    fun extractValidCodes(codes: Set<String>): List<Code> {
      return codes
        .map { BrailleCommonUtils.valueOfSafe(it, Code.STUB) }
        .filter { it != Code.STUB }
    }

    /**
     * Reads current active output {@link Code}. Use the first match of system language or the first
     * preferred code if empty.
     */
    @JvmStatic
    fun readCurrentActiveOutputCodeAndCorrect(context: Context): Code {
      var returnCode: Code
      val codeName =
        getSharedPreferences(context, BRAILLE_SHARED_PREFS_FILENAME)
          .getString(
            context.getString(R.string.pref_bd_output_code),
            BrailleLanguages.getDefaultCode(context).name,
          )
      returnCode = BrailleCommonUtils.valueOfSafe(codeName!!, BrailleLanguages.getDefaultCode(context))
      val availablePreferredCodes = readAvailablePreferredCodes(context)
      if (!availablePreferredCodes.contains(returnCode)) {
        returnCode = availablePreferredCodes[0]
      }
      // Update current active output code preference.
      writeCurrentActiveOutputCode(context, returnCode)
      return returnCode
    }

    @JvmStatic
    fun writeCurrentActiveOutputCode(context: Context, code: Code) {
      getSharedPreferences(context, BRAILLE_SHARED_PREFS_FILENAME)
        .edit()
        .putString(context.getString(R.string.pref_bd_output_code), code.name)
        .apply()
    }
  }
}
