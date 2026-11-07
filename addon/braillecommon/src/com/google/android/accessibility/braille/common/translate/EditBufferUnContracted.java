/*
 * Copyright 2019 Google Inc.
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

package com.google.android.accessibility.braille.common.translate;

import static com.google.android.accessibility.braille.common.translate.BrailleTranslateUtils.DOT6;
import static com.google.android.accessibility.braille.common.translate.BrailleTranslateUtils.DOTS3456;
import static com.google.android.accessibility.braille.common.translate.EditBufferUtils.NO_CURSOR;

import android.content.Context;
import android.content.res.Resources;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.inputmethod.InputConnection;
import androidx.annotation.VisibleForTesting;
import com.google.android.accessibility.braille.common.BrailleCommonUtils;
import com.google.android.accessibility.braille.common.ImeConnection;
import com.google.android.accessibility.braille.common.R;
import com.google.android.accessibility.braille.common.TalkBackSpeaker;
import com.google.android.accessibility.braille.interfaces.BrailleCharacter;
import com.google.android.accessibility.braille.interfaces.BrailleDisplayForBrailleIme.ResultForDisplay.HoldingsInfo;
import com.google.android.accessibility.braille.interfaces.BrailleWord;
import com.google.android.accessibility.braille.translate.BrailleTranslator;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.nio.ByteBuffer;
import java.util.Optional;

/**
 * A common {@link EditBuffer}.
 *
 * <p>A list of 'holdings' characters is kept, which corresponds closely to the Editor's 'composing
 * text', but holds {@link BrailleCharacter} instead of print, and can hold non-printable braille
 * such as UEB's capitalization prefix, depending on what input the user has provided.
 *
 * <p>Here is an example sequence of the holdings list and operations that modify it:
 *
 * <ul>
 *   <li>[]
 *   <li>appendBraille(6)
 *   <li>[6]
 *   <li>appendBraille(1245)
 *   <li>[6, 1245]
 *   <li>appendBraille(136)
 *   <li>[6, 1245, 136]
 *   <li>deleteCharacter
 *   <li>[6, 1245]
 *   <li>appendBraille(135)
 *   <li>[6, 1245, 135]
 *   <li>appendSpace()
 *   <li>[]
 *   <li>Send "Go " to Editor via InputConnection.
 * </ul>
 */
public class EditBufferUnContracted implements EditBuffer {

  private static final String TAG = "EditBufferCommon";
  private static final int DELETE_WORD_MAX = 50;
  private final TalkBackSpeaker talkBack;
  protected final BrailleTranslator translator;
  private final Context context;
  protected final BrailleWord holdings = new BrailleWord();
  protected int lastCommitIndexOfHoldings = NO_CURSOR;

  protected EditBufferUnContracted(
      Context context, BrailleTranslator ueb1Translator, TalkBackSpeaker talkBack) {
    this.context = context;
    this.translator = ueb1Translator;
    this.talkBack = talkBack;
  }

