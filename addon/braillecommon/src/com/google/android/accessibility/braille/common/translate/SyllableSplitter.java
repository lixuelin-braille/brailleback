/*
 * Copyright 2026 The BrailleBack Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.google.android.accessibility.braille.common.translate;

import com.google.android.accessibility.braille.interfaces.BrailleCharacter;
import com.google.android.accessibility.braille.interfaces.BrailleWord;
import com.google.android.accessibility.braille.translate.BrailleTranslator;
import java.util.Collections;
import java.util.List;

/**
 * 把中文盲文的 holdings 切成"已确认前缀"和"尾部待确认音节"。
 *
 * <p>这是 {@link EditBufferChinese} 两阶段模型的核心：
 *
 * <ul>
 *   <li><b>已确认前缀</b> → 写进 composing，用户按空格时落成文本
 *   <li><b>待确认音节</b> → 不写进 composing，只在候选阶段播报；用户换字、确认后才进前缀
 * </ul>
 *
 * <p>为什么要分成两段：中文盲文一个音节平均 15.6 个同音字，译出来的字只是"猜得最像的那个"。
 * 如果立刻写进输入框，用户看不见下划线，就无法区分"这字还没定"和"这字已经进去了"。
 *
 * <p>刻意不依赖 Android（Context/Resources/InputConnection），只吃 {@link BrailleWord} 和
 * 索引，因为切分是整个输入链路里最容易出错的一环——吞字、重复字、前面已定的字被改写都发生在
 * 这里，必须在 JVM 单测里能直接验证。
 */
final class SyllableSplitter {

  /**
   * 中文表的数字符（第四五六点）。
   *
   * <p>不用 {@code BrailleTranslateUtils.DOTS3456}：那个类初始化时会加载 Android 资源，把它
   * 拖进来会让本类在 JVM 单测里直接 {@code ExceptionInInitializerError}，而切分逻辑恰恰是最
   * 需要能单测的一段。点位值本身就是表里写死的约定，另外有测试盯着它。
   */
  private static final BrailleCharacter NUMBER_SIGN = new BrailleCharacter("3456");

  private SyllableSplitter() {}

  /** 一次切分的结果。字段全部只读，调用方按需取用。 */
  static final class Result {
    /** 已确认、可以写进 composing 的文本。 */
    final String confirmedText;

    /** 待确认音节的起始格下标；{@code -1} 表示没有待确认音节。 */
    final int pendingStart;

    /** 待确认音节的候选列表（已按字频排序）；没有待确认音节时为空列表。 */
    final List<String> pendingCandidates;

    /** 待确认音节当前选中的候选序号。 */
    final int pendingChoice;

    /** 尾部没凑成一个字的格数（半截音节）；按空格提交时这部分会被丢弃，需要告知用户。 */
    final int pendingCells;

    /** 尾部盲文组合在翻译表里根本不存在。 */
    final boolean invalidTail;

    /** 切分过程的诊断轨迹，只在日志里用。 */
    final String trace;

    Result(
        String confirmedText,
        int pendingStart,
        List<String> pendingCandidates,
        int pendingChoice,
        int pendingCells,
        boolean invalidTail,
        String trace) {
      this.confirmedText = confirmedText;
      this.pendingStart = pendingStart;
      this.pendingCandidates = pendingCandidates;
      this.pendingChoice = pendingChoice;
      this.pendingCells = pendingCells;
      this.invalidTail = invalidTail;
      this.trace = trace;
    }

    boolean hasPending() {
      return pendingStart >= 0;
    }
  }

