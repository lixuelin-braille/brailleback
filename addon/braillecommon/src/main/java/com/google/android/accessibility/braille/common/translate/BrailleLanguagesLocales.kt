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

import java.util.Locale

/**
 * 盲文语言清单用到的语言标签（[Locale]）常量集中定义。
 *
 * 标签字符串（如 "ar"、"en-IN"）与原清单逐字一致；拆分只做搬移，不改名、不归并、不调顺序。
 */
object BrailleLanguagesLocales {
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
}

