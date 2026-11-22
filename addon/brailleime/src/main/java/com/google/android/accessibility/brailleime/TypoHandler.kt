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

package com.google.android.accessibility.brailleime

import android.content.Context
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.style.SuggestionSpan.FLAG_GRAMMAR_ERROR
import android.text.style.TtsSpan
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.InputConnection
import androidx.annotation.VisibleForTesting
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.google.android.accessibility.braille.common.BrailleTypoFinder
import com.google.android.accessibility.braille.common.BrailleTypoFinder.NoTypoFocusFoundException
import com.google.android.accessibility.braille.common.TalkBackSpeaker
import com.google.android.accessibility.braille.interfaces.ScreenReaderActionPerformer.ScreenReaderAction
import com.google.android.accessibility.utils.AccessibilityNodeInfoUtils.SpellingSuggestion
import com.google.android.accessibility.utils.FocusFinder
import com.google.common.collect.ImmutableList
import com.google.errorprone.annotations.CanIgnoreReturnValue

/** Class handles all the typo correction functionalities in Braille keyboard. */
class TypoHandler(
    private val context: Context,
    private val callback: Callback,
    private val talkBackSpeaker: TalkBackSpeaker,
) {
  private var typoFinder: BrailleTypoFinder
  private var lastCorrectedTypo: CharSequence? = null
  private var lastCorrectedTypoHeadIndex: Int = NON_INITIALIZED
  private var lastCorrectedSuggestionTailIndex: Int = NON_INITIALIZED

  /** Callback for {@link TypoHandler}. */
  interface Callback {
    /** Returns the {@link InputConnection} of the current focused edit text. */
    fun getInputConnection(): InputConnection?

    /** Returns the {@link FocusFinder} to find the focused node. */
    fun getFocusFinder(): FocusFinder

    /** Performs the {@link ScreenReaderAction} with the given arguments. */
    fun performAction(action: ScreenReaderAction, vararg arg: Any?): Boolean
  }

  init {
    typoFinder = BrailleTypoFinder(callback.getFocusFinder())
    clearCorrectionCache()
  }

  /** Refreshes focused typo. */
  @CanIgnoreReturnValue
  fun updateTypoTarget(): Boolean {
    clearCorrectionCache()
    return typoFinder.updateTypoCorrectionFrom(context, AccessibilityNodeInfo.FOCUS_INPUT)
  }

  /** Navigates to next suggestion for current typo. */
  @CanIgnoreReturnValue
  fun nextSuggestion(): Boolean {
    val suggestion: String
    val indexString: String
    try {
      suggestion = typoFinder.nextSuggestion()
      val suggestionList: ImmutableList<String> = typoFinder.getSuggestionCandidates()
      indexString =
          context.getString(
              R.string.index_of_spelling_suggestion,
              suggestionList.indexOf(suggestion) + 1,
              suggestionList.size)
    } catch (e: NoTypoFocusFoundException) {
      if (updateTypoTarget()) {
        return nextSuggestion()
      }
      return false
    }
    talkBackSpeaker.speak(
        getSuggestionSpannableString(suggestion, getSuggestionTypeString(), indexString))
    return true
  }

  /** Navigates to previous suggestion for current typo. */
  @CanIgnoreReturnValue
  fun previousSuggestion(): Boolean {
    val suggestion: String
    val indexString: String
    try {
      suggestion = typoFinder.previousSuggestion()
      val suggestionList: ImmutableList<String> = typoFinder.getSuggestionCandidates()
      indexString =
          context.getString(
              R.string.index_of_spelling_suggestion,
              suggestionList.indexOf(suggestion) + 1,
              suggestionList.size)
    } catch (e: NoTypoFocusFoundException) {
      if (updateTypoTarget()) {
        return previousSuggestion()
      }
      return false
    }
    talkBackSpeaker.speak(
        getSuggestionSpannableString(suggestion, getSuggestionTypeString(), indexString))
    return true
  }

  /** Confirms current suggestion as final. */
  fun confirmSuggestion(): Boolean {
    val suggestionCandidate: String
    try {
      suggestionCandidate = typoFinder.getCurrentSuggestionCandidate()
      if (TextUtils.isEmpty(suggestionCandidate)) {
        // There are 2 possible causes: 1. This typo doesn't have suggestions. 2. User hasn't chosen
        // a suggestion.
        BrailleImeLog.e(TAG, "suggestion is empty")
        return false
      }
    } catch (e: NoTypoFocusFoundException) {
      BrailleImeLog.e(TAG, "no typo found", e)
      return false
    }
    val spellingSuggestion = typoFinder.getSpellingSuggestion()!!
    val node: AccessibilityNodeInfoCompat? = typoFinder.getTargetNode()
    lastCorrectedTypoHeadIndex = spellingSuggestion.start()
    lastCorrectedTypo =
        spellingSuggestion
            .misspelledWord()
            .subSequence(0, spellingSuggestion.misspelledWord().length)
    lastCorrectedSuggestionTailIndex =
        lastCorrectedTypoHeadIndex + suggestionCandidate.length - 1
    typoFinder.clear()
    return callback.performAction(
        ScreenReaderAction.TYPO_CORRECT, node, suggestionCandidate, spellingSuggestion)
  }

  /** Undoes last confirmed final to original typo. */
  fun undoConfirmSuggestion(): Boolean {
    val inputConnection = callback.getInputConnection()
    if (inputConnection == null) {
      BrailleImeLog.w(TAG, "inputConnection is null")
      return false
    }
    if (lastCorrectedTypo == null) {
      BrailleImeLog.w(TAG, "lastCorrectedTypo is null")
      return false
    }
    // Replace replaced suggestion with original typo.
    val lastTypo = lastCorrectedTypo!!
    inputConnection.beginBatchEdit()
    inputConnection.setComposingRegion(
        lastCorrectedTypoHeadIndex, lastCorrectedSuggestionTailIndex + 1)
    inputConnection.setComposingText(lastTypo, 0)
    inputConnection.finishComposingText()
    inputConnection.endBatchEdit()
    clearCorrectionCache()
    return true
  }

  /** Whether grammar mistake or not. */
  fun isGrammarMistake(): Boolean {
    return (typoFinder.getSuggestionSpanFlag() and FLAG_GRAMMAR_ERROR) != 0
  }

  private fun clearCorrectionCache() {
    lastCorrectedTypoHeadIndex = NON_INITIALIZED
    lastCorrectedTypo = null
    lastCorrectedSuggestionTailIndex = NON_INITIALIZED
  }

  /**
   * The suggestion will first be read aloud as a whole word, and then each of its characters will
   * be read out individually. The index string will be read out at the end.
   */
  private fun getSuggestionSpannableString(
      suggestion: String,
      type: String,
      indexSting: String,
  ): Spanned {
    return SpannableStringBuilder()
        .append(suggestion)
        .append(SEPARATOR_PERIOD)
        .append(type)
        .append(SEPARATOR_PERIOD)
        .append(SPACE, TtsSpan.VerbatimBuilder(suggestion).build(), 0)
        .append(SEPARATOR_PERIOD)
        .append(indexSting)
  }

  private fun getSuggestionTypeString(): String {
    return context.getString(
        if (isGrammarMistake()) R.string.grammar_suggestion
        else R.string.spelling_suggestion)
  }

  @VisibleForTesting
  internal fun testing_setTypoFinder(typoFinder: BrailleTypoFinder) {
    this.typoFinder = typoFinder
  }

  companion object {
    private const val TAG = "TypoHandler"
    private const val SEPARATOR_PERIOD = ". "
    private const val SPACE = " "
    private const val NON_INITIALIZED = -1
  }
}