  /**
   * 切分 holdings。
   *
   * @param word 累积的盲文格
   * @param index 音节索引（点位串 → 同音字候选）
   * @param translator 兜底翻译器，用于标点、数字和索引里查不到的格
   * @param cursor 候选选择记录，读它决定每个音节取哪个字
   * @param lastConfirmedStart 已经确认过的最后一个音节起始下标；{@code -1} 表示还没确认过任何字
   * @param allowPending 是否允许把尾部音节挂在"待确认"上。密码框和非文本框没有 composing 可用，
   *     挂起会导致按空格时字被连同 holdings 一起清掉，所以那里必须传 {@code false}
   */
  static Result split(
      BrailleWord word,
      ChineseBrailleTableIndex index,
      BrailleTranslator translator,
      CandidateCursor cursor,
      int lastConfirmedStart,
      boolean allowPending) {
    if (word.isEmpty()) {
      return new Result("", -1, Collections.emptyList(), 0, 0, false, "");
    }

    StringBuilder resolved = new StringBuilder();
    StringBuilder trace = new StringBuilder();
    int start = 0;
    boolean numericMode = false;
    boolean invalid = false;
    int pendingCells = 0;
    // 切分是单遍从前往后做的，走到某个音节时还不知道它后面还有没有内容，也就无法判断它是不是
    // "最后一个、要等用户确认上屏"的那一个。把最近一个音节压在这里，等后面出现了别的内容
    // （数字、标点、半截音节）或者循环结束，再决定它是"已确认"还是"待确认"。
    HeldSyllable held = null;
    final int size = word.size();

    while (start < size) {
      int remaining = size - start;
      BrailleCharacter cell = word.get(start);

      // 数字符 3456：进入数字模式。它本身不产生字符（numsign 只切换状态），这里只置标志、
      // 不输出，避免把数字符翻成难以理解的符号。
      if (NUMBER_SIGN.equals(cell)) {
        held = flushHeld(resolved, held);
        numericMode = true;
        trace.append("[3456=numsign]");
        start++;
        continue;
      }

      // 数字模式：逐格翻成 0-9，直到按空格提交（appendSpace 会清空 holdings）。
      if (numericMode) {
        held = flushHeld(resolved, held);
        resolved.append(translator.translateToPrint(word.subword(start, start + 1)));
        trace.append('[').append(dots(word, start, 1)).append("=digit]");
        start++;
        continue;
      }

      if (remaining >= 3 && index.hasCandidate(dots(word, start, 3))) {
        held = flushHeld(resolved, held);
        held = buildSyllable(word, start, 3, index, translator, cursor);
        trace.append('[').append(held.dots).append("=3]");
        start += 3;
        continue;
      }
      if (remaining >= 2 && index.hasCandidate(dots(word, start, 2))) {
        held = flushHeld(resolved, held);
        held = buildSyllable(word, start, 2, index, translator, cursor);
        trace.append('[').append(held.dots).append("=2]");
        start += 2;
        continue;
      }

      // 后面接的既不是音节也不是数字：压着的那个音节一定不是最后一个，先落定。
      held = flushHeld(resolved, held);

      // 走到这里说明余下的格凑不成一个完整音节，只剩两种可能：还在半路上，或者拼错了。
      if (index.canStartSyllable(dots(word, start, 1))) {
        if (remaining == 1) {
          // 只有一格、且它能作音节首格：挂起等后续格。此时绝不能翻译——中文表里
          // digit 1 1 是无条件定义，单格会被翻成数字，看起来像已经上屏。
          trace.append("[pending:").append(dots(word, start, 1)).append(']');
          pendingCells = remaining;
          break;
        }
        if (remaining == 2 && index.isThreeCellPrefix(dots(word, start, 2))) {
          // 这 2 格自身不是音节，但能接声调凑成 3 格音节：继续挂起。
          trace.append("[pending:").append(dots(word, start, 2)).append(']');
          pendingCells = remaining;
          break;
        }
        // 首格是合法音节首格，后面接的格却既不成音节、也不是任何音节的前缀：
        // 这串盲文在表里根本不存在。既不能当数字吐出去（用户会以为已经上屏），
        // 也不能静默吞掉（用户会以为键盘坏了），标记出来交给上层播报。
        invalid = true;
        trace.append("[invalid:").append(dots(word, start, remaining)).append(']');
        pendingCells = remaining;
        break;
      }

      // 首格不能作音节首格：可能是标点/符号，也可能是用户把一格的几个点分开按了（成了多格）。
      // 单格能不能直接产出字符，必须严格判断，见 isStandaloneSingleCell()。
      String single = translator.translateToPrint(word.subword(start, start + 1));
      if (!isStandaloneSingleCell(single)) {
        trace.append("[pending-single:").append(dots(word, start, 1)).append(']');
        pendingCells = remaining;
        break;
      }
      resolved.append(single);
      trace.append('[').append(dots(word, start, 1)).append("=1]");
      start++;
    }

    int pendingStart = -1;
    List<String> pendingCandidates = Collections.emptyList();
    int pendingChoice = 0;
    if (held != null) {
      // 循环结束时还压着的就是最后一个完整音节。它要不要等用户确认，取决于两件事：是不是已经
      // 确认过（打下一格时前一个音节会被自动确认，这里就跳过），以及有没有第二个候选可选
      // （唯一解直接落定，省掉一次没有意义的选择）。
      if (allowPending && held.start > lastConfirmedStart && held.candidates.size() > 1) {
        pendingStart = held.start;
        pendingCandidates = held.candidates;
        pendingChoice = held.choice;
        trace.append("[awaiting-confirm:").append(held.dots).append(']');
      } else {
        resolved.append(held.text());
        trace.append('[').append(held.dots).append("=settled]");
      }
    }
    return new Result(
        resolved.toString(),
        pendingStart,
        pendingCandidates,
        pendingChoice,
        pendingCells,
        invalid,
        trace.toString());
  }

