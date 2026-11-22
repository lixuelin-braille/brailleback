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

import android.content.res.Configuration
import android.os.Bundle
import android.os.Handler
import android.text.SpannableStringBuilder
import android.text.TextUtils
import android.util.Pair
import androidx.annotation.StringRes
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import com.google.android.accessibility.braille.common.BrailleCommonTalkBackSpeaker
import com.google.android.accessibility.braille.common.BrailleImeAction
import com.google.android.accessibility.braille.common.BrailleUserPreferences
import com.google.android.accessibility.braille.interfaces.TalkBackForBrailleIme
import com.google.android.accessibility.braille.interfaces.TalkBackForBrailleIme.ServiceStatus
import com.google.android.accessibility.brailleime.BrailleImeGestureAction
import com.google.android.accessibility.brailleime.BrailleImeGestureController
import com.google.android.accessibility.brailleime.BrailleImeLog
import com.google.android.accessibility.brailleime.BrailleImeVibrator
import com.google.android.accessibility.brailleime.CustomGestureView.CustomGestureCallback
import com.google.android.accessibility.brailleime.FeatureFlagReader
import com.google.android.accessibility.brailleime.R
import com.google.android.accessibility.brailleime.SupportedCommand
import com.google.android.accessibility.brailleime.SupportedCommand.Category
import com.google.android.accessibility.brailleime.SupportedCommand.Category.TEXT_SELECTION_AND_EDITING
import com.google.android.accessibility.brailleime.SupportedCommand.SubCategory.LINE
import com.google.android.accessibility.brailleime.Utils
import com.google.android.accessibility.brailleime.dialog.ChangeGestureDialog
import com.google.android.accessibility.brailleime.dialog.CustomGestureDialog
import com.google.android.accessibility.brailleime.dialog.CustomGestureTalkBackOffDialog
import com.google.android.accessibility.brailleime.dialog.CustomGestureTooFewTouchPointsDialog
import com.google.android.accessibility.brailleime.dialog.MirroredGestureDialog
import com.google.android.accessibility.brailleime.dialog.MirroredGestureDialog.ButtonClickListener
import com.google.android.accessibility.brailleime.dialog.ResetToDefaultDialog
import com.google.android.accessibility.brailleime.input.DotHoldSwipe
import com.google.android.accessibility.brailleime.input.Gesture
import com.google.android.accessibility.brailleime.input.Swipe
import com.google.android.accessibility.brailleime.input.UnassignedGesture
import com.google.android.accessibility.brailleime.keyboardview.AccessibilityOverlayKeyboardView
import com.google.android.accessibility.brailleime.keyboardview.KeyboardView
import com.google.android.accessibility.brailleime.keyboardview.KeyboardView.KeyboardViewCallback
import com.google.android.accessibility.utils.PreferenceSettingsUtils
import com.google.android.accessibility.utils.preference.PreferencesActivity
import com.google.android.material.snackbar.Snackbar
import com.google.common.annotations.VisibleForTesting
import com.google.common.collect.ImmutableList
import com.google.common.collect.ImmutableList.toImmutableList
import java.time.Duration
import java.util.Optional

/** An activity for showing BrailleIme gesture commands. */
class BrailleImeGestureCommandActivity : PreferencesActivity() {

  companion object {
    private var talkBackForBrailleIme: TalkBackForBrailleIme? = null

    /** TalkBack invokes this to provide us with the TalkBackForBrailleIme instance. */
    @JvmStatic
    fun initialize(talkBackForBrailleIme: TalkBackForBrailleIme?) {
      this.talkBackForBrailleIme = talkBackForBrailleIme
    }
  }

  override fun createPreferenceFragment(): PreferenceFragmentCompat {
    return BrailleImeGestureCommandFragment()
  }

