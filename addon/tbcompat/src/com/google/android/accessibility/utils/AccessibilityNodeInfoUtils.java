/*
 * 移植说明：TalkBack 的 AccessibilityNodeInfoUtils 有 3,495 行，覆盖节点遍历、文本操作、
 * 角色判断等。盲文键盘只用到"拼写纠错建议"与"按类名匹配"两组能力，
 * 因此这里只提供等价的最小实现，其余 API 不提供（需要时再补）。
 */

package com.google.android.accessibility.utils;

import android.content.Context;
import android.text.InputType;
import android.text.Spannable;
import android.text.Spanned;
import android.text.TextUtils;
import android.view.accessibility.AccessibilityNodeInfo;
import android.text.style.SuggestionSpan;
import androidx.annotation.Nullable;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import com.google.common.collect.ImmutableList;
import java.util.ArrayList;
import java.util.List;

/** 无障碍节点工具（精简实现，仅覆盖盲文键盘所需）。 */
public final class AccessibilityNodeInfoUtils {

  private AccessibilityNodeInfoUtils() {}

  /** 一次拼写纠错建议。 */
  public static final class SpellingSuggestion {
    private final int start;
    private final int end;
    private final CharSequence misspelledWord;
    private final SuggestionSpan suggestionSpan;

    private SpellingSuggestion(
        int start, int end, CharSequence misspelledWord, SuggestionSpan suggestionSpan) {
      this.start = start;
      this.end = end;
      this.misspelledWord = misspelledWord;
      this.suggestionSpan = suggestionSpan;
    }

    public static SpellingSuggestion create(
        int start, int end, CharSequence misspelledWord, SuggestionSpan suggestionSpan) {
      return new SpellingSuggestion(start, end, misspelledWord, suggestionSpan);
    }

    public int start() {
      return start;
    }

    public int end() {
      return end;
    }

    public CharSequence misspelledWord() {
      return misspelledWord;
    }

    public SuggestionSpan suggestionSpan() {
      return suggestionSpan;
    }

    @Override
    public String toString() {
      return "[" + start + "-" + end + "][" + misspelledWord + "]";
    }
  }

  /** 节点类名是否匹配给定类型。 */
  public static boolean nodeMatchesClassByType(
      @Nullable AccessibilityNodeInfoCompat node, @Nullable Class<?> referenceClass) {
    if (node == null || referenceClass == null) {
      return false;
    }
    CharSequence nodeClassName = node.getClassName();
    return TextUtils.equals(nodeClassName, referenceClass.getName());
  }

  /** 返回节点上的拼写纠错建议列表；无建议时返回空列表。 */
  public static ImmutableList<SpellingSuggestion> getSpellingSuggestions(
      Context context, @Nullable AccessibilityNodeInfoCompat node) {
    if (node == null || hasNoSuggestionsNeed(node.getInputType())) {
      return ImmutableList.of();
    }
    int start = node.getTextSelectionStart();
    int end = node.getTextSelectionEnd();
    if (start != end) {
      // 选中文本时不做拼写建议。
      return ImmutableList.of();
    }
    return getSpellingSuggestions(context, node, end);
  }

  public static ImmutableList<SpellingSuggestion> getSpellingSuggestions(
      Context context, @Nullable AccessibilityNodeInfoCompat node, int cursorPosition) {
    if (node == null) {
      return ImmutableList.of();
    }
    CharSequence text = node.getText();
    if (TextUtils.isEmpty(text) || !(text instanceof Spannable spannedText)) {
      return ImmutableList.of();
    }

    // 光标后若紧跟空格/标点，把光标回退一格，使 helllo,| 也能命中。
    int position = cursorPosition;
    if (position > 0) {
      if (position < text.length()) {
        if (!Character.isLetterOrDigit(text.charAt(position - 1))
            && !Character.isLetterOrDigit(text.charAt(position))) {
          position--;
        }
      } else if (!Character.isLetterOrDigit(text.charAt(position - 1))) {
        position--;
      }
    }

    SuggestionSpan[] spans = spannedText.getSpans(0, text.length(), SuggestionSpan.class);
    List<SpellingSuggestion> suggestions = new ArrayList<>();
    for (SuggestionSpan span : spans) {
      int spanStart = spannedText.getSpanStart(span);
      int spanEnd = spannedText.getSpanEnd(span);
      if (spanStart <= position && spanEnd >= position) {
        suggestions.add(
            SpellingSuggestion.create(
                spanStart,
                spanEnd,
                text.subSequence(spanStart, spanEnd),
                span));
      }
    }
    return ImmutableList.copyOf(suggestions);
  }

  /** 该输入类型是否根本不会产生拼写建议。 */
  private static boolean hasNoSuggestionsNeed(int inputType) {
    if (inputType == 0) {
      return true;
    }
    int inputClass = inputType & InputType.TYPE_MASK_CLASS;
    return inputClass != InputType.TYPE_CLASS_TEXT;
  }

  /** 安全地把 AccessibilityNodeInfo 包成 Compat 对象。 */
  public static @Nullable AccessibilityNodeInfoCompat toCompat(
      @Nullable AccessibilityNodeInfo info) {
    return (info == null) ? null : AccessibilityNodeInfoCompat.wrap(info);
  }

  /** 节点是否可点击。 */
  public static boolean isClickable(@Nullable AccessibilityNodeInfoCompat node) {
    return node != null && node.isClickable();
  }

  /** 节点上可见文本。 */
  public static @Nullable CharSequence getNodeText(@Nullable AccessibilityNodeInfoCompat node) {
    if (node == null) {
      return null;
    }
    CharSequence text = node.getText();
    return TextUtils.isEmpty(text) ? node.getContentDescription() : text;
  }

  /** 判断 span 是否覆盖指定位置。 */
  static boolean spanContains(Spanned spanned, Object span, int position) {
    return spanned.getSpanStart(span) <= position && spanned.getSpanEnd(span) >= position;
  }
}
