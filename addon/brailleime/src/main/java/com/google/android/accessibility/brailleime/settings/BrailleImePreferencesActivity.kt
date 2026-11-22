/*
 * Copyright 2018 Google Inc.
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
import android.content.DialogInterface
import android.content.SharedPreferences
import android.database.ContentObserver
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.provider.Settings
import android.provider.Settings.ACTION_INPUT_METHOD_SETTINGS
import androidx.appcompat.app.AlertDialog
import android.text.SpannableString
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.view.MenuItem
import android.view.View
import android.widget.TextView
import androidx.annotation.VisibleForTesting
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import com.google.android.accessibility.braille.common.BrailleCommonUtils
import com.google.android.accessibility.braille.common.BraillePreferenceUtils
import com.google.android.accessibility.braille.common.BrailleUserPreferences
import com.google.android.accessibility.braille.common.TouchDots
import com.google.android.accessibility.braille.common.settings.BrailleLanguagesActivity
import com.google.android.accessibility.braille.interfaces.TalkBackForBrailleIme
import com.google.android.accessibility.braille.interfaces.TalkBackForBrailleIme.ServiceStatus
import com.google.android.accessibility.brailleime.FeatureFlagReader
import com.google.android.accessibility.brailleime.R
import com.google.android.accessibility.brailleime.Utils
import com.google.android.accessibility.utils.AccessibilityServiceCompatUtils.Constants
import com.google.android.accessibility.utils.FeatureSupport
import com.google.android.accessibility.utils.PackageManagerUtils
import com.google.android.accessibility.utils.PreferenceSettingsUtils
import com.google.android.accessibility.utils.SettingsUtils
import com.google.android.accessibility.utils.material.MaterialComponentUtils
import com.google.android.accessibility.utils.preference.PreferencesActivity

/** Activity used to set BrailleIme's user options. */
class BrailleImePreferencesActivity : PreferencesActivity() {

  private var preferenceFragmentCompat: PreferenceFragmentCompat? = null

  companion object {
    private const val TAG = "BrailleImePreferencesActivity"
    private const val REQUEST_CODE_IME_SETTINGS = 100
    private const val KEYBOARD_ICON_TOKEN = "KEYBOARD_ICON"

    private var talkBackForBrailleIme: TalkBackForBrailleIme? = null

    /** TalkBack invokes this to provide us with the TalkBackForBrailleIme instance. */
    @JvmStatic
    fun initialize(talkBackForBrailleIme: TalkBackForBrailleIme?) {
      this.talkBackForBrailleIme = talkBackForBrailleIme
    }

    @JvmStatic
    private fun insertHyperLinkToSubString(spannableString: SpannableString, subString: String) {
      Utils.formatSubstringAsUrl(
          spannableString,
          subString,
          "http://play.google.com/store/apps/details?id=" + Constants.GBOARD_PACKAGE_NAME)
    }
  }

  override fun onOptionsItemSelected(item: MenuItem): Boolean {
    if (item.itemId == android.R.id.home) {
      val startTalkBackSettings = Intent()
      startTalkBackSettings.component = Constants.SETTINGS_ACTIVITY
      startTalkBackSettings.addFlags(Intent.FLAG_ACTIVITY_PREVIOUS_IS_TOP)
      startActivity(startTalkBackSettings)
      finish()
      return true
    }
    return super.onOptionsItemSelected(item)
  }

  override fun createPreferenceFragment(): PreferenceFragmentCompat {
    preferenceFragmentCompat = BrailleImePrefFragment()
    return preferenceFragmentCompat!!
  }

  override fun getFragmentTag(): String? {
    return TAG
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    // 与键盘本体一致：进设置页时也保证"语言"列表已播种，否则用户看到的是只有一个选项的下拉。
    BrailleUserPreferences.ensureRecommendedPreferredCodes(this)
    contentResolver
        .registerContentObserver(
            Settings.Secure.getUriFor(Settings.Secure.ENABLED_INPUT_METHODS),
            /* notifyForDescendants= */ false,
            imeSettingsContentObserver)
  }