  /** Fragment that holds the braille keyboard gesture command preference. */
  class BrailleImeGestureCommandFragment : PreferenceFragmentCompat() {
    private var keyboardView: KeyboardView? = null
    private var customGesturedialog: CustomGestureDialog? = null
    private var changeGestureDialog: ChangeGestureDialog? = null
    private var customGestureTalkBackOffDialog: CustomGestureTalkBackOffDialog? = null
    private var customGestureTooFewTouchPointsDialog: CustomGestureTooFewTouchPointsDialog? = null
    private var resetToDefaultDialog: ResetToDefaultDialog? = null
    private var mirroredGestureDialog: MirroredGestureDialog? = null
    private var brailleImeAction: BrailleImeAction? = null
    private var assignedGesture: Gesture? = null
    private var handler: Handler? = null
    private var orientation: Int = 0

    companion object {
      private const val TAG = "BrailleImeGestureCommandFragment"
      private val CUSTOM_GESTURE_TIMEOUT: Duration = Duration.ofSeconds(30)
      private val UNASSIGNED_GESTURE = UnassignedGesture()
      private val PERFORM_GESTURE_REPLAY_INTERVAL: Duration = Duration.ofSeconds(15)
      private const val MSG_GESTURE_TIMEOUT = 1
      private const val MSG_PERFORM_GESTURE_REPLAY = 2
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
      preferenceManager.sharedPreferencesName = BrailleUserPreferences.BRAILLE_SHARED_PREFS_FILENAME
      val category =
          activity!!.intent.getSerializableExtra(BrailleImeGestureActivity.CATEGORY) as Category
      PreferenceSettingsUtils.addPreferencesFromResource(this, R.xml.brailleime_gesture_commands)
      activity!!.title = category.getTitle(resources)

      val description = category.getDescription(resources)
      val descriptionPreference =
          findPreference<Preference>(getString(R.string.pref_key_brailleime_action_category_description))!!
      if (TextUtils.isEmpty(description)) {
        preferenceScreen!!.removePreference(descriptionPreference)
      } else {
        descriptionPreference.summary = description
      }

      for (subCategory in category.getSubCategories()) {
        if (category == TEXT_SELECTION_AND_EDITING && subCategory == LINE) {
          // TODO: As the text selection for line granularity movement does not work,
          // we mask off the preference of selecting text by line.
          continue
        }
        val subCategoryTitle = subCategory.getName(resources)
        var preferenceCategory: PreferenceCategory? = null
        if (!TextUtils.isEmpty(subCategoryTitle)) {
          preferenceCategory = PreferenceCategory(context!!)
          preferenceCategory.setTitle(subCategory.getName(resources))
          preferenceScreen!!.addPreference(preferenceCategory)
        }
        val filteredCommands: ImmutableList<SupportedCommand> =
            SupportedCommand.getSupportedCommands(context!!).stream()
                .filter { supportedCommand ->
                  supportedCommand.getCategory() == category &&
                      supportedCommand.getSubCategory() == subCategory
                }
                .collect(toImmutableList())
        for (command in filteredCommands) {
          val preference = Preference(context!!)
          preference.title = command.getActionDescription(context!!)
          preference.summary = command.getGestureDescription(context!!)
          // setEnabled is to gray out preference. In older design, it's not gray out.
          preference.isEnabled =
              !FeatureFlagReader.useGestureCustomization(context!!) || command.isEditable()
          val action = command.getBrailleImeAction()
          preference.key = action.name
          preference.onPreferenceClickListener =
              Preference.OnPreferenceClickListener {
                brailleImeAction = action
                customGesturedialog!!.setAction(action)
                customGesturedialog!!.show()
                true
              }
          if (preferenceCategory != null) {
            preferenceCategory.addPreference(preference)
          } else {
            preferenceScreen!!.addPreference(preference)
          }
        }
        handler =
            Handler(
                Handler.Callback { msg ->
                  when (msg.what) {
                    MSG_GESTURE_TIMEOUT -> {
                      showNoGesturePerformedHint()
                      true
                    }
                    MSG_PERFORM_GESTURE_REPLAY -> {
                      BrailleCommonTalkBackSpeaker.getInstance().speak(getPerformGestureHint())
                      true
                    }
                    else -> false
                  }
                })
        orientation = resources.configuration.orientation
        keyboardView = AccessibilityOverlayKeyboardView(context!!, keyboardViewCallback)
        customGesturedialog = CustomGestureDialog(context!!, customGestureDialogCallback)
        changeGestureDialog = ChangeGestureDialog(context!!, changeGestureDialogCallback)
        customGestureTalkBackOffDialog =
            CustomGestureTalkBackOffDialog(context!!, talkbackOffDialogCallback)
        customGestureTooFewTouchPointsDialog = CustomGestureTooFewTouchPointsDialog(context!!)
        resetToDefaultDialog = ResetToDefaultDialog(context!!, resetToDefaultDialogCallback)
        mirroredGestureDialog = MirroredGestureDialog(context!!, mirroredGestureDialogCallback)
      }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
      super.onConfigurationChanged(newConfig)
      if (orientation != newConfig.orientation) {
        orientation = newConfig.orientation
        keyboardView!!.onOrientationChanged(newConfig.orientation)
      }
    }

