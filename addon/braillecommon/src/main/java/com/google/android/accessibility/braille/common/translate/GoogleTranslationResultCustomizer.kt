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
import androidx.annotation.Nullable
import com.google.android.accessibility.braille.interfaces.BrailleCharacter
import com.google.android.accessibility.braille.interfaces.BrailleWord
import com.google.android.accessibility.braille.translate.BrailleTranslator
import com.google.android.accessibility.braille.translate.TranslationResult
import com.google.android.accessibility.braille.translate.TranslationResultCustomizer
import com.google.android.accessibility.utils.EmojiUtils
import java.util.regex.Matcher

/**
 * TranslationResultCustomizer customizes the result for some indicator replacement such as newline.
 */
open class GoogleTranslationResultCustomizer(context: Context) : TranslationResultCustomizer {

    private val context: Context = context

    @Nullable
    override fun customize(
        result: TranslationResult?,
        translator: BrailleTranslator,
    ): TranslationResult? {
        var current = result ?: return null
        // Force translate '\n' to space-1246-123.
        for (i in 0 until current.text().length) {
            if (current.text()[i] != NEW_LINE) {
                continue
            }
            val correctBrailleWord = BrailleWord()
            correctBrailleWord.append(BrailleCharacter.EMPTY_CELL)
            correctBrailleWord.append(BrailleWord.NEW_LINE)
            current = TranslationResult.correctTranslation(current, correctBrailleWord, i, i + 1)
        }

        // Replace emoji with emoji's short code.
        val emojis: Matcher = EmojiUtils.findEmoji(current.text())
        while (emojis.find()) {
            val replacement = EmojiUtils.getEmojiShortCode(emojis.group())
            val correctBrailleWord = BrailleWord()
            correctBrailleWord.append(BrailleCharacter.EMPTY_CELL)
            correctBrailleWord.append(
                translator.translate(replacement, -1 /* cursorPosition= */).cells(),
            )
            current =
                TranslationResult.correctTranslation(
                    current,
                    correctBrailleWord,
                    emojis.start(),
                    emojis.end(),
                )
        }
        return current
    }

    companion object {
        private const val NEW_LINE = '\n'
    }
}
