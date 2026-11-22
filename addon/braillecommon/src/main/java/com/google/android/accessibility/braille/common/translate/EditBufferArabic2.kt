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

package com.google.android.accessibility.braille.common.translate;

import android.content.Context
import com.google.android.accessibility.braille.common.TalkBackSpeaker
import com.google.android.accessibility.braille.interfaces.BrailleCharacter
import com.google.android.accessibility.braille.translate.BrailleTranslator

/** An EditBuffer for Arabic Braille Grade 2. */
class EditBufferArabic2(
    context: Context,
    translator: BrailleTranslator,
    talkBack: TalkBackSpeaker,
) : EditBufferContracted(context, translator, talkBack) {

    override fun fillTranslatorMaps(
        initialCharacterTranslationMap: MutableMap<String, String>,
        nonInitialCharacterTranslationMap: MutableMap<String, String>,
    ) {
        initialCharacterTranslationMap.put("1", "ا");
        initialCharacterTranslationMap.put("12", "ب");
        initialCharacterTranslationMap.put("2345", "ت");
        initialCharacterTranslationMap.put("1456", "ث");
        initialCharacterTranslationMap.put("245", "ج");
        initialCharacterTranslationMap.put("156", "ح");
        initialCharacterTranslationMap.put("1346", "خ");
        initialCharacterTranslationMap.put("145", "د");
        initialCharacterTranslationMap.put("2346", "ذ");
        initialCharacterTranslationMap.put("1235", "ر");
        initialCharacterTranslationMap.put("1356", "ز");
        initialCharacterTranslationMap.put("234", "س");
        initialCharacterTranslationMap.put("146", "ش");
        initialCharacterTranslationMap.put("12346", "ص");
        initialCharacterTranslationMap.put("1246", "ض");
        initialCharacterTranslationMap.put("23456", "ط");
        initialCharacterTranslationMap.put("123456", "ظ");
        initialCharacterTranslationMap.put("12356", "ع");
        initialCharacterTranslationMap.put("126", "غ");
        initialCharacterTranslationMap.put("124", "ف");
        initialCharacterTranslationMap.put("12345", "ق");
        initialCharacterTranslationMap.put("13", "ك");
        initialCharacterTranslationMap.put("123", "ل");
        initialCharacterTranslationMap.put("134", "م");
        initialCharacterTranslationMap.put("1345", "ن");
        initialCharacterTranslationMap.put("125", "ه");
        initialCharacterTranslationMap.put("2456", "و");
        initialCharacterTranslationMap.put("24", "ي");
    }

    override fun isLetter(character: Char): Boolean = false

    override fun getCapitalize(): BrailleCharacter? = null

    override fun getNumeric(): BrailleCharacter = BrailleTranslateUtils.DOTS3456

    override fun forceInitialDefaultTranslation(dotsNumber: String): Boolean = false

    override fun forceNonInitialDefaultTranslation(dotsNumber: String): Boolean = false
}