    override fun onStop() {
      super.onStop()
      BrailleImeLog.d(TAG, "onStop")
      changeGestureDialog!!.dismiss()
      customGesturedialog!!.dismiss()
      customGestureTalkBackOffDialog!!.dismiss()
      customGestureTooFewTouchPointsDialog!!.dismiss()
      resetToDefaultDialog!!.dismiss()
      assignedGesture = null
      keyboardView!!.tearDown()
      handler!!.removeCallbacksAndMessages(null)
    }

    private fun addInputView() {
      if (keyboardView!!.isViewContainerCreated()) {
        return
      }
      keyboardView!!.setWindowManager(talkBackForBrailleIme!!.getWindowManager())
      keyboardView!!.createViewContainer()
      keyboardView!!.createAndAddCustomGestureView(customGestureCallback)
      showPerformGestureHint()
      handler!!.sendEmptyMessageDelayed(MSG_GESTURE_TIMEOUT, CUSTOM_GESTURE_TIMEOUT.toMillis())
      handler!!.sendEmptyMessageDelayed(
          MSG_PERFORM_GESTURE_REPLAY, PERFORM_GESTURE_REPLAY_INTERVAL.toMillis())
    }

    private fun setUpGesture(gesture: Gesture) {
      if (assignedGesture == null) {
        if (isEditable(gesture)) {
          assignedGesture = gesture
        }
        showGestureResultHint(gesture)
      } else if (assignedGesture!!.equals(gesture)) {
        assignedGesture = null
        keyboardView!!.tearDown()
        removeGestureAndUpdatePreferenceSummary(gesture)
        writeGestureAction(brailleImeAction!!, ImmutableList.of(gesture.getId()))
        updatePreferenceSummary(brailleImeAction!!)
        updateMirroredGesture(gesture)
      } else {
        assignedGesture = null
        showGestureNotMatchHint()
      }
    }

    private fun writeGestureAction(action: BrailleImeAction, gestures: List<String>) {
      BrailleUserPreferences.writeGestureAction(context!!, Pair(action, gestures))
      val sameActions = BrailleImeGestureAction.getActionWithSameRoot(action)
      for (sameAction in sameActions) {
        BrailleUserPreferences.writeGestureAction(context!!, Pair(sameAction, gestures))
      }
    }

    private fun resetGestureAction(action: BrailleImeAction) {
      BrailleUserPreferences.resetGestureAction(context!!, action)
      val sameActions = BrailleImeGestureAction.getActionWithSameRoot(action)
      for (sameAction in sameActions) {
        BrailleUserPreferences.resetGestureAction(context!!, sameAction)
      }
    }

    private fun removeGestureAndUpdatePreferenceSummary(gesture: Gesture) {
      val otherActions = getOtherAction(gesture)
      if (otherActions.isEmpty()) {
        return
      }
      for (oldAction in otherActions) {
        val gestures =
            BrailleImeGestureAction.getGesture(context!!, oldAction) as MutableList<Gesture>
        gestures.remove(gesture)
        writeGestureAction(
            oldAction,
            if (gestures.isEmpty())
                ImmutableList.of(UNASSIGNED_GESTURE.getId())
            else gestures.stream().map(Gesture::getId).collect(toImmutableList()))
        updatePreferenceSummary(oldAction)
      }
    }

