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

/** An EditBuffer for Welsh Braille Grade 2. */
class EditBufferWelsh2(
    context: Context,
    translator: BrailleTranslator,
    talkBack: TalkBackSpeaker,
) : EditBufferContracted(context, translator, talkBack) {

    override fun fillTranslatorMaps(
        initialCharacterTranslationMap: MutableMap<String, String>,
        nonInitialCharacterTranslationMap: MutableMap<String, String>,
    ) {
        initialCharacterTranslationMap.put("1", "a");
        initialCharacterTranslationMap.put("12", "b");
        initialCharacterTranslationMap.put("14", "c");
        initialCharacterTranslationMap.put("16", "ch");
        initialCharacterTranslationMap.put("145", "d");
        initialCharacterTranslationMap.put("15", "e");
        initialCharacterTranslationMap.put("124", "f");
        initialCharacterTranslationMap.put("1245", "g");
        initialCharacterTranslationMap.put("125", "h");
        initialCharacterTranslationMap.put("24", "i");
        initialCharacterTranslationMap.put("245", "j");
        initialCharacterTranslationMap.put("13", "k");
        initialCharacterTranslationMap.put("123", "l");
        initialCharacterTranslationMap.put("134", "m");
        initialCharacterTranslationMap.put("1345", "n");
        initialCharacterTranslationMap.put("135", "o");
        initialCharacterTranslationMap.put("1234", "p");
        initialCharacterTranslationMap.put("12345", "q");
        initialCharacterTranslationMap.put("1235", "r");
        initialCharacterTranslationMap.put("234", "s");
        initialCharacterTranslationMap.put("2345", "t");
        initialCharacterTranslationMap.put("136", "u");
        initialCharacterTranslationMap.put("1236", "v");
        initialCharacterTranslationMap.put("2456", "w");
        initialCharacterTranslationMap.put("1346", "x");
        initialCharacterTranslationMap.put("13456", "y");
        initialCharacterTranslationMap.put("1356", "z");
    }

    override fun isLetter(character: Char): Boolean = false

    override fun getCapitalize(): BrailleCharacter = BrailleCharacter.DOT6

    override fun getNumeric(): BrailleCharacter = BrailleTranslateUtils.DOTS3456

    override fun forceInitialDefaultTranslation(dotsNumber: String): Boolean = false

    override fun forceNonInitialDefaultTranslation(dotsNumber: String): Boolean = false
}
