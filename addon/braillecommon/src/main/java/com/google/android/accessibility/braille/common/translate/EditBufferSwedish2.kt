package com.google.android.accessibility.braille.common.translate;

import android.content.Context
import com.google.android.accessibility.braille.common.TalkBackSpeaker
import com.google.android.accessibility.braille.interfaces.BrailleCharacter
import com.google.android.accessibility.braille.translate.BrailleTranslator

/** An EditBuffer for Swedish Braille Grade 2. */
class EditBufferSwedish2(
    context: Context,
    translator: BrailleTranslator,
    talkBack: TalkBackSpeaker,
) : EditBufferContracted(context, translator, talkBack) {

    override fun fillTranslatorMaps(
        initialCharacterTranslationMap: MutableMap<String, String>,
        nonInitialCharacterTranslationMap: MutableMap<String, String>,
    ) {
        // See:
        // https://www.pharmabraille.com/wp-content/uploads/2014/11/World-Braille-Usage-Third-Edition-1.pdf
        initialCharacterTranslationMap.put("1", "a");
        initialCharacterTranslationMap.put("12", "b");
        initialCharacterTranslationMap.put("14", "c");
        initialCharacterTranslationMap.put("145", "d");
        initialCharacterTranslationMap.put("15", "e");
        initialCharacterTranslationMap.put("124", "f");
        initialCharacterTranslationMap.put("1245", "g");
        initialCharacterTranslationMap.put("125", "h");
        initialCharacterTranslationMap.put("35", "ı");
        initialCharacterTranslationMap.put("24", "i");
        initialCharacterTranslationMap.put("245", "j");
        initialCharacterTranslationMap.put("13", "k");
        initialCharacterTranslationMap.put("123", "l");
        initialCharacterTranslationMap.put("134", "m");
        initialCharacterTranslationMap.put("1345", "n");
        initialCharacterTranslationMap.put("135", "o");
        initialCharacterTranslationMap.put("1234", "p");
        initialCharacterTranslationMap.put("1235", "r");
        initialCharacterTranslationMap.put("234", "s");
        initialCharacterTranslationMap.put("2345", "t");
        initialCharacterTranslationMap.put("136", "u");
        initialCharacterTranslationMap.put("1236", "v");
        initialCharacterTranslationMap.put("2456", "w");
        initialCharacterTranslationMap.put("13456", "y");
        initialCharacterTranslationMap.put("1356", "z");

        initialCharacterTranslationMap.put("16", "å");
        initialCharacterTranslationMap.put("12356", "à");
        initialCharacterTranslationMap.put("345", "ä"); // ä/æ
        initialCharacterTranslationMap.put("123456", "é");
        initialCharacterTranslationMap.put("2346", "è");
        initialCharacterTranslationMap.put("246", "ö"); // ö/ø
        initialCharacterTranslationMap.put("1256", "ü");
    }

    override fun isLetter(character: Char): Boolean = false

    override fun getCapitalize(): BrailleCharacter = BrailleTranslateUtils.DOT6

    override fun getNumeric(): BrailleCharacter = BrailleTranslateUtils.DOTS3456

    override fun forceInitialDefaultTranslation(dotsNumber: String): Boolean = false

    override fun forceNonInitialDefaultTranslation(dotsNumber: String): Boolean = false
}
