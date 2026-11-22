/*
 * Copyright 2019 Google Inc.
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

import android.content.Context
import com.google.android.accessibility.braille.translate.BrailleTranslator
import com.google.android.accessibility.braille.translate.TranslatorFactory

/** Creates Translator instances that delegate to the LibLouis braille translation library. */
class LibLouis : TranslatorFactory {

  override fun create(context: Context, codeName: String, contractedMode: Boolean): BrailleTranslator =
      when (codeName) {
        "ARABIC" -> {
          val uncontracted = LibLouisTranslator(context, "ar-ar-g1.utb")
          val contracted = LibLouisTranslator(context, "ar-ar-g2.ctb")
          if (contractedMode) {
            ExpandableContractedTranslator(uncontracted, contracted)
          } else {
            uncontracted
          }
        }
        "ARABIC_COMP8" -> LibLouisTranslator(context, "ar-ar-comp8.utb")
        "BULGARIAN" -> LibLouisTranslator(context, "bg.utb")
        "BULGARIAN_COMP8" -> LibLouisTranslator(context, "bg.tbl")
        "CANTONESE" -> LibLouisTranslator(context, "zh-hk.ctb")
        "CHINESE_TAIWAN" -> LibLouisTranslator(context, "zh-tw.ctb")
        "CHINESE_CHINA_CURRENT_WITH_TONES" -> LibLouisTranslator(context, "zhcn-g1.ctb")
        "CHINESE_CHINA_CURRENT_WITHOUT_TONES" -> LibLouisTranslator(context, "zh_CHN.tbl")
        "CHINESE_CHINA_TWO_CELLS" -> LibLouisTranslator(context, "zhcn-g2.ctb")
        "CHINESE_CHINA_COMMON" -> LibLouisTranslator(context, "zhcn-cbs.ctb")
        "CATALAN" -> LibLouisTranslator(context, "ca.tbl")
        "CROATIAN" -> LibLouisTranslator(context, "hr-g1.tbl")
        "CROATIAN_COMP8" -> LibLouisTranslator(context, "hr-comp8.tbl")
        "CZECH" -> LibLouisTranslator(context, "cs.tbl")
        "CZECH_COMP8" -> LibLouisTranslator(context, "cs-comp8.utb")
        "DANISH" -> getBrailleTranslator(context, contractedMode, "da-dk-g16.ctb", "da-dk-g26.ctb")
        "DANISH_8" -> getBrailleTranslator(context, contractedMode, "da-dk-g18.ctb", "da-dk-g28.ctb")
        "DANISH_COMP8" -> LibLouisTranslator(context, "da-dk-g08.ctb")
        "DUTCH_COMP8" -> LibLouisTranslator(context, "nl-comp8.utb")
        "DUTCH_NL" -> LibLouisTranslator(context, "nl-NL-g0.utb")
        "EN_UK" -> getBrailleTranslator(context, contractedMode, "en-gb-g1.utb", "en_GB.tbl")
        "EN_IN" -> LibLouisTranslator(context, "en-in-g1.ctb")
        "EN_NABCC" -> LibLouisTranslator(context, "en-nabcc.utb")
        "EN_COMP6" -> LibLouisTranslator(context, "en-us-comp6.ctb")
        "UEB" ->
            getBrailleTranslator(
                contractedMode,
                LibLouisTranslatorUeb1(context),
                LibLouisTranslatorUeb2(context))
        "EN_US_EBAE" -> getBrailleTranslator(context, contractedMode, "en-us-g1.ctb", "en_US.tbl")
        "ESTONIAN" -> LibLouisTranslator(context, "et-g0.utb")
        "FINNISH" -> LibLouisTranslator(context, "fi.utb")
        "FINNISH_COMP8" -> LibLouisTranslator(context, "fi-fi-8dot.ctb")
        "FRENCH" ->
            getBrailleTranslator(
                contractedMode,
                LibLouisTranslatorFrench(context),
                LibLouisTranslator(context, "fr-bfu-g2.ctb"))
        "FRENCH_COMP8" -> LibLouisTranslator(context, "fr-bfu-comp8.utb")
        "GERMAN" -> getBrailleTranslator(context, contractedMode, "de-g0.utb", "de-g2.ctb")
        "GERMAN_COMP8" -> LibLouisTranslator(context, "de-de-comp8.ctb")
        "GREEK" -> LibLouisTranslator(context, "el.ctb")
        "GREEK_INTERNATIONAL" -> LibLouisTranslator(context, "grc-international-en.utb")
        "GUJARATI" -> LibLouisTranslator(context, "gu.tbl")
        "HEBREW" -> LibLouisTranslator(context, "he-IL.utb")
        "HEBREW_COMP8" -> LibLouisTranslator(context, "he-IL-comp8.utb")
        "HINDI" -> LibLouisTranslator(context, "hi.tbl")
        "HUNGARIAN" -> getBrailleTranslator(context, contractedMode, "hu.tbl", "hu-hu-g2.ctb")
        "HUNGARIAN_COMP8" -> LibLouisTranslator(context, "hu-hu-comp8.ctb")
        "ICELANDIC" -> LibLouisTranslator(context, "is.tbl")
        "ICELANDIC_COMP8" -> LibLouisTranslator(context, "is.ctb")
        "ITALIAN_COMP6" -> LibLouisTranslator(context, "it.tbl")
        "ITALIAN_COMP8" -> LibLouisTranslator(context, "it-it-comp8.utb")
        "JAPANESE" -> LibLouisTranslator(context, "ja-kantenji-ucs2.utb")
        "KANNADA" -> LibLouisTranslator(context, "kn.tbl")
        "KHMER" -> LibLouisTranslator(context, "km-g1.utb")
        "KOREAN" -> getBrailleTranslator(context, contractedMode, "ko-g1.ctb", "ko-g2.ctb")
        "KOREAN_2006" ->
            getBrailleTranslator(context, contractedMode, "ko-2006-g1.ctb", "ko-2006-g2.ctb")
        "KURDISH" -> LibLouisTranslator(context, "ckb.tbl")
        "LITHUANIAN" -> LibLouisTranslator(context, "lt-6dot.utb")
        "LITHUANIAN_8" -> LibLouisTranslator(context, "lt-8dot.utb")
        "LATVIAN" -> LibLouisTranslator(context, "lv.tbl")
        "MALAYALAM_IN" -> LibLouisTranslator(context, "ml.tbl")
        "MARATHI_IN" -> LibLouisTranslator(context, "mr.tbl")
        "NEPALI" -> LibLouisTranslator(context, "ne.ctb")
        "NEPALI_IN" -> LibLouisTranslator(context, "ne.tbl")
        "NORWEGIAN" -> getBrailleTranslator(context, contractedMode, "no-no-g0.utb", "no-no-g3.ctb")
        "NORWEGIAN_8" -> LibLouisTranslator(context, "no-no-8dot.utb")
        "NORWEGIAN_8_6FALLBACK" -> LibLouisTranslator(context, "no-no-8dot-fallback-6dot-g0.utb")
        "NORWEGIAN_8_NO" -> LibLouisTranslator(context, "no-no-generic.ctb")
        "NORWEGIAN_COMP8" -> LibLouisTranslator(context, "no-no-comp8.ctb")
        "POLISH" -> LibLouisTranslator(context, "pl.tbl")
        "POLISH_COMP8" -> LibLouisTranslator(context, "pl-pl-comp8.ctb")
        "PORTUGUESE" -> getBrailleTranslator(context, contractedMode, "pt-pt-g1.utb", "pt.tbl")
        "PORTUGUESE_COMP8" -> LibLouisTranslator(context, "pt-pt-comp8.ctb")
        "PUNJABI" -> LibLouisTranslator(context, "pa.tbl")
        "ROMANIAN_COMP8" -> LibLouisTranslator(context, "ro.tbl")
        "RUSSIAN" -> LibLouisTranslator(context, "ru-ru-g1.ctb")
        "RUSSIAN_COMP8" -> LibLouisTranslator(context, "ru-comp8.utb")
        "RUSSIAN_DETAILED" -> LibLouisTranslator(context, "ru-litbrl-detailed.utb")
        "SERBIAN" -> LibLouisTranslator(context, "sr-g1.ctb")
        "SINDHI_IN" -> LibLouisTranslator(context, "sd.tbl")
        "SINHALA" -> LibLouisTranslator(context, "sin.utb")
        "SLOVAK" -> LibLouisTranslator(context, "sk-g1.ctb")
        "SPANISH" ->
            getBrailleTranslator(
                contractedMode,
                LibLouisTranslatorSpanish(context),
                LibLouisTranslator(context, "es-g2.ctb"))
        "SPANISH_COMP8" -> LibLouisTranslator(context, "Es-Es-G0.utb")
        "SWEDEN" -> getBrailleTranslator(context, contractedMode, "sv-g0.utb", "sv-g2.ctb")
        "SWEDEN_8" -> LibLouisTranslator(context, "se-se.ctb")
        "SWEDISH_COMP8_1989" -> LibLouisTranslator(context, "sv-1989.ctb")
        "SWEDISH_COMP8_1996" -> LibLouisTranslator(context, "sv-1996.ctb")
        "TAMIL" -> LibLouisTranslator(context, "ta-ta-g1.ctb")
        "TELUGU_IN" -> LibLouisTranslator(context, "te.tbl")
        "TURKISH_COMP8" -> LibLouisTranslator(context, "tr.tbl")
        "TURKISH" -> getBrailleTranslator(context, contractedMode, "tr-g1.ctb", "tr-g2.tbl")
        "UKRAINIAN" -> LibLouisTranslator(context, "uk.utb")
        "UKRAINIAN_COMP8" -> LibLouisTranslator(context, "uk-comp.utb")
        "URDU" -> getBrailleTranslator(context, contractedMode, "ur-pk-g1.utb", "ur-pk-g2.ctb")
        "VIETNAMESE" -> getBrailleTranslator(context, contractedMode, "vi-vn-g0.utb", "vi-vn-g2.ctb")
        "VIETNAMESE_COMP8" -> LibLouisTranslator(context, "vi-cb8.utb")
        "WELSH" -> getBrailleTranslator(context, contractedMode, "cy-cy-g1.utb", "cy.tbl")
        else -> throw IllegalArgumentException("unrecognized code $codeName")
      }

  override fun getLibraryName(): String = javaClass.simpleName

  private fun getBrailleTranslator(
      context: Context,
      contractedMode: Boolean,
      grade1Table: String,
      grade2Table: String,
  ): BrailleTranslator =
      getBrailleTranslator(
          contractedMode,
          LibLouisTranslator(context, grade1Table),
          LibLouisTranslator(context, grade2Table))

  private fun getBrailleTranslator(
      contractedMode: Boolean,
      uncontracted: LibLouisTranslator,
      contracted: LibLouisTranslator,
  ): BrailleTranslator =
      if (contractedMode) {
        ExpandableContractedTranslator(uncontracted, contracted)
      } else {
        uncontracted
      }
}
