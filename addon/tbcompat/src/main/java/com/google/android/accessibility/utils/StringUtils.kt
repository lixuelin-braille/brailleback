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
package com.google.android.accessibility.utils

import android.text.TextUtils
import java.util.Locale

/** Utility functions for strings. */
class StringUtils private constructor() {

  companion object {
    /**
     * Capitalizes the first letter of a string. Supports Unicode.
     *
     * @param str The input [String] for which to capitalize the first letter
     * @return The input [String] with the first letter capitalized
     */
    @JvmStatic
    fun capitalizeFirstLetter(str: String): String {
      if (TextUtils.isEmpty(str)) {
        return str
      }
      return if (Character.isUpperCase(str[0])) {
        str
      } else {
        str.substring(0, 1).uppercase(Locale.getDefault()) + str.substring(1)
      }
    }
  }
}
