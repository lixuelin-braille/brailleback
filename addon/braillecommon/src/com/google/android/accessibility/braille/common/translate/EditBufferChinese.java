/*
 * Copyright 2026 The BrailleBack Authors
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

import android.content.Context;
import com.google.android.accessibility.braille.common.TalkBackSpeaker;
import com.google.android.accessibility.braille.interfaces.BrailleWord;
import com.google.android.accessibility.braille.translate.BrailleTranslator;

/**
 * 中文现行盲文（带声调）的 {@link EditBuffer}。
 *
 * <p>与通用的 {@link EditBufferUnContracted} 唯一区别是翻译策略：不把整串 holdings 一次性丢给
 * liblouis，而是<b>先按合法音节切分，再逐段翻译拼接</b>。
 *
 * <p>为什么必须切：中文一个音节 2~3 格，liblouis 整串反译按规则长度匹配，连续输入时
 * {@code 2格+2格} 会被切成 {@code 3格+1格}（出"一个汉字 + 一个数字"），而且每追加一格都会让
 * 前面的字重新分词、在输入框里跳变。按音节切分后，已确定的前缀不会再被改写。
 *
 * <p>切分规则（贪心，从头开始，已确定部分不回溯）：
 *
 * <ol>
 *   <li>剩余 ≥3 格且这 3 格是表内合法汉字 → 取 3 格（现行盲文带声调，3 格占 81%）
 *   <li>否则剩余 ≥2 格且这 2 格是表内合法汉字 → 取 2 格（零声母字、无调字）
 *   <li>否则取 1 格，交给翻译表——通常得到数字，作为可见的中间态保留
 * </ol>
 *
 * <p>残留歧义：若某个 2 格字 A 恰好与后面一格拼成合法的 3 格字 B，贪心会输出 B。这类歧义
 * 只有语言模型能解，当前版本接受此限制。
 */
public class EditBufferChinese extends EditBufferUnContracted {

  private final ChineseBrailleTableIndex index;

  private EditBufferChinese(
      Context context,
      BrailleTranslator translator,
      TalkBackSpeaker talkBack,
      ChineseBrailleTableIndex index) {
    super(context, translator, talkBack);
    this.index = index;
  }

  /**
   * 创建中文 {@link EditBuffer}；音节索引不可用时降级到通用的整串翻译，保证仍能输入。
   */
  public static EditBufferUnContracted create(
      Context context, BrailleTranslator translator, TalkBackSpeaker talkBack) {
    ChineseBrailleTableIndex index = ChineseBrailleTableIndex.getInstance(context);
    if (index == null) {
      return new EditBufferUnContracted(context, translator, talkBack);
    }
    return new EditBufferChinese(context, translator, talkBack, index);
  }

  @Override
  protected String translateHoldings(BrailleWord word) {
    if (word.isEmpty()) {
      return "";
    }
    StringBuilder result = new StringBuilder();
    int start = 0;
    final int size = word.size();
    while (start < size) {
      int remaining = size - start;
      int take;
      if (remaining >= 3 && index.hasCandidate(dots(word, start, 3))) {
        take = 3;
      } else if (remaining >= 2 && index.hasCandidate(dots(word, start, 2))) {
        take = 2;
      } else {
        take = 1;
      }
      result.append(translator.translateToPrint(word.subword(start, start + take)));
      start += take;
    }
    return result.toString();
  }

  /** 把 {@code word[start, start + count)} 转成表内使用的点位串，如 {@code 125-35-1}。 */
  private static String dots(BrailleWord word, int start, int count) {
    return word.subword(start, start + count).toString();
  }
}
