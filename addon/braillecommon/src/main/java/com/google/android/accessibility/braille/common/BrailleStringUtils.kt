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

package com.google.android.accessibility.braille.common

import android.text.TextUtils
import java.util.Locale

/** Braille string utils. */
class BrailleStringUtils private constructor() {

  companion object {
    /** Converts string to character title case. */
    @JvmStatic
    fun toCharacterTitleCase(input: CharSequence): CharSequence {
      if (TextUtils.isEmpty(input)) {
        return input
      }
      return input[0].toString().uppercase(Locale.getDefault()) +
          input.subSequence(1, input.length)
    }
  }
}
