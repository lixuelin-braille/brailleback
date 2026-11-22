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

/** An EditBuffer for British Braille Grade 2. */
class EditBufferBritish2(
    context: Context,
    translator: BrailleTranslator,
    talkBack: TalkBackSpeaker,
) : EditBufferContracted(context, translator, talkBack) {

    private val forceInitialTranslationSet: MutableSet<String> = HashSet()
    private val forceNonInitialTranslationSet: MutableSet<String> = HashSet()

    init {
        fillForceDefaultTranslationSets()
    }

    private fun fillForceDefaultTranslationSets() {
        // It announced Cap, but should be dot 6, ex: cation(14-6-1345).
        forceInitialTranslationSet.add("6")

        // It announced Number, but should be number(Initial) or ble(Medial or Terminal)
        forceNonInitialTranslationSet.add("3456")
        // It's first character of composite wordsigns, ex: word(45-2456).
        forceNonInitialTranslationSet.add("45")
        // It's first character of composite wordsigns, ex: cannot(456-14).
        forceNonInitialTranslationSet.add("456")
        // It's first character of composite wordsigns, ex: day(5-145).
        forceNonInitialTranslationSet.add("5")
        // It's first character of composite groupsigns, ex: ence(56-15).
        forceNonInitialTranslationSet.add("56")
        // It announced 1, but should be ea(Medial) or ,(Terminal)
        forceNonInitialTranslationSet.add("2")
        // It announced 2, but should be bb(Medial) or ;(Terminal)
        forceNonInitialTranslationSet.add("23")
        // It announced 3, but should be cc(Medial) or :(Terminal)
        forceNonInitialTranslationSet.add("25")
        // It announced .(Period), but should be dd(Medial) or .(Terminal)
        forceNonInitialTranslationSet.add("256")
        // It announced .(Period), but should be com(Medial) or -(Terminal)
        forceNonInitialTranslationSet.add("36")
        // It announced 6, but should be ff(Medial) or !(Terminal)
        forceNonInitialTranslationSet.add("235")
        // It announced )(Right paren), but should be gg(Medial) or )(Terminal)
        forceNonInitialTranslationSet.add("2356")
    }

    protected override fun fillTranslatorMaps(
        initialCharacterTranslationMap: MutableMap<String, String>,
        nonInitialCharacterTranslationMap: MutableMap<String, String>,
    ) {
        initialCharacterTranslationMap["1"] = "a"
        initialCharacterTranslationMap["12"] = "b"
        initialCharacterTranslationMap["14"] = "c"
        initialCharacterTranslationMap["145"] = "d"
        initialCharacterTranslationMap["15"] = "e"
        initialCharacterTranslationMap["124"] = "f"
        initialCharacterTranslationMap["1245"] = "g"
        initialCharacterTranslationMap["125"] = "h"
        initialCharacterTranslationMap["24"] = "i"
        initialCharacterTranslationMap["245"] = "j"
        initialCharacterTranslationMap["13"] = "k"
        initialCharacterTranslationMap["123"] = "l"
        initialCharacterTranslationMap["134"] = "m"
        initialCharacterTranslationMap["1345"] = "n"
        initialCharacterTranslationMap["135"] = "o"
        initialCharacterTranslationMap["1234"] = "p"
        initialCharacterTranslationMap["12345"] = "q"
        initialCharacterTranslationMap["1235"] = "r"
        initialCharacterTranslationMap["234"] = "s"
        initialCharacterTranslationMap["2345"] = "t"
        initialCharacterTranslationMap["136"] = "u"
        initialCharacterTranslationMap["1236"] = "v"
        initialCharacterTranslationMap["2456"] = "w"
        initialCharacterTranslationMap["1346"] = "x"
        initialCharacterTranslationMap["13456"] = "y"
        initialCharacterTranslationMap["1356"] = "z"

        initialCharacterTranslationMap["12346"] = "and"
        initialCharacterTranslationMap["123456"] = "for"
        initialCharacterTranslationMap["12356"] = "of"
        initialCharacterTranslationMap["2346"] = "the"
        initialCharacterTranslationMap["23456"] = "with"
        initialCharacterTranslationMap["16"] = "ch"
        initialCharacterTranslationMap["126"] = "gh"
        initialCharacterTranslationMap["146"] = "sh"
        initialCharacterTranslationMap["1456"] = "th"
        initialCharacterTranslationMap["156"] = "wh"
        initialCharacterTranslationMap["1246"] = "ed"
        initialCharacterTranslationMap["12456"] = "er"
        initialCharacterTranslationMap["1256"] = "ou"
        initialCharacterTranslationMap["246"] = "ow"
        initialCharacterTranslationMap["34"] = "st"
        initialCharacterTranslationMap["345"] = "ar"
        initialCharacterTranslationMap["26"] = "en"
        initialCharacterTranslationMap["35"] = "in"

        nonInitialCharacterTranslationMap["1"] = "a"
        nonInitialCharacterTranslationMap["12"] = "b"
        nonInitialCharacterTranslationMap["14"] = "c"
        nonInitialCharacterTranslationMap["145"] = "d"
        nonInitialCharacterTranslationMap["15"] = "e"
        nonInitialCharacterTranslationMap["124"] = "f"
        nonInitialCharacterTranslationMap["1245"] = "g"
        nonInitialCharacterTranslationMap["125"] = "h"
        nonInitialCharacterTranslationMap["24"] = "i"
        nonInitialCharacterTranslationMap["245"] = "j"
        nonInitialCharacterTranslationMap["13"] = "k"
        nonInitialCharacterTranslationMap["123"] = "l"
        nonInitialCharacterTranslationMap["134"] = "m"
        nonInitialCharacterTranslationMap["1345"] = "n"
        nonInitialCharacterTranslationMap["135"] = "o"
        nonInitialCharacterTranslationMap["1234"] = "p"
        nonInitialCharacterTranslationMap["12345"] = "q"
        nonInitialCharacterTranslationMap["1235"] = "r"
        nonInitialCharacterTranslationMap["234"] = "s"
        nonInitialCharacterTranslationMap["2345"] = "t"
        nonInitialCharacterTranslationMap["136"] = "u"
        nonInitialCharacterTranslationMap["1236"] = "v"
        nonInitialCharacterTranslationMap["2456"] = "w"
        nonInitialCharacterTranslationMap["1346"] = "x"
        nonInitialCharacterTranslationMap["13456"] = "y"
        nonInitialCharacterTranslationMap["1356"] = "z"

        nonInitialCharacterTranslationMap["12346"] = "and"
        nonInitialCharacterTranslationMap["123456"] = "for"
        nonInitialCharacterTranslationMap["12356"] = "of"
        nonInitialCharacterTranslationMap["2346"] = "the"
        nonInitialCharacterTranslationMap["23456"] = "with"
        nonInitialCharacterTranslationMap["16"] = "ch"
        nonInitialCharacterTranslationMap["126"] = "gh"
        nonInitialCharacterTranslationMap["146"] = "sh"
        nonInitialCharacterTranslationMap["1456"] = "th"
        nonInitialCharacterTranslationMap["156"] = "wh"
        nonInitialCharacterTranslationMap["1246"] = "ed"
        nonInitialCharacterTranslationMap["12456"] = "er"
        nonInitialCharacterTranslationMap["1256"] = "ou"
        nonInitialCharacterTranslationMap["246"] = "ow"
        nonInitialCharacterTranslationMap["34"] = "st"
        nonInitialCharacterTranslationMap["345"] = "ar"
        nonInitialCharacterTranslationMap["346"] = "ing"

        nonInitialCharacterTranslationMap["26"] = "en"
        nonInitialCharacterTranslationMap["35"] = "in"
    }

    protected override fun isLetter(character: Char): Boolean {
        return false
    }

    protected override fun getCapitalize(): BrailleCharacter? {
        return BrailleTranslateUtils.DOT6
    }

    protected override fun getNumeric(): BrailleCharacter? {
        return BrailleTranslateUtils.DOTS3456
    }

    protected override fun forceInitialDefaultTranslation(dotsNumber: String): Boolean {
        return forceInitialTranslationSet.contains(dotsNumber)
    }

    protected override fun forceNonInitialDefaultTranslation(dotsNumber: String): Boolean {
        return forceNonInitialTranslationSet.contains(dotsNumber)
    }
}
