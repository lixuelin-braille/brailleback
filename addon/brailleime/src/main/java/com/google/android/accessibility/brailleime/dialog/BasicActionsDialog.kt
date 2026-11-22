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

import com.google.android.accessibility.brailleime.SupportedCommand.Category.BASIC

import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.text.SpannableString
import android.text.Spanned
import android.text.style.BulletSpan
import android.widget.ArrayAdapter
import android.widget.ListView
import androidx.annotation.ColorInt
import androidx.core.content.res.ResourcesCompat
import com.google.android.accessibility.brailleime.R
import com.google.android.accessibility.brailleime.SupportedCommand
import com.google.android.accessibility.brailleime.settings.BrailleImeGestureActivity
import com.google.android.accessibility.utils.BuildVersionUtils
import com.google.android.accessibility.utils.material.A11yAlertDialogWrapper
import com.google.android.material.color.MaterialColors

/** A dialog controller that presents basic actions for braille keyboard. */
class BasicActionsDialog(
  context: Context,
  private val callback: BasicActionsCallback,
) : ViewAttachedDialog() {
  /** Callback for basic gestures dialog. */
  interface BasicActionsCallback {
    fun onClickMoreGestures()
  }

  private val context: Context = context

  override fun makeDialog(): Dialog {
    // To sync with dialog in the Settings, the dialog will use v7 AlertDialog (Target to change to
    // material dialog on T). The other situation, the dialog will use material dialog.
    val descriptions: List<CharSequence> =
        SupportedCommand.getSupportedCommands(context)
            .filter { supportedCommand -> supportedCommand.getCategory() == BASIC }
            .map { command ->
              val spannableString =
                  SpannableString.valueOf(
                      context.getString(
                          R.string.review_gesture_format,
                          command.getActionDescription(context),
                          command.getGestureDescription(context)))
              setBulletSpan(context, spannableString)
              spannableString
            }
    val list: Array<CharSequence> = descriptions.toTypedArray()
    val dialogContext = Dialogs.getDialogContext(context)
    val arrayAdapter =
        object : ArrayAdapter<CharSequence>(dialogContext, android.R.layout.simple_list_item_1, list) {
          override fun areAllItemsEnabled(): Boolean {
            return false
          }

          override fun isEnabled(position: Int): Boolean {
            return false
          }
        }
    val listView = ListView(context)
    listView.divider = null
    listView.adapter = arrayAdapter
    val builder: A11yAlertDialogWrapper.Builder =
        A11yAlertDialogWrapper.materialDialogBuilder(dialogContext)
            .setTitle(context.getString(R.string.braille_keyboard_basic_controls))
            .setView(listView)
            .setNegativeButton(
                context.getString(R.string.review_gestures_review_all_button),
                { _, _ ->
                  val intent = Intent(context, BrailleImeGestureActivity::class.java)
                  intent.addFlags(
                      Intent.FLAG_ACTIVITY_NEW_TASK
                          or Intent.FLAG_ACTIVITY_CLEAR_TOP
                          or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                  context.startActivity(intent)
                  callback.onClickMoreGestures()
                })
            .setPositiveButton(
                context.getString(R.string.review_gestures_close_button), /* listener= */ null)
    return builder.create().getDialog()
  }

  companion object {
    @JvmStatic
    private fun setBulletSpan(context: Context, spannableString: SpannableString) {
      @ColorInt
      val textColor =
          MaterialColors.getColor(
              context, android.R.attr.textColorPrimary, "Unsupported color palette")
      if (BuildVersionUtils.isAtLeastP()) {
        spannableString.setSpan(
            BulletSpan(
                ResourcesCompat.getFloat(context.resources, R.dimen.gap_width).toInt(),
                textColor,
                ResourcesCompat.getFloat(context.resources, R.dimen.bullet_radius).toInt()),
            /* start= */ 0,
            /* end= */ 1,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
      } else {
        spannableString.setSpan(
            BulletSpan(
                ResourcesCompat.getFloat(context.resources, R.dimen.gap_width).toInt(), textColor),
            /* start= */ 0,
            /* end= */ 1,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
      }
    }
  }
}
