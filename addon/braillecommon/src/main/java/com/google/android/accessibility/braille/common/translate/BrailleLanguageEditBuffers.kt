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
import com.google.android.accessibility.braille.common.TalkBackSpeaker
import com.google.android.accessibility.braille.translate.BrailleTranslator

/**
 * 各 [BrailleLanguages.Code] 对应的 EditBuffer 装配策略，从原 BrailleLanguages.kt 枚举条目的匿名装配体逐字搬移。
 *
 * 分支顺序与原条目顺序一致；未覆盖的条目回落到 [EditBufferUnContracted]，与原枚举默认实现一致。
 */
internal fun BrailleLanguages.Code.codeEditBuffer(
    context: Context,
    talkBack: TalkBackSpeaker,
    translator: BrailleTranslator,
    contractedMode: Boolean,
): EditBuffer {
    return when (this) {
        BrailleLanguages.Code.ARABIC -> {
            if (contractedMode) {
                return EditBufferArabic2(context, translator, talkBack)
            }
            return EditBufferUnContracted(context, translator, talkBack)
        }
        BrailleLanguages.Code.BULGARIAN -> {
            return EditBufferBulgarian(context, translator, talkBack)
        }
        BrailleLanguages.Code.CATALAN -> {
            return EditBufferCatalan(context, translator, talkBack)
        }
        BrailleLanguages.Code.CHINESE_CHINA_CURRENT_WITH_TONES -> {
            // 一个音节 2~3 格，必须按音节切分再翻译，否则连续输入会出现跨字歧义。
            return EditBufferChinese.create(context, translator, talkBack)
        }
        BrailleLanguages.Code.CROATIAN -> {
            return EditBufferCroatian(context, translator, talkBack)
        }
        BrailleLanguages.Code.DANISH -> {
            if (contractedMode) {
                return EditBufferDanish2(context, translator, talkBack)
            }
            return EditBufferDanish(context, translator, talkBack)
        }
        BrailleLanguages.Code.DANISH_8 -> {
            if (contractedMode) {
                return EditBufferDanish2(context, translator, talkBack)
            }
            return EditBufferDanish(context, translator, talkBack)
        }
        BrailleLanguages.Code.DUTCH_NL -> {
            return EditBufferDutch(context, translator, talkBack)
        }
        BrailleLanguages.Code.UEB -> {
            if (contractedMode) {
                return EditBufferUeb2(context, translator, talkBack)
            }
            return EditBufferUnContracted(context, translator, talkBack)
        }
        BrailleLanguages.Code.EN_UK -> {
            if (contractedMode) {
                return EditBufferBritish2(context, translator, talkBack)
            }
            return EditBufferUnContracted(context, translator, talkBack)
        }
        BrailleLanguages.Code.EN_US_EBAE -> {
            if (contractedMode) {
                return EditBufferEbae2(context, translator, talkBack)
            }
            return EditBufferUnContracted(context, translator, talkBack)
        }
        BrailleLanguages.Code.EN_COMP6 -> {
            return EditBufferEnComp6(context, translator, talkBack)
        }
        BrailleLanguages.Code.FRENCH -> {
            if (contractedMode) {
                return EditBufferFrench2(context, translator, talkBack)
            }
            return EditBufferFrench(context, translator, talkBack)
        }
        BrailleLanguages.Code.GREEK -> {
            return EditBufferGreek(context, translator, talkBack)
        }
        BrailleLanguages.Code.GERMAN -> {
            if (contractedMode) {
                return EditBufferGerman2(context, translator, talkBack)
            }
            return EditBufferGerman(context, translator, talkBack)
        }
        BrailleLanguages.Code.HUNGARIAN -> {
            if (contractedMode) {
                return EditBufferHungarian2(context, translator, talkBack)
            }
            return EditBufferHungarian(context, translator, talkBack)
        }
        BrailleLanguages.Code.ITALIAN_COMP6 -> {
            return EditBufferItalian(context, translator, talkBack)
        }
        BrailleLanguages.Code.KOREAN_2006 -> {
            if (contractedMode) {
                return EditBufferKorean2(context, translator, talkBack)
            }
            return EditBufferKorean(context, translator, talkBack)
        }
        BrailleLanguages.Code.LATVIAN -> {
            return EditBufferLatvian(context, translator, talkBack)
        }
        BrailleLanguages.Code.LITHUANIAN -> {
            return EditBufferLithuanian(context, translator, talkBack)
        }
        BrailleLanguages.Code.NORWEGIAN -> {
            if (contractedMode) {
                return EditBufferNorwegian2(context, translator, talkBack)
            }
            return EditBufferUnContracted(context, translator, talkBack)
        }
        BrailleLanguages.Code.POLISH -> {
            return EditBufferPolish(context, translator, talkBack)
        }
        BrailleLanguages.Code.PORTUGUESE -> {
            if (contractedMode) {
                return EditBufferPortuguese2(context, translator, talkBack)
            }
            return EditBufferPortuguese(context, translator, talkBack)
        }
        BrailleLanguages.Code.RUSSIAN -> {
            return EditBufferRussian(context, translator, talkBack)
        }
        BrailleLanguages.Code.RUSSIAN_DETAILED -> {
            return EditBufferRussian(context, translator, talkBack)
        }
        BrailleLanguages.Code.SERBIAN -> {
            return EditBufferSerbian(context, translator, talkBack)
        }
        BrailleLanguages.Code.SPANISH -> {
            if (contractedMode) {
                return EditBufferSpanish2(context, translator, talkBack)
            }
            return EditBufferSpanish(context, translator, talkBack)
        }
        BrailleLanguages.Code.SWEDEN -> {
            if (contractedMode) {
                return EditBufferSwedish2(context, translator, talkBack)
            }
            return EditBufferUnContracted(context, translator, talkBack)
        }
        BrailleLanguages.Code.TURKISH -> {
            if (contractedMode) {
                return EditBufferTurkish2(context, translator, talkBack)
            }
            return EditBufferUnContracted(context, translator, talkBack)
        }
        BrailleLanguages.Code.UKRAINIAN -> {
            // Based on Russian.
            return EditBufferRussian(context, translator, talkBack)
        }
        BrailleLanguages.Code.URDU -> {
            if (contractedMode) {
                return EditBufferUrdu2(context, translator, talkBack)
            }
            return EditBufferUnContracted(context, translator, talkBack)
        }
        BrailleLanguages.Code.VIETNAMESE -> {
            if (contractedMode) {
                return EditBufferVietnamese2(context, translator, talkBack)
            }
            return EditBufferVietnamese(context, translator, talkBack)
        }
        BrailleLanguages.Code.WELSH -> {
            if (contractedMode) {
                return EditBufferWelsh2(context, translator, talkBack)
            }
            return EditBufferUnContracted(context, translator, talkBack)
        }
        BrailleLanguages.Code.STUB -> {
            return EditBufferStub(context, translator, talkBack)
        }
        else -> EditBufferUnContracted(context, translator, talkBack)
    }
}

