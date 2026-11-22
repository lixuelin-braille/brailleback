/*
 * Copyright 2019 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
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

/**
 * Unified English Braille (UEB) Grade 2 EditBuffer.
 *
 * This class is similar to [EditBufferUeb2] but differs in that it uses
 * [BrailleTranslator.translateToPrintPartial].
 */
class EditBufferUeb2(
    context: Context,
    ueb2Translator: BrailleTranslator,
    talkBack: TalkBackSpeaker,
) : EditBufferContracted(context, ueb2Translator, talkBack) {

    /**
     * Set for non-initial braille characters. Since the single braille character translation with
     * multiple meanings, it should be forced to announce the dot number.
     */
    private val forceNonInitialTranslationSet: MutableSet<String> = HashSet()

    init {
        fillForceDefaultTranslationSets()
    }

    private fun fillForceDefaultTranslationSets() {
        forceNonInitialTranslationSet.add("2") // -ea-, ,
        forceNonInitialTranslationSet.add("23") // -bb-, ;
        forceNonInitialTranslationSet.add("25") // -cc-, :
        forceNonInitialTranslationSet.add("235") // -ff-, !
        forceNonInitialTranslationSet.add("256") // -dd-, .
        forceNonInitialTranslationSet.add("2356") // -gg-, '
    }

    protected override fun fillTranslatorMaps(
        initialCharacterTranslationMap: MutableMap<String, String>,
        nonInitialCharacterTranslationMap: MutableMap<String, String>,
    ) {
        // See: https://en.wikipedia.org/wiki/English_Braille.
        initialCharacterTranslationMap["1"] = "a"
        initialCharacterTranslationMap["2"] = ","
        initialCharacterTranslationMap["12"] = "b"
        initialCharacterTranslationMap["3"] = "'"
        initialCharacterTranslationMap["13"] = "k"
        initialCharacterTranslationMap["23"] = "be"
        initialCharacterTranslationMap["123"] = "l"
        initialCharacterTranslationMap["14"] = "c"
        initialCharacterTranslationMap["24"] = "i"
        initialCharacterTranslationMap["124"] = "f"
        initialCharacterTranslationMap["34"] = "st"
        initialCharacterTranslationMap["134"] = "m"
        initialCharacterTranslationMap["234"] = "s"
        initialCharacterTranslationMap["1234"] = "p"
        initialCharacterTranslationMap["15"] = "e"
        initialCharacterTranslationMap["25"] = "con"
        initialCharacterTranslationMap["125"] = "h"
        initialCharacterTranslationMap["35"] = "in"
        initialCharacterTranslationMap["135"] = "o"
        initialCharacterTranslationMap["235"] = "!"
        initialCharacterTranslationMap["1235"] = "r"
        initialCharacterTranslationMap["145"] = "d"
        initialCharacterTranslationMap["245"] = "j"
        initialCharacterTranslationMap["1245"] = "g"
        initialCharacterTranslationMap["345"] = "ar"
        initialCharacterTranslationMap["1345"] = "n"
        initialCharacterTranslationMap["2345"] = "t"
        initialCharacterTranslationMap["12345"] = "q"
        initialCharacterTranslationMap["16"] = "ch"
        initialCharacterTranslationMap["26"] = "en"
        initialCharacterTranslationMap["126"] = "gh"
        initialCharacterTranslationMap["36"] = "-"
        initialCharacterTranslationMap["136"] = "u"
        initialCharacterTranslationMap["1236"] = "v"
        initialCharacterTranslationMap["146"] = "sh"
        initialCharacterTranslationMap["246"] = "ow"
        initialCharacterTranslationMap["1246"] = "ed"
        initialCharacterTranslationMap["1346"] = "x"
        initialCharacterTranslationMap["2346"] = "the"
        initialCharacterTranslationMap["12346"] = "and"
        initialCharacterTranslationMap["156"] = "wh"
        initialCharacterTranslationMap["256"] = "dis"
        initialCharacterTranslationMap["1256"] = "ou"
        initialCharacterTranslationMap["1356"] = "z"
        initialCharacterTranslationMap["12356"] = "of"
        initialCharacterTranslationMap["1456"] = "th"
        initialCharacterTranslationMap["2456"] = "w"
        initialCharacterTranslationMap["12456"] = "er"
        initialCharacterTranslationMap["13456"] = "y"
        initialCharacterTranslationMap["23456"] = "with"
        initialCharacterTranslationMap["123456"] = "for"

        nonInitialCharacterTranslationMap["346"] = "ing"
        nonInitialCharacterTranslationMap["356"] = "\""
    }

    protected override fun forceInitialDefaultTranslation(dotsNumber: String): Boolean {
        return false
    }

    protected override fun forceNonInitialDefaultTranslation(dotsNumber: String): Boolean {
        return forceNonInitialTranslationSet.contains(dotsNumber)
    }

    protected override fun isLetter(character: Char): Boolean {
        return ('a' <= character && character <= 'z') || ('A' <= character && character <= 'Z')
    }

    protected override fun getCapitalize(): BrailleCharacter? {
        return BrailleTranslateUtils.DOT6
    }

    protected override fun getNumeric(): BrailleCharacter? {
        return BrailleTranslateUtils.DOTS3456
    }

    companion object {
        private const val TAG = "EditBufferUeb2"
    }
}
