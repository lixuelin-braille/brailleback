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
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.google.android.accessibility.braille.common.translate

import android.content.Context
import androidx.annotation.StringRes
import androidx.core.content.res.ResourcesCompat
import com.google.android.accessibility.braille.common.R
import com.google.android.accessibility.braille.common.TalkBackSpeaker
import com.google.android.accessibility.braille.translate.BrailleTranslator
import com.google.android.accessibility.braille.translate.TranslatorFactory
import java.util.Locale

/**
 * Holds the list of supported [Code] and provides an [EditBuffer] for given [Code] and [TranslatorFactory].
 *
 * 本类是对外统一入口：码表条目定义见下方嵌套枚举 [Code]，各条目的 EditBuffer 装配策略见
 * [codeEditBuffer]，语言标签常量见 [BrailleLanguagesLocales]；这里只保留清单装配、默认码表回落与工厂转发。
 */
class BrailleLanguages private constructor() {

    companion object {
        /**
         * Finds and produces a [EditBuffer] based on [code], [translatorFactory], and
         * [contractedMode].
         */
        @JvmStatic
        fun createEditBuffer(
            context: Context,
            talkBack: TalkBackSpeaker,
            code: Code,
            translatorFactory: TranslatorFactory,
            contractedMode: Boolean,
        ): EditBuffer {
            val translator: BrailleTranslator =
                translatorFactory.create(context, code.name, contractedMode)
            return code.createEditBuffer(context, talkBack, translator, contractedMode)
        }

        /** Retrieves a [List]<[Code]> contains all available translator [Code]. */
        @JvmStatic
        fun getAvailableCodes(context: Context): List<Code> {
            val availableCodes: MutableList<Code> = ArrayList()
            for (code in Code.values()) {
                if (code.isAvailable(context)) {
                    availableCodes.add(code)
                }
            }
            return availableCodes
        }

        /** Extracts all available [List]<[Code]> within [codes]. */
        @JvmStatic
        fun extractAvailableCodes(context: Context, codes: List<Code>): List<Code> {
            val availableCodes: MutableList<Code> = ArrayList(getAvailableCodes(context))
            for (i in availableCodes.size - 1 downTo 0) {
                if (!codes.contains(availableCodes[i])) {
                    availableCodes.removeAt(i)
                }
            }
            return availableCodes
        }

        /** Gets the default [Code] based on the [Locale]. */
        @JvmStatic
        fun getDefaultCode(context: Context): Code {
            val systemLocale = Locale.getDefault()
            if (systemLocale.language.equals(Locale.ENGLISH.language)) {
                return Code.UEB
            }
            val localeLanguageCodes =
                getAvailableCodes(context).filter { code ->
                    code.locale.language.equals(systemLocale.language)
                }
            val firstLocaleCountryCode =
                localeLanguageCodes.firstOrNull { code ->
                    code.locale.country.equals(systemLocale.country)
                }
            if (firstLocaleCountryCode != null) {
                return firstLocaleCountryCode
            } else if (localeLanguageCodes.isNotEmpty()) {
                return localeLanguageCodes[0]
            }
            return Code.UEB
        }
    }

