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
import android.view.accessibility.AccessibilityNodeInfo.FOCUS_ACCESSIBILITY
import android.view.accessibility.AccessibilityNodeInfo.FOCUS_INPUT
import android.widget.EditText
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.google.android.accessibility.utils.AccessibilityNodeInfoUtils
import com.google.android.accessibility.utils.AccessibilityNodeInfoUtils.SpellingSuggestion
import com.google.android.accessibility.utils.FocusFinder
import com.google.android.accessibility.utils.FocusFinder.FocusType
import com.google.common.collect.ImmutableList
import com.google.errorprone.annotations.CanIgnoreReturnValue
import java.util.Arrays

/**
 * Handles typo correction and provide necessary result for user selecting for TalkBack corrects it.
 */
class BrailleTypoFinder(private val focusFinder: FocusFinder) {
  private var targetNode: AccessibilityNodeInfoCompat? = null
  private var spellingSuggestion: SpellingSuggestion? = null
  private var suggestionCandidates: Array<String>? = null
  private var suggestionSpanFlag: Int = 0
  private var index: Int = NONE_INITIALIZED

  /**
   * Updates the typo correction from given focus type.
   *
   * @param focusType defines find typo correction from which focus.
   * @return true if there has typo correction candidate.
   */
  @CanIgnoreReturnValue
  fun updateTypoCorrectionFrom(context: Context, @FocusType focusType: Int): Boolean {
    clear()
    when (focusType) {
      FOCUS_ACCESSIBILITY -> {
        val node = focusFinder.findAccessibilityFocus()
        if (node != null &&
          node.isFocused() &&
          AccessibilityNodeInfoUtils.nodeMatchesClassByType(node, EditText::class.java)
        ) {
          return obtainSuggestions(context, node)
        }
        return false
      }
      FOCUS_INPUT -> {
        return obtainSuggestions(context, focusFinder.findFocusCompat(FOCUS_INPUT))
      }
      else -> {
        return false
      }
    }
  }

  /** Gets the next targeted suggestion candidate. */
  @Throws(NoTypoFocusFoundException::class)
  fun nextSuggestion(): String {
    if (suggestionCandidates == null) {
      throw NoTypoFocusFoundException()
    }
    if (index == NONE_INITIALIZED) {
      index = 0
    } else {
      index++
      if (index >= suggestionCandidates!!.size) {
        index = 0
      }
    }
    return suggestionCandidates!![index]
  }

  /** Gets the previous targeted suggestion candidate. */
  @Throws(NoTypoFocusFoundException::class)
  fun previousSuggestion(): String {
    if (suggestionCandidates == null) {
      throw NoTypoFocusFoundException()
    }
    if (index == NONE_INITIALIZED) {
      index = 0
    } else {
      index--
      if (index < 0) {
        index = suggestionCandidates!!.size - 1
      }
    }
    return suggestionCandidates!![index]
  }

  /** Clears the cache fields. Usually used when typo suggestion has confirmed. */
  fun clear() {
    index = NONE_INITIALIZED
    targetNode = null
    spellingSuggestion = null
    suggestionCandidates = null
  }

  /** Gets the current targeted suggestion candidate. */
  @Throws(NoTypoFocusFoundException::class)
  fun getCurrentSuggestionCandidate(): String {
    if (suggestionCandidates == null) {
      throw NoTypoFocusFoundException()
    }
    if (index == NONE_INITIALIZED) {
      return ""
    }
    return suggestionCandidates!![index]
  }

  /** Gets the suggestion candidates. */
  @Throws(NoTypoFocusFoundException::class)
  fun getSuggestionCandidates(): ImmutableList<String> {
    if (suggestionCandidates == null) {
      throw NoTypoFocusFoundException()
    }
    return ImmutableList.copyOf(Arrays.asList(*suggestionCandidates!!))
  }

  /**
   * Gets the {@link AccessibilityNodeInfoCompat} found from called {@link
   * BrailleTypoFinder#updateTypoCorrectionFrom(Context, int)}.
   */
  fun getTargetNode(): AccessibilityNodeInfoCompat? {
    return targetNode
  }

  /**
   * Gets the closest {@link SpellingSuggestion} found from the {@link AccessibilityNodeInfoCompat}.
   */
  fun getSpellingSuggestion(): SpellingSuggestion? {
    return spellingSuggestion
  }

  /** Returns the SuggestionSpanFlag of current suggestion. */
  fun getSuggestionSpanFlag(): Int {
    return suggestionSpanFlag
  }

  private fun obtainSuggestions(context: Context, node: AccessibilityNodeInfoCompat?): Boolean {
    if (node == null) {
      return false
    }
    val spellingSuggestions: ImmutableList<SpellingSuggestion> =
      AccessibilityNodeInfoUtils.getSpellingSuggestions(context, node)
    if (spellingSuggestions.isEmpty()) {
      return false
    }
    val spellingSuggestion: SpellingSuggestion = spellingSuggestions[/* index= */ 0]
    val span = spellingSuggestion.suggestionSpan() ?: return false
    targetNode = node
    this.spellingSuggestion = spellingSuggestion
    suggestionCandidates = span.suggestions
    suggestionSpanFlag = span.flags
    return suggestionCandidates!!.isNotEmpty()
  }

  /**
   * An {@link Exception} throws when the method called before interact with a typo or the focused
   * typo was handled.
   */
  class NoTypoFocusFoundException : Exception {
    constructor() : super("You have to call updateTypoCorrectionFrom(int) in advance!")
  }

  companion object {
    private const val NONE_INITIALIZED = -1
  }
}
