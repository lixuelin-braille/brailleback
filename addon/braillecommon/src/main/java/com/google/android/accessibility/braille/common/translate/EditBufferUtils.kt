/*
 * Copyright 2020 Google Inc.
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
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import com.google.android.accessibility.braille.common.BrailleCommonUtils
import com.google.android.accessibility.braille.common.ImeConnection
import com.google.android.accessibility.braille.common.ImeConnection.AnnounceType.SILENCE
import com.google.android.accessibility.braille.common.R
import com.google.android.accessibility.braille.common.TalkBackSpeaker
import com.google.android.accessibility.braille.common.TalkBackSpeaker.AnnounceType
import com.google.android.accessibility.utils.output.SpeechCleanupUtils

/** Utils for translation of Braille. */
class EditBufferUtils private constructor() {

    companion object {
        const val NO_CURSOR = -1

        /** Determines whether emit single characters feedback. */
        @JvmStatic
        fun shouldEmitPerCharacterFeedback(imeConnection: ImeConnection): Boolean {
            return imeConnection.announceType != SILENCE ||
                !BrailleCommonUtils.isTextField(imeConnection.editorInfo)
        }

        /** Returns cursor position of the edit field. */
        @JvmStatic
        fun getCursorPosition(inputConnection: InputConnection): Int {
            return BrailleCommonUtils.getTextSelection(inputConnection).end
        }

        /** Returns whether the edit field is multi-line. */
        @JvmStatic
        fun isMultiLineField(inputType: Int): Boolean {
            val mask =
                EditorInfo.TYPE_TEXT_FLAG_MULTI_LINE or EditorInfo.TYPE_TEXT_FLAG_IME_MULTI_LINE
            // Consider this a multiline field if it is multiline in the main
            // text field, and not multiline only in the ime fullscreen mode.
            return ((inputType and mask) == EditorInfo.TYPE_TEXT_FLAG_MULTI_LINE)
        }

        /** Returns the text displayed in the text field. */
        @JvmStatic
        fun getTextFieldText(inputConnection: InputConnection): String {
            val extractedText: ExtractedText? =
                inputConnection.getExtractedText(ExtractedTextRequest(), 0)
            if (extractedText != null && extractedText.text != null) {
                return extractedText.text.toString()
            }
            return ""
        }

        /** Speaks cleaned up text. */
        @JvmStatic
        fun speak(context: Context, talkBackSpeaker: TalkBackSpeaker, text: String) {
            val speakText = SpeechCleanupUtils.cleanUp(context, text).toString()
            talkBackSpeaker.speak(speakText, AnnounceType.INTERRUPT)
        }

        /** Speaks cleaned up deleted text. */
        @JvmStatic
        fun speakDelete(
            context: Context,
            talkBackSpeaker: TalkBackSpeaker,
            text: String,
        ) {
            val speakText = SpeechCleanupUtils.cleanUp(context, text).toString()
            talkBackSpeaker.speak(
                context.getString(R.string.read_out_deleted, speakText), AnnounceType.INTERRUPT
            )
        }
    }
}