  override fun onDestroy() {
    contentResolver.unregisterContentObserver(imeSettingsContentObserver)
    super.onDestroy()
  }

  /** Panel holding a set of developer preferences. */
  class BrailleImePrefFragment : PreferenceFragmentCompat() {
    private var brailleGradePreference: Preference? = null

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
      preferenceManager
          .sharedPreferencesName = BrailleUserPreferences.BRAILLE_SHARED_PREFS_FILENAME
      PreferenceSettingsUtils.addPreferencesFromResource(this, R.xml.brailleime_preferences)
      brailleGradePreference =
          findPreference<Preference>(getString(R.string.pref_braille_contracted_mode))
      val preference =
          findPreference<Preference>(getString(R.string.pref_brailleime_review_all_gestures))!!
      preference.setTitle(
          if (FeatureFlagReader.useGestureCustomization(context!!))
              R.string.review_and_customize_gestures_title
          else R.string.review_all_gestures_title)
      preferenceManager.sharedPreferences!!
          .registerOnSharedPreferenceChangeListener(onSharedPreferenceChangeListener)
    }

    override fun onResume() {
      super.onResume()
      configurePrefs()
    }

    override fun onDestroy() {
      super.onDestroy()
      preferenceManager.sharedPreferences!!
          .unregisterOnSharedPreferenceChangeListener(onSharedPreferenceChangeListener)
    }

    fun configurePrefs() {
      // Turn on keyboard preference.
      val turnOnKeyboardPref =
          findPreference<Preference>(
              getString(R.string.pref_brailleime_turn_on_braille_keyboard))!!
      turnOnKeyboardPref.onPreferenceClickListener =
          Preference.OnPreferenceClickListener {
            (activity as BrailleImePreferencesActivity).showTurnOnKeyboardDialog()
            true
          }
      turnOnKeyboardPref.title =
          if (BrailleCommonUtils.isBrailleKeyboardEnabled(context!!))
              getString(R.string.how_to_use_braille_keyboard)
          else getString(R.string.set_up_braille_keyboard)

      // Typing codes preference.
      val typingCodesPref =
          findPreference<Preference>(
              getString(R.string.pref_brailleime_translator_codes_preferred))!!
      typingCodesPref.intent = Intent(context!!, BrailleLanguagesActivity::class.java)
      BraillePreferenceUtils.setupPreferredCodePreference(
          context!!,
          typingCodesPref,
          Preference.OnPreferenceChangeListener { _, _ ->
            configurePrefs()
            false
          })

      // Preferred input codes preference.
      val preferredInputCodePref =
          findPreference<ListPreference>(
              getString(R.string.pref_brailleime_translator_code))
      if (preferredInputCodePref != null) {
        BraillePreferenceUtils.setupLanguageListPreference(
            context!!,
            preferredInputCodePref,
            BrailleUserPreferences::readCurrentActiveInputCodeAndCorrect,
            BrailleUserPreferences::writeCurrentActiveInputCode,
            Preference.OnPreferenceChangeListener { _, _ ->
              if (BrailleUserPreferences.readShowSwitchBrailleKeyboardInputCodeGestureTip(
                  context!!)) {
                showSwitchInputCodeGestureTipDialog()
              }
              false
            })
      }

      // See all actions preference.
      val seeAllActionPref =
          findPreference<Preference>(
              getString(R.string.pref_brailleime_review_all_gestures))!!
      seeAllActionPref.intent = Intent(context!!, BrailleImeGestureActivity::class.java)

      // Accumulate mode preference.
      val accumulateModePref =
          findPreference<SwitchPreferenceCompat>(
              getString(R.string.pref_brailleime_accumulate_mode))!!
      accumulateModePref.isChecked = BrailleUserPreferences.readAccumulateMode(context!!)
      accumulateModePref.onPreferenceClickListener =
          Preference.OnPreferenceClickListener { preference ->
            BrailleUserPreferences.writeAccumulateMode(
                context!!, (preference as SwitchPreferenceCompat).isChecked)
            true
          }
      accumulateModePref.isChecked = BrailleUserPreferences.readAccumulateMode(context!!)

      // Reverse dots mode preference.
      val reverseDotsModePref =
          findPreference<SwitchPreferenceCompat>(
              getString(R.string.pref_brailleime_reverse_dots_mode))!!
      reverseDotsModePref.isChecked =
          BrailleUserPreferences.readReverseDotsMode(context!!)
      reverseDotsModePref.onPreferenceClickListener =
          Preference.OnPreferenceClickListener { preference ->
            BrailleUserPreferences.writeReverseDotsMode(
                context!!, (preference as SwitchPreferenceCompat).isChecked)
            true
          }
      reverseDotsModePref.isChecked =
          BrailleUserPreferences.readReverseDotsMode(context!!)

      // Layout settings preference.
      val layoutModePref =
          findPreference<ListPreference>(getString(R.string.pref_brailleime_layout_mode))
      if (layoutModePref != null) {
        layoutModePref.entryValues = TouchDots.values().map { it.name }.toTypedArray()
        layoutModePref.entries =
            TouchDots.values().map { value -> value.getLayoutDescription(resources) }
                .toTypedArray()
        layoutModePref.value = BrailleUserPreferences.readLayoutMode(context!!).name
        layoutModePref.summaryProvider =
            Preference.SummaryProvider<ListPreference> {
              BrailleUserPreferences.readLayoutMode(context!!).getLayoutName(resources)
            }
        layoutModePref.onPreferenceChangeListener =
            Preference.OnPreferenceChangeListener { _, newValue ->
              BrailleUserPreferences.writeLayoutMode(
                  context!!, TouchDots.valueOf(newValue.toString()))
              true
            }
      }

      // Preferred braille grade
      updateBrailleGradeSummary()
    }

