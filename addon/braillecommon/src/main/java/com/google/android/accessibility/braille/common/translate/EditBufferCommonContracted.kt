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

package com.google.android.accessibility.braille.common.translate

import android.content.Context
import com.google.android.accessibility.braille.common.TalkBackSpeaker
import com.google.android.accessibility.braille.interfaces.BrailleCharacter
import com.google.android.accessibility.braille.translate.BrailleTranslator

/** A general contracted braille edit buffer. */
class EditBufferCommonContracted(
    context: Context,
    translator: BrailleTranslator,
    talkBack: TalkBackSpeaker,
) : EditBufferContracted(context, translator, talkBack) {

    protected override fun fillTranslatorMaps(
        initialCharacterTranslationMap: MutableMap<String, String>,
        nonInitialCharacterTranslationMap: MutableMap<String, String>,
    ) {
    }

    protected override fun isLetter(character: Char): Boolean {
        return false
    }

    protected override fun getCapitalize(): BrailleCharacter? {
        return null
    }

    protected override fun getNumeric(): BrailleCharacter? {
        return null
    }

    protected override fun forceInitialDefaultTranslation(dotsNumber: String): Boolean {
        return false
    }

    protected override fun forceNonInitialDefaultTranslation(dotsNumber: String): Boolean {
        return false
    }
}
