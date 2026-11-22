/*
 * Copyright (C) 2021 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.android.accessibility.braille.common

import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.VisibleForTesting
import com.google.android.accessibility.braille.common.translate.BrailleLanguages
import com.google.android.accessibility.braille.common.translate.BrailleLanguages.Code
import com.google.android.accessibility.utils.SharedPreferencesUtils
import com.google.common.collect.ImmutableSet
import java.util.Locale

/** SharedPreferences 文件入口与历史版本迁移。 */
class BrailleUserPreferencesMigration private constructor() {

  companion object {
    const val BRAILLE_SHARED_PREFS_FILENAME = "braille_keyboard"
    private const val SHARED_PREFS_VERSION = "brailleime_shared_prefs_version"
    private const val AAS12_0_SHARED_PREFS_VERSION = 1
    private const val AAS12_2_SHARED_PREFS_VERSION = 2
    private const val AAS13_1_SHARED_PREFS_VERSION = 3
    private const val AAS14_1_SHARED_PREFS_VERSION = 4
    private const val AAS16_2_SHARED_PREFS_VERSION = 5
    private var currentVersion = -1

    private const val ACCUMULATE_MODE_DEFAULT = true
    private const val REVERSE_DOTS_MODE_DEFAULT = false
    private const val LAUNCH_TUTORIAL_DEFAULT = true
    private const val EXIT_KEYBOARD_DEFAULT = 0
    private const val SHOW_OPTION_DIALOG_DEFAULT = 0

    private const val DEPRECATED_UEB_1 = "UEB_1"
    private const val DEPRECATED_UEB_2 = "UEB_2"
    private const val DEPRECATED_SINHALA_IN = "SINHALA_IN"

    @JvmStatic
    fun getSharedPreferences(context: Context, fileName: String): SharedPreferences {
      migrateIfNecessary(context)
      return SharedPreferencesUtils.getSharedPreferences(context, fileName)
    }

    /** Migrates user preferences if necerssary.. */
    @JvmStatic
    fun migrateIfNecessary(context: Context) {
      val brailleKeyboardSharedPreferences: SharedPreferences =
        SharedPreferencesUtils.getSharedPreferences(context, BRAILLE_SHARED_PREFS_FILENAME)
      if (currentVersion == -1) {
        currentVersion =
          brailleKeyboardSharedPreferences.getInt(
            SHARED_PREFS_VERSION,
            0,
          ) // 0 could be older versions or never open Talkback.
      }
      val talkBackSharedPreferences: SharedPreferences =
        SharedPreferencesUtils.getSharedPreferences(context)
      val brailleKeyboardSharedPreferencesEditor = brailleKeyboardSharedPreferences.edit()
      // Since we did not record the version number in older builds, it is difficult to determine if
      // this is the first time the application has been launched. Therefore, the command change
      // dialog will be displayed regardless.
      if (currentVersion < AAS16_2_SHARED_PREFS_VERSION) {
        BrailleUserPreferencesDisplay.writeShowCommandChangeDialog(context, /* showDialog= */ true)
      }
      // 1. Rename released key names to new names.
      // 2. copy the existing (old) prefs from TalkBack SharedPreference to braille keyboard
      // SharedPreference, and then remove the old TalkBack SharedPreference.
      // 3. Mark migrated.
      if (currentVersion < AAS12_0_SHARED_PREFS_VERSION) {
        val talkBackSharedPreferencesEditor = talkBackSharedPreferences.edit()
        // Start migrating.
        val newAccumulateModeKey = context.getString(R.string.pref_brailleime_accumulate_mode)
        val oldAccumulatedModeKey = getOldKey(newAccumulateModeKey)
        brailleKeyboardSharedPreferencesEditor.putBoolean(
          newAccumulateModeKey,
          talkBackSharedPreferences.getBoolean(oldAccumulatedModeKey, ACCUMULATE_MODE_DEFAULT),
        )
        talkBackSharedPreferencesEditor.remove(oldAccumulatedModeKey)

        val newReverseDotsModeKey = context.getString(R.string.pref_brailleime_reverse_dots_mode)
        val oldReverseDotsModeKey = getOldKey(newReverseDotsModeKey)
        brailleKeyboardSharedPreferencesEditor.putBoolean(
          newReverseDotsModeKey,
          talkBackSharedPreferences.getBoolean(oldReverseDotsModeKey, REVERSE_DOTS_MODE_DEFAULT),
        )
        talkBackSharedPreferencesEditor.remove(oldReverseDotsModeKey)

        val newContractedModeKey = "pref_brailleime_contracted_mode"
        val oldContractedModeKey = getOldKey(newContractedModeKey)
        brailleKeyboardSharedPreferencesEditor.putBoolean(
          newContractedModeKey,
          talkBackSharedPreferences.getBoolean(oldContractedModeKey, false),
        )
        talkBackSharedPreferencesEditor.remove(oldContractedModeKey)

        val newAutoLaunchKey = context.getString(R.string.pref_brailleime_auto_launch_tutorial)
        val oldAutoLaunchKey = getOldKey(newAutoLaunchKey)
        brailleKeyboardSharedPreferencesEditor.putBoolean(
          newAutoLaunchKey,
          talkBackSharedPreferences.getBoolean(oldAutoLaunchKey, LAUNCH_TUTORIAL_DEFAULT),
        )
        talkBackSharedPreferencesEditor.remove(oldAutoLaunchKey)

        val newExitCountKey = context.getString(R.string.pref_brailleime_exit_keyboard_count)
        val oldExitCountKey = getOldKey(newExitCountKey)
        brailleKeyboardSharedPreferencesEditor.putInt(
          newExitCountKey,
          talkBackSharedPreferences.getInt(oldExitCountKey, EXIT_KEYBOARD_DEFAULT),
        )
        talkBackSharedPreferencesEditor.remove(oldExitCountKey)

        val newShowOptionDialogCountKey =
          context.getString(R.string.pref_brailleime_show_option_dialog_count)
        val oldShowOptionDialogCountKey = getOldKey(newShowOptionDialogCountKey)
        brailleKeyboardSharedPreferencesEditor.putInt(
          newShowOptionDialogCountKey,
          talkBackSharedPreferences.getInt(
            oldShowOptionDialogCountKey,
            SHOW_OPTION_DIALOG_DEFAULT,
          ),
        )
        talkBackSharedPreferencesEditor.remove(oldShowOptionDialogCountKey)

        // Upgrade version.
        brailleKeyboardSharedPreferencesEditor.putInt(
          SHARED_PREFS_VERSION,
          AAS12_0_SHARED_PREFS_VERSION,
        )
        currentVersion = AAS12_0_SHARED_PREFS_VERSION

        brailleKeyboardSharedPreferencesEditor.apply()
        talkBackSharedPreferencesEditor.apply()
      }
      // Replace Code.UEB with Code.UEB1 and Code.UEB2. Code.UEB is no longer exists.
      if (currentVersion < AAS12_2_SHARED_PREFS_VERSION) {
        // Update current Code.
        val contracted =
          brailleKeyboardSharedPreferences.getBoolean("pref_brailleime_contracted_mode", false)
        val codePrefKey = context.getString(R.string.pref_brailleime_translator_code)
        if (brailleKeyboardSharedPreferences.getString(codePrefKey, "UEB").equals("UEB")) {
          val newCode = if (contracted) DEPRECATED_UEB_2 else DEPRECATED_UEB_1
          brailleKeyboardSharedPreferencesEditor.putString(codePrefKey, newCode)
        }
        // Update selected Codes.
        val selectedCodePrefKey =
          context.getString(R.string.pref_brailleime_translator_codes_preferred)
        val selectedCodesInStringSet =
          brailleKeyboardSharedPreferences
            .getStringSet(selectedCodePrefKey, ImmutableSet.of())!!
            .toMutableSet()
        if (selectedCodesInStringSet.remove(Code.UEB.name)) {
          selectedCodesInStringSet.add(DEPRECATED_UEB_1)
          selectedCodesInStringSet.add(DEPRECATED_UEB_2)
        }
        if (selectedCodesInStringSet.isEmpty()) {
          selectedCodesInStringSet.add(BrailleLanguages.getDefaultCode(context).name)
        }
        brailleKeyboardSharedPreferencesEditor.putStringSet(
          selectedCodePrefKey,
          selectedCodesInStringSet,
        )
        // Upgrade version.
        brailleKeyboardSharedPreferencesEditor.putInt(
          SHARED_PREFS_VERSION,
          AAS12_2_SHARED_PREFS_VERSION,
        )
        currentVersion = AAS12_2_SHARED_PREFS_VERSION

        brailleKeyboardSharedPreferencesEditor.apply()
      }
      // Replace UEB_1 and UEB_2 to UEB.
      if (currentVersion < AAS13_1_SHARED_PREFS_VERSION) {
        var contracted = false
        // Update input active code.
        var codePrefKey = context.getString(R.string.pref_brailleime_translator_code)
        val inputCode = brailleKeyboardSharedPreferences.getString(codePrefKey, Code.UEB.name)
        if (inputCode == DEPRECATED_UEB_1) {
          brailleKeyboardSharedPreferencesEditor.putString(codePrefKey, Code.UEB.name)
        } else if (inputCode == DEPRECATED_UEB_2) {
          brailleKeyboardSharedPreferencesEditor.putString(codePrefKey, Code.UEB.name)
          contracted = true
        }
        // Update output active code.
        codePrefKey = context.getString(R.string.pref_bd_output_code)
        val outputCode = brailleKeyboardSharedPreferences.getString(codePrefKey, Code.UEB.name)
        if (outputCode == DEPRECATED_UEB_1) {
          brailleKeyboardSharedPreferencesEditor.putString(codePrefKey, Code.UEB.name)
        } else if (outputCode == DEPRECATED_UEB_2) {
          brailleKeyboardSharedPreferencesEditor.putString(codePrefKey, Code.UEB.name)
          contracted = true
        }
        // Update contracted.
        val contractedModePrefKey = context.getString(R.string.pref_braille_contracted_mode)
        brailleKeyboardSharedPreferencesEditor.putBoolean(contractedModePrefKey, contracted)

        // Update selected Codes.
        var hasUeb = false
        val selectedCodePrefKey =
          context.getString(R.string.pref_brailleime_translator_codes_preferred)
        val selectedCodesInStringSet =
          brailleKeyboardSharedPreferences
            .getStringSet(selectedCodePrefKey, ImmutableSet.of())!!
            .toMutableSet()
        if (selectedCodesInStringSet.remove(DEPRECATED_UEB_1)) {
          hasUeb = true
        }
        if (selectedCodesInStringSet.remove(DEPRECATED_UEB_2)) {
          hasUeb = true
        }
        if (hasUeb) {
          selectedCodesInStringSet.add(Code.UEB.name)
        }
        if (selectedCodesInStringSet.isEmpty()) {
          selectedCodesInStringSet.add(BrailleLanguages.getDefaultCode(context).name)
        }
        brailleKeyboardSharedPreferencesEditor.putStringSet(
          selectedCodePrefKey,
          selectedCodesInStringSet,
        )

        // Upgrade version.
        brailleKeyboardSharedPreferencesEditor.putInt(
          SHARED_PREFS_VERSION,
          AAS13_1_SHARED_PREFS_VERSION,
        )
        currentVersion = AAS13_1_SHARED_PREFS_VERSION

        brailleKeyboardSharedPreferencesEditor.apply()
      }
      // Replace SINHALA_IN with SINHALA or SINDHI_IN.
      if (currentVersion < AAS14_1_SHARED_PREFS_VERSION) {
        val locale = Locale.getDefault()
        val newCode: String
        if (locale.language == Code.SINDHI_IN.locale.language &&
          locale.country == Code.SINDHI_IN.locale.country
        ) {
          newCode = Code.SINDHI_IN.name
        } else {
          newCode = Code.SINHALA.name
        }
        // Update selected Codes.
        val selectedCodePrefKey =
          context.getString(R.string.pref_brailleime_translator_codes_preferred)
        val selectedCodesInStringSet =
          brailleKeyboardSharedPreferences
            .getStringSet(selectedCodePrefKey, ImmutableSet.of())!!
            .toMutableSet()
        if (selectedCodesInStringSet.remove(DEPRECATED_SINHALA_IN)) {
          selectedCodesInStringSet.add(newCode)
        }
        brailleKeyboardSharedPreferencesEditor.putStringSet(
          selectedCodePrefKey,
          selectedCodesInStringSet,
        )
        // Update input active code.
        val codePrefKey = context.getString(R.string.pref_brailleime_translator_code)
        val inputCode = brailleKeyboardSharedPreferences.getString(codePrefKey, Code.UEB.name)
        if (inputCode == DEPRECATED_SINHALA_IN) {
          brailleKeyboardSharedPreferencesEditor.putString(codePrefKey, newCode)
        }
        // Update output active code.
        val outputCodeKey = context.getString(R.string.pref_bd_output_code)
        val outputCode = brailleKeyboardSharedPreferences.getString(outputCodeKey, Code.UEB.name)
        if (outputCode == DEPRECATED_SINHALA_IN) {
          brailleKeyboardSharedPreferencesEditor.putString(outputCodeKey, newCode)
        }
        // Upgrade version.
        brailleKeyboardSharedPreferencesEditor.putInt(
          SHARED_PREFS_VERSION,
          AAS14_1_SHARED_PREFS_VERSION,
        )
        currentVersion = AAS14_1_SHARED_PREFS_VERSION

        brailleKeyboardSharedPreferencesEditor.apply()
      }
      // Show command change dialog.
      if (currentVersion < AAS16_2_SHARED_PREFS_VERSION) {
        brailleKeyboardSharedPreferencesEditor.putInt(
          SHARED_PREFS_VERSION,
          AAS16_2_SHARED_PREFS_VERSION,
        )
        currentVersion = AAS16_2_SHARED_PREFS_VERSION
      }
    }

    private fun getOldKey(key: String): String {
      return key.replace("_brailleime", "")
    }

    @VisibleForTesting
    @JvmStatic
    fun testing_resetCurrentVersion(context: Context) {
      currentVersion = -1
      SharedPreferencesUtils.getSharedPreferences(context, BRAILLE_SHARED_PREFS_FILENAME)
        .edit()
        .remove(SHARED_PREFS_VERSION)
        .apply()
    }
  }
}