    private fun showGestureInvalidHint() {
      val title = getString(R.string.custom_gesture_cannot_used)
      val hint =
          getString(R.string.custom_gesture_try_another, getString(R.string.custom_gesture_cancel))
      val hintText = SpannableStringBuilder().append(title).append("\n\n").append(hint)
      keyboardView!!.getCustomGestureView()!!.showHint(hintText, title, hint, /* warning= */ true)
      BrailleCommonTalkBackSpeaker.getInstance().speak(hintText)
    }

    private fun showPerformGestureHint() {
      val title =
          getString(
              R.string.custom_gesture_perform,
              getString(
                  R.string.custom_gesture_action,
                  brailleImeAction!!.getDescriptionRes(resources)))
      val hint = getString(R.string.custom_gesture_cancel)
      val hintText = SpannableStringBuilder().append(title).append("\n\n").append(hint)
      keyboardView!!.getCustomGestureView()!!.showHint(hintText, title, hint, /* warning= */ false)
      BrailleCommonTalkBackSpeaker.getInstance()
          .speak(
              getString(
                  R.string.custom_gesture_hint_announcement,
                  getLayoutMode(),
                  getPerformGestureHint()))
    }

    private fun showNoGesturePerformedHint() {
      val title = getString(R.string.custom_gesture_no_gesture)
      val hint =
          getString(R.string.custom_gesture_try_another, getString(R.string.custom_gesture_cancel))
      val hintText = SpannableStringBuilder().append(title).append("\n\n").append(hint)
      keyboardView!!.getCustomGestureView()!!.showHint(hintText, title, hint, /* warning= */ true)
      BrailleCommonTalkBackSpeaker.getInstance().speak(hintText)
    }

    private fun showGestureNotMatchHint() {
      val title = getString(R.string.custom_gesture_no_match)
      val hint =
          getString(
              R.string.custom_gesture_perform_again, getString(R.string.custom_gesture_cancel))
      val hintText = SpannableStringBuilder().append(title).append("\n\n").append(hint)
      keyboardView!!.getCustomGestureView()!!.showHint(hintText, title, hint, /* warning= */ true)
      BrailleCommonTalkBackSpeaker.getInstance().speak(hintText)
    }

    private fun showGestureResultHint(gesture: Gesture) {
      val title = gesture.getDescription(resources)
      var hint = getString(R.string.custom_gesture_confirm)
      val otherAction = getOtherAction(gesture)
      if (!otherAction.isEmpty()) {
        hint =
            getString(
                if (isEditable(gesture)) R.string.custom_gesture_conflict
                else R.string.custom_gesture_conflict_try_another,
                otherAction[0].getDescriptionRes(resources),
                getString(R.string.custom_gesture_cancel))
      }
      val hintText = SpannableStringBuilder().append(title).append("\n\n").append(hint)
      keyboardView!!.getCustomGestureView()!!.showHint(hintText, title, hint, /* warning= */ false)
      BrailleCommonTalkBackSpeaker.getInstance().speak(hintText)
    }

    private fun getPerformGestureHint(): String {
      val performHint =
          getString(
              R.string.custom_gesture_perform, brailleImeAction!!.getDescriptionRes(resources))
      val cancelHint = getString(R.string.custom_gesture_cancel)
      return getString(R.string.custom_gesture_hint_announcement_repeat, performHint, cancelHint)
    }

    private fun updateMirroredGesture(gesture: Gesture) {
      if (gesture.equals(gesture.mirrorDots())) {
        showSnackBar(R.string.braille_keyboard_snackbar_change, Snackbar.LENGTH_LONG)
      } else {
        mirroredGestureDialog!!.setGestureAction(brailleImeAction!!, gesture)
        mirroredGestureDialog!!.show()
      }
    }

    private fun getOtherAction(gesture: Gesture?): ImmutableList<BrailleImeAction> {
      val brailleImeActionList = BrailleImeGestureAction.getAction(context!!, gesture)
      return brailleImeActionList.stream()
          .filter { action -> !action.equals(brailleImeAction) }
          .collect(toImmutableList())
    }

