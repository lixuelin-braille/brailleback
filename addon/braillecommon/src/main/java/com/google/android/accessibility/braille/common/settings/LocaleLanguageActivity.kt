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

package com.google.android.accessibility.braille.common.settings

import android.os.Bundle
import androidx.preference.CheckBoxPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import com.google.android.accessibility.braille.common.BrailleStringUtils
import com.google.android.accessibility.braille.common.BrailleUserPreferences
import com.google.android.accessibility.braille.common.R
import com.google.android.accessibility.braille.common.translate.BrailleLanguages
import com.google.android.accessibility.braille.common.translate.BrailleLanguages.Code
import com.google.android.accessibility.utils.PreferenceSettingsUtils
import com.google.android.accessibility.utils.preference.PreferencesActivity
import java.text.Collator
import java.util.Locale

/** Preferred language pick up activity based on specific locale. */
class LocaleLanguageActivity : PreferencesActivity() {

  companion object {
    const val LOCALE_KEY = "locale"
    private const val TAG = "PreferredLocaleLanguageActivity"
  }

  private lateinit var locale: Locale

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    title = BrailleStringUtils.toCharacterTitleCase(locale.displayLanguage)
  }

  override fun createPreferenceFragment(): PreferenceFragmentCompat {
    locale = Locale.forLanguageTag(intent.getStringExtra(LOCALE_KEY))
    val fragment = PreferredLocaleLanguageFragment()
    fragment.setLocale(locale)
    return fragment
  }

  override fun getFragmentTag(): String? {
    return TAG
  }

  /** Preferred language pick up fragment based on specific locale. */
  class PreferredLocaleLanguageFragment : PreferenceFragmentCompat() {

    private lateinit var languages: PreferenceCategory
    private var locale: Locale? = null

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
      preferenceManager.sharedPreferencesName = BrailleUserPreferences.BRAILLE_SHARED_PREFS_FILENAME
      PreferenceSettingsUtils.addPreferencesFromResource(
        this,
        R.xml.braille_language_locale_preferences,
      )

      languages =
        findPreference<PreferenceCategory>(
          getString(R.string.pref_braille_preferred_locale_languages_category_key)
        )!!
      refresh()
    }

    fun setLocale(locale: Locale) {
      this.locale = locale
    }

    private fun refresh() {
      languages.removeAll()
      val codes =
        BrailleLanguages.getAvailableCodes(context!!)
          .filter { code -> code.locale.language == locale!!.language }
          .distinct()
          .toMutableList()
      val collator = Collator.getInstance(Locale.getDefault())
      codes.sortWith(Comparator { code1, code2 ->
        collator.compare(
          code1.getUserFacingName(context!!),
          code2.getUserFacingName(context!!),
        )
      })
      val preferredCodes =
        BrailleUserPreferences.readAvailablePreferredCodes(context!!).toMutableList()
      for (code in codes) {
        languages.addPreference(createPreference(code, preferredCodes))
      }
    }

    private fun createPreference(code: Code, preferredCodes: MutableList<Code>): CheckBoxPreference {
      val preference = CheckBoxPreference(context!!)
      preference.title = code.getUserFacingName(context!!)
      preference.isChecked = preferredCodes.contains(code)
      preference.onPreferenceClickListener =
        Preference.OnPreferenceClickListener { preference1 ->
          val checked = (preference1 as CheckBoxPreference).isChecked
          if (checked) {
            preferredCodes.add(code)
            BrailleUserPreferences.writePreferredCodes(context!!, preferredCodes)
          } else {
            BrailleUserPreferences.removePreferredCode(context!!, code)
          }
          true
        }
      return preference
    }
  }
}
