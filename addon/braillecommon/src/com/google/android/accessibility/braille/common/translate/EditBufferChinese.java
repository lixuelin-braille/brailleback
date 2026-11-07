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
import android.content.res.Resources;
import android.util.Log;
import com.google.android.accessibility.braille.common.ImeConnection;
import com.google.android.accessibility.braille.common.R;
import com.google.android.accessibility.braille.common.TalkBackSpeaker;
import com.google.android.accessibility.braille.interfaces.BrailleCharacter;
import com.google.android.accessibility.braille.interfaces.BrailleWord;
import com.google.android.accessibility.braille.translate.BrailleTranslator;
import java.util.List;
import java.util.Optional;

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

  private final Context context;

  private final TalkBackSpeaker talkBack;

  /**
   * 最近一次 {@link #translateHoldings} 的尾部是否为表内不存在的盲文组合。
   *
   * <p>与"尚未凑满音节"必须分开：前者是用户按错了格，必须立刻告知；后者是正常输入中间态，
   * 保持安静等下一格即可。合并处理就会变成"按错一格后键盘彻底失声"。
   */
  private boolean invalidTail;

  /** 尾部还有几格没被翻译成字符（未完成的音节）。按空格提交时这部分会被丢弃，需要告知。 */
  private int pendingCells;

  private EditBufferChinese(
      Context context,
      BrailleTranslator translator,
      TalkBackSpeaker talkBack,
      ChineseBrailleTableIndex index) {
    super(context, translator, talkBack);
    this.index = index;
    this.context = context;
    this.talkBack = talkBack;
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
      invalidTail = false;
      pendingCells = 0;
      return "";
    }
    StringBuilder resolved = new StringBuilder();
    StringBuilder trace = new StringBuilder();
    int start = 0;
    boolean numericMode = false;
    boolean invalid = false;
    int pending = 0;
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
        resolved.append(firstCandidate(word, start, 3));
        trace.append('[').append(dots(word, start, 3)).append("=3]");
        start += 3;
        continue;
      }
      if (remaining >= 2 && index.hasCandidate(dots(word, start, 2))) {
        resolved.append(firstCandidate(word, start, 2));
        trace.append('[').append(dots(word, start, 2)).append("=2]");
        start += 2;
        continue;
      }

      // 走到这里说明余下的格凑不成一个完整音节，只剩两种可能：还在半路上，或者拼错了。
      if (index.canStartSyllable(dots(word, start, 1))) {
        if (remaining == 1) {
          // 只有一格、且它能作音节首格：挂起等后续格。此时绝不能翻译——中文表里
          // digit 1 1 是无条件定义，单格会被翻成数字，看起来像已经上屏。
          trace.append("[pending:").append(dots(word, start, 1)).append(']');
          pending = remaining;
          break;
        }
        if (remaining == 2 && index.isThreeCellPrefix(dots(word, start, 2))) {
          // 这 2 格自身不是音节，但能接声调凑成 3 格音节：继续挂起。
          trace.append("[pending:").append(dots(word, start, 2)).append(']');
          pending = remaining;
          break;
        }
        // 首格是合法音节首格，后面接的格却既不成音节、也不是任何音节的前缀：
        // 这串盲文在表里根本不存在。既不能当数字吐出去（用户会以为已经上屏），
        // 也不能静默吞掉（用户会以为键盘坏了），标记出来交给上层播报。
        invalid = true;
        trace.append("[invalid:").append(dots(word, start, remaining)).append(']');
        pending = remaining;
        break;
      }

      // 首格不能作音节首格 → 标点、符号等，按原样逐格交给翻译表。
      resolved.append(translator.translateToPrint(word.subword(start, start + 1)));
      trace.append('[').append(dots(word, start, 1)).append("=1]");
      start++;
    }
    invalidTail = invalid;
    pendingCells = pending;
    Log.d(SPLIT_TAG, "holdings=" + word + " split=" + trace + " out=[" + resolved + "]");
    return resolved.toString();
  }

  /**
   * 空格提交时，未完成的音节会被 {@code commit()} 直接丢弃。用户是盲人，看不到输入框，
   * 如果不提示，他会以为自己打的字已经上去了——实际一个字都没留下。
   */
  @Override
  public void appendSpace(ImeConnection imeConnection) {
    if (pendingCells > 0) {
      talkBack.speak(
          context.getString(R.string.bb_incomplete_braille_ignored),
          TalkBackSpeaker.AnnounceType.INTERRUPT);
    }
    super.appendSpace(imeConnection);
  }

  /**
   * 尾部盲文组合不存在时播报提示，让用户立刻知道是自己按错了格，而不是键盘没反应。
   *
   * <p>父类只在译文为空时走这个分支，正好是非法组合的场景（没有产出任何字符）。
   */
  @Override
  protected Optional<String> getAppendBrailleTextToSpeak(
      Resources resources, BrailleCharacter brailleCharacter) {
    if (invalidTail) {
      return Optional.of(resources.getString(R.string.bb_invalid_braille_combination));
    }
    return super.getAppendBrailleTextToSpeak(resources, brailleCharacter);
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

  /**
   * 取该音节的输出字。
   *
   * <p>用索引里排序后的第一个候选（GB2312 一级字优先），而不是直接信 liblouis 的反译结果：
   * 后者按 Unicode 码位顺序返回，实测只有 38.9% 的音节首选是常用字，其余先吐出"玼、趀、偨"
   * 这类生僻字，用户就以为"打出来的不是字"。索引不可用时才回退到翻译器。
   */
  private String firstCandidate(BrailleWord word, int start, int count) {
    List<String> candidates = index.getCandidates(dots(word, start, count));
    if (!candidates.isEmpty()) {
      return candidates.get(0);
    }
    return translator.translateToPrint(word.subword(start, start + count));
  }
}