    /**
     * An enum for holding Braille language family instances, such as UEB or ComputerBraille.
     *
     * Each instance has a spoken language with which it is associated, which may later become a
     * collection of spoken languages (or a collection of locales) as we add support for more spoken
     * languages. Multiple instances can be associated with the same spoken language; for example both
     * UEB and Computer Braille can be associated with English.
     *
     * Each instance has the important tasks of furnishing an [EditBuffer] via the [Code.createEditBuffer]
     * method and provides a user facing name if it is available.
     */
    // LINT.IfChange(supported_languages)
    enum class Code(
        val locale: Locale,
        @StringRes private val stringId: Int,
        private val supportsContracted: Boolean,
        private val eightDot: Boolean,
    ) {
        ARABIC(
            BrailleLanguagesLocales.LOCALE_AR,
            R.string.code_user_facing_name_arabic, /* supportedContracted= */ true, /* eightDot= */ false,
        ),
        ARABIC_COMP8(
            BrailleLanguagesLocales.LOCALE_AR,
            R.string.code_user_facing_name_arabic_comp8, /* supportedContracted= */ false, /* eightDot= */ true,
        ),
        BULGARIAN(
            BrailleLanguagesLocales.LOCALE_BG,
            R.string.code_user_facing_name_bulgarian, /* supportedContracted= */ false, /* eightDot= */ false,
        ),
        BULGARIAN_COMP8(
            BrailleLanguagesLocales.LOCALE_BG,
            R.string.code_user_facing_name_bulgarian_comp8, /* supportedContracted= */ false, /* eightDot= */ true,
        ),
        CANTONESE(
            BrailleLanguagesLocales.LOCALE_YUE,
            R.string.code_user_facing_name_catonese, /* supportedContracted= */ false, /* eightDot= */ false,
        ),
        CATALAN(
            BrailleLanguagesLocales.LOCALE_CA,
            R.string.code_user_facing_name_ca_catalan, /* supportedContracted= */ false, /* eightDot= */ false,
        ),
        CHINESE_CHINA_CURRENT_WITH_TONES(
            BrailleLanguagesLocales.LOCALE_ZH_CN,
            R.string.code_user_facing_name_chinese_china_current_with_tones, /* supportedContracted= */ false, /* eightDot= */ false,
        ),
        CHINESE_CHINA_CURRENT_WITHOUT_TONES(
            BrailleLanguagesLocales.LOCALE_ZH_CN,
            R.string.code_user_facing_name_chinese_china_current_without_tones, /* supportedContracted= */ false, /* eightDot= */ false,
        ),
        CHINESE_CHINA_TWO_CELLS(
            BrailleLanguagesLocales.LOCALE_ZH_CN,
            R.string.code_user_facing_name_chinese_china_two_cells, /* supportedContracted= */ false, /* eightDot= */ false,
        ),
        CHINESE_CHINA_COMMON(
            BrailleLanguagesLocales.LOCALE_ZH_CN,
            R.string.code_user_facing_name_chinese_china_common, /* supportedContracted= */ false, /* eightDot= */ false,
        ),
        CHINESE_TAIWAN(
            BrailleLanguagesLocales.LOCALE_ZH_TW,
            R.string.code_user_facing_name_chinese_taiwan, /* supportedContracted= */ false, /* eightDot= */ false,
        ),
        CROATIAN(
            BrailleLanguagesLocales.LOCALE_HR,
            R.string.code_user_facing_name_croatian, /* supportedContracted= */ false, /* eightDot= */ false,
        ),
        CROATIAN_COMP8(
            BrailleLanguagesLocales.LOCALE_HR,
            R.string.code_user_facing_name_croatian_comp8, /* supportedContracted= */ false, /* eightDot= */ true,
        ),
        CZECH(
            BrailleLanguagesLocales.LOCALE_CS,
            R.string.code_user_facing_name_czech, /* supportedContracted= */ false, /* eightDot= */ false,
        ),
        CZECH_COMP8(
            BrailleLanguagesLocales.LOCALE_CS,
            R.string.code_user_facing_name_czech_comp8, /* supportedContracted= */ false, /* eightDot= */ true,
        ),
        DANISH(
            BrailleLanguagesLocales.LOCALE_DA,
            R.string.code_user_facing_name_danish, /* supportedContracted= */ true, /* eightDot= */ false,
        ),
        DANISH_8(
            BrailleLanguagesLocales.LOCALE_DA,
            R.string.code_user_facing_name_danish_eight, /* supportedContracted= */ true, /* eightDot= */ true,
        ),
        DANISH_COMP8(
            BrailleLanguagesLocales.LOCALE_DA,
            R.string.code_user_facing_name_danish_comp8, /* supportedContracted= */ false, /* eightDot= */ true,
        ),
        DUTCH_NL(
            BrailleLanguagesLocales.LOCALE_NL_NL,
            R.string.code_user_facing_name_dutch_nl, /* supportedContracted= */ false, /* eightDot= */ false,
        ),
        DUTCH_COMP8(
            BrailleLanguagesLocales.LOCALE_NL,
            R.string.code_user_facing_name_dutch_comp8, /* supportedContracted= */ false, /* eightDot= */ true,
        ),
        EN_IN(
            BrailleLanguagesLocales.LOCALE_EN_IN,
            R.string.code_user_facing_name_en_in, /* supportedContracted= */ false, /* eightDot= */ false,
        ),
        UEB(
            BrailleLanguagesLocales.LOCALE_EN,
            R.string.code_user_facing_name_ueb1, /* supportedContracted= */ true, /* eightDot= */ false,
        ),
        EN_UK(
            BrailleLanguagesLocales.LOCALE_EN_GB,
            R.string.code_user_facing_name_en_uk, /* supportedContracted= */ true, /* eightDot= */ false,
        ),
        EN_US_EBAE(
            BrailleLanguagesLocales.LOCALE_EN_US,
            R.string.code_user_facing_name_en_us_ebae, /* supportedContracted= */ true, /* eightDot= */ false,
        ),
        EN_NABCC(
            BrailleLanguagesLocales.LOCALE_EN,
            R.string.code_user_facing_name_en_nabcc_comp8, /* supportedContracted= */ false, /* eightDot= */ true,
        ),
        EN_COMP6(
            BrailleLanguagesLocales.LOCALE_EN_US,
            R.string.code_user_facing_name_en_comp6, /* supportedContracted= */ false, /* eightDot= */ false,
        ),
        ESTONIAN(
            BrailleLanguagesLocales.LOCALE_ET,
            R.string.code_user_facing_name_estonian, /* supportedContracted= */ false, /* eightDot= */ true,
        ),
        FINNISH(
            BrailleLanguagesLocales.LOCALE_FI,
            R.string.code_user_facing_name_finnish, /* supportedContracted= */ false, /* eightDot= */ false,
        ),
        FINNISH_COMP8(
            BrailleLanguagesLocales.LOCALE_FI,
            R.string.code_user_facing_name_finnish_comp8, /* supportedContracted= */ false, /* eightDot= */ true,
        ),
        FRENCH(
            BrailleLanguagesLocales.LOCALE_FR,
            R.string.code_user_facing_name_french, /* supportedContracted= */ true, /* eightDot= */ false,
        ),
        FRENCH_COMP8(
            BrailleLanguagesLocales.LOCALE_FR,
            R.string.code_user_facing_name_french_comp8, /* supportedContracted= */ false, /* eightDot= */ true,
        ),
        GREEK(
            BrailleLanguagesLocales.LOCALE_EL,
            R.string.code_user_facing_name_greek, /* supportedContracted= */ false, /* eightDot= */ false,
        ),
        GREEK_INTERNATIONAL(
            BrailleLanguagesLocales.LOCALE_GRC,
            R.string.code_user_facing_name_greek_international, /* supportedContracted= */ false, /* eightDot= */ false,
        ),
        GERMAN(
            BrailleLanguagesLocales.LOCALE_DE,
            R.string.code_user_facing_name_german, /* supportedContracted= */ true, /* eightDot= */ false,
        ),
        GERMAN_COMP8(
            BrailleLanguagesLocales.LOCALE_DE,
            R.string.code_user_facing_name_german_comp8, /* supportedContracted= */ false, /* eightDot= */ true,
        ),
        GUJARATI(
            BrailleLanguagesLocales.LOCALE_GU_IN,
            R.string.code_user_facing_name_gujarati_india, /* supportedContracted= */ false, /* eightDot= */ false,
        ),
        HEBREW(
            BrailleLanguagesLocales.LOCALE_HE_IL,
            R.string.code_user_facing_name_hebrew, /* supportedContracted= */ false, /* eightDot= */ false,
        ),
        HEBREW_COMP8(
            BrailleLanguagesLocales.LOCALE_HE_IL,
            R.string.code_user_facing_name_hebrew_comp8, /* supportedContracted= */ false, /* eightDot= */ true,
        ),
        HINDI(
            BrailleLanguagesLocales.LOCALE_HI_IN,
            R.string.code_user_facing_name_hindi_india, /* supportedContracted= */ false, /* eightDot= */ false,
        ),
        HUNGARIAN(
            BrailleLanguagesLocales.LOCALE_HU,
            R.string.code_user_facing_name_hungarian, /* supportedContracted= */ true, /* eightDot= */ false,
        ),
        HUNGARIAN_COMP8(
            BrailleLanguagesLocales.LOCALE_HU,
            R.string.code_user_facing_name_hungarian_comp8, /* supportedContracted= */ false, /* eightDot= */ true,
        ),
        ICELANDIC(
            BrailleLanguagesLocales.LOCALE_IS,
            R.string.code_user_facing_name_icelandic, /* supportedContracted= */ false, /* eightDot= */ false,
        ),
        ICELANDIC_COMP8(
            BrailleLanguagesLocales.LOCALE_IS,
            R.string.code_user_facing_name_icelandic_comp8, /* supportedContracted= */ false, /* eightDot= */ true,
        ),
        ITALIAN_COMP6(
            BrailleLanguagesLocales.LOCALE_IT,
            R.string.code_user_facing_name_italian_comp6, /* supportedContracted= */ false, /* eightDot= */ false,
        ),
        ITALIAN_COMP8(
            BrailleLanguagesLocales.LOCALE_IT,
            R.string.code_user_facing_name_italian_comp8, /* supportedContracted= */ false, /* eightDot= */ true,
        ),
        JAPANESE(
            BrailleLanguagesLocales.LOCALE_JP,
            R.string.code_user_facing_name_japanese, /* supportedContracted= */ false, /* eightDot= */ true,
        ),
        KANNADA(
            BrailleLanguagesLocales.LOCALE_KN,
            R.string.code_user_facing_name_kannada, /* supportedContracted= */ false, /* eightDot= */ false,
        ),
        KHMER(
            BrailleLanguagesLocales.LOCALE_KM,
            R.string.code_user_facing_name_khmer, /* supportedContracted= */ false, /* eightDot= */ false,
        ),
        KOREAN_2006(
            BrailleLanguagesLocales.LOCALE_KO,
            R.string.code_user_facing_name_korean_2006, /* supportedContracted= */ true, /* eightDot= */ false,
        ),
        KURDISH(
            BrailleLanguagesLocales.LOCALE_CKB,
            R.string.code_user_facing_name_central_kurdish, /* supportedContracted= */ false, /* eightDot= */ false,
        ),
        LATVIAN(
            BrailleLanguagesLocales.LOCALE_LV,
            R.string.code_user_facing_name_latvian, /* supportedContracted= */ false, /* eightDot= */ false,
        ),
        LITHUANIAN(
            BrailleLanguagesLocales.LOCALE_LT,
            R.string.code_user_facing_name_lithuanian, /* supportedContracted= */ false, /* eightDot= */ false,
        ),
        LITHUANIAN_8(
            BrailleLanguagesLocales.LOCALE_LT,
            R.string.code_user_facing_name_lithuanian_8, /* supportedContracted= */ false, /* eightDot= */ true,
        ),
        MALAYALAM_IN(
            BrailleLanguagesLocales.LOCALE_ML_IN,
            R.string.code_user_facing_name_malayalam_indian, /* supportedContracted= */ false, /* eightDot= */ false,
        ),
        MARATHI_IN(
            BrailleLanguagesLocales.LOCALE_MR_IN,
            R.string.code_user_facing_name_marathi_indian, /* supportedContracted= */ false, /* eightDot= */ false,
        ),
        NEPALI(
            BrailleLanguagesLocales.LOCALE_NE,
            R.string.code_user_facing_name_nepali, /* supportedContracted= */ false, /* eightDot= */ true,
        ),
        NEPALI_IN(
            BrailleLanguagesLocales.LOCALE_NE_IN,
            R.string.code_user_facing_name_nepali_indian, /* supportedContracted= */ false, /* eightDot= */ false,
        ),
        NORWEGIAN(
            BrailleLanguagesLocales.LOCALE_NO,
            R.string.code_user_facing_name_norwegian, /* supportedContracted= */ true, /* eightDot= */ false,
        ),
        NORWEGIAN_8_NO(
            BrailleLanguagesLocales.LOCALE_NO,
            R.string.code_user_facing_name_norwegian_8_no, /* supportedContracted= */ false, /* eightDot= */ true,
        ),
        NORWEGIAN_COMP8(
            BrailleLanguagesLocales.LOCALE_NO,
            R.string.code_user_facing_name_norwegian_comp8, /* supportedContracted= */ false, /* eightDot= */ true,
        ),
        NORWEGIAN_8(
            BrailleLanguagesLocales.LOCALE_NO,
            R.string.code_user_facing_name_norwegian_8, /* supportedContracted= */ false, /* eightDot= */ true,
        ),
        NORWEGIAN_8_6FALLBACK(
            BrailleLanguagesLocales.LOCALE_NO,
            R.string.code_user_facing_name_norwegian_8_with_6_fallback, /* supportedContracted= */ false, /* eightDot= */ true,
        ),
        POLISH(
            BrailleLanguagesLocales.LOCALE_PL,
            R.string.code_user_facing_name_polish, /* supportedContracted= */ false, /* eightDot= */ false,
        ),
        POLISH_COMP8(
            BrailleLanguagesLocales.LOCALE_PL,
            R.string.code_user_facing_name_polish_comp8, /* supportedContracted= */ false, /* eightDot= */ true,
        ),
        PORTUGUESE(
            BrailleLanguagesLocales.LOCALE_PT,
            R.string.code_user_facing_name_portuguese, /* supportedContracted= */ true, /* eightDot= */ false,
        ),
        PORTUGUESE_COMP8(
            BrailleLanguagesLocales.LOCALE_PT,
            R.string.code_user_facing_name_portuguese_comp8, /* supportedContracted= */ false, /* eightDot= */ true,
        ),
        PUNJABI(
            BrailleLanguagesLocales.LOCALE_PA,
            R.string.code_user_facing_name_punjabi, /* supportedContracted= */ false, /* eightDot= */ false,
        ),
        ROMANIAN_COMP8(
            BrailleLanguagesLocales.LOCALE_RO,
            R.string.code_user_facing_name_romanian_comp8, /* supportedContracted= */ false, /* eightDot= */ true,
        ),
        RUSSIAN(
            BrailleLanguagesLocales.LOCALE_RU,
            R.string.code_user_facing_name_russian,
            /* supportedContracted= */ false, // Contracted.
            /* eightDot= */ false,
        ),
        RUSSIAN_DETAILED(
            BrailleLanguagesLocales.LOCALE_RU,
            R.string.code_user_facing_name_russian_detailed, /* supportedContracted= */ false, /* eightDot= */ false,
        ),
        RUSSIAN_COMP8(
            BrailleLanguagesLocales.LOCALE_RU,
            R.string.code_user_facing_name_russian_comp8, /* supportedContracted= */ false, /* eightDot= */ true,
        ),
        SERBIAN(
            BrailleLanguagesLocales.LOCALE_SR,
            R.string.code_user_facing_name_serbian, /* supportedContracted= */ false, /* eightDot= */ false,
        ),
        SINDHI_IN(
            BrailleLanguagesLocales.LOCALE_SD_IN,
            R.string.code_user_facing_name_sindhi_indian, /* supportedContracted= */ false, /* eightDot= */ false,
        ),
        SINHALA(
            BrailleLanguagesLocales.LOCALE_SI,
            R.string.code_user_facing_name_sinhala, /* supportedContracted= */ false, /* eightDot= */ false,
        ),
        SLOVAK(
            BrailleLanguagesLocales.LOCALE_SK,
            R.string.code_user_facing_name_slovak, /* supportedContracted= */ false, /* eightDot= */ false,
        ),
        SPANISH(
            BrailleLanguagesLocales.LOCALE_ES,
            R.string.code_user_facing_name_spanish, /* supportedContracted= */ true, /* eightDot= */ false,
        ),
        SPANISH_COMP8(
            BrailleLanguagesLocales.LOCALE_ES,
            R.string.code_user_facing_name_spanish_comp8, /* supportedContracted= */ false, /* eightDot= */ true,
        ),
        SWEDEN(
            BrailleLanguagesLocales.LOCALE_SV,
            R.string.code_user_facing_name_sweden, /* supportedContracted= */ true, /* eightDot= */ false,
        ),
        SWEDEN_8(
            BrailleLanguagesLocales.LOCALE_SE,
            R.string.code_user_facing_name_sweden_8, /* supportedContracted= */ false, /* eightDot= */ true,
        ),
        SWEDISH_COMP8_1996(
            BrailleLanguagesLocales.LOCALE_SV,
            R.string.code_user_facing_name_swedish_comp8_1996, /* supportedContracted= */ false, /* eightDot= */ true,
        ),
        SWEDISH_COMP8_1989(
            BrailleLanguagesLocales.LOCALE_SV,
            R.string.code_user_facing_name_swedish_comp8_1989, /* supportedContracted= */ false, /* eightDot= */ true,
        ),
        TAMIL(
            BrailleLanguagesLocales.LOCALE_TA,
            R.string.code_user_facing_name_tamil, /* supportedContracted= */ false, /* eightDot= */ false,
        ),
        TELUGU_IN(
            BrailleLanguagesLocales.LOCALE_TE_IN,
            R.string.code_user_facing_name_telugu_in, /* supportedContracted= */ false, /* eightDot= */ false,
        ),
        TURKISH(
            BrailleLanguagesLocales.LOCALE_TR,
            R.string.code_user_facing_name_turkish, /* supportedContracted= */ true, /* eightDot= */ false,
        ),
        TURKISH_COMP8(
            BrailleLanguagesLocales.LOCALE_TR,
            R.string.code_user_facing_name_turkish_comp8, /* supportedContracted= */ false, /* eightDot= */ true,
        ),
        UKRAINIAN(
            BrailleLanguagesLocales.LOCALE_UK,
            R.string.code_user_facing_name_ukrainian, /* supportedContracted= */ false, /* eightDot= */ false,
        ),
        UKRAINIAN_COMP8(
            BrailleLanguagesLocales.LOCALE_UK,
            R.string.code_user_facing_name_ukrainian_comp8, /* supportedContracted= */ false, /* eightDot= */ true,
        ),
        URDU(
            BrailleLanguagesLocales.LOCALE_UR,
            R.string.code_user_facing_name_urdu, /* supportedContracted= */ true, /* eightDot= */ false,
        ),
        VIETNAMESE(
            BrailleLanguagesLocales.LOCALE_VI,
            R.string.code_user_facing_name_vietnamese, /* supportedContracted= */ true, /* eightDot= */ false,
        ),
        VIETNAMESE_COMP8(
            BrailleLanguagesLocales.LOCALE_VI,
            R.string.code_user_facing_name_vietnamese_comp8, /* supportedContracted= */ false, /* eightDot= */ true,
        ),
        WELSH(
            BrailleLanguagesLocales.LOCALE_CY,
            R.string.code_user_facing_name_welsh, /* supportedContracted= */ true, /* eightDot= */ false,
        ),
        STUB(
            BrailleLanguagesLocales.LOCALE_EN,
            ResourcesCompat.ID_NULL, /* supportedContracted= */ false, /* eightDot= */ false,
        );
        // LINT.ThenChange(
        // //depot/google3/java/com/google/android/accessibility/braille/translate/\
        //     liblouis_tables.bzl:supported_languages,
        // //depot/google3/logs/proto/wireless/android/aas/brailleime/\
        //     brailleime_log.proto:supported_languages)

        /** Returns the user-facing name of the code, or null if the code is not user-facing. */
        fun getUserFacingName(context: Context): String {
            return if (isAvailable(context)) context.resources.getString(stringId) else ""
        }

        fun isAvailable(context: Context): Boolean {
            if (stringId != ResourcesCompat.ID_NULL) {
                when (this) {
                    else -> {}
                }
                return true
            }
            return false
        }

        open fun createEditBuffer(
            context: Context,
            talkBack: TalkBackSpeaker,
            translator: BrailleTranslator,
            contractedMode: Boolean,
        ): EditBuffer = codeEditBuffer(context, talkBack, translator, contractedMode)

        /** Returns whether BrailleIme supports contracted mode, depends on the feature flag. */
        fun isSupportsContracted(context: Context): Boolean {
            when (this) {
                else -> {}
            }
            return supportsContracted
        }

        fun isEightDot(): Boolean {
            return eightDot
        }
    }
}

