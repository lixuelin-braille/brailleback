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
import android.util.Log;
import com.google.android.accessibility.braille.common.TalkBackSpeaker;
import com.google.android.accessibility.braille.interfaces.BrailleCharacter;
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
 * <p>切分规则（从头开始，已确定部分不回溯）：
 *
 * <ol>
 *   <li>遇到数字符 {@code 3456} → 进入数字模式，它本身不产生字符，后续逐格翻成 0-9
 *   <li>剩余 ≥3 格且这 3 格是表内合法汉字 → 取 3 格（现行盲文带声调，3 格占 81%）
 *   <li>否则剩余 ≥2 格且这 2 格是表内合法汉字 → 取 2 格（零声母字、无调字）
 *   <li>否则剩余 ≤2 格且首格能作为某音节第一格 → <b>挂起</b>，不翻译
 *   <li>否则取 1 格，交给翻译表（标点、符号等）
 * </ol>
 *
 * <p>第 4 条是"数字看着像已经上屏、后面拼不起来"的根因修复。中文表里 {@code digit 1 1} 是无条件
 * 定义，单格会被直接翻成数字；如果把它当成品显示出去，用户就会认为这一格已经定死，后续格自然
 * 拼不成汉字。挂起后输入框只保留已经凑满的音节，凑满那一刻整串替换成汉字并播报。
 *
 * <p>残留歧义：若某个 2 格字 A 恰好与后面一格拼成合法的 3 格字 B，会优先取 2 格。这类歧义
 * 只有语言模型能解，当前版本接受此限制。
 */
public class EditBufferChinese extends EditBufferUnContracted {

  private static final String SPLIT_TAG = "BBSplit";

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
    StringBuilder resolved = new StringBuilder();
    StringBuilder trace = new StringBuilder();
    int start = 0;
    boolean numericMode = false;
    final int size = word.size();
    while (start < size) {
      int remaining = size - start;
      BrailleCharacter cell = word.get(start);

      // 数字符 3456：进入数字模式。它本身不产生字符（numsign 只切换状态），这里只置标志、
      // 不输出，避免把数字符翻成难以理解的符号。
      if (BrailleTranslateUtils.DOTS3456.equals(cell)) {
        numericMode = true;
        trace.append("[3456=numsign]");
        start++;
        continue;
      }

      // 数字模式：逐格翻成 0-9，直到按空格提交（appendSpace 会清空 holdings）。
      if (numericMode) {
        resolved.append(translator.translateToPrint(word.subword(start, start + 1)));
        trace.append('[').append(dots(word, start, 1)).append("=digit]");
        start++;
        continue;
      }

      if (remaining >= 3 && index.hasCandidate(dots(word, start, 3))) {
        resolved.append(translator.translateToPrint(word.subword(start, start + 3)));
        trace.append('[').append(dots(word, start, 3)).append("=3]");
        start += 3;
        continue;
      }
      if (remaining >= 2 && index.hasCandidate(dots(word, start, 2))) {
        resolved.append(translator.translateToPrint(word.subword(start, start + 2)));
        trace.append('[').append(dots(word, start, 2)).append("=2]");
        start += 2;
        continue;
      }

      // 余下的是"未完成音节"：只剩 1 格声母，或 2 格但还能接声调变成 3 格。
      // 这类绝对不能翻译成字符——单格会被表翻成数字，看着像已经上屏，用户就会以为
      // "后面的格子跟它拼不起来"。挂起等待后续格，输入框保持已确定的汉字不变。
      if (remaining <= 2 && index.canStartSyllable(dots(word, start, 1))) {
        trace.append("[pending:").append(dots(word, start, remaining)).append(']');
        break;
      }

      // 既凑不成音节、也不是数字模式（标点/符号等），交给表逐格翻译。
      resolved.append(translator.translateToPrint(word.subword(start, start + 1)));
      trace.append('[').append(dots(word, start, 1)).append("=1]");
      start++;
    }
    Log.d(SPLIT_TAG, "holdings=" + word + " split=" + trace + " out=[" + resolved + "]");
    return resolved.toString();
  }

  /**
   * 中文一个音节 2~3 格，译出汉字的那一刻输入框整串被替换，读屏不会主动读新出现的字，
   * 所以必须自己播报。否则用户只听到"点几"，看不到也听不到汉字真的出来了。
   */
  @Override
  protected boolean shouldSpeakResolvedTranslation() {
    return true;
  }

  /** 把 {@code word[start, start + count)} 转成表内使用的点位串，如 {@code 125-35-1}。 */
  private static String dots(BrailleWord word, int start, int count) {
    return word.subword(start, start + count).toString();
  }
}
