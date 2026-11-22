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

package com.google.android.accessibility.braille.translate

import androidx.annotation.Nullable
import com.google.android.accessibility.braille.interfaces.BrailleWord

/** Translates from text to braille with custom translation. */
class GoogleBrailleTranslator(
  private val translator: BrailleTranslator,
  @Nullable private val customizer: TranslationResultCustomizer?,
) : BrailleTranslator {

  override fun translateToPrint(brailleWord: BrailleWord): String {
    return translator.translateToPrint(brailleWord)
  }

  override fun translateToPrintPartial(brailleWord: BrailleWord): String {
    return translator.translateToPrintPartial(brailleWord)
  }

  override fun translate(text: CharSequence, cursorPosition: Int): TranslationResult {
    val result = translator.translate(text, cursorPosition)
    return if (customizer == null) result else customizer.customize(result, this) ?: result
  }
}
