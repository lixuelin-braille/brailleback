/*
 * Copyright 2020 Google Inc.
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

package com.google.android.accessibility.brailleime.dialog

import android.app.Dialog
import android.app.KeyguardManager
import android.content.Context
import androidx.appcompat.app.AlertDialog
import android.text.TextUtils
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.AdapterView.OnItemClickListener
import android.widget.ArrayAdapter
import android.widget.TextView
import androidx.annotation.StringRes
import com.google.android.accessibility.braille.common.BrailleUserPreferences
import com.google.android.accessibility.brailleime.BrailleIme
import com.google.android.accessibility.brailleime.R
import com.google.android.accessibility.brailleime.Utils
import com.google.android.accessibility.brailleime.analytics.BrailleImeAnalytics
import com.google.android.accessibility.brailleime.analytics.BrailleImeAnalytics.ContextMenuSelections
import com.google.android.accessibility.utils.material.MaterialComponentUtils
import com.google.common.collect.ImmutableList

/** A menu that has settings items for Braille keyboard. */
class ContextMenuDialog(
  context: Context,
  private val callback: Callback,
) : ViewAttachedDialog() {

  /** A callback to communicate with {@link BrailleIme}. */
  interface Callback {

    fun onDialogHidden()

    fun onDialogShown()

    fun onLaunchSettings()

    fun onTutorialOpen()

    fun onTutorialClosed()

    fun onCalibration()
  }

  private var contextMenuDialog: AlertDialog? = null
  private var contextMenuListAdapter: ContextMenuListAdapter? = null
  private val basicActionsDialog: BasicActionsDialog
  private val context: Context = context
  private val itemsAndActions: MutableList<MenuItem>
  private var tutorialMode: Boolean = false

  init {
    itemsAndActions = mutableListOf()
    basicActionsDialog =
        BasicActionsDialog(
            context,
            object : BasicActionsDialog.BasicActionsCallback {
              override fun onClickMoreGestures() {
                dismiss()
              }
            })
  }

  override fun dismiss() {
    dismissContextMenuDialog()
    dismissBasicActionsDialog()
  }

  override fun makeDialog(): Dialog {
    updateItemsAndActionsList()
    val dialogContext = Dialogs.getDialogContext(context)
    val dialogBuilder: AlertDialog.Builder = MaterialComponentUtils.alertDialogBuilder(dialogContext)
    contextMenuListAdapter = ContextMenuListAdapter(dialogContext, itemsAndActions)
    dialogBuilder
        .setTitle(context.getString(R.string.context_menu_title))
        .setAdapter(contextMenuListAdapter, null)
        // TODO: put onDialogHidden in onDismissListener.
        .setNegativeButton(
            R.string.context_menu_close_button, { _, _ -> callback.onDialogHidden() })
        .setOnCancelListener { _ -> callback.onDialogHidden() }
    contextMenuDialog = dialogBuilder.create()
    contextMenuDialog!!.setOnShowListener { _ -> callback.onDialogShown() }
    contextMenuDialog!!.setCanceledOnTouchOutside(false)
    contextMenuDialog!!.listView.setOnItemClickListener(itemClickListener)
    return contextMenuDialog!!
  }

  private fun showBasicActionsDialog() {
    basicActionsDialog.show(viewToAttach)
  }

  private fun dismissContextMenuDialog() {
    if (contextMenuDialog != null) {
      contextMenuDialog!!.dismiss()
    }
  }

  private fun dismissBasicActionsDialog() {
    basicActionsDialog.dismiss()
  }

  private fun updateItemsAndActionsList() {
    itemsAndActions.clear()
    for (strRes in ITEM_STRING_IDS) {
      if (strRes == R.string.context_menu_switch_contracted_status_selection) {
        if (!BrailleUserPreferences.readCurrentActiveInputCodeAndCorrect(context)
                .isSupportsContracted(context)) {
          continue
        }
        val nextContractedStatusStrRes: Int =
            if (BrailleUserPreferences.readContractedMode(context)) R.string.uncontracted
            else R.string.contracted
        itemsAndActions.add(
            MenuItem(
                context.getString(strRes, context.getString(nextContractedStatusStrRes)),
                /* closeWhenClick= */ false,
                generateItemAction(strRes)))
      } else if (strRes == R.string.context_menu_tutorial_selection) {
        if (!tutorialMode) {
          itemsAndActions.add(
              MenuItem(
                  context.getString(strRes),
                  /* closeWhenClick= */ true,
                  generateItemAction(strRes)))
        }
      } else if (strRes == R.string.context_menu_tutorial_finish) {
        if (tutorialMode) {
          itemsAndActions.add(
              MenuItem(
                  context.getString(strRes),
                  /* closeWhenClick= */ true,
                  generateItemAction(strRes)))
        }
      } else if (strRes == R.string.context_menu_input_language_selection) {
        if (BrailleUserPreferences.readAvailablePreferredCodes(context).size <= 1) {
          continue
        }
        val currentCode =
            BrailleUserPreferences.readCurrentActiveInputCodeAndCorrect(context)
                .getUserFacingName(context)
        val nextCode =
            BrailleUserPreferences.getNextInputCode(context).getUserFacingName(context)
        itemsAndActions.add(
            MenuItem(
                context.getString(strRes, nextCode),
                context.getString(
                    R.string.context_menu_input_language_selection_summary, currentCode),
                /* closeWhenClick= */ false,
                generateItemAction(strRes)))
      } else if (strRes == R.string.context_menu_settings_selection) {
        // Removes settings if phone is locked.
        val keyguardManager =
            context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        if (!keyguardManager.isKeyguardLocked) {
          itemsAndActions.add(
              MenuItem(
                  context.getString(strRes),
                  /* closeWhenClick= */ true,
                  generateItemAction(strRes)))
        }
      } else if (strRes == R.string.context_menu_layout_calibration) {
        if (!tutorialMode) {
          itemsAndActions.add(
              MenuItem(
                  context.getString(strRes),
                  /* closeWhenClick= */ true,
                  generateItemAction(strRes)))
        }
      } else {
        itemsAndActions.add(
            MenuItem(
                context.getString(strRes),
                /* closeWhenClick= */ false,
                generateItemAction(strRes)))
      }
    }
  }

  /** The context menu items varies base on the tutorial mode. */
  fun setTutorialMode(tutorialMode: Boolean) {
    this.tutorialMode = tutorialMode
  }

  /** Generates the Runnable for specific selections click actions. */
  private fun generateItemAction(@StringRes strRes: Int): Runnable {
    if (strRes == R.string.context_menu_input_language_selection) {
      return Runnable {
        BrailleUserPreferences.writeCurrentActiveInputCode(
            context, BrailleUserPreferences.getNextInputCode(context))
      }
    } else if (strRes == R.string.context_menu_switch_contracted_status_selection) {
      return Runnable {
        BrailleUserPreferences.writeContractedMode(
            context, !BrailleUserPreferences.readContractedMode(context))
      }
    } else if (strRes == R.string.context_menu_review_gestures_selection) {
      return Runnable { showBasicActionsDialog() }
    } else if (strRes == R.string.context_menu_tutorial_selection) {
      return Runnable { callback.onTutorialOpen() }
    } else if (strRes == R.string.context_menu_tutorial_finish) {
      return Runnable { callback.onTutorialClosed() }
    } else if (strRes == R.string.context_menu_settings_selection) {
      return Runnable { callback.onLaunchSettings() }
    } else if (strRes == R.string.context_menu_layout_calibration) {
      return Runnable { callback.onCalibration() }
    }
    return Runnable {}
  }

  private val itemClickListener =
      object : OnItemClickListener {
        override fun onItemClick(
            adapterView: AdapterView<*>, view: View?, i: Int, l: Long
        ) {
          itemsAndActions[i].action.run()
          // Logging option usage count.
          BrailleImeAnalytics.getInstance(context)
              .logContextMenuOptionCount(convertContextMenuSelections(i))
          if (itemsAndActions[i].closeWhenClick) {
            contextMenuDialog!!.dismiss()
          } else {
            updateItemsAndActionsList()
            contextMenuListAdapter!!.notifyDataSetChanged()
          }
        }

        private fun convertContextMenuSelections(optionPosition: Int): ContextMenuSelections {
          val stringRes = ContextMenuDialog.ITEM_STRING_IDS.get(optionPosition)
          if (stringRes == R.string.context_menu_input_language_selection) {
            return ContextMenuSelections.TYPING_LANGUAGE
          } else if (stringRes == R.string.context_menu_switch_contracted_status_selection) {
            return ContextMenuSelections.SWITCH_CONTRACTED_STATUS
          } else if (stringRes == R.string.context_menu_review_gestures_selection) {
            return ContextMenuSelections.SEE_ALL_ACTIONS
          } else if (stringRes == R.string.context_menu_tutorial_selection) {
            return ContextMenuSelections.TUTORIAL_OPEN
          } else if (stringRes == R.string.context_menu_tutorial_finish) {
            return ContextMenuSelections.TUTORIAL_FINISH
          } else if (stringRes == R.string.context_menu_settings_selection) {
            return ContextMenuSelections.GO_TO_SETTINGS
          } else if (stringRes == R.string.context_menu_layout_calibration) {
            return ContextMenuSelections.CALIBRATION
          }
          return ContextMenuSelections.UNSPECIFIED_OPTION
        }
      }

  private class ContextMenuListAdapter(
    context: Context,
    private val menuItemList: List<MenuItem>,
  ) : ArrayAdapter<MenuItem>(context, android.R.layout.simple_list_item_1, menuItemList) {
    private val context: Context = context

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
      val view = super.getView(position, convertView, parent)
      val textView: TextView = view.findViewById(android.R.id.text1)
      textView.setTextSize(
          TypedValue.COMPLEX_UNIT_PX,
          context.resources.getDimensionPixelSize(R.dimen.context_menu_item_text_size).toFloat())
      val paddingHorizontalInPixels =
          context.resources.getDimensionPixelOffset(R.dimen.context_menu_item_padding_horizontal)
      textView.setPadding(paddingHorizontalInPixels, 0, paddingHorizontalInPixels, 0)
      textView.setText(
          ContextMenuDialog.convertItemContentToStringArray(context, menuItemList)[position])
      return view
    }
  }

  /** Context menu item. */
  class MenuItem(
    // Menu item title.
    val itemTitle: CharSequence,
    // Menu item summary.
    val itemSummary: CharSequence,
    // Menu should close after click or not.
    val closeWhenClick: Boolean,
    // Menu item click action.
    val action: Runnable,
  ) {
    constructor(
        itemTitle: CharSequence, closeWhenClick: Boolean, action: Runnable
    ) : this(itemTitle, "", closeWhenClick, action)
  }

  companion object {
    // LINT.IfChange(menu_options)
    @JvmField
    val ITEM_STRING_IDS: ImmutableList<Int> =
        ImmutableList.of(
            R.string.context_menu_input_language_selection,
            R.string.context_menu_switch_contracted_status_selection,
            R.string.context_menu_layout_calibration,
            R.string.context_menu_review_gestures_selection,
            R.string.context_menu_tutorial_selection,
            R.string.context_menu_tutorial_finish,
            R.string.context_menu_settings_selection)
    // LINT.ThenChange(//depot/google3/logs/proto/wireless/android/aas/brailleime/brailleime_log.proto:context_menu_option)

    @JvmStatic
    private fun convertItemContentToStringArray(
      context: Context, menuItems: List<MenuItem>
    ): Array<CharSequence> {
      return menuItems
          .map { menuItem ->
            if (!TextUtils.isEmpty(menuItem.itemSummary)) {
              // Return title concatenated with grey summary string.
              Utils.appendWithColoredString(
                  menuItem.itemTitle,
                  menuItem.itemSummary,
                  context.getColor(R.color.dialog_secondary_text))
            } else {
              menuItem.itemTitle
            }
          }
          .toTypedArray()
    }
  }
}
