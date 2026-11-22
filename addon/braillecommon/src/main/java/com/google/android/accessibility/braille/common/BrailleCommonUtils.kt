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

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.TextUtils
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import com.google.android.accessibility.braille.interfaces.SelectionRange
import com.google.android.accessibility.utils.AccessibilityServiceCompatUtils
import com.google.android.accessibility.utils.KeyboardUtils
import com.google.errorprone.annotations.CanIgnoreReturnValue
import java.util.Objects

/** Provides the utilities for common braille usages. */
class BrailleCommonUtils private constructor() {

  companion object {
    /**
     * Returns the number of characters that a backward word-deletion should remove from the given
     * {@code charSequence}.
     *
     * <p>In the following examples, {@code a} means any non whitespace character and {@code N} means
     * newline):
     *
     * <ul>
     *   <li>"" -> 0
     *   <li>"a" -> 1
     *   <li>"aa" -> 2
     *   <li>"aaa" -> 3
     *   <li>" " -> 1
     *   <li>" " -> 2
     *   <li>"a " -> 2
     *   <li>"a a" -> 1
     *   <li>"a a " -> 2
     *   <li>"N" -> 1
     *   <li>"NN" -> 1
     *   <li>"NNN" -> 1
     *   <li>"N " -> 1
     * </ul>
     *
     * @param charSequence the CharSequence on which to operate
     * @return the number of characters that should be deleted
     */
    @JvmStatic
    fun getLastWordLengthForDeletion(charSequence: CharSequence): Int {
      // Returns 0 if charSequence is empty or null.
      if (TextUtils.isEmpty(charSequence)) {
        return 0
      }

      val length = charSequence.length
      val lastIndexOfWhitespace = lastIndexOfWhitespace(charSequence)
      // Returns charSequence.length if there is no space inside.
      if (lastIndexOfWhitespace < 0) {
        return length
      }

      if (lastIndexOfWhitespace != length - 1) {
        // Hunk ends with a non-whitespace. Delete up to and excluding the greatest whitespace.
        return length - lastIndexOfWhitespace - 1
      }

      val terminalCharacter = charSequence[length - 1]
      if (isNewline(terminalCharacter)) {
        // Hunk ends with newline.
        return 1
      }

      val trailingWhitespaceLength = trailingWhitespaceCount(charSequence)
      if (length <= 1 || trailingWhitespaceLength != 1 || terminalCharacter != ' ') {
        return trailingWhitespaceNonNewlineCount(charSequence)
      }

      val cTrimmed = charSequence.subSequence(0, length - trailingWhitespaceLength)
      val lastIndexOfWhitespace2 = lastIndexOfWhitespace(cTrimmed)
      if (lastIndexOfWhitespace2 < 0) {
        return length
      }
      return length - lastIndexOfWhitespace2 - 1
    }

    private fun isNewline(c: Char): Boolean {
      return (c == '\r' || c == '\n')
    }

    /** Returns the number of consecutive whitespace characters at the end of {@code charSequence}. */
    @JvmStatic
    fun trailingWhitespaceCount(charSequence: CharSequence): Int {
      var whitespaceCount = 0
      var i = charSequence.length - 1
      while (i >= 0 && Character.isWhitespace(charSequence[i])) {
        whitespaceCount++
        i--
      }
      return whitespaceCount
    }

    /**
     * Returns the number of consecutive, non-newline whitespace characters at the end of {@param
     * charSequence}.
     */
    @JvmStatic
    fun trailingWhitespaceNonNewlineCount(charSequence: CharSequence): Int {
      var whitespaceCount = 0
      var i = charSequence.length - 1
      while (
        i >= 0 &&
          Character.isWhitespace(charSequence[i]) &&
          !isNewline(charSequence[i])
      ) {
        whitespaceCount++
        i--
      }
      return whitespaceCount
    }

    /**
     * Finds the last index of whitespace in {@code charSequence}, returning -1 if it contains no
     * whitespace.
     */
    @JvmStatic
    fun lastIndexOfWhitespace(charSequence: CharSequence): Int {
      var i: Int
      i = charSequence.length - 1
      while (i >= 0 && !Character.isWhitespace(charSequence[i])) {
        i--
      }
      return i
    }

    /** Returns {@code true} if {@code editorInfo}'s input type is text. */
    @JvmStatic
    fun isTextField(editorInfo: EditorInfo): Boolean {
      val inputTypeClass = getInputTypeClass(editorInfo.inputType)
      // All type classes not among {number, phone, datetime} are considered text.
      return !(inputTypeClass == InputType.TYPE_CLASS_NUMBER ||
          inputTypeClass == InputType.TYPE_CLASS_PHONE ||
          inputTypeClass == InputType.TYPE_CLASS_DATETIME)
    }

    /** Returns {@code true} if {@code editorInfo}'s input type is password. */
    @JvmStatic
    fun isPasswordField(editorInfo: EditorInfo?): Boolean {
      return editorInfo != null &&
          (isNumberPasswordField(editorInfo.inputType) ||
              isTextPasswordField(editorInfo.inputType))
    }

    /** Returns the start and the end of selected text. */
    @JvmStatic
    fun getTextSelection(inputConnection: InputConnection): SelectionRange {
      val extractedText: ExtractedText? =
        inputConnection.getExtractedText(ExtractedTextRequest(), 0)
      var selectionStart = 0
      var selectionEnd = 0
      if (extractedText != null) {
        selectionStart =
          if (extractedText.selectionStart > 0) extractedText.selectionStart else selectionStart
        selectionEnd =
          if (extractedText.selectionEnd > 0) extractedText.selectionEnd else selectionEnd
      }
      return SelectionRange(selectionStart, selectionEnd)
    }

    /**
     * Invoke valueOf with a putative enum value name, returning the corresponding enum value if the
     * name is matched by one of the values in the enum collection; otherwise return the default
     * value.
     */
    @JvmStatic
    fun <E : Enum<E>> valueOfSafe(enumName: String, def: E): E {
      return try {
        java.lang.Enum.valueOf(def.javaClass, enumName)
      } catch (e: IllegalArgumentException) {
        def
      }
    }

    /**
     * Performs key action with specific key code.
     *
     * @return true if success; false if either action down or action up performs on invalid {@link
     *     InputConnection}.
     */
    @CanIgnoreReturnValue
    @JvmStatic
    fun performKeyAction(inputConnection: InputConnection, keyCode: Int): Boolean {
      if (inputConnection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))) {
        return inputConnection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
      }
      return false
    }

    private fun isNumberPasswordField(inputType: Int): Boolean {
      val variation = inputType and InputType.TYPE_MASK_VARIATION
      return getInputTypeClass(inputType) == InputType.TYPE_CLASS_NUMBER &&
          (variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD)
    }

    private fun isTextPasswordField(inputType: Int): Boolean {
      val variation = inputType and InputType.TYPE_MASK_VARIATION
      return getInputTypeClass(inputType) == InputType.TYPE_CLASS_TEXT &&
          (variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
              variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
              variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)
    }

    /** Returns {@code true} if {@code editorInfo}'s input type is visible password. */
    @JvmStatic
    fun isVisiblePasswordField(editorInfo: EditorInfo?): Boolean {
      val variation = editorInfo!!.inputType and InputType.TYPE_MASK_VARIATION
      return isPasswordField(editorInfo) &&
          variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
    }

    /**
     * Determines from {@link InputMethodManager} if {@link BRAILLE_KEYBOARD} is an enabled input
     * method.
     */
    @JvmStatic
    fun isBrailleKeyboardEnabled(context: Context): Boolean {
      return KeyboardUtils.isImeEnabled(context, AccessibilityServiceCompatUtils.Constants.BRAILLE_KEYBOARD)
    }

    /** Determines, from system settings, if {@link BRAILLE_KEYBOARD} is the default input method. */
    @JvmStatic
    fun isBrailleKeyboardDefault(context: Context): Boolean {
      val defaultImeId: String? =
        Settings.Secure.getString(
          context.contentResolver,
          Settings.Secure.DEFAULT_INPUT_METHOD,
        )

      return defaultImeId != null &&
          Objects.equals(ComponentName.unflattenFromString(defaultImeId), AccessibilityServiceCompatUtils.Constants.BRAILLE_KEYBOARD)
    }

    /** Filters non-printing characters like \u202A and \u202C. */
    @JvmStatic
    fun filterNonPrintCharacter(text: CharSequence): CharSequence {
      if (TextUtils.isEmpty(text)) {
        return ""
      }
      val stringBuilder = SpannableStringBuilder(text)
      for (i in text.length - 1 downTo 0) {
        if (Character.isIdentifierIgnorable(stringBuilder[i])) {
          stringBuilder.delete(i, i + 1)
        }
      }
      return stringBuilder
    }

    private fun getInputTypeClass(inputType: Int): Int {
      val typeClass = inputType and InputType.TYPE_MASK_CLASS
      return if (typeClass == 0 && (inputType and InputType.TYPE_MASK_VARIATION) != 0) {
        InputType.TYPE_CLASS_TEXT
      } else {
        typeClass
      }
    }
  }
}
