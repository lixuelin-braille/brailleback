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
import android.content.res.Resources
import android.text.TextUtils
import android.view.KeyEvent
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import com.google.android.accessibility.braille.common.BrailleCommonUtils
import com.google.android.accessibility.braille.common.ImeConnection
import com.google.android.accessibility.braille.common.ImeConnection.AnnounceType.NORMAL
import com.google.android.accessibility.braille.common.R
import com.google.android.accessibility.braille.common.TalkBackSpeaker
import com.google.android.accessibility.braille.interfaces.BrailleCharacter
import com.google.android.accessibility.braille.interfaces.BrailleDisplayForBrailleIme.ResultForDisplay.HoldingsInfo
import com.google.android.accessibility.braille.interfaces.BrailleWord
import com.google.android.accessibility.braille.translate.BrailleTranslator
import com.google.common.base.Strings
import com.google.common.base.Strings.nullToEmpty
import com.google.common.collect.ImmutableList
import java.nio.ByteBuffer

/** Basement abstraction class for contracted braille languages. */
abstract class EditBufferContracted(
    context: Context,
    translator: BrailleTranslator,
    talkBack: TalkBackSpeaker,
) : EditBuffer {
    private val context: Context = context
    protected val translator: BrailleTranslator = translator
    private val holdings: BrailleWord = BrailleWord()
    private val talkBack: TalkBackSpeaker = talkBack

    /**
     * Mapping for initial braille characters. Defines the single braille character translation
     * without any context or relationship with other braille characters.
     */
    private val initialCharacterTranslationMap: MutableMap<String, String> = HashMap()

    /**
     * Mapping for non-initial braille characters. Defines the single braille character translation
     * that place not at the word's beginning.
     */
    private val nonInitialCharacterTranslationMap: MutableMap<String, String> = HashMap()

    private var holdingPosition = EditBufferUtils.NO_CURSOR

    init {
        fillTranslatorMaps(initialCharacterTranslationMap, nonInitialCharacterTranslationMap)
    }

    override fun appendBraille(
        imeConnection: ImeConnection,
        brailleCharacter: BrailleCharacter,
    ): String? {
        val selectedText: CharSequence? = imeConnection.inputConnection.getSelectedText(0)
        if (!TextUtils.isEmpty(selectedText)) {
            // Delete selection first.
            imeConnection.inputConnection.commitText("", 1)
        }
        updateHoldingsPosition(brailleCharacter)
        val previousTranslationIndex = holdingPosition - 1
        var result =
            getAnnouncement(context.resources, translator, holdings, previousTranslationIndex)
        if (EditBufferUtils.shouldEmitPerCharacterFeedback(imeConnection)) {
            result =
                hideTextForPasswordIfNecessary(imeConnection, result, /* brailleCharacterLength= */ 1)
            EditBufferUtils.speak(context, talkBack, result)
        }
        return result
    }

    override fun appendSpace(imeConnection: ImeConnection) {
        updateHoldingsPosition(BrailleCharacter())
        clearHoldingsAndSendToEditor(imeConnection, /* ignoreHoldingsPosition= */ false)
    }

    override fun appendNewline(imeConnection: ImeConnection) {
        if (EditBufferUtils.isMultiLineField(imeConnection.editorInfo.inputType)) {
            clearHoldingsAndSendToEditor(imeConnection, /* ignoreHoldingsPosition= */ false, NEW_LINE)
        } else {
            EditBufferUtils.speak(
                context, talkBack, context.getString(R.string.new_line_not_supported)
            )
        }
    }

    override fun deleteCharacterBackward(imeConnection: ImeConnection) {
        if (holdings.isEmpty()) {
            BrailleCommonUtils.performKeyAction(imeConnection.inputConnection, KeyEvent.KEYCODE_DEL)
            return
        }
        if (holdingPosition <= 0) {
            return
        }
        holdingPosition--
        var result = getAnnouncement(context.resources, translator, holdings, holdingPosition)
        holdings.remove(holdingPosition)
        if (holdings.isEmpty()) {
            holdingPosition = EditBufferUtils.NO_CURSOR
        }
        result = hideTextForPasswordIfNecessary(imeConnection, result, /* brailleCharacterLength= */ 1)
        EditBufferUtils.speakDelete(context, talkBack, result)
    }

    override fun deleteCharacterForward(imeConnection: ImeConnection) {
        if (holdings.isEmpty()) {
            BrailleCommonUtils.performKeyAction(
                imeConnection.inputConnection, KeyEvent.KEYCODE_FORWARD_DEL
            )
            return
        }
        if (holdingPosition >= holdings.size()) {
            return
        }
        var result = getAnnouncement(context.resources, translator, holdings, holdingPosition)
        holdings.remove(holdingPosition)
        if (holdings.isEmpty()) {
            holdingPosition = EditBufferUtils.NO_CURSOR
        }
        result = hideTextForPasswordIfNecessary(imeConnection, result, /* brailleCharacterLength= */ 1)
        EditBufferUtils.speakDelete(context, talkBack, result)
    }

    override fun deleteWord(imeConnection: ImeConnection) {
        // If there is any holdings left, clear it out; otherwise delete at the Editor level.
        if (!holdings.isEmpty()) {
            val holdingsStringBuilder = ImmutableList.builder<String>()
            for (i in 0 until holdings.size()) {
                holdingsStringBuilder.add(
                    getAnnouncement(context.resources, translator, holdings, i)
                )
            }
            var deletedWord = TextUtils.join(DELIMITER, holdingsStringBuilder.build())
            deletedWord =
                hideTextForPasswordIfNecessary(imeConnection, deletedWord, holdings.size())
            EditBufferUtils.speakDelete(context, talkBack, deletedWord)
            holdingPosition = EditBufferUtils.NO_CURSOR
            holdings.clear()
            imeConnection.inputConnection.setComposingText("", 0)
        } else {
            val hunkBeforeCursor =
                imeConnection.inputConnection.getTextBeforeCursor(DELETE_WORD_MAX, 0)
            val charactersToDeleteCount =
                BrailleCommonUtils.getLastWordLengthForDeletion(hunkBeforeCursor!!)
            if (charactersToDeleteCount > 0) {
                imeConnection.inputConnection.deleteSurroundingText(charactersToDeleteCount, 0)
            }
        }
    }

    override fun commit(imeConnection: ImeConnection) {
        clearHoldingsAndSendToEditor(imeConnection, /* ignoreHoldingsPosition= */ true)
    }

    override fun moveCursorForward(imeConnection: ImeConnection): Boolean {
        if (holdings.isEmpty()) {
            return false
        }
        return moveHoldingsCursor(imeConnection, holdingPosition + 1)
    }

    override fun moveCursorBackward(imeConnection: ImeConnection): Boolean {
        if (holdings.isEmpty()) {
            return false
        }
        return moveHoldingsCursor(imeConnection, holdingPosition - 1)
    }

    override fun moveHoldingsCursor(imeConnection: ImeConnection, index: Int): Boolean {
        if (0 <= index && index <= holdings.size()) {
            var start = holdingPosition
            var end = index
            if (holdingPosition > index) {
                start = index
                end = holdingPosition
            }
            holdingPosition = index
            var announcement =
                getAnnouncement(context.resources, translator, holdings, start, end)
            announcement =
                hideTextForPasswordIfNecessary(
                    imeConnection, announcement, /* brailleCharacterLength= */ end - start
                )
            EditBufferUtils.speak(context, talkBack, announcement)
        } else {
            val oldPosition = EditBufferUtils.getCursorPosition(imeConnection.inputConnection)
            commit(imeConnection)
            if (index < 0) {
                return imeConnection.inputConnection.setSelection(oldPosition, oldPosition)
            }
        }
        return true
    }

    override fun moveTextFieldCursor(imeConnection: ImeConnection, index: Int): Boolean {
        if (0 <= index &&
            index <= EditBufferUtils.getTextFieldText(imeConnection.inputConnection).length
        ) {
            return imeConnection.inputConnection.setSelection(index, index)
        }
        return false
    }

    override fun getHoldingsInfo(imeConnection: ImeConnection): HoldingsInfo {
        return HoldingsInfo.create(ByteBuffer.wrap(holdings.toByteArray()), holdingPosition)
    }

    // TODO: Improve the design.
    /**
     * Fills [initialCharacterTranslationMap] and [nonInitialCharacterTranslationMap].
     */
    protected abstract fun fillTranslatorMaps(
        initialCharacterTranslationMap: MutableMap<String, String>,
        nonInitialCharacterTranslationMap: MutableMap<String, String>,
    )

    /** Whether the character is a letter. */
    protected abstract fun isLetter(character: Char): Boolean

    /** Gets capitalize indicator braille character. */
    protected abstract fun getCapitalize(): BrailleCharacter?

    /** Gets numeric indicator braille character. */
    protected abstract fun getNumeric(): BrailleCharacter?

    /** Whether the initial braille character should be forced to announce the dots. */
    protected abstract fun forceInitialDefaultTranslation(dotsNumber: String): Boolean

    /** Whether the non-initial braille character should be forced to announce the dots. */
    protected abstract fun forceNonInitialDefaultTranslation(dotsNumber: String): Boolean

    private fun updateHoldingsPosition(brailleCharacter: BrailleCharacter) {
        if (holdingPosition == EditBufferUtils.NO_CURSOR || holdingPosition == holdings.size()) {
            holdings.append(brailleCharacter)
            holdingPosition = holdings.size()
        } else {
            holdings.insert(holdingPosition, brailleCharacter)
            holdingPosition++
        }
    }

    private fun clearHoldingsAndSendToEditor(
        imeConnection: ImeConnection,
        ignoreHoldingsPosition: Boolean,
    ) {
        clearHoldingsAndSendToEditor(imeConnection, ignoreHoldingsPosition, "")
    }

    private fun clearHoldingsAndSendToEditor(
        imeConnection: ImeConnection,
        ignoreHoldingsPosition: Boolean,
        appendix: String,
    ) {
        if (holdings.isEmpty()) {
            if (!TextUtils.isEmpty(appendix)) {
                imeConnection.inputConnection.commitText(appendix, /* newCursorPosition= */ 1)
            }
            return
        }
        if (ignoreHoldingsPosition) {
            holdingPosition = holdings.size()
        }
        val holdingsBeforeCursor =
            translator.translateToPrint(holdings.subword(0, holdingPosition))
        val holdingsAfterCursor =
            translator.translateToPrint(holdings.subword(holdingPosition, holdings.size()))

        val textCursorStartIndex = getTextCursorStartIndex(imeConnection.inputConnection)

        checkSpaceBetweenNumberAndChar(imeConnection, holdingsBeforeCursor)
        imeConnection.inputConnection.commitText(
            holdingsBeforeCursor + appendix + holdingsAfterCursor, /* newCursorPosition= */ 1
        )

        val unused =
            moveTextFieldCursor(
                imeConnection,
                holdingsBeforeCursor.length + textCursorStartIndex + appendix.length,
            )

        holdings.clear()
        holdingPosition = EditBufferUtils.NO_CURSOR
    }

    protected open fun checkSpaceBetweenNumberAndChar(
        imeConnection: ImeConnection,
        appendedText: String,
    ) {
        // This method is only for some specific brailles, such as Korean.
    }

    private fun getTextCursorStartIndex(inputConnection: InputConnection): Int {
        val extractedText: ExtractedText? =
            inputConnection.getExtractedText(ExtractedTextRequest(), 0)
        var textLengthBeforeCursor: CharSequence = ""
        if (extractedText != null && extractedText.text != null && extractedText.selectionStart >= 0) {
            textLengthBeforeCursor = extractedText.text.subSequence(0, extractedText.selectionStart)
        }
        return if (TextUtils.isEmpty(textLengthBeforeCursor)) 0 else textLengthBeforeCursor.length
    }

    private fun getAnnouncement(
        resources: Resources,
        translator: BrailleTranslator,
        brailleWord: BrailleWord,
        startIndex: Int,
        endIndex: Int,
    ): String {
        val sb = StringBuilder()
        for (i in startIndex until endIndex) {
            sb.append(getAnnouncement(resources, translator, brailleWord, i))
        }
        return sb.toString()
    }

    protected open fun getAnnouncement(
        resources: Resources,
        translator: BrailleTranslator,
        brailleWord: BrailleWord,
        index: Int,
    ): String {
        val brailleCharacter = brailleWord.get(index)
        var result = getNonInitialCharacterTranslation(resources, brailleCharacter)
        if (index == 0) {
            result = getInitialCharacterTranslation(resources, brailleCharacter)
        }
        if (result.isEmpty()) {
            result = getTranslateDifference(translator, brailleWord, index, index + 1)
            if (result.isEmpty() || isLetter(result[0])) {
                result =
                    getCharacterTranslation(
                        resources, brailleCharacter, brailleWord.size() > 1
                    )
            }
        }
        return result
    }

    private fun getInitialCharacterTranslation(
        resources: Resources,
        brailleCharacter: BrailleCharacter,
    ): String {
        var translation = getTextToSpeak(resources, brailleCharacter)
        if (forceInitialDefaultTranslation(brailleCharacter.toString())) {
            translation = BrailleTranslateUtils.getDotsText(resources, brailleCharacter)
        }
        if (TextUtils.isEmpty(translation)) {
            translation = nullToEmpty(initialCharacterTranslationMap[brailleCharacter.toString()])
            if (TextUtils.isEmpty(translation)) {
                translation = BrailleTranslateUtils.getDotsText(resources, brailleCharacter)
            }
        }
        return translation
    }

    private fun getNonInitialCharacterTranslation(
        resources: Resources,
        brailleCharacter: BrailleCharacter,
    ): String {
        var translation = getTextToSpeak(resources, brailleCharacter)
        if (forceNonInitialDefaultTranslation(brailleCharacter.toString())) {
            translation = BrailleTranslateUtils.getDotsText(resources, brailleCharacter)
        }
        if (TextUtils.isEmpty(translation)) {
            translation = nullToEmpty(nonInitialCharacterTranslationMap[brailleCharacter.toString()])
        }
        return translation
    }

    protected fun getTextToSpeak(
        resources: Resources,
        brailleCharacter: BrailleCharacter,
    ): String {
        if (brailleCharacter.equals(getCapitalize())) {
            return resources.getString(R.string.capitalize_announcement)
        } else if (brailleCharacter.equals(getNumeric())) {
            return resources.getString(R.string.number_announcement)
        }
        return ""
    }

    private fun hideTextForPasswordIfNecessary(
        imeConnection: ImeConnection,
        text: String,
        brailleCharacterLength: Int,
    ): String {
        if (imeConnection.announceType == NORMAL ||
            !BrailleCommonUtils.isPasswordField(imeConnection.editorInfo)
        ) {
            return text
        }
        return Strings.repeat(BrailleTranslateUtils.PASSWORD_BULLET, brailleCharacterLength)
    }

    /**
     * Gets the proper translation for single braille character. For example, dot 16 can be "child" or
     * "ch", it's better to get "ch" in most use experience. Returns [initialCharacterTranslationMap]
     * of there has other holdings; otherwise [nonInitialCharacterTranslationMap].
     */
    private fun getCharacterTranslation(
        resources: Resources,
        brailleCharacter: BrailleCharacter,
        hasOtherHoldings: Boolean,
    ): String {
        return if (hasOtherHoldings) {
            getInitialCharacterTranslation(resources, brailleCharacter)
        } else {
            getNonInitialCharacterTranslation(resources, brailleCharacter)
        }
    }

    companion object {
        private const val TAG = "EditBufferCommonContracted"
        private const val DELIMITER = ","
        private const val NEW_LINE = "\n"
        private const val DELETE_WORD_MAX = 50

        private fun getTranslateDifference(
            translator: BrailleTranslator,
            brailleWord: BrailleWord,
            firstIndex: Int,
            secondIndex: Int,
        ): String {
            val longerString = translator.translateToPrint(brailleWord.subword(0, secondIndex))
            val shorterString = translator.translateToPrint(brailleWord.subword(0, firstIndex))
            if (longerString.startsWith(shorterString)) {
                return longerString.substring(shorterString.length)
            }
            return ""
        }
    }
}