  @CanIgnoreReturnValue
  @Override
  public String appendBraille(ImeConnection imeConnection, BrailleCharacter brailleCharacter) {
    String result = "";
    String previousTranslation = translateHoldings(holdings);
    holdings.append(brailleCharacter);
    String currentTranslation = translateHoldings(holdings);
    if (currentTranslation.startsWith(previousTranslation)) {
      result = currentTranslation.substring(previousTranslation.length());
    }

    // 诊断：记录每次落键后的整体译文与增量（tag=BBTranslate）。
    // 若 cur 是数字串，说明是翻译表把点反译成了数字，与键盘点阵无关。
    android.util.Log.d(
        "BBTranslate",
        "append bc="
            + brailleCharacter
            + " prev=["
            + previousTranslation
            + "] cur=["
            + currentTranslation
            + "] delta=["
            + result
            + "] textField="
            + BrailleCommonUtils.isTextField(imeConnection.editorInfo)
            + " password="
            + BrailleCommonUtils.isPasswordField(imeConnection.editorInfo));

    // 译文整体是否发生变化。中文现行盲文一个汉字 = 三格（声母-韵母-声调），第一格反译出的是
    // 数字/字母，第三格才跳成汉字；此时 currentTranslation 不以 previousTranslation 开头，
    // 上面的 result 会是空串。若仍按 result 判空决定是否刷新 composing，输入框就会永远停在
    // 第一格的内容上，看起来像"怎么打都只出一个数字"。
    final boolean translationChanged = !currentTranslation.equals(previousTranslation);

    // Use setComposingText() to send user input braille character in text but not password fields;
    // Otherwise use commitText().
    if (BrailleCommonUtils.isTextField(imeConnection.editorInfo)
        && !BrailleCommonUtils.isPasswordField(imeConnection.editorInfo)) {
      // 文本框内 composing 必须写整体译文，不能写增量：多格构成一个字符的语言（中文盲文）
      // 只有整体译文才是正确的半成品状态。
      if (translationChanged) {
        imeConnection.inputConnection.setComposingText(currentTranslation, 1);
        lastCommitIndexOfHoldings = holdings.size();
      }
    } else if (!TextUtils.isEmpty(result)) {
      imeConnection.inputConnection.commitText(result, 1);
      lastCommitIndexOfHoldings = holdings.size();
    }

    if (TextUtils.isEmpty(result) || !BrailleTranslateUtils.isPronounceable(result)) {
      String textToSpeak =
          getAppendBrailleTextToSpeak(context.getResources(), brailleCharacter).orElse(null);
      if (TextUtils.isEmpty(textToSpeak)) {
        textToSpeak = BrailleTranslateUtils.getDotsText(context.getResources(), brailleCharacter);
      }
      if (EditBufferUtils.shouldEmitPerCharacterFeedback(imeConnection)) {
        talkBack.speak(textToSpeak, TalkBackSpeaker.AnnounceType.INTERRUPT);
      }
      result = textToSpeak;
    }
    return result;
  }

  /**
   * Translates the accumulated holdings into print.
   *
   * <p>子类可以覆写这个方法改变"整串翻译"的策略。中文现行盲文在多字连打时，整串丢给 liblouis
   * 会按规则长度做匹配，2 格字 + 2 格字 很容易被切成 3 格 + 1 格（跨字歧义），前面的字还会因为
   * 重新分词而跳变。中文子类在这里先按合法音节切分，再逐段翻译拼接。
   */
  protected String translateHoldings(BrailleWord word) {
    return translator.translateToPrint(word);
  }

  /** Provides (optionally) an audial announcement for a just-appended {@code brailleCharacter}. */
  protected Optional<String> getAppendBrailleTextToSpeak(
      Resources resources, BrailleCharacter brailleCharacter) {
    if (brailleCharacter.equals(getNumeric())) {
      return Optional.of(resources.getString(R.string.number_announcement));
    } else if (brailleCharacter.equals(getCapitalize())) {
      return Optional.of(resources.getString(R.string.capitalize_announcement));
    }
    return Optional.empty();
  }

  @Override
  public void appendSpace(ImeConnection imeConnection) {
    commit(imeConnection);
    imeConnection.inputConnection.commitText(" ", 1);
  }

  @Override
  public void appendNewline(ImeConnection imeConnection) {
    if (EditBufferUtils.isMultiLineField(imeConnection.editorInfo.inputType)) {
      commit(imeConnection);
      imeConnection.inputConnection.commitText("\n", 1);
    } else {
      EditBufferUtils.speak(context, talkBack, context.getString(R.string.new_line_not_supported));
    }
  }

  @Override
  public void deleteCharacterBackward(ImeConnection imeConnection) {
    deleteCharacter(imeConnection.inputConnection, KeyEvent.KEYCODE_DEL);
  }

  @Override
  public void deleteCharacterForward(ImeConnection imeConnection) {
    deleteCharacter(imeConnection.inputConnection, KeyEvent.KEYCODE_FORWARD_DEL);
  }

