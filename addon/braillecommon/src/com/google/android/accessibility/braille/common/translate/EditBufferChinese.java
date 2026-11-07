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
import android.text.TextUtils;
import android.util.Log;
import com.google.android.accessibility.braille.common.BrailleCommonUtils;
import com.google.android.accessibility.braille.common.ImeConnection;
import com.google.android.accessibility.braille.common.R;
import com.google.android.accessibility.braille.common.TalkBackSpeaker;
import com.google.android.accessibility.braille.interfaces.BrailleCharacter;
import com.google.android.accessibility.braille.interfaces.BrailleWord;
import com.google.android.accessibility.braille.translate.BrailleTranslator;
import java.util.Collections;
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
 *
 * <p><b>同音字与候选</b>：一个合法音节平均对应 15.6 个汉字（1343 个音节 / 20974 条定义），
 * 87.8% 的音节有多候选。所以"译出一个字"只是猜中最常见的那个，不一定是用户要的。这里保留
 * 一个候选态：尾部音节刚凑满时，{@link #moveToNextCandidate} 可以在候选之间移动，输入框里的
 * 字实时跟着换。用户不换就直接打下一格或按空格，当前选中的字自然落定——<b>确认是隐式的，
 * 不需要多按一下</b>；需要选字时才有额外操作。
 *
 * <p>没有独立"确认"手势是有意的：盲文键盘上没有空格键，"空格"就是单指右滑，而那是打字最高频
 * 的动作之一，不能拿来当确认键。
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

  /**
   * 用户在尾部音节上选定的候选，以及前后移动逻辑。
   *
   * <p>为什么按"起始格下标"记：每次落键都会把整串 holdings 重新切分一遍，如果不记住用户的
   * 选择，第二次切分就会退回候选 0，用户刚换的字当场跳回去。前缀音节的起始下标在追加输入
   * 时不会变，所以这个 key 是稳定的。
   */
  private final CandidateCursor candidateCursor = new CandidateCursor();

  /** 最近一次切分里最后一个完整音节的起始格下标；{@code -1} 表示尾部没有完整音节。 */
  private int lastCandidateStart = -1;

  /** 上一个完整音节的候选列表（已按字频排序）；空列表表示尾部没有可换字的音节。 */
  private List<String> lastCandidates = Collections.emptyList();

  /** 上一个完整音节当前选中的候选序号。 */
  private int lastChosenIndex;

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
    // 每次重算都先复位"尾部音节"三元组：下面只有在真的切出完整音节时才会重新赋值，
    // 留在上一轮的值会让"换候选"作用在已经不属于尾部的音节上。
    lastCandidateStart = -1;
    lastCandidates = Collections.emptyList();
    lastChosenIndex = 0;
    // 删字/提交让 holdings 变短后，落在已不存在区间里的候选选择要丢掉。
    candidateCursor.trimTo(word.size());

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
    // 尾部最后一个单元是不是"完整音节"。只有它是，才有候选态可换字：尾部是标点、数字或
    // 半截音节时，换候选就会改到前面早已定好的字上，用户会以为"删掉了一个字"。
    boolean tailIsSyllable = false;
    final int size = word.size();
    while (start < size) {
      int remaining = size - start;
      BrailleCharacter cell = word.get(start);
      tailIsSyllable = false;

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
        appendResolvedSyllable(resolved, word, start, 3);
        tailIsSyllable = true;
        trace.append('[').append(dots(word, start, 3)).append("=3]");
        start += 3;
        continue;
      }
      if (remaining >= 2 && index.hasCandidate(dots(word, start, 2))) {
        appendResolvedSyllable(resolved, word, start, 2);
        tailIsSyllable = true;
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

      // 首格不能作音节首格：可能是标点/符号，也可能是用户把一格的几个点分开按了（成了多格）。
      // 中文表里 {@code digit 1 1} 是无条件定义，非数字模式下的单格会被反译成数字——但那个
      // "数字"并不是用户想输入的数字，只是表在缺上下文时的默认反译。若把它显示出去，用户会
      // 以为这一格已经定死，后面的格再也拼不成字。所以只播报点位、不产出字符。
      String single = translator.translateToPrint(word.subword(start, start + 1));
      if (isDigitsOnly(single)) {
        trace.append("[pending-digit:").append(dots(word, start, 1)).append(']');
        pending = remaining;
        break;
      }
      resolved.append(single);
      trace.append('[').append(dots(word, start, 1)).append("=1]");
      start++;
    }
    invalidTail = invalid;
    pendingCells = pending;
    if (!tailIsSyllable) {
      // 尾部不是完整音节：没有候选态。这里必须清掉 appendResolvedSyllable 在中间某轮留下的
      // 下标，否则"打完一个字又开始打下一个字"时，左滑会去换前一个字的候选。
      lastCandidateStart = -1;
    }
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

  /**
   * 半截音节不产出任何文本，必须播报点位。
   *
   * <p>上游的"逐字符回显"开关在文本框 + {@code announceType == SILENCE} 时是关的（因为一格一
   * 字的语言里文本上屏就会被读出来）。中文盲文在凑满音节之前输入框<b>根本不变</b>，若再挡掉
   * 点位播报，用户按下去就既没有声音也没有字——只能判断成"键盘坏了"。实测 63 个单格里 55 个
   * 属于"音节首格"，都会被挂起，所以这条不是边缘情况，而是主路径。
   */
  @Override
  protected boolean shouldEmitDotAnnouncement(
      ImeConnection imeConnection, String currentTranslation) {
    return TextUtils.isEmpty(currentTranslation)
        || super.shouldEmitDotAnnouncement(imeConnection, currentTranslation);
  }

  /**
   * 把一个完整音节译成汉字并追加到 {@code resolved}，同时把"尾部音节"三元组更新到这个音节。
   *
   * <p>取哪个字由 {@link #candidateChoices} 决定：没有记录（用户还没换过）取排序后的第一个，
   * 也就是字频最高的常用字；换过就取用户选定的那个。这样追加下一格触发整串重算时，用户先前
   * 的选择不会被打回原形。
   */
  private void appendResolvedSyllable(
      StringBuilder resolved, BrailleWord word, int start, int count) {
    List<String> candidates = index.getCandidates(dots(word, start, count));
    if (candidates.isEmpty()) {
      // 索引里没有（理论上 hasCandidate 已经挡掉），退回翻译器，保证不吞字。
      resolved.append(translator.translateToPrint(word.subword(start, start + count)));
      return;
    }
    int chosen = candidateCursor.indexFor(start, candidates.size());
    resolved.append(candidates.get(chosen));
    lastCandidateStart = start;
    lastCandidates = candidates;
    lastChosenIndex = chosen;
  }

  @Override
  public boolean moveToNextCandidate(ImeConnection imeConnection) {
    return shiftCandidate(imeConnection, /* delta= */ 1);
  }

  @Override
  public boolean moveToPreviousCandidate(ImeConnection imeConnection) {
    return shiftCandidate(imeConnection, /* delta= */ -1);
  }

  /**
   * 把尾部音节的候选前后移动一格并刷新 composing，成功返回 {@code true}。
   *
   * <p>返回 {@code false} 的三种情况都必须让调用方回落到原动作，否则用户会觉得手势丢了：
   *
   * <ul>
   *   <li>不在普通文本框里（密码框/composing 会被直接提交，换字没有意义）
   *   <li>尾部不是完整音节（还在半路上，没有候选可换）
   *   <li>候选只有一个（唯一解，换了也是同一个字）
   * </ul>
   */
  private boolean shiftCandidate(ImeConnection imeConnection, int delta) {
    if (!BrailleCommonUtils.isTextField(imeConnection.editorInfo)
        || BrailleCommonUtils.isPasswordField(imeConnection.editorInfo)) {
      return false;
    }
    // 先重算一次：holdingsEndsWithPrefix() 之类的试探性翻译会把三元组留在别的子串上，
    // 不刷新就可能对着一个早已不属于尾部的音节换字。
    translateHoldings(holdings);
    if (lastCandidateStart < 0 || lastCandidates.size() <= 1) {
      return false;
    }
    // 拷到局部：紧接着的第二次 translateHoldings 会重新赋值这两个字段，播报和日志必须用
    // "决定这一跳时"的那份数据，否则可能对着另一个音节念字，甚至下标越界。
    final int start = lastCandidateStart;
    final List<String> candidates = lastCandidates;
    int next = candidateCursor.shift(start, lastChosenIndex, candidates.size(), delta);
    String picked = candidates.get(next);

    String text = translateHoldings(holdings);
    imeConnection.inputConnection.setComposingText(text, 1);
    Log.d(
        SPLIT_TAG,
        "candidate start=" + start + " index=" + next + " of " + candidates.size()
            + " -> " + picked + " composing=[" + text + "]");
    talkBack.speak(
        context.getString(R.string.bb_candidate_selected, next + 1, picked),
        TalkBackSpeaker.AnnounceType.INTERRUPT);
    return true;
  }

  /**
   * 音节凑满时补一句候选数。
   *
   * <p>中文同音字平均 15.6 个，光念出打中的那个字会让用户以为这就是唯一解 —— 他看不见输入框，
   * 不报候选数就永远不会去换。候选只有一个时不加这句，免得每次都是废话。
   */
  @Override
  protected String getResolvedTranslationAnnouncement(String resolvedText) {
    if (lastCandidateStart < 0 || lastCandidates.size() <= 1) {
      return resolvedText;
    }
    return context.getString(
        R.string.bb_candidate_available, resolvedText, lastCandidates.size());
  }

  /** 把 {@code word[start, start + count)} 转成表内使用的点位串，如 {@code 125-35-1}。 */
  private static String dots(BrailleWord word, int start, int count) {
    return word.subword(start, start + count).toString();
  }

  /** 译文是否**全部**是数字字符（含全角数字）。用于识别"非数字模式下的假数字"。 */
  private static boolean isDigitsOnly(String text) {
    if (text.isEmpty()) {
      return false;
    }
    for (int i = 0; i < text.length(); i++) {
      if (!Character.isDigit(text.charAt(i))) {
        return false;
      }
    }
    return true;
  }
}
