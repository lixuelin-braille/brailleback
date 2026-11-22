/*
 * Copyright 2020 Google Inc.
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

package com.google.android.accessibility.braille.translate.liblouis

import android.content.Context
import com.google.android.accessibility.braille.interfaces.BrailleCharacter

/** LibLouis Spanish translator. */
class LibLouisTranslatorSpanish(context: Context) :
    LibLouisTranslator(context, "es.tbl") {

  init {
    setupCommutativityReplacementMap()
  }

  private fun setupCommutativityReplacementMap() {
    // LibLouis's Spanish translator translates "3" as apostrophe, but in almost all cases, "3"
    // should be translated as a period.
    getCommutativityMap().put(BrailleCharacter(3), ".")

    // LibLouis's Spanish translator translates "23" as 2. It should be ;.
    getCommutativityMap().put(BrailleCharacter(2, 3), ";")

    // LibLouis's Spanish translator translates "25" as 3. It should be :.
    getCommutativityMap().put(BrailleCharacter(2, 5), ":")

    // LibLouis's Spanish translator translates "26" as 5. It should be ?.
    getCommutativityMap().put(BrailleCharacter(2, 6), "?")
  }

  override fun transformPostTranslation(translation: String): String {
    var translation = translation
    if (translation.length > 0) {
      val char0 = translation[0]
      // If the translation begins with an invertible punctuation character, invert that character
      // in accordance with Spanish orthographic custom.
      if (PUNCTUATION_INVERSE_MAP.containsKey(char0)) {
        for (invertiblePunctuationSequence in INVERTIBLE_PUNCTUATION_SEQUENCES) {
          if (translation.startsWith(invertiblePunctuationSequence)) {
            translation =
                invertPunctuation(invertiblePunctuationSequence) +
                    translation.substring(invertiblePunctuationSequence.length)
          }
        }
      }
    }
    return translation
  }

  companion object {
    private val INVERTIBLE_PUNCTUATION_SEQUENCES = arrayOf("?", "!", "?!", "!?")

    private val PUNCTUATION_INVERSE_MAP: MutableMap<Char, Char> = LinkedHashMap()

    init {
      PUNCTUATION_INVERSE_MAP['?'] = '¿'
      PUNCTUATION_INVERSE_MAP['!'] = '¡'
    }

    private fun invertPunctuation(string: String): String {
      return string.replace('?', '¿').replace('!', '¡')
    }
  }
}
