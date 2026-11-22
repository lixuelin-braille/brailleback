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

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.CheckBox
import android.widget.TextView
import androidx.annotation.Nullable
import androidx.appcompat.app.AlertDialog
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.Preference.OnPreferenceChangeListener
import com.google.android.accessibility.braille.common.R
import com.google.android.accessibility.braille.common.translate.BrailleLanguages
import com.google.android.accessibility.braille.common.translate.BrailleLanguages.Code
import com.google.android.accessibility.utils.material.MaterialComponentUtils
import java.util.function.BiConsumer
import java.util.function.Function

/** Util class for braille preferences. */
class BraillePreferenceUtils private constructor() {

  companion object {
    /**
     * Configures the preferred code {@link Preference} object with appropriate listeners and summary
     * providers.
     */
    @JvmStatic
    fun setupPreferredCodePreference(
      context: Context,
      preferredCodesPref: Preference,
      @Nullable onPreferenceChangeListener: OnPreferenceChangeListener?,
    ) {
      if (BrailleUserPreferences.readAvailablePreferredCodes(context).isEmpty()) {
        setDefaultCodesAsPreferredCodes(context)
      }
      preferredCodesPref.summaryProvider =
        Preference.SummaryProvider<Preference> { _ ->
          val selectedCodes: List<Code> =
            BrailleUserPreferences.readAvailablePreferredCodes(context)
          val sb = StringBuilder()
          for (i in selectedCodes.indices) {
            val lang = selectedCodes[i].getUserFacingName(context)
            if (i == 0) {
              sb.append(lang)
            } else {
              sb.append(context.resources.getString(R.string.split_comma, lang))
            }
          }
          sb
        }
      preferredCodesPref.onPreferenceChangeListener =
        OnPreferenceChangeListener { preference, newValue ->
          if (onPreferenceChangeListener != null) {
            onPreferenceChangeListener.onPreferenceChange(preference, newValue)
          }
          false
        }
    }

    /**
     * Configures the {@link Code} for {@link ListPreference} object with appropriate listeners and
     * value providers.
     */
    @JvmStatic
    fun setupLanguageListPreference(
      context: Context,
      listPreference: ListPreference,
      readCode: Function<Context, Code>,
      writeCode: BiConsumer<Context, Code>,
      @Nullable onPreferenceChangeListener: OnPreferenceChangeListener?,
    ) {
      val preferredCodes: List<Code> =
        BrailleUserPreferences.readAvailablePreferredCodes(context)
      listPreference.entryValues = preferredCodes.map { it.name }.toTypedArray()
      listPreference.entries =
        preferredCodes.map { it.getUserFacingName(context) }.toTypedArray()
      listPreference.value = readCode.apply(context).name
      listPreference.summaryProvider =
        Preference.SummaryProvider<Preference> { readCode.apply(context).getUserFacingName(context) }
      listPreference.onPreferenceChangeListener =
        OnPreferenceChangeListener { _, newValue ->
          writeCode.accept(context, Code.valueOf(newValue.toString()))
          if (onPreferenceChangeListener != null) {
            onPreferenceChangeListener.onPreferenceChange(listPreference, newValue)
          }
          true
        }
    }

    /** Creates dialog with "don't show again". */
    @JvmStatic
    fun createDontShowAgainDialog(
      context: Context,
      title: String,
      message: String,
      checkboxConsumer: BiConsumer<Context, Boolean>,
    ): AlertDialog {
      return createDialogWithCheckbox(
        context,
        title,
        message,
        context.getString(R.string.dont_show_again),
        checkboxConsumer,
      )
    }

    /** Creates dialog with check box. */
    @JvmStatic
    fun createDialogWithCheckbox(
      context: Context,
      title: String,
      message: CharSequence,
      checkboxMessage: String,
      checkboxConsumer: BiConsumer<Context, Boolean>,
    ): AlertDialog {
      var builder: AlertDialog.Builder = MaterialComponentUtils.alertDialogBuilder(context)
      val view: View =
        LayoutInflater.from(context).inflate(R.layout.braille_common_dialog_with_checkbox, null)
      val dontShowAgainCheckBox: CheckBox = view.findViewById(R.id.check_box)
      dontShowAgainCheckBox.text = checkboxMessage
      val messageTextView: TextView = view.findViewById(R.id.text_view)
      messageTextView.text = message
      builder =
        builder
          .setTitle(title)
          .setView(view)
          .setPositiveButton(
            android.R.string.ok,
          ) { _, _ -> checkboxConsumer.accept(context, dontShowAgainCheckBox.isChecked) }
      return builder.create()
    }

    private fun setDefaultCodesAsPreferredCodes(context: Context) {
      BrailleUserPreferences.writePreferredCodes(
        context,
        arrayListOf(BrailleLanguages.getDefaultCode(context)),
      )
    }
  }
}
