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
import android.content.res.Resources
import android.text.TextUtils
import android.view.KeyEvent
import com.google.android.accessibility.braille.common.BrailleCommonUtils
import com.google.android.accessibility.braille.common.ImeConnection
import com.google.android.accessibility.braille.common.R
import com.google.android.accessibility.braille.common.TalkBackSpeaker
import com.google.android.accessibility.braille.common.TalkBackSpeaker.AnnounceType
import com.google.android.accessibility.braille.interfaces.BrailleCharacter
import com.google.android.accessibility.braille.interfaces.BrailleDisplayForBrailleIme.ResultForDisplay.HoldingsInfo
import com.google.android.accessibility.braille.interfaces.BrailleWord
import com.google.android.accessibility.braille.translate.BrailleTranslator
import com.google.android.accessibility.utils.output.SpeechCleanupUtils
import java.nio.ByteBuffer
import java.util.Optional

/**
 * Unified English Braille (UEB) Grade 2 EditBuffer.
 *
 * This class is similar to [EditBufferUeb2] but differs in that it uses
 * [BrailleTranslator.translateToPrintPartial].
 *
 * This is a work-in-progress.
 */
class EditBufferUeb2WithPartial(
    context: Context,
    ueb2Translator: BrailleTranslator,
    talkBack: TalkBackSpeaker,
) : EditBuffer {
    private val context: Context = context
    private val translator: BrailleTranslator = ueb2Translator
    private val holdings: BrailleWord = BrailleWord()
    private val talkBack: TalkBackSpeaker = talkBack

    private var holdingPosition = EditBufferUtils.NO_CURSOR

    override fun appendBraille(
        imeConnection: ImeConnection,
        brailleCharacter: BrailleCharacter,
    ): String? {
        var result = ""
        val previousTranslationIndex: Int
        if (holdingPosition == EditBufferUtils.NO_CURSOR || holdingPosition == holdings.size()) {
            holdings.append(brailleCharacter)
            holdingPosition = holdings.size()
            previousTranslationIndex = holdingPosition - 1
        } else {
            // TODO: Check the readout is correct.
            holdings.insert(holdingPosition, brailleCharacter)
            previousTranslationIndex = holdingPosition
        }
        val previousTranslation =
            translator.translateToPrintPartial(holdings.subword(0, previousTranslationIndex))
        val currentTranslation =
            translator.translateToPrintPartial(
                holdings.subword(0, previousTranslationIndex + 1)
            )
        if (currentTranslation.startsWith(previousTranslation)) {
            result = currentTranslation.substring(previousTranslation.length)
        }

        if (TextUtils.isEmpty(result) || !BrailleTranslateUtils.isPronounceable(result)) {
            result = getAppendBrailleTextToSpeak(context.resources, brailleCharacter).orElse(null)
            if (TextUtils.isEmpty(result)) {
                result = BrailleTranslateUtils.getDotsText(context.resources, brailleCharacter)
            }
        }
        if (EditBufferUtils.shouldEmitPerCharacterFeedback(imeConnection)) {
            speak(result!!)
        }
        return result
    }

    override fun appendSpace(imeConnection: ImeConnection) {
        clearHoldingsAndSendToEditor(imeConnection)
        imeConnection.inputConnection.commitText(" ", 1)
    }

    override fun appendNewline(imeConnection: ImeConnection) {
        clearHoldingsAndSendToEditor(imeConnection)
        imeConnection.inputConnection.commitText("\n", 1)
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
        val previousTranslation = translator.translateToPrintPartial(holdings)
        val deletedBrailleCharacter = holdings.remove(holdingPosition)
        speakDelete(getDeletedTextToSpeak(previousTranslation, deletedBrailleCharacter))
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
        val previousTranslation = translator.translateToPrintPartial(holdings)
        val deletedBrailleCharacter = holdings.remove(holdingPosition)
        speakDelete(getDeletedTextToSpeak(previousTranslation, deletedBrailleCharacter))
    }

    override fun deleteWord(imeConnection: ImeConnection) {
        // If there is any holdings left, clear it out; otherwise delete at the Editor level.
        if (!holdings.isEmpty()) {
            val deletedWord = translator.translateToPrint(holdings)
            speakDelete(deletedWord)
            holdingPosition = EditBufferUtils.NO_CURSOR
            // speak(context.getString(R.string.read_out_deleted));
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
        clearHoldingsAndSendToEditor(imeConnection)
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

    override fun moveTextFieldCursor(imeConnection: ImeConnection, index: Int): Boolean {
        if (0 <= index &&
            index <= EditBufferUtils.getTextFieldText(imeConnection.inputConnection).length
        ) {
            return imeConnection.inputConnection.setSelection(index, index)
        }
        return false
    }

    override fun moveHoldingsCursor(imeConnection: ImeConnection, index: Int): Boolean {
        if (0 <= index && index <= holdings.size()) {
            // TODO: speak feedback.
            holdingPosition = index
            return true
        }
        return false
    }

    override fun getHoldingsInfo(imeConnection: ImeConnection): HoldingsInfo {
        return HoldingsInfo.create(ByteBuffer.wrap(holdings.toByteArray()), holdingPosition)
    }

    override fun toString(): String {
        return holdings.toString()
    }

    private fun getDeletedTextToSpeak(
        previousTranslation: String,
        deletedBrailleCharacter: BrailleCharacter,
    ): String {
        var result = ""
        val currentTranslation = translator.translateToPrintPartial(holdings)
        if (previousTranslation.startsWith(currentTranslation)) {
            result = previousTranslation.substring(currentTranslation.length)
        }

        if (TextUtils.isEmpty(result) || !BrailleTranslateUtils.isPronounceable(result)) {
            result =
                getAppendBrailleTextToSpeak(context.resources, deletedBrailleCharacter).orElse(null)
            if (TextUtils.isEmpty(result)) {
                result = BrailleTranslateUtils.getDotsText(
                    context.resources, deletedBrailleCharacter
                )
            }
        }
        return result
    }

    private fun clearHoldingsAndSendToEditor(imeConnection: ImeConnection) {
        if (holdings.isEmpty()) {
            return
        }
        val currentTranslation = translator.translateToPrint(holdings)
        imeConnection.inputConnection.commitText(currentTranslation, 1)

        holdings.clear()
        holdingPosition = EditBufferUtils.NO_CURSOR
    }

    private fun speak(text: String) {
        val speakText = SpeechCleanupUtils.cleanUp(context, text).toString()
        talkBack.speak(speakText, AnnounceType.INTERRUPT)
    }

    private fun speakDelete(text: String) {
        val speakText = SpeechCleanupUtils.cleanUp(context, text).toString()
        talkBack.speak(
            context.getString(R.string.read_out_deleted, speakText), AnnounceType.INTERRUPT
        )
    }

    companion object {
        private const val TAG = "EditBufferUeb2"
        private const val DELETE_WORD_MAX = 50

        private fun getAppendBrailleTextToSpeak(
            resources: Resources,
            brailleCharacter: BrailleCharacter,
        ): Optional<String> {
            return Optional.of(BrailleTranslateUtilsUeb.getTextToSpeak(resources, brailleCharacter))
        }
    }
}
