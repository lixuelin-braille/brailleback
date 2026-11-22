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

import android.content.Context

/** Creates Translator instances that delegate to the Google braille translation library. */
class GoogleBrailleTranslatorFactory(
  private val translatorFactory: TranslatorFactory,
  private val customizer: TranslationResultCustomizer,
) : TranslatorFactory {

  override fun create(context: Context, codeName: String, contractedMode: Boolean): BrailleTranslator {
    return GoogleBrailleTranslator(
        translatorFactory.create(context, codeName, contractedMode), customizer)
  }

  override fun getLibraryName(): String {
    return translatorFactory.getLibraryName()
  }
}
