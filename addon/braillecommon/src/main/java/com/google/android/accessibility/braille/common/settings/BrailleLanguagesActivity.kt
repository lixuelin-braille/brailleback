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

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.ImageButton
import androidx.core.graphics.drawable.DrawableCompat
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceViewHolder
import com.google.android.accessibility.braille.common.BrailleUserPreferences
import com.google.android.accessibility.braille.common.R
import com.google.android.accessibility.braille.common.translate.BrailleLanguages.Code
import com.google.android.accessibility.utils.PreferenceSettingsUtils
import com.google.android.accessibility.utils.preference.PreferencesActivity

/** Picks up braille preferred languages activity. */
class BrailleLanguagesActivity : PreferencesActivity() {

  companion object {
    private const val TAG = "BrailleLanguageActivity"
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
  }

  override fun createPreferenceFragment(): PreferenceFragmentCompat {
    return BrailleLanguageFragment()
  }

  override fun getFragmentTag(): String? {
    return TAG
  }

  /** Picks up preferred languages fragment. */
  class BrailleLanguageFragment : PreferenceFragmentCompat() {

    private lateinit var languagesPreferenceCategory: PreferenceCategory

    override fun onCreatePreferences(bundle: Bundle?, rootKey: String?) {
      preferenceManager.sharedPreferencesName = BrailleUserPreferences.BRAILLE_SHARED_PREFS_FILENAME
      PreferenceSettingsUtils.addPreferencesFromResource(
        this,
        R.xml.braille_languages_preferences,
      )
      languagesPreferenceCategory =
        findPreference<PreferenceCategory>(
          getString(R.string.pref_braille_preferred_languages_category_key)
        )!!
      val addLanguagePreference =
        findPreference<Preference>(getString(R.string.pref_braille_add_language_preference_key))!!
      addLanguagePreference.intent = Intent(context!!, AddLanguageActivity::class.java)
      DrawableCompat.setTint(
        addLanguagePreference.icon!!,
        context!!.getColor(R.color.settings_primary_text),
      )

      refresh()
    }

    override fun onResume() {
      super.onResume()
      refresh()
    }

    private fun refresh() {
      val codes = BrailleUserPreferences.readAvailablePreferredCodes(context!!)
      val languagesCategoryList = getLanguagesCategoryList()
      for (i in codes.indices) {
        if (i < languagesCategoryList.size) {
          if (codes[i].name != languagesCategoryList[i].key) {
            updateLanguagePreference(languagesPreferenceCategory.getPreference(i), codes[i])
          }
        } else {
          languagesPreferenceCategory.addPreference(createLanguagePreference(codes[i]))
        }
      }
      for (i in languagesPreferenceCategory.preferenceCount - 1 downTo codes.size) {
        languagesPreferenceCategory.removePreference(languagesPreferenceCategory.getPreference(i))
      }
    }

    private fun createLanguagePreference(code: Code): Preference {
      val preference = LanguagePreference(context!!)
      preference.title = code.getUserFacingName(context!!)
      preference.key = code.name
      return preference
    }

    private fun updateLanguagePreference(preference: Preference, code: Code) {
      preference.title = code.getUserFacingName(context!!)
      preference.key = code.name
    }

    private fun getLanguagesCategoryList(): MutableList<Preference> {
      val preferences = mutableListOf<Preference>()
      for (i in 0 until languagesPreferenceCategory.preferenceCount) {
        preferences.add(languagesPreferenceCategory.getPreference(i))
      }
      return preferences
    }

    private class LanguagePreference(context: Context) : Preference(context) {

      init {
        // Prevent triggered by clicking preference.
        isSelectable = false
        widgetLayoutResource = R.layout.image_preference
      }

      override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        val removeButton = holder.findViewById(R.id.remove_button) as ImageButton
        DrawableCompat.setTint(
          removeButton.drawable!!,
          context!!.getColor(R.color.settings_primary_text),
        )
        removeButton.contentDescription =
          context!!.getString(R.string.remove_language_button_content_description, title)
        removeButton.setOnClickListener {
          BrailleUserPreferences.removePreferredCode(
            context!!,
            Code.valueOf(key!!),
          )
          parent!!.removePreference(this)
        }
      }
    }
  }
}