    private fun closeKeyboard(swipe: Swipe): Boolean {
      if (!BrailleImeGestureAction.getGesture(context!!, BrailleImeAction.HIDE_KEYBOARD).contains(swipe)) {
        return false
      }
      keyboardView!!.tearDown()
      handler!!.removeCallbacksAndMessages(null)
      showSnackBar(R.string.braille_keyboard_snackbar_cancel, Snackbar.LENGTH_SHORT)
      customGesturedialog!!.show()
      return true
    }

    private fun updatePreferenceSummary(action: BrailleImeAction) {
      val supportedCommand: Optional<SupportedCommand> =
          SupportedCommand.getSupportedCommands(context!!).stream()
              .filter { command -> command.getBrailleImeAction().equals(action) }
              .findAny()
      supportedCommand.ifPresent { command ->
        val preference = findPreference<Preference>(action.name)
        if (preference != null) {
          preference.summary = command.getGestureDescription(context!!)
        }
      }
    }

    private fun isEditable(gesture: Gesture): Boolean {
      // Gestures might not be listed in supported commands, so those not in uneditable are
      // editable.
      return SupportedCommand.getSupportedCommands(context!!).stream()
          .noneMatch { command ->
            !command.isEditable() &&
                command.getBrailleImeGesture(context!!).contains(gesture)
          }
    }

    private fun getLayoutMode(): String {
      return getString(
          if (keyboardView!!.getCustomGestureView()!!.isCurrentTableTopMode())
              R.string.tabletop
          else R.string.screen_away)
    }

    private val customGestureCallback =
        object : CustomGestureCallback {
          override fun onInvalidGesture(): Boolean {
            BrailleImeLog.v(TAG, "onInvalidGesture")
            talkBackForBrailleIme!!.interruptSpeak()
            if (assignedGesture != null) {
              showGestureNotMatchHint()
              return true
            }
            showGestureInvalidHint()
            return true
          }

          override fun onSwipeProduced(swipe: Swipe): Boolean {
            BrailleImeLog.v(TAG, "onSwipeProduced")
            talkBackForBrailleIme!!.interruptSpeak()
            handler!!.removeCallbacksAndMessages(null)
            if (closeKeyboard(swipe)) {
              return true
            }
            setUpGesture(swipe)
            return true
          }

          override fun onDotHoldAndDotSwipe(dotHoldSwipe: DotHoldSwipe): Boolean {
            BrailleImeLog.v(TAG, "onDotHoldAndDotSwipe")
            talkBackForBrailleIme!!.interruptSpeak()
            handler!!.removeCallbacksAndMessages(null)
            setUpGesture(dotHoldSwipe)
            return true
          }

          override fun onHoldProduced(pointersHeldCount: Int): Boolean {
            BrailleImeLog.v(TAG, "onHoldProduced")
            return BrailleImeGestureController(
                context!!, /* brailleImeActor= */ null)
                .performDotHoldAction(pointersHeldCount)
          }

          override fun onDetectionChanged(isTabletop: Boolean) {
            talkBackForBrailleIme!!.interruptSpeak()
            keyboardView!!.setTableMode(isTabletop)
            BrailleCommonTalkBackSpeaker.getInstance().speak(getLayoutMode())
          }
        }

    private val keyboardViewCallback =
        object : KeyboardViewCallback {
          override fun onViewReady() {
            BrailleImeLog.d(TAG, "onViewReady")
            if (talkBackForBrailleIme!!.isVibrationFeedbackEnabled()) {
              BrailleImeVibrator.getInstance(context!!).enable()
            }
            talkBackForBrailleIme!!.onBrailleImeActivated(
                /* disableEbt= */ true,
                Utils.useImeSuppliedInputWindow(),
                // Region might be null for short time before onTalkBackResumed() is called.
                keyboardView!!.obtainImeViewRegion().orElse(null))
          }

          override fun onViewUpdated() {
            BrailleImeLog.d(TAG, "onViewUpdated")
          }

