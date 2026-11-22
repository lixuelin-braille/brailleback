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
import com.google.common.collect.ImmutableMap

/** An EditBuffer for Danish Braille Grade 2. */
class EditBufferDanish2(
    context: Context,
    translator: BrailleTranslator,
    talkBack: TalkBackSpeaker,
) : EditBufferContracted(context, translator, talkBack) {

    override fun fillTranslatorMaps(
        initialCharacterTranslationMap: MutableMap<String, String>,
        nonInitialCharacterTranslationMap: MutableMap<String, String>,
    ) {
        INITIAL_MAP.forEach(initialCharacterTranslationMap::put)
    }

    override fun isLetter(character: Char): Boolean = false

    override fun getCapitalize(): BrailleCharacter = BrailleTranslateUtils.DOTS46

    override fun getNumeric(): BrailleCharacter = BrailleTranslateUtils.DOTS3456

    override fun forceInitialDefaultTranslation(dotsNumber: String): Boolean = false

    override fun forceNonInitialDefaultTranslation(dotsNumber: String): Boolean = false

    companion object {
        private val INITIAL_MAP: ImmutableMap<String, String> =
            ImmutableMap.builder<String, String>()
                .put("1", "a")
                .put("12", "b")
                .put("14", "c")
                .put("145", "d")
                .put("15", "e")
                .put("124", "f")
                .put("1245", "g")
                .put("125", "h")
                .put("24", "i")
                .put("245", "j")
                .put("13", "k")
                .put("123", "l")
                .put("134", "m")
                .put("1345", "n")
                .put("135", "o")
                .put("1234", "p")
                .put("12345", "q")
                .put("1235", "r")
                .put("234", "s")
                .put("2345", "t")
                .put("136", "u")
                .put("1236", "v")
                .put("2456", "w")
                .put("1346", "x")
                .put("13456", "y")
                .put("1356", "z")
                .put("345", "æ")
                .put("246", "ø")
                .put("16", "å")
                .buildOrThrow()
    }
}
