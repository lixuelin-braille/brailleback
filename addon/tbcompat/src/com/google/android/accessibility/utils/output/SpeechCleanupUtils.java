/*
 * 移植说明：原版 SpeechCleanupUtils（427 行）负责把文本转成"适合朗读"的形式：
 * 表情符号转描述、去掉重复标点、把 URL 读作"链接"等，并依赖 utils.R 的字符串表。
 * 盲文输入时朗读的是单个字符/单词，不需要这套清理，因此这里只保留最基本的
 * 空白与不可打印字符处理，接口保持一致。
 */

package com.google.android.accessibility.utils.output;

import android.content.Context;
import android.text.TextUtils;
import androidx.annotation.Nullable;

/** 朗读文本清理（精简实现）。 */
public final class SpeechCleanupUtils {

  private SpeechCleanupUtils() {}

  /** 把文本整理为适合朗读的形式。 */
  public static CharSequence cleanUp(Context context, @Nullable CharSequence text) {
    if (TextUtils.isEmpty(text)) {
      return "";
    }
    StringBuilder builder = new StringBuilder(text.length());
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      // 过滤零宽字符与方向控制符，它们对读屏无意义却会打断朗读。
      if (c == '\u200B' || c == '\u200C' || c == '\u200D' || c == '\uFEFF') {
        continue;
      }
      if (c == '\u202A' || c == '\u202B' || c == '\u202C' || c == '\u202D' || c == '\u202E') {
        continue;
      }
      builder.append(c);
    }
    return builder.toString();
  }

  /** 兼容重载：无 Context 版本。 */
  public static CharSequence cleanUp(@Nullable CharSequence text) {
    return cleanUp(null, text);
  }
}