          override fun onViewCleared() {
            BrailleImeLog.d(TAG, "onViewCleared")
            BrailleImeVibrator.getInstance(context!!).disable()
            if (talkBackForBrailleIme != null) {
              talkBackForBrailleIme!!.onBrailleImeInactivated(Utils.useImeSuppliedInputWindow())
            }
          }

          override fun isHideScreenMode(): Boolean {
            return talkBackForBrailleIme != null && talkBackForBrailleIme!!.isHideScreenMode()
          }
        }

    private val customGestureDialogCallback =
        object : CustomGestureDialog.ButtonClickListener {
          override fun onChangeGesture() {
            if (talkBackForBrailleIme == null ||
                talkBackForBrailleIme!!.getServiceStatus() == ServiceStatus.OFF) {
              customGestureTalkBackOffDialog!!.show()
            } else if (!BrailleUserPreferences.readChangeGestureIntroNeverShow(context!!)) {
              changeGestureDialog!!.show()
            } else if (!Utils.isMultiTouchSupported(context!!)) {
              customGestureTooFewTouchPointsDialog!!.show()
            } else {
              addInputView()
            }
          }

          override fun onResetToDefault() {
            resetToDefaultDialog!!.setAction(brailleImeAction!!)
            resetToDefaultDialog!!.show()
          }

          override fun onRemove() {
            writeGestureAction(brailleImeAction!!, ImmutableList.of(UNASSIGNED_GESTURE.getId()))
            updatePreferenceSummary(brailleImeAction!!)
            showSnackBar(R.string.braille_keyboard_snackbar_remove, Snackbar.LENGTH_LONG)
          }
        }

    private val changeGestureDialogCallback =
        object : ChangeGestureDialog.ButtonClickListener {
          override fun onContinue() {
            addInputView()
          }

          override fun onNeverShown() {
            BrailleUserPreferences.writeChangeGestureIntroNeverShow(context!!)
          }
        }

    private val talkbackOffDialogCallback =
        object : CustomGestureTalkBackOffDialog.ButtonClickListener {
          override fun onLaunchSettings() {
            // Highlight TalkBack item in Accessibility Settings upon arriving there (Pixel only).
            Utils.highlightTalkBackSettings(context!!)
            // Collapse notification panel (quick settings).
            Utils.collapseNotificationPanel(context!!)
          }
        }

    private val resetToDefaultDialogCallback =
        object : ResetToDefaultDialog.ButtonClickListener {
          override fun onReset() {
            val defaultGestures: List<Gesture> =
                BrailleImeGestureAction.getDefaultGesture(brailleImeAction!!)
            for (gesture in defaultGestures) {
              if (gesture.equals(UNASSIGNED_GESTURE)) {
                continue
              }
              removeGestureAndUpdatePreferenceSummary(gesture)
            }
            resetGestureAction(brailleImeAction!!)
            updatePreferenceSummary(brailleImeAction!!)
            showSnackBar(R.string.reset_to_default_dialog_snackbar, Snackbar.LENGTH_LONG)
          }
        }

    private val mirroredGestureDialogCallback =
        object : ButtonClickListener {
          override fun onMirrored(gesture: Gesture?) {
            val action = getOtherAction(gesture!!.mirrorDots())
            if (!action.isEmpty()) {
              // Mirrored is being used.
              writeGestureAction(action[0], ImmutableList.of(UNASSIGNED_GESTURE.getId()))
              updatePreferenceSummary(action[0])
            }
            writeGestureAction(
                brailleImeAction!!,
                ImmutableList.of(gesture.getId(), gesture.mirrorDots()!!.getId()))
            updatePreferenceSummary(brailleImeAction!!)
            showSnackBar(
                R.string.braille_keyboard_snackbar_change_mirrored_gesture, Snackbar.LENGTH_LONG)
          }
        }

    private fun showSnackBar(@StringRes resId: Int, duration: Int) {
      val themedContext = context!!
      Snackbar.make(themedContext, view!!, themedContext.getString(resId), duration).show()
    }

    @VisibleForTesting
    fun testing_getKeyboardView(): KeyboardView = keyboardView!!
  }
}
