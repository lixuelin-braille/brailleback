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

package com.google.android.accessibility.brailleime.settings

import android.content.Intent
import android.os.Bundle
import androidx.annotation.StringRes
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.google.android.accessibility.braille.common.BrailleUserPreferences
import com.google.android.accessibility.brailleime.FeatureFlagReader
import com.google.android.accessibility.brailleime.R
import com.google.android.accessibility.brailleime.SupportedCommand.Category
import com.google.android.accessibility.brailleime.SupportedCommand.Category.BASIC
import com.google.android.accessibility.brailleime.SupportedCommand.Category.CURSOR_MOVEMENT
import com.google.android.accessibility.brailleime.SupportedCommand.Category.SPELL_CHECK
import com.google.android.accessibility.brailleime.SupportedCommand.Category.TEXT_SELECTION_AND_EDITING
import com.google.android.accessibility.brailleime.dialog.ResetAllGestureDialog
import com.google.android.accessibility.utils.PreferenceSettingsUtils
import com.google.android.accessibility.utils.preference.PreferencesActivity
import com.google.android.material.snackbar.Snackbar

/** An activity for showing a BrailleIme gesture. */
class BrailleImeGestureActivity : PreferencesActivity() {

  companion object {
    const val CATEGORY = "category"
  }

  override fun createPreferenceFragment(): PreferenceFragmentCompat {
    return BrailleImeGestureFragment()
  }

  /** Fragment that holds the braille keyboard gesture preference. */
  class BrailleImeGestureFragment : PreferenceFragmentCompat() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
      preferenceManager.sharedPreferencesName =
          BrailleUserPreferences.BRAILLE_SHARED_PREFS_FILENAME
      PreferenceSettingsUtils.addPreferencesFromResource(this, R.xml.brailleime_gesture_category)
      setIntentForClickEvent(R.string.pref_key_brailleime_basic_controls, BASIC)
      setIntentForClickEvent(R.string.pref_key_brailleime_cursor_movement, CURSOR_MOVEMENT)
      setIntentForClickEvent(
          R.string.pref_key_brailleime_text_selection_and_editing, TEXT_SELECTION_AND_EDITING)
      setIntentForClickEvent(R.string.pref_key_brailleime_spell_check, SPELL_CHECK)
      val preference =
          findPreference<Preference>(getString(R.string.pref_key_brailleime_reset_all_gestures))!!
      preference.isVisible = FeatureFlagReader.useGestureCustomization(context!!)
      preference.onPreferenceClickListener =
          Preference.OnPreferenceClickListener {
            val dialog =
                ResetAllGestureDialog(
                    context!!,
                    object : ResetAllGestureDialog.ButtonClickListener {
                      override fun onReset() {
                        BrailleUserPreferences.resetAllGestureActionMap(
                            this@BrailleImeGestureFragment.context!!)
                        showSnackBar(
                            R.string.braille_keyboard_snackbar_reset_all, Snackbar.LENGTH_LONG)
                      }
                    })
            dialog.show()
            true
          }
    }

    private fun setIntentForClickEvent(prefKeyId: Int, category: Category) {
      val preference = findPreference<Preference>(getString(prefKeyId))!!
      val intent = Intent(context!!, BrailleImeGestureCommandActivity::class.java)
      intent.putExtra(BrailleImeGestureActivity.CATEGORY, category)
      preference.intent = intent
    }

    private fun showSnackBar(@StringRes resId: Int, duration: Int) {
      val themedContext = context!!
      Snackbar.make(themedContext, view!!, themedContext.getString(resId), duration).show()
    }
  }
}
