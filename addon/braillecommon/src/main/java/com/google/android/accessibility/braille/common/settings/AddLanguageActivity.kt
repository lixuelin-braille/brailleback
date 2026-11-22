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

import android.content.Intent
import android.os.Bundle
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import com.google.android.accessibility.braille.common.BrailleStringUtils
import com.google.android.accessibility.braille.common.BrailleUserPreferences
import com.google.android.accessibility.braille.common.R
import com.google.android.accessibility.braille.common.translate.BrailleLanguages
import com.google.android.accessibility.utils.PreferenceSettingsUtils
import com.google.android.accessibility.utils.preference.PreferencesActivity
import java.text.Collator
import java.util.Locale

/** Preferred language pick up activity list all of the supported locales. */
class AddLanguageActivity : PreferencesActivity() {

  companion object {
    private const val TAG = "BrailleLanguageActivity"
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
  }

  override fun createPreferenceFragment(): PreferenceFragmentCompat {
    return AddLanguageFragment()
  }

  override fun getFragmentTag(): String? {
    return TAG
  }

  /** Fragment of AddLanguageActivity. */
  class AddLanguageFragment : PreferenceFragmentCompat() {

    private lateinit var localeCategoryPreference: PreferenceCategory

    override fun onCreate(savedInstanceState: Bundle?) {
      super.onCreate(savedInstanceState)
    }

    override fun onCreatePreferences(bundle: Bundle?, rootKey: String?) {
      preferenceManager.sharedPreferencesName = BrailleUserPreferences.BRAILLE_SHARED_PREFS_FILENAME
      PreferenceSettingsUtils.addPreferencesFromResource(
        this,
        R.xml.braille_add_language_preferences,
      )

      localeCategoryPreference =
        findPreference<PreferenceCategory>(getString(R.string.pref_braille_languages_category_key))!!

      initPreferences()
    }

    private fun initPreferences() {
      val locales: MutableList<Locale> =
        BrailleLanguages.getAvailableCodes(context!!)
          .map { code -> Locale.forLanguageTag(code.locale.language) }
          .distinct()
          .toMutableList()
      val systemLocale = Locale.getDefault()
      val collator = Collator.getInstance(systemLocale)
      locales.sortWith(Comparator { locale1, locale2 ->
        collator.compare(locale1.getDisplayLanguage(), locale2.getDisplayLanguage())
      })
      var systemLocaleIndex = -1
      for (i in locales.indices) {
        if (locales[i].language == systemLocale.language) {
          systemLocaleIndex = i
          break
        }
      }
      // Place system locale at the beginning.
      if (systemLocaleIndex != -1) {
        locales.add(/* index= */ 0, locales.removeAt(systemLocaleIndex))
      }
      for (locale in locales) {
        localeCategoryPreference.addPreference(createAvailableLocalePreference(locale))
      }
      if (localeCategoryPreference.preferenceCount == 0) {
        localeCategoryPreference.isVisible = false
      }
    }

    private fun createAvailableLocalePreference(locale: Locale): Preference {
      val preference = Preference(context!!)
      preference.title = BrailleStringUtils.toCharacterTitleCase(locale.displayLanguage)
      preference.onPreferenceClickListener =
        Preference.OnPreferenceClickListener {
          val intent = Intent(context!!, LocaleLanguageActivity::class.java)
          intent.putExtra(LocaleLanguageActivity.LOCALE_KEY, locale.language)
          context!!.startActivity(intent)
          finishContainerActivity()
          true
        }
      return preference
    }

    private fun finishContainerActivity() {
      if (activity != null) {
        activity!!.finish()
      }
    }
  }
}
