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
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
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
 */
class BrailleLanguages private constructor() {

    companion object {
        @JvmField val LOCALE_AR: Locale = Locale.forLanguageTag("ar")
        @JvmField val LOCALE_BG: Locale = Locale.forLanguageTag("bg")
        @JvmField val LOCALE_CA: Locale = Locale.forLanguageTag("ca")
        @JvmField val LOCALE_CKB: Locale = Locale.forLanguageTag("ckb")
        @JvmField val LOCALE_CS: Locale = Locale.forLanguageTag("cs")
        @JvmField val LOCALE_CY: Locale = Locale.forLanguageTag("cy")
        @JvmField val LOCALE_DA: Locale = Locale.forLanguageTag("da")
        @JvmField val LOCALE_DE: Locale = Locale.GERMAN
        @JvmField val LOCALE_EL: Locale = Locale.forLanguageTag("el")
        @JvmField val LOCALE_EN: Locale = Locale.ENGLISH
        @JvmField val LOCALE_EN_GB: Locale = Locale.UK
        @JvmField val LOCALE_EN_IN: Locale = Locale.forLanguageTag("en-IN")
        @JvmField val LOCALE_EN_US: Locale = Locale.US
        @JvmField val LOCALE_ES: Locale = Locale.forLanguageTag("es")
        @JvmField val LOCALE_ET: Locale = Locale.forLanguageTag("et")
        @JvmField val LOCALE_FI: Locale = Locale.forLanguageTag("fi")
        @JvmField val LOCALE_FR: Locale = Locale.FRENCH
        @JvmField val LOCALE_GRC: Locale = Locale.forLanguageTag("grc")
        @JvmField val LOCALE_GU_IN: Locale = Locale.forLanguageTag("gu-IN")
        @JvmField val LOCALE_HI_IN: Locale = Locale.forLanguageTag("hi-IN")
        @JvmField val LOCALE_HE_IL: Locale = Locale.forLanguageTag("he-IL")
        @JvmField val LOCALE_HR: Locale = Locale.forLanguageTag("hr")
        @JvmField val LOCALE_HU: Locale = Locale.forLanguageTag("hu")
        @JvmField val LOCALE_IS: Locale = Locale.forLanguageTag("is")
        @JvmField val LOCALE_IT: Locale = Locale.ITALIAN
        @JvmField val LOCALE_JP: Locale = Locale.JAPANESE
        @JvmField val LOCALE_KM: Locale = Locale.forLanguageTag("km")
        @JvmField val LOCALE_KO: Locale = Locale.KOREAN
        @JvmField val LOCALE_KN: Locale = Locale.forLanguageTag("kn")
        @JvmField val LOCALE_LT: Locale = Locale.forLanguageTag("lt")
        @JvmField val LOCALE_LV: Locale = Locale.forLanguageTag("lv")
        @JvmField val LOCALE_ML_IN: Locale = Locale.forLanguageTag("ml-IN")
        @JvmField val LOCALE_MR_IN: Locale = Locale.forLanguageTag("mr-IN")
        @JvmField val LOCALE_NE: Locale = Locale.forLanguageTag("ne")
        @JvmField val LOCALE_NE_IN: Locale = Locale.forLanguageTag("ne-IN")
        @JvmField val LOCALE_NL: Locale = Locale.forLanguageTag("nl")
        @JvmField val LOCALE_NL_NL: Locale = Locale.forLanguageTag("nl-NL")
        @JvmField val LOCALE_NO: Locale = Locale.forLanguageTag("no")
        @JvmField val LOCALE_PA: Locale = Locale.forLanguageTag("pa")
        @JvmField val LOCALE_PL: Locale = Locale.forLanguageTag("pl")
        @JvmField val LOCALE_PT: Locale = Locale.forLanguageTag("pt")
        @JvmField val LOCALE_RO: Locale = Locale.forLanguageTag("ro")
        @JvmField val LOCALE_RU: Locale = Locale.forLanguageTag("ru")
        @JvmField val LOCALE_SD_IN: Locale = Locale.forLanguageTag("sd-IN")
        @JvmField val LOCALE_SE: Locale = Locale.forLanguageTag("se")
        @JvmField val LOCALE_SI: Locale = Locale.forLanguageTag("si")
        @JvmField val LOCALE_SK: Locale = Locale.forLanguageTag("sk")
        @JvmField val LOCALE_SR: Locale = Locale.forLanguageTag("sr")
        @JvmField val LOCALE_SV: Locale = Locale.forLanguageTag("sv")
        @JvmField val LOCALE_TA: Locale = Locale.forLanguageTag("ta")
        @JvmField val LOCALE_TE_IN: Locale = Locale.forLanguageTag("te-IN")
        @JvmField val LOCALE_TR: Locale = Locale.forLanguageTag("tr")
        @JvmField val LOCALE_UK: Locale = Locale.forLanguageTag("uk")
        @JvmField val LOCALE_UR: Locale = Locale.forLanguageTag("ur")
        @JvmField val LOCALE_VI: Locale = Locale.forLanguageTag("vi")
        @JvmField val LOCALE_YUE: Locale = Locale.forLanguageTag("yue")
        @JvmField val LOCALE_ZH_CN: Locale = Locale.SIMPLIFIED_CHINESE
        @JvmField val LOCALE_ZH_TW: Locale = Locale.TRADITIONAL_CHINESE

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
            LOCALE_AR,
            R.string.code_user_facing_name_arabic,
            /* supportedContracted= */ true,
            /* eightDot= */ false,
        ) {
            override fun createEditBuffer(
                context: Context,
                talkBack: TalkBackSpeaker,
                translator: BrailleTranslator,
                contractedMode: Boolean,
            ): EditBuffer {
                if (contractedMode) {
                    return EditBufferArabic2(context, translator, talkBack)
                }
                return EditBufferUnContracted(context, translator, talkBack)
            }
        },
        ARABIC_COMP8(
            LOCALE_AR,
            R.string.code_user_facing_name_arabic_comp8,
            /* supportedContracted= */ false,
            /* eightDot= */ true,
        ),
        BULGARIAN(
            LOCALE_BG,
            R.string.code_user_facing_name_bulgarian,
            /* supportedContracted= */ false,
            /* eightDot= */ false,
        ) {
            override fun createEditBuffer(
                context: Context,
                talkBack: TalkBackSpeaker,
                translator: BrailleTranslator,
                contractedMode: Boolean,
            ): EditBuffer {
                return EditBufferBulgarian(context, translator, talkBack)
            }
        },
        BULGARIAN_COMP8(
            LOCALE_BG,
            R.string.code_user_facing_name_bulgarian_comp8,
            /* supportedContracted= */ false,
            /* eightDot= */ true,
        ),
        CANTONESE(
            LOCALE_YUE,
            R.string.code_user_facing_name_catonese,
            /* supportedContracted= */ false,
            /* eightDot= */ false,
        ),
        CATALAN(
            LOCALE_CA,
            R.string.code_user_facing_name_ca_catalan,
            /* supportedContracted= */ false,
            /* eightDot= */ false,
        ) {
            override fun createEditBuffer(
                context: Context,
                talkBack: TalkBackSpeaker,
                translator: BrailleTranslator,
                contractedMode: Boolean,
            ): EditBuffer {
                return EditBufferCatalan(context, translator, talkBack)
            }
        },
        CHINESE_CHINA_CURRENT_WITH_TONES(
            LOCALE_ZH_CN,
            R.string.code_user_facing_name_chinese_china_current_with_tones,
            /* supportedContracted= */ false,
            /* eightDot= */ false,
        ) {
            override fun createEditBuffer(
                context: Context,
                talkBack: TalkBackSpeaker,
                translator: BrailleTranslator,
                contractedMode: Boolean,
            ): EditBuffer {
                // 一个音节 2~3 格，必须按音节切分再翻译，否则连续输入会出现跨字歧义。
                return EditBufferChinese.create(context, translator, talkBack)
            }
        },
        CHINESE_CHINA_CURRENT_WITHOUT_TONES(
            LOCALE_ZH_CN,
            R.string.code_user_facing_name_chinese_china_current_without_tones,
            /* supportedContracted= */ false,
            /* eightDot= */ false,
        ),
        CHINESE_CHINA_TWO_CELLS(
            LOCALE_ZH_CN,
            R.string.code_user_facing_name_chinese_china_two_cells,
            /* supportedContracted= */ false,
            /* eightDot= */ false,
        ),
        CHINESE_CHINA_COMMON(
            LOCALE_ZH_CN,
            R.string.code_user_facing_name_chinese_china_common,
            /* supportedContracted= */ false,
            /* eightDot= */ false,
        ),
        CHINESE_TAIWAN(
            LOCALE_ZH_TW,
            R.string.code_user_facing_name_chinese_taiwan,
            /* supportedContracted= */ false,
            /* eightDot= */ false,
        ),
        CROATIAN(
            LOCALE_HR,
            R.string.code_user_facing_name_croatian,
            /* supportedContracted= */ false,
            /* eightDot= */ false,
        ) {
            override fun createEditBuffer(
                context: Context,
                talkBack: TalkBackSpeaker,
                translator: BrailleTranslator,
                contractedMode: Boolean,
            ): EditBuffer {
                return EditBufferCroatian(context, translator, talkBack)
            }
        },
        CROATIAN_COMP8(
            LOCALE_HR,
            R.string.code_user_facing_name_croatian_comp8,
            /* supportedContracted= */ false,
            /* eightDot= */ true,
        ),
        CZECH(
            LOCALE_CS,
            R.string.code_user_facing_name_czech,
            /* supportedContracted= */ false,
            /* eightDot= */ false,
        ),
        CZECH_COMP8(
            LOCALE_CS,
            R.string.code_user_facing_name_czech_comp8,
            /* supportedContracted= */ false,
            /* eightDot= */ true,
        ),
        DANISH(
            LOCALE_DA,
            R.string.code_user_facing_name_danish,
            /* supportedContracted= */ true,
            /* eightDot= */ false,
        ) {
            override fun createEditBuffer(
                context: Context,
                talkBack: TalkBackSpeaker,
                translator: BrailleTranslator,
                contractedMode: Boolean,
            ): EditBuffer {
                if (contractedMode) {
                    return EditBufferDanish2(context, translator, talkBack)
                }
                return EditBufferDanish(context, translator, talkBack)
            }
        },
        DANISH_8(
            LOCALE_DA,
            R.string.code_user_facing_name_danish_eight,
            /* supportedContracted= */ true,
            /* eightDot= */ true,
        ) {
            override fun createEditBuffer(
                context: Context,
                talkBack: TalkBackSpeaker,
                translator: BrailleTranslator,
                contractedMode: Boolean,
            ): EditBuffer {
                if (contractedMode) {
                    return EditBufferDanish2(context, translator, talkBack)
                }
                return EditBufferDanish(context, translator, talkBack)
            }
        },
        DANISH_COMP8(
            LOCALE_DA,
            R.string.code_user_facing_name_danish_comp8,
            /* supportedContracted= */ false,
            /* eightDot= */ true,
        ),
        DUTCH_NL(
            LOCALE_NL_NL,
            R.string.code_user_facing_name_dutch_nl,
            /* supportedContracted= */ false,
            /* eightDot= */ false,
        ) {
            override fun createEditBuffer(
                context: Context,
                talkBack: TalkBackSpeaker,
                translator: BrailleTranslator,
                contractedMode: Boolean,
            ): EditBuffer {
                return EditBufferDutch(context, translator, talkBack)
            }
        },
        DUTCH_COMP8(
            LOCALE_NL,
            R.string.code_user_facing_name_dutch_comp8,
            /* supportedContracted= */ false,
            /* eightDot= */ true,
        ),
        EN_IN(
            LOCALE_EN_IN,
            R.string.code_user_facing_name_en_in,
            /* supportedContracted= */ false,
            /* eightDot= */ false,
        ),
        UEB(
            LOCALE_EN,
            R.string.code_user_facing_name_ueb1,
            /* supportedContracted= */ true,
            /* eightDot= */ false,
        ) {
            override fun createEditBuffer(
                context: Context,
                talkBack: TalkBackSpeaker,
                translator: BrailleTranslator,
                contractedMode: Boolean,
            ): EditBuffer {
                if (contractedMode) {
                    return EditBufferUeb2(context, translator, talkBack)
                }
                return EditBufferUnContracted(context, translator, talkBack)
            }
        },
        EN_UK(
            LOCALE_EN_GB,
            R.string.code_user_facing_name_en_uk,
            /* supportedContracted= */ true,
            /* eightDot= */ false,
        ) {
            override fun createEditBuffer(
                context: Context,
                talkBack: TalkBackSpeaker,
                translator: BrailleTranslator,
                contractedMode: Boolean,
            ): EditBuffer {
                if (contractedMode) {
                    return EditBufferBritish2(context, translator, talkBack)
                }
                return EditBufferUnContracted(context, translator, talkBack)
            }
        },
        EN_US_EBAE(
            LOCALE_EN_US,
            R.string.code_user_facing_name_en_us_ebae,
            /* supportedContracted= */ true,
            /* eightDot= */ false,
        ) {
            override fun createEditBuffer(
                context: Context,
                talkBack: TalkBackSpeaker,
                translator: BrailleTranslator,
                contractedMode: Boolean,
            ): EditBuffer {
                if (contractedMode) {
                    return EditBufferEbae2(context, translator, talkBack)
                }
                return EditBufferUnContracted(context, translator, talkBack)
            }
        },
        EN_NABCC(
            LOCALE_EN,
            R.string.code_user_facing_name_en_nabcc_comp8,
            /* supportedContracted= */ false,
            /* eightDot= */ true,
        ),
        EN_COMP6(
            LOCALE_EN_US,
            R.string.code_user_facing_name_en_comp6,
            /* supportedContracted= */ false,
            /* eightDot= */ false,
        ) {
            override fun createEditBuffer(
                context: Context,
                talkBack: TalkBackSpeaker,
                translator: BrailleTranslator,
                contractedMode: Boolean,
            ): EditBuffer {
                return EditBufferEnComp6(context, translator, talkBack)
            }
        },
        ESTONIAN(
            LOCALE_ET,
            R.string.code_user_facing_name_estonian,
            /* supportedContracted= */ false,
            /* eightDot= */ true,
        ),
        FINNISH(
            LOCALE_FI,
            R.string.code_user_facing_name_finnish,
            /* supportedContracted= */ false,
            /* eightDot= */ false,
        ),
        FINNISH_COMP8(
            LOCALE_FI,
            R.string.code_user_facing_name_finnish_comp8,
            /* supportedContracted= */ false,
            /* eightDot= */ true,
        ),
        FRENCH(
            LOCALE_FR,
            R.string.code_user_facing_name_french,
            /* supportedContracted= */ true,
            /* eightDot= */ false,
        ) {
            override fun createEditBuffer(
                context: Context,
                talkBack: TalkBackSpeaker,
                translator: BrailleTranslator,
                contractedMode: Boolean,
            ): EditBuffer {
                if (contractedMode) {
                    return EditBufferFrench2(context, translator, talkBack)
                }
                return EditBufferFrench(context, translator, talkBack)
            }
        },
        FRENCH_COMP8(
            LOCALE_FR,
            R.string.code_user_facing_name_french_comp8,
            /* supportedContracted= */ false,
            /* eightDot= */ true,
        ),
        GREEK(
            LOCALE_EL,
            R.string.code_user_facing_name_greek,
            /* supportedContracted= */ false,
            /* eightDot= */ false,
        ) {
            override fun createEditBuffer(
                context: Context,
                talkBack: TalkBackSpeaker,
                translator: BrailleTranslator,
                contractedMode: Boolean,
            ): EditBuffer {
                return EditBufferGreek(context, translator, talkBack)
            }
        },
        GREEK_INTERNATIONAL(
            LOCALE_GRC,
            R.string.code_user_facing_name_greek_international,
            /* supportedContracted= */ false,
            /* eightDot= */ false,
        ),
        GERMAN(
            LOCALE_DE,
            R.string.code_user_facing_name_german,
            /* supportedContracted= */ true,
            /* eightDot= */ false,
        ) {
            override fun createEditBuffer(
                context: Context,
                talkBack: TalkBackSpeaker,
                translator: BrailleTranslator,
                contractedMode: Boolean,
            ): EditBuffer {
                if (contractedMode) {
                    return EditBufferGerman2(context, translator, talkBack)
                }
                return EditBufferGerman(context, translator, talkBack)
            }
        },
        GERMAN_COMP8(
            LOCALE_DE,
            R.string.code_user_facing_name_german_comp8,
            /* supportedContracted= */ false,
            /* eightDot= */ true,
        ),
        GUJARATI(
            LOCALE_GU_IN,
            R.string.code_user_facing_name_gujarati_india,
            /* supportedContracted= */ false,
            /* eightDot= */ false,
        ),
        HEBREW(
            LOCALE_HE_IL,
            R.string.code_user_facing_name_hebrew,
            /* supportedContracted= */ false,
            /* eightDot= */ false,
        ),
        HEBREW_COMP8(
            LOCALE_HE_IL,
            R.string.code_user_facing_name_hebrew_comp8,
            /* supportedContracted= */ false,
            /* eightDot= */ true,
        ),
        HINDI(
            LOCALE_HI_IN,
            R.string.code_user_facing_name_hindi_india,
            /* supportedContracted= */ false,
            /* eightDot= */ false,
        ),
        HUNGARIAN(
            LOCALE_HU,
            R.string.code_user_facing_name_hungarian,
            /* supportedContracted= */ true,
            /* eightDot= */ false,
        ) {
            override fun createEditBuffer(
                context: Context,
                talkBack: TalkBackSpeaker,
                translator: BrailleTranslator,
                contractedMode: Boolean,
            ): EditBuffer {
                if (contractedMode) {
                    return EditBufferHungarian2(context, translator, talkBack)
                }
                return EditBufferHungarian(context, translator, talkBack)
            }
        },
        HUNGARIAN_COMP8(
            LOCALE_HU,
            R.string.code_user_facing_name_hungarian_comp8,
            /* supportedContracted= */ false,
            /* eightDot= */ true,
        ),
        ICELANDIC(
            LOCALE_IS,
            R.string.code_user_facing_name_icelandic,
            /* supportedContracted= */ false,
            /* eightDot= */ false,
        ),
        ICELANDIC_COMP8(
            LOCALE_IS,
            R.string.code_user_facing_name_icelandic_comp8,
            /* supportedContracted= */ false,
            /* eightDot= */ true,
        ),
        ITALIAN_COMP6(
            LOCALE_IT,
            R.string.code_user_facing_name_italian_comp6,
            /* supportedContracted= */ false,
            /* eightDot= */ false,
        ) {
            override fun createEditBuffer(
                context: Context,
                talkBack: TalkBackSpeaker,
                translator: BrailleTranslator,
                contractedMode: Boolean,
            ): EditBuffer {
                return EditBufferItalian(context, translator, talkBack)
            }
        },
        ITALIAN_COMP8(
            LOCALE_IT,
            R.string.code_user_facing_name_italian_comp8,
            /* supportedContracted= */ false,
            /* eightDot= */ true,
        ),
        JAPANESE(
            LOCALE_JP,
            R.string.code_user_facing_name_japanese,
            /* supportedContracted= */ false,
            /* eightDot= */ true,
        ),
        KANNADA(
            LOCALE_KN,
            R.string.code_user_facing_name_kannada,
            /* supportedContracted= */ false,
            /* eightDot= */ false,
        ),
        KHMER(
            LOCALE_KM,
            R.string.code_user_facing_name_khmer,
            /* supportedContracted= */ false,
            /* eightDot= */ false,
        ),
        KOREAN_2006(
            LOCALE_KO,
            R.string.code_user_facing_name_korean_2006,
            /* supportedContracted= */ true,
            /* eightDot= */ false,
        ) {
            override fun createEditBuffer(
                context: Context,
                talkBack: TalkBackSpeaker,
                translator: BrailleTranslator,
                contractedMode: Boolean,
            ): EditBuffer {
                if (contractedMode) {
                    return EditBufferKorean2(context, translator, talkBack)
                }
                return EditBufferKorean(context, translator, talkBack)
            }
        },
        // There are 3 kinds of Kurdish, each of them has different locale. It should be central Kurdish
        // but since it has been released, keep it as it.
        KURDISH(
            LOCALE_CKB,
            R.string.code_user_facing_name_central_kurdish,
            /* supportedContracted= */ false,
            /* eightDot= */ false,
        ),
        LATVIAN(
            LOCALE_LV,
            R.string.code_user_facing_name_latvian,
            /* supportedContracted= */ false,
            /* eightDot= */ false,
        ) {
            override fun createEditBuffer(
                context: Context,
                talkBack: TalkBackSpeaker,
                translator: BrailleTranslator,
                contractedMode: Boolean,
            ): EditBuffer {
                return EditBufferLatvian(context, translator, talkBack)
            }
        },
        LITHUANIAN(
            LOCALE_LT,
            R.string.code_user_facing_name_lithuanian,
            /* supportedContracted= */ false,
            /* eightDot= */ false,
        ) {
            override fun createEditBuffer(
                context: Context,
                talkBack: TalkBackSpeaker,
                translator: BrailleTranslator,
                contractedMode: Boolean,
            ): EditBuffer {
                return EditBufferLithuanian(context, translator, talkBack)
            }
        },
        LITHUANIAN_8(
            LOCALE_LT,
            R.string.code_user_facing_name_lithuanian_8,
            /* supportedContracted= */ false,
            /* eightDot= */ true,
        ),
        MALAYALAM_IN(
            LOCALE_ML_IN,
            R.string.code_user_facing_name_malayalam_indian,
            /* supportedContracted= */ false,
            /* eightDot= */ false,
        ),
        MARATHI_IN(
            LOCALE_MR_IN,
            R.string.code_user_facing_name_marathi_indian,
            /* supportedContracted= */ false,
            /* eightDot= */ false,
        ),
        NEPALI(
            LOCALE_NE,
            R.string.code_user_facing_name_nepali,
            /* supportedContracted= */ false,
            /* eightDot= */ true,
        ),
        NEPALI_IN(
            LOCALE_NE_IN,
            R.string.code_user_facing_name_nepali_indian,
            /* supportedContracted= */ false,
            /* eightDot= */ false,
        ),
        NORWEGIAN(
            LOCALE_NO,
            R.string.code_user_facing_name_norwegian,
            /* supportedContracted= */ true,
            /* eightDot= */ false,
        ) {
            override fun createEditBuffer(
                context: Context,
                talkBack: TalkBackSpeaker,
                translator: BrailleTranslator,
                contractedMode: Boolean,
            ): EditBuffer {
                if (contractedMode) {
                    return EditBufferNorwegian2(context, translator, talkBack)
                }
                return EditBufferUnContracted(context, translator, talkBack)
            }
        },
        NORWEGIAN_8_NO(
            LOCALE_NO,
            R.string.code_user_facing_name_norwegian_8_no,
            /* supportedContracted= */ false,
            /* eightDot= */ true,
        ),
        NORWEGIAN_COMP8(
            LOCALE_NO,
            R.string.code_user_facing_name_norwegian_comp8,
            /* supportedContracted= */ false,
            /* eightDot= */ true,
        ),
        NORWEGIAN_8(
            LOCALE_NO,
            R.string.code_user_facing_name_norwegian_8,
            /* supportedContracted= */ false,
            /* eightDot= */ true,
        ),
        NORWEGIAN_8_6FALLBACK(
            LOCALE_NO,
            R.string.code_user_facing_name_norwegian_8_with_6_fallback,
            /* supportedContracted= */ false,
            /* eightDot= */ true,
        ),
        POLISH(
            LOCALE_PL,
            R.string.code_user_facing_name_polish,
            /* supportedContracted= */ false,
            /* eightDot= */ false,
        ) {
            override fun createEditBuffer(
                context: Context,
                talkBack: TalkBackSpeaker,
                translator: BrailleTranslator,
                contractedMode: Boolean,
            ): EditBuffer {
                return EditBufferPolish(context, translator, talkBack)
            }
        },
        POLISH_COMP8(
            LOCALE_PL,
            R.string.code_user_facing_name_polish_comp8,
            /* supportedContracted= */ false,
            /* eightDot= */ true,
        ),
        PORTUGUESE(
            LOCALE_PT,
            R.string.code_user_facing_name_portuguese,
            /* supportedContracted= */ true,
            /* eightDot= */ false,
        ) {
            override fun createEditBuffer(
                context: Context,
                talkBack: TalkBackSpeaker,
                translator: BrailleTranslator,
                contractedMode: Boolean,
            ): EditBuffer {
                if (contractedMode) {
                    return EditBufferPortuguese2(context, translator, talkBack)
                }
                return EditBufferPortuguese(context, translator, talkBack)
            }
        },
        PORTUGUESE_COMP8(
            LOCALE_PT,
            R.string.code_user_facing_name_portuguese_comp8,
            /* supportedContracted= */ false,
            /* eightDot= */ true,
        ),
        PUNJABI(
            LOCALE_PA,
            R.string.code_user_facing_name_punjabi,
            /* supportedContracted= */ false,
            /* eightDot= */ false,
        ),
        ROMANIAN_COMP8(
            LOCALE_RO,
            R.string.code_user_facing_name_romanian_comp8,
            /* supportedContracted= */ false,
            /* eightDot= */ true,
        ),
        RUSSIAN(
            LOCALE_RU,
            R.string.code_user_facing_name_russian,
            /* supportedContracted= */ false, // Contracted.
            /* eightDot= */ false,
        ) {
            override fun createEditBuffer(
                context: Context,
                talkBack: TalkBackSpeaker,
                translator: BrailleTranslator,
                contractedMode: Boolean,
            ): EditBuffer {
                return EditBufferRussian(context, translator, talkBack)
            }
        },
        RUSSIAN_DETAILED(
            LOCALE_RU,
            R.string.code_user_facing_name_russian_detailed,
            /* supportedContracted= */ false,
            /* eightDot= */ false,
        ) {
            override fun createEditBuffer(
                context: Context,
                talkBack: TalkBackSpeaker,
                translator: BrailleTranslator,
                contractedMode: Boolean,
            ): EditBuffer {
                return EditBufferRussian(context, translator, talkBack)
            }
        },
        RUSSIAN_COMP8(
            LOCALE_RU,
            R.string.code_user_facing_name_russian_comp8,
            /* supportedContracted= */ false,
            /* eightDot= */ true,
        ),
        SERBIAN(
            LOCALE_SR,
            R.string.code_user_facing_name_serbian,
            /* supportedContracted= */ false,
            /* eightDot= */ false,
        ) {
            override fun createEditBuffer(
                context: Context,
                talkBack: TalkBackSpeaker,
                translator: BrailleTranslator,
                contractedMode: Boolean,
            ): EditBuffer {
                return EditBufferSerbian(context, translator, talkBack)
            }
        },
        SINDHI_IN(
            LOCALE_SD_IN,
            R.string.code_user_facing_name_sindhi_indian,
            /* supportedContracted= */ false,
            /* eightDot= */ false,
        ),
        SINHALA(
            LOCALE_SI,
            R.string.code_user_facing_name_sinhala,
            /* supportedContracted= */ false,
            /* eightDot= */ false,
        ),
        SLOVAK(
            LOCALE_SK,
            R.string.code_user_facing_name_slovak,
            /* supportedContracted= */ false,
            /* eightDot= */ false,
        ),
        SPANISH(
            LOCALE_ES,
            R.string.code_user_facing_name_spanish,
            /* supportedContracted= */ true,
            /* eightDot= */ false,
        ) {
            override fun createEditBuffer(
                context: Context,
                talkBack: TalkBackSpeaker,
                translator: BrailleTranslator,
                contractedMode: Boolean,
            ): EditBuffer {
                if (contractedMode) {
                    return EditBufferSpanish2(context, translator, talkBack)
                }
                return EditBufferSpanish(context, translator, talkBack)
            }
        },
        SPANISH_COMP8(
            LOCALE_ES,
            R.string.code_user_facing_name_spanish_comp8,
            /* supportedContracted= */ false,
            /* eightDot= */ true,
        ),
        SWEDEN(
            LOCALE_SV,
            R.string.code_user_facing_name_sweden,
            /* supportedContracted= */ true,
            /* eightDot= */ false,
        ) {
            override fun createEditBuffer(
                context: Context,
                talkBack: TalkBackSpeaker,
                translator: BrailleTranslator,
                contractedMode: Boolean,
            ): EditBuffer {
                if (contractedMode) {
                    return EditBufferSwedish2(context, translator, talkBack)
                }
                return EditBufferUnContracted(context, translator, talkBack)
            }
        },
        SWEDEN_8(
            LOCALE_SE,
            R.string.code_user_facing_name_sweden_8,
            /* supportedContracted= */ false,
            /* eightDot= */ true,
        ),
        SWEDISH_COMP8_1996(
            LOCALE_SV,
            R.string.code_user_facing_name_swedish_comp8_1996,
            /* supportedContracted= */ false,
            /* eightDot= */ true,
        ),
        SWEDISH_COMP8_1989(
            LOCALE_SV,
            R.string.code_user_facing_name_swedish_comp8_1989,
            /* supportedContracted= */ false,
            /* eightDot= */ true,
        ),
        TAMIL(
            LOCALE_TA,
            R.string.code_user_facing_name_tamil,
            /* supportedContracted= */ false,
            /* eightDot= */ false,
        ),
        TELUGU_IN(
            LOCALE_TE_IN,
            R.string.code_user_facing_name_telugu_in,
            /* supportedContracted= */ false,
            /* eightDot= */ false,
        ),
        TURKISH(
            LOCALE_TR,
            R.string.code_user_facing_name_turkish,
            /* supportedContracted= */ true,
            /* eightDot= */ false,
        ) {
            override fun createEditBuffer(
                context: Context,
                talkBack: TalkBackSpeaker,
                translator: BrailleTranslator,
                contractedMode: Boolean,
            ): EditBuffer {
                if (contractedMode) {
                    return EditBufferTurkish2(context, translator, talkBack)
                }
                return EditBufferUnContracted(context, translator, talkBack)
            }
        },
        TURKISH_COMP8(
            LOCALE_TR,
            R.string.code_user_facing_name_turkish_comp8,
            /* supportedContracted= */ false,
            /* eightDot= */ true,
        ),
        UKRAINIAN(
            LOCALE_UK,
            R.string.code_user_facing_name_ukrainian,
            /* supportedContracted= */ false,
            /* eightDot= */ false,
        ) {
            override fun createEditBuffer(
                context: Context,
                talkBack: TalkBackSpeaker,
                translator: BrailleTranslator,
                contractedMode: Boolean,
            ): EditBuffer {
                // Based on Russian.
                return EditBufferRussian(context, translator, talkBack)
            }
        },
        UKRAINIAN_COMP8(
            LOCALE_UK,
            R.string.code_user_facing_name_ukrainian_comp8,
            /* supportedContracted= */ false,
            /* eightDot= */ true,
        ),
        URDU(
            LOCALE_UR,
            R.string.code_user_facing_name_urdu,
            /* supportedContracted= */ true,
            /* eightDot= */ false,
        ) {
            override fun createEditBuffer(
                context: Context,
                talkBack: TalkBackSpeaker,
                translator: BrailleTranslator,
                contractedMode: Boolean,
            ): EditBuffer {
                if (contractedMode) {
                    return EditBufferUrdu2(context, translator, talkBack)
                }
                return EditBufferUnContracted(context, translator, talkBack)
            }
        },
        VIETNAMESE(
            LOCALE_VI,
            R.string.code_user_facing_name_vietnamese,
            /* supportedContracted= */ true,
            /* eightDot= */ false,
        ) {
            override fun createEditBuffer(
                context: Context,
                talkBack: TalkBackSpeaker,
                translator: BrailleTranslator,
                contractedMode: Boolean,
            ): EditBuffer {
                if (contractedMode) {
                    return EditBufferVietnamese2(context, translator, talkBack)
                }
                return EditBufferVietnamese(context, translator, talkBack)
            }
        },
        VIETNAMESE_COMP8(
            LOCALE_VI,
            R.string.code_user_facing_name_vietnamese_comp8,
            /* supportedContracted= */ false,
            /* eightDot= */ true,
        ),
        WELSH(
            LOCALE_CY,
            R.string.code_user_facing_name_welsh,
            /* supportedContracted= */ true,
            /* eightDot= */ false,
        ) {
            override fun createEditBuffer(
                context: Context,
                talkBack: TalkBackSpeaker,
                translator: BrailleTranslator,
                contractedMode: Boolean,
            ): EditBuffer {
                if (contractedMode) {
                    return EditBufferWelsh2(context, translator, talkBack)
                }
                return EditBufferUnContracted(context, translator, talkBack)
            }
        },
        STUB(
            LOCALE_EN,
            ResourcesCompat.ID_NULL,
            /* supportedContracted= */ false,
            /* eightDot= */ false,
        ) {
            override fun createEditBuffer(
                context: Context,
                talkBack: TalkBackSpeaker,
                translator: BrailleTranslator,
                contractedMode: Boolean,
            ): EditBuffer {
                return EditBufferStub(context, translator, talkBack)
            }
        };
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
        ): EditBuffer {
            return EditBufferUnContracted(context, translator, talkBack)
        }


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