    private fun showSwitchInputCodeGestureTipDialog() {
      BraillePreferenceUtils.createDontShowAgainDialog(
              context!!,
              getString(R.string.switch_input_code_gesture_tip_dialog_title),
              getString(R.string.switch_input_code_gesture_tip_dialog_message),
          ) { context, checked ->
            BrailleUserPreferences.writeShowSwitchBrailleKeyboardInputCodeGestureTip(
                context, !checked)
          }
          .show()
    }

    private fun updateBrailleGradeSummary() {
      brailleGradePreference!!.setSummary(
          getString(
              if (BrailleUserPreferences.readContractedMode(context!!))
                  R.string.bd_preference_braille_contracted
              else R.string.bd_preference_braille_uncontracted))
    }

    private val onSharedPreferenceChangeListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
          if (key == getString(R.string.pref_braille_contracted_mode)) {
            updateBrailleGradeSummary()
          }
        }
  }

  private fun showTurnOnKeyboardDialog() {
    val builder = MaterialComponentUtils.alertDialogBuilder(this)
    builder.setTitle(
        if (BrailleCommonUtils.isBrailleKeyboardEnabled(this))
            getString(R.string.how_to_use_braille_keyboard)
        else getString(R.string.set_up_braille_keyboard))
    if (BrailleCommonUtils.isBrailleKeyboardEnabled(this)) {
      builder
          .setMessage(getDialogMessageForImeEnabled())
          .setPositiveButton(android.R.string.ok, null)
    } else {
      builder
          .setMessage(getDialogMessageForImeDisabled())
          .setPositiveButton(
              getString(
                  if (supportEnableIme())
                      R.string.use_brailleime_pref_button_case_ime_disabled_turn_on
                  else R.string.use_brailleime_pref_button_case_ime_disabled_settings),
              DialogInterface.OnClickListener { _, _ ->
                Utils.setComponentEnabled(this, Constants.BRAILLE_KEYBOARD, true)
                if (supportEnableIme() && talkBackForBrailleIme!!.setInputMethodEnabled()) {
                  (supportFragmentManager.findFragmentByTag(TAG) as BrailleImePrefFragment)
                      .configurePrefs()
                } else {
                  startActivityForResult(
                      Intent(ACTION_INPUT_METHOD_SETTINGS), REQUEST_CODE_IME_SETTINGS)
                }
              })
          .setNegativeButton(
              android.R.string.cancel,
              DialogInterface.OnClickListener { dialogInterface, _ -> dialogInterface.dismiss() })
    }
    val dialog = builder.create()
    if (!BrailleCommonUtils.isBrailleKeyboardEnabled(this) && supportEnableIme()) {
      dialog.setOnShowListener {
        dialog
            .getButton(AlertDialog.BUTTON_POSITIVE)
            .contentDescription =
                getString(
                    R.string.use_brailleime_pref_button_case_ime_disabled_turn_on_announcement)
      }
    }
    dialog.show()

    // Set movement method to url link.
    val message: View? = dialog.findViewById(android.R.id.message)
    if (message is TextView) {
      message.movementMethod = LinkMovementMethod.getInstance()
    }
  }

  private fun supportEnableIme(): Boolean {
    return FeatureSupport.supportEnableDisableIme() &&
        talkBackForBrailleIme != null &&
        talkBackForBrailleIme!!.getServiceStatus() != ServiceStatus.OFF
  }

  @VisibleForTesting
  internal fun getDialogMessageForImeEnabled(): Spanned {
    val gboardName = getString(R.string.gboard_name)
    val gboardMessage =
        getString(R.string.use_brailleime_pref_dialog_case_ime_enabled_gboard, gboardName)
    val message = StringBuilder()
    message.append(
        getString(
            R.string.use_brailleime_pref_dialog_case_ime_enabled,
            KEYBOARD_ICON_TOKEN,
            getString(R.string.braille_ime_service_name)))
    if (PackageManagerUtils.hasGmsCorePackage(this)) {
      message.append("\n\n").append(gboardMessage)
    }

    var spannableMessageString: SpannableString = SpannableString.valueOf(message)
    spannableMessageString = replaceKeyboardIconTokenToIconDrawable(spannableMessageString)
    if (SettingsUtils.allowLinksOutOfSettings(this)) {
      insertHyperLinkToSubString(spannableMessageString, gboardName)
    }

    return spannableMessageString
  }

  @VisibleForTesting
  internal fun getDialogMessageForImeDisabled(): Spanned {
    val gboardName = getString(R.string.gboard_name)
    val gboardMessage =
        getString(
            if (supportEnableIme())
                R.string.use_brailleime_pref_dialog_case_ime_disabled_turn_on_gboard
            else R.string.use_brailleime_pref_dialog_case_ime_enabled_gboard,
            gboardName)
    val message = StringBuilder()
    message.append(
        getString(
            /* resId= */ if (supportEnableIme())
                R.string.use_brailleime_pref_dialog_case_ime_disabled_turn_on
            else R.string.use_brailleime_pref_dialog_case_ime_disabled_settings,
            getString(R.string.braille_ime_service_name),
            KEYBOARD_ICON_TOKEN))
    if (PackageManagerUtils.hasGmsCorePackage(this)) {
      message.append("\n\n").append(gboardMessage)
    }

    var spannableMessageString: SpannableString = SpannableString.valueOf(message)
    spannableMessageString = replaceKeyboardIconTokenToIconDrawable(spannableMessageString)
    if (SettingsUtils.allowLinksOutOfSettings(this)) {
      insertHyperLinkToSubString(spannableMessageString, gboardName)
    }
    return spannableMessageString
  }

  private fun replaceKeyboardIconTokenToIconDrawable(spannableString: SpannableString): SpannableString {
    return Utils.formatSubstringAsDrawable(
        spannableString,
        KEYBOARD_ICON_TOKEN,
        getDrawable(R.drawable.quantum_ic_keyboard_grey600_24)!!)
  }

  private val imeSettingsContentObserver =
      object : ContentObserver(Handler()) {
        override fun deliverSelfNotifications(): Boolean {
          return false
        }

        override fun onChange(selfChange: Boolean) {
          onChange(selfChange, null)
        }

        override fun onChange(selfChange: Boolean, uri: Uri?) {
          if (BrailleCommonUtils.isBrailleKeyboardEnabled(this@BrailleImePreferencesActivity)) {
            finishActivity(REQUEST_CODE_IME_SETTINGS)
          }
        }
      }
}