  @Override
  public void deleteWord(ImeConnection imeConnection) {
    // Delete all terminal prefixes from holdings, if any.
    while (holdingsEndsWithPrefix()) {
      holdings.remove(holdings.size() - 1);
    }
    // If there is any holdings left, clear it out; otherwise delete at the Editor level.
    if (holdings.size() > 0) {
      holdings.clear();
      lastCommitIndexOfHoldings = NO_CURSOR;
      imeConnection.inputConnection.setComposingText("", 0);
    } else {
      CharSequence hunkBeforeCursor =
          imeConnection.inputConnection.getTextBeforeCursor(DELETE_WORD_MAX, 0);
      int charactersToDeleteCount =
          BrailleCommonUtils.getLastWordLengthForDeletion(hunkBeforeCursor);
      if (charactersToDeleteCount > 0) {
        imeConnection.inputConnection.deleteSurroundingText(charactersToDeleteCount, 0);
      }
    }
  }

  @CanIgnoreReturnValue
  @Override
  public boolean moveCursorForward(ImeConnection imeConnection) {
    commit(imeConnection);
    return false;
  }

  @CanIgnoreReturnValue
  @Override
  public boolean moveCursorBackward(ImeConnection imeConnection) {
    commit(imeConnection);
    return false;
  }

  @CanIgnoreReturnValue
  @Override
  public boolean moveTextFieldCursor(ImeConnection imeConnection, int index) {
    commit(imeConnection);
    if (0 <= index
        && index <= EditBufferUtils.getTextFieldText(imeConnection.inputConnection).length()) {
      return imeConnection.inputConnection.setSelection(index, index);
    }
    return false;
  }

  @Override
  public HoldingsInfo getHoldingsInfo(ImeConnection imeConnection) {
    BrailleWord unCommit = new BrailleWord();
    if (!holdings.isEmpty()) {
      // If user enter 4-4-4-4-1 then 6, unCommit is 6.
      unCommit =
          holdings.subword(
              lastCommitIndexOfHoldings == NO_CURSOR ? 0 : lastCommitIndexOfHoldings,
              holdings.size());
    }
    return HoldingsInfo.create(
        ByteBuffer.wrap(unCommit.toByteArray()), unCommit.isEmpty() ? NO_CURSOR : unCommit.size());
  }

  @Override
  public void commit(ImeConnection imeConnection) {
    clearHoldingsAndFinishComposing(imeConnection.inputConnection);
  }

  @Override
  public String toString() {
    return holdings.toString();
  }

  /** Gets capitalize indicator braille character. */
  protected BrailleCharacter getCapitalize() {
    return DOT6;
  }

  /** Gets numeric indicator braille character. */
  protected BrailleCharacter getNumeric() {
    return DOTS3456;
  }

  private void clearHoldingsAndFinishComposing(InputConnection inputConnection) {
    holdings.clear();
    lastCommitIndexOfHoldings = NO_CURSOR;
    inputConnection.finishComposingText();
  }

  private boolean holdingsEndsWithPrefix() {
    return !holdings.isEmpty()
        && translateHoldings(holdings)
            .equals(translateHoldings(holdings.subword(0, holdings.size() - 1)));
  }

  private void deleteCharacter(InputConnection inputConnection, int keyCode) {
    // Delete a single terminal prefix from holdings, if any. In that case, stop.
    if (holdingsEndsWithPrefix()) {
      BrailleCharacter brailleCharacter = holdings.remove(holdings.size() - 1);
      String textToSpeak =
          getAppendBrailleTextToSpeak(context.getResources(), brailleCharacter).orElse(null);
      if (TextUtils.isEmpty(textToSpeak)) {
        textToSpeak = BrailleTranslateUtils.getDotsText(context.getResources(), brailleCharacter);
      }
      EditBufferUtils.speakDelete(context, talkBack, textToSpeak);
    } else {
      // Otherwise, remove the terminal root, and forward a deletion press to the Editor.
      if (!holdings.isEmpty()) {
        holdings.remove(holdings.size() - 1);
      }
      BrailleCommonUtils.performKeyAction(inputConnection, keyCode);
    }
    lastCommitIndexOfHoldings = holdings.size();
  }

  /**
   * For testing, returns true if the holdings matches the given list of {@link BrailleCharacter}.
   */
  @VisibleForTesting
  boolean testing_holdingsMatches(BrailleWord expectedHoldings) {
    return holdings.equals(expectedHoldings);
  }
}
