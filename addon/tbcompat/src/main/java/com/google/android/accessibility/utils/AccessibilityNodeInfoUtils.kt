/*
 * 移植说明：TalkBack 的 AccessibilityNodeInfoUtils 有 3,495 行，覆盖节点遍历、文本操作、
 * 角色判断等。盲文键盘只用到"拼写纠错建议"与"按类名匹配"两组能力，
 * 因此这里只提供等价的最小实现，其余 API 不提供（需要时再补）。
 */

package com.google.android.accessibility.utils

import android.content.Context
import android.text.InputType
import android.text.Spannable
import android.text.Spanned
import android.text.TextUtils
import android.view.accessibility.AccessibilityNodeInfo
import android.text.style.SuggestionSpan
import androidx.annotation.Nullable
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.google.common.collect.ImmutableList

/** 无障碍节点工具（精简实现，仅覆盖盲文键盘所需）。 */
class AccessibilityNodeInfoUtils private constructor() {

  /** 一次拼写纠错建议。 */
  class SpellingSuggestion private constructor(
    private val startValue: Int,
    private val endValue: Int,
    private val misspelledWordValue: CharSequence,
    private val suggestionSpanValue: SuggestionSpan,
  ) {

    companion object {
      @JvmStatic
      fun create(
        start: Int,
        end: Int,
        misspelledWord: CharSequence,
        suggestionSpan: SuggestionSpan,
      ): SpellingSuggestion =
        SpellingSuggestion(start, end, misspelledWord, suggestionSpan)
    }

    fun start(): Int = startValue

    fun end(): Int = endValue

    fun misspelledWord(): CharSequence = misspelledWordValue

    fun suggestionSpan(): SuggestionSpan = suggestionSpanValue

    override fun toString(): String = "[$startValue-$endValue][$misspelledWordValue]"
  }

  companion object {
    /** 节点类名是否匹配给定类型。 */
    @JvmStatic
    fun nodeMatchesClassByType(
      @Nullable node: AccessibilityNodeInfoCompat?,
      @Nullable referenceClass: Class<*>?,
    ): Boolean {
      if (node == null || referenceClass == null) {
        return false
      }
      val nodeClassName = node.getClassName()
      return TextUtils.equals(nodeClassName, referenceClass.name)
    }

    /** 返回节点上的拼写纠错建议列表；无建议时返回空列表。 */
    @JvmStatic
    fun getSpellingSuggestions(
      context: Context,
      @Nullable node: AccessibilityNodeInfoCompat?,
    ): ImmutableList<SpellingSuggestion> {
      if (node == null || hasNoSuggestionsNeed(node.getInputType())) {
        return ImmutableList.of()
      }
      val start = node.getTextSelectionStart()
      val end = node.getTextSelectionEnd()
      if (start != end) {
        // 选中文本时不做拼写建议。
        return ImmutableList.of()
      }
      return getSpellingSuggestions(context, node, end)
    }

    @JvmStatic
    fun getSpellingSuggestions(
      context: Context,
      @Nullable node: AccessibilityNodeInfoCompat?,
      cursorPosition: Int,
    ): ImmutableList<SpellingSuggestion> {
      if (node == null) {
        return ImmutableList.of()
      }
      val text = node.getText()
      if (TextUtils.isEmpty(text) || text !is Spannable) {
        return ImmutableList.of()
      }
      val spannedText: Spannable = text

      // 光标后若紧跟空格/标点，把光标回退一格，使 helllo,| 也能命中。
      var position = cursorPosition
      if (position > 0) {
        if (position < text.length) {
          if (!Character.isLetterOrDigit(text[position - 1]) &&
            !Character.isLetterOrDigit(text[position])
          ) {
            position--
          }
        } else if (!Character.isLetterOrDigit(text[position - 1])) {
          position--
        }
      }

      val spans = spannedText.getSpans(0, text.length, SuggestionSpan::class.java)
      val suggestions: MutableList<SpellingSuggestion> = mutableListOf()
      for (span in spans) {
        val spanStart = spannedText.getSpanStart(span)
        val spanEnd = spannedText.getSpanEnd(span)
        if (spanStart <= position && spanEnd >= position) {
          suggestions.add(
            SpellingSuggestion.create(
              spanStart,
              spanEnd,
              text.subSequence(spanStart, spanEnd),
              span,
            ),
          )
        }
      }
      return ImmutableList.copyOf(suggestions)
    }

    /** 该输入类型是否根本不会产生拼写建议。 */
    private fun hasNoSuggestionsNeed(inputType: Int): Boolean {
      if (inputType == 0) {
        return true
      }
      val inputClass = inputType and InputType.TYPE_MASK_CLASS
      return inputClass != InputType.TYPE_CLASS_TEXT
    }

    /** 安全地把 AccessibilityNodeInfo 包成 Compat 对象。 */
    @JvmStatic
    @Nullable
    fun toCompat(@Nullable info: AccessibilityNodeInfo?): AccessibilityNodeInfoCompat? {
      return if (info == null) null else AccessibilityNodeInfoCompat.wrap(info)
    }

    /** 节点是否可点击。 */
    @JvmStatic
    fun isClickable(@Nullable node: AccessibilityNodeInfoCompat?): Boolean {
      return node != null && node.isClickable
    }

    /** 节点上可见文本。 */
    @JvmStatic
    @Nullable
    fun getNodeText(@Nullable node: AccessibilityNodeInfoCompat?): CharSequence? {
      if (node == null) {
        return null
      }
      val text = node.getText()
      return if (TextUtils.isEmpty(text)) node.getContentDescription() else text
    }

    /** 判断 span 是否覆盖指定位置。 */
    @JvmStatic
    fun spanContains(spanned: Spanned, span: Any, position: Int): Boolean {
      return spanned.getSpanStart(span) <= position && spanned.getSpanEnd(span) >= position
    }
  }
}