  /**
   * 算出一个完整音节的候选和当前选择，暂不决定去留。
   *
   * <p>索引里查不到时退回翻译器，并包成单元素候选列表——候选数为 1 会让它走"唯一解直接落定"，
   * 不会挂在待确认上，保证不吞字。
   */
  private static HeldSyllable buildSyllable(
      BrailleWord word,
      int start,
      int count,
      ChineseBrailleTableIndex index,
      BrailleTranslator translator,
      CandidateCursor cursor) {
    String dots = dots(word, start, count);
    List<String> candidates = index.getCandidates(dots);
    if (candidates.isEmpty()) {
      candidates =
          Collections.singletonList(translator.translateToPrint(word.subword(start, start + count)));
    }
    int chosen = cursor.indexFor(start, candidates.size());
    return new HeldSyllable(start, dots, candidates, chosen);
  }

  /** 落定一个被压着的音节（它已经不是最后一个了），返回 null 方便调用方直接赋值。 */
  private static HeldSyllable flushHeld(StringBuilder resolved, HeldSyllable held) {
    if (held != null) {
      resolved.append(held.text());
    }
    return null;
  }

  /** 切分过程中暂存的完整音节，用来判断它是"已确认"还是"等用户选完上屏"。 */
  private static final class HeldSyllable {
    final int start;
    final String dots;
    final List<String> candidates;
    final int choice;

    HeldSyllable(int start, String dots, List<String> candidates, int choice) {
      this.start = start;
      this.dots = dots;
      this.candidates = candidates;
      this.choice = choice;
    }

    String text() {
      return candidates.get(choice);
    }
  }

  /** 把 {@code word[start, start + count)} 转成表内使用的点位串，如 {@code 125-35-1}。 */
  private static String dots(BrailleWord word, int start, int count) {
    return word.subword(start, start + count).toString();
  }

  /**
   * 一个单格能不能直接产出字符。
   *
   * <p>中文表里单格的 {@code sign}（汉字）定义是 <b>0 条</b>，所有汉字都要 2~3 格；但表里同时
   * 带着 26 个 {@code lowercase}（a=点 1、b=点 12、k=点 13…）、26 个 {@code uppercase} 和
   * 一个 {@code capsletter 6}（点 6 = 大写符）。这些是英文输入的残留定义，一旦有格落单，
   * liblouis 就会按它们反译，吐出全角字母或大写符。
   *
   * <p>把它写进输入框的后果是读屏念"大写""全角 a"，用户完全无法理解发生了什么——他按的是
   * 一个汉字的头几个点，屏幕上却出现了别的东西。所以单格只允许产出标点/符号，其余一律挂起，
   * 等后续格把它补成完整音节。
   */
  private static boolean isStandaloneSingleCell(String text) {
    if (text.isEmpty()) {
      return false;
    }
    for (int i = 0; i < text.length(); i++) {
      char ch = text.charAt(i);
      if (isCjk(ch)) {
        // 汉字是正常内容（表里单格没有汉字定义，这里只是防御）。
        continue;
      }
      if (Character.isLetterOrDigit(ch)) {
        // 全角/半角字母、数字：前者来自 lowercase/uppercase，后者来自无条件 digit。
        return false;
      }
      if (ch >= 0xE000 && ch <= 0xF8FF) {
        // 私用区：表里的 \xE2xx 段是盲文内部字符，读屏会念成乱码。
        return false;
      }
      if (Character.isISOControl(ch)) {
        return false;
      }
    }
    return true;
  }

  private static boolean isCjk(char ch) {
    return (ch >= 0x4E00 && ch <= 0x9FFF) || (ch >= 0x3400 && ch <= 0x4DBF);
  }
}
