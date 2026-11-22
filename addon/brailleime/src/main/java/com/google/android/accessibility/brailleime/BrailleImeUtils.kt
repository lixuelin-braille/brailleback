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

package com.google.android.accessibility.brailleime

import android.content.Context
import android.content.Intent
import androidx.annotation.Nullable
import com.google.android.accessibility.brailleime.SupportedCommand.Category.SPELL_CHECK
import com.google.android.accessibility.brailleime.settings.BrailleImeGestureActivity
import com.google.android.accessibility.brailleime.settings.BrailleImeGestureCommandActivity

/** Utils of impl version Braille keyboard. */
class BrailleImeUtils private constructor() {

  companion object {
    /**
     * Gets the intent for starting spell check gesture command activity. Returns null if not
     * supported.
     */
    @Nullable
    @JvmStatic
    fun getStartSpellCheckGestureCommandActivityIntent(context: Context): Intent? {
      val intent = Intent(context, BrailleImeGestureCommandActivity::class.java)
      intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      intent.putExtra(BrailleImeGestureActivity.CATEGORY, SPELL_CHECK)
      return intent
    }
  }
}
