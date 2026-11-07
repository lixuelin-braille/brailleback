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
 * <p><b>两阶段：输入选字 → 上屏</b>。中文现行盲文一个合法音节平均对应 15.6 个汉字（1343 个
 * 音节 / 20974 条定义），87.8% 的音节有多候选，"译出一个字"只是猜中最常见的那个。所以照标准
 * 输入法的模型分成两步：
 *
 * <ol>
 *   <li><b>阶段 1（输入和选字）</b>：音节凑满时<b>不</b>把汉字写进输入框，只把选中的候选播报
 *       出来（"某字，多少候选"）。此时 {@link #moveToNextCandidate} 可以前后换字，用户听到的
 *       是"第 N 个，某字"，输入框一直是空的——这一点很关键：盲人看不见下划线，如果字提前
 *       出现，他无法分清"还没上屏"和"已经上屏"。
 *   <li><b>阶段 2（上屏）</b>：按空格（或提交）时先把选中的字并进 composing，再提交成文本，
 *       并播报"上屏"。打下一格时，前一个音节因为不再是最后一个，自动确认并进入 composing，
 *       这就是连续输入时不必每字按空格的原因。
 * </ol>
 *
 * <p>没有独立的"确认"手势是有意的：盲文键盘上没有空格键，"空格"就是单指右滑，而那是打字最高频
 * 的动作之一，不能拿来当确认键。确认复用空格，和标准中文输入法的"空格上屏"一致。
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

  /**
   * 已经确认过的最后一个音节的起始格下标；{@code -1} 表示一个字都还没确认。
   *
   * <p>"确认"是阶段 1（输入选字）和阶段 2（上屏）的分界：确认之前的字只在候选阶段，不写进
   * 输入框；确认之后才进 composing，按空格或提交时落成文本。追加新格时，前一个音节因为不再是
   * 最后一个，会被自动确认——这就是"自动确认上一个字"。
   */
  private int lastConfirmedSyllableStart = -1;

  /**
   * 当前输入框能不能做"选字"这一步。
   *
   * <p>密码框和非文本框没有 composing 可用，待确认的字根本无处安放，按空格时会被连同 holdings
   * 一起清掉，等于吞字。这两种场景直接退化成"凑满即落定"，不做两阶段。
   */
  private boolean supportsCandidateSelection = true;

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
    // 删字/提交让 holdings 变短后，落在已不存在区间里的候选选择要丢掉。
    candidateCursor.trimTo(word.size());
    if (lastConfirmedSyllableStart >= word.size()) {
      // holdings 已经短到不包括那个音节了，确认标记随之失效。
      lastConfirmedSyllableStart = -1;
    }
    SyllableSplitter.Result result =
        SyllableSplitter.split(
            word,
            index,
            translator,
            candidateCursor,
            lastConfirmedSyllableStart,
            supportsCandidateSelection);
    lastCandidateStart = result.pendingStart;
    lastCandidates = result.pendingCandidates;
    lastChosenIndex = result.pendingChoice;
    pendingCells = result.pendingCells;
    invalidTail = result.invalidTail;
    Log.d(
        SPLIT_TAG,
        "holdings="
            + word
            + " split="
            + result.trace
            + " out=["
            + result.confirmedText
            + "] pending="
            + result.pendingStart);
    return result.confirmedText;
  }

  /**
   * 空格 = 阶段 2 的"上屏"。
   *
   * <p>必须做两件事：先把待确认音节的选字结果并进 composing（否则 commit 时它根本不在文本里，
   * 会被直接丢掉），再给一次明确的可听反馈。用户看不见输入框，选字阶段听到的是"某字、多少候选"，
   * 上屏如果没有声音，就分不清"已经落进文本"和"还在选"，两阶段就白设计了。
   *
   * <p>半截音节会在提交时被丢弃，这条提示和"上屏"合并成一句播报：两次 INTERRUPT 会互相打断，
   * 后一句把前一句顶掉，用户反而只听到一半。
   */
  @Override
  public void appendSpace(ImeConnection imeConnection) {
    StringBuilder announcement = new StringBuilder();
    if (pendingCells > 0) {
      announcement.append(context.getString(R.string.bb_incomplete_braille_ignored)).append('，');
    }
    confirmPendingSyllable(imeConnection);
    super.appendSpace(imeConnection);
    announcement.append(context.getString(R.string.bb_text_committed));
    talkBack.speak(announcement.toString(), TalkBackSpeaker.AnnounceType.INTERRUPT);
  }

  /**
   * 上屏前必须先确认待选字。
   *
   * <p>提交、移动光标、切换应用都会走 {@code commit()}，那里会清空 holdings 并结束 composing。
   * 待确认的字此时还没进 composing，不先并进去就等于被静默吞掉。
   */
  @Override
  public void commit(ImeConnection imeConnection) {
    confirmPendingSyllable(imeConnection);
    super.commit(imeConnection);
  }

  /**
   * 把待确认音节的选字结果并进 composing（阶段 1 → 阶段 2 的分界线）。
   *
   * <p>只推进"确认到哪一格"并刷新 composing，不提交文本；提交交给调用方，这样"打下一格自动
   * 确认"和"按空格上屏"复用同一段逻辑。已经确认过时原样返回，可以安全重复调用。
   */
  private void confirmPendingSyllable(ImeConnection imeConnection) {
    if (lastCandidateStart < 0) {
      return;
    }
    lastConfirmedSyllableStart = lastCandidateStart;
    lastCandidateStart = -1;
    refreshComposing(imeConnection);
  }

  /** 用最新译文刷新 composing；密码框和非文本框没有 composing 可写，跳过。 */
  private void refreshComposing(ImeConnection imeConnection) {
    if (!BrailleCommonUtils.isTextField(imeConnection.editorInfo)
        || BrailleCommonUtils.isPasswordField(imeConnection.editorInfo)) {
      return;
    }
    imeConnection.inputConnection.setComposingText(translateHoldings(holdings), 1);
  }

  /**
   * 记录当前输入框能不能走两阶段。
   *
   * <p>必须在 {@code super.appendBraille} 之前设好：{@link #translateHoldings} 要靠它决定
   * 要不要把最后一个音节挂在"待确认"上，而密码框里挂起就等于吞字。
   */
  @Override
  public String appendBraille(ImeConnection imeConnection, BrailleCharacter brailleCharacter) {
    supportsCandidateSelection =
        BrailleCommonUtils.isTextField(imeConnection.editorInfo)
            && !BrailleCommonUtils.isPasswordField(imeConnection.editorInfo);
    return super.appendBraille(imeConnection, brailleCharacter);
  }

  /**
   * 尾部盲文组合不存在时播报提示；音节刚凑满时播报候选列表。
   *
   * <p>候选这条是两阶段的关键提示：字还在选字阶段、没进输入框，如果只播点位，用户会以为
   * 什么都没发生。
   *
   * <p>播报内容必须是<b>候选本身</b>，不能只说"共 N 个"：盲人看不见候选栏，不知道能选什么
   * 就永远不会去换，那个"85 个候选"的提示等于零信息。这里按输入法候选栏的方式念一小段
   * （见 {@link CandidateAnnouncer}），被截断时才补总数。
   */
  @Override
  protected Optional<String> getAppendBrailleTextToSpeak(
      Resources resources, BrailleCharacter brailleCharacter) {
    if (invalidTail) {
      return Optional.of(resources.getString(R.string.bb_invalid_braille_combination));
    }
    if (lastCandidateStart >= 0 && lastCandidates.size() > 1) {
      return Optional.of(describeCandidates());
    }
    return super.getAppendBrailleTextToSpeak(resources, brailleCharacter);
  }

  /** 拼出"候选 你，尼，呢"，候选被截断时补"共 N 个"。 */
  private String describeCandidates() {
    String window =
        CandidateAnnouncer.window(
            lastCandidates,
            lastChosenIndex,
            CandidateAnnouncer.WINDOW,
            context.getString(R.string.bb_candidate_separator));
    if (CandidateAnnouncer.hasMore(lastCandidates, lastChosenIndex, CandidateAnnouncer.WINDOW)) {
      return context.getString(R.string.bb_candidate_list_more, window, lastCandidates.size());
    }
    return context.getString(R.string.bb_candidate_list_all, window);
  }

  /**
   * 尾部音节<b>直接落定</b>时播报新出现的字（唯一解的情况）。
   *
   * <p>为什么需要：中文里约 12% 的音节只有唯一候选（"的"=145-26 就只有一个字），按设计
   * 不进候选态、直接落定。这时如果既不播候选也不播字，用户按完最后一点后<b>什么也听不到</b>，
   * 只能反复猜自己打出了什么——他会把这种情况理解成"没有候选"。
   *
   * <p>为什么不能一律播报：追加下一格时，前一个音节会被自动确认进 composing，这时念上一个字
   * 会盖掉新音节的点位播报，变成每打一字都重复一遍。区分方法是看尾部有没有待处理的内容：
   * 有 {@code lastCandidateStart}（候选态）或 {@code pendingCells}（半截音节）时说明用户已经
   * 在打下一个音节，闭嘴；否则就是这一格刚刚独立凑成一个字，必须报出来。
   */
  @Override
  protected boolean shouldSpeakResolvedTranslation() {
    return lastCandidateStart < 0 && pendingCells == 0;
  }

  /**
   * 半截音节不产出任何文本，必须播报点位。
   *
   * <p>上游的"逐字符回显"开关在文本框 + {@code announceType == SILENCE} 时是关的（因为一格一
   * 字的语言里文本上屏就会被读出来）。中文盲文在凑满音节之前输入框<b>根本不变</b>，若再挡掉
   * 点位播报，用户按下去就既没有声音也没有字——只能判断成"键盘坏了"。实测 63 个单格里 55 个
   * 属于"音节首格"，都会被挂起，所以这条不是边缘情况，而是主路径。
   *
   * <p>有候选待确认时同理必须放行：那时 composing 里只有已经确认的字，这一格本身没有产出，
   * 挡掉播报就会变成"按下去了、什么也没说"。
   */
  @Override
  protected boolean shouldEmitDotAnnouncement(
      ImeConnection imeConnection, String currentTranslation) {
    return lastCandidateStart >= 0
        || TextUtils.isEmpty(currentTranslation)
        || super.shouldEmitDotAnnouncement(imeConnection, currentTranslation);
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
   * 把尾部音节的候选前后移动一格，成功返回 {@code true}。
   *
   * <p>调用方是上下滑（原本是移动光标）：候选是一个列表，上下滑动就是列表上下滚动，方向符合
   * 直觉。刻意不占用左右滑——单指左滑是删字、单指右滑是空格上屏，候选态下这两个动作仍然必需
   * （删掉打错的格、确认上屏），借走它们会让用户在最需要的时候找不到。
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
    if (lastCandidateStart < 0) {
      return false;
    }
    if (lastCandidates.size() <= 1) {
      return false;
    }
    // 拷到局部：后续字段还可能被别的路径改写，播报和日志必须用"决定这一跳时"的那份数据，
    // 否则可能对着另一个音节念字，甚至下标越界。
    final int start = lastCandidateStart;
    final List<String> candidates = lastCandidates;
    int next = candidateCursor.shift(start, lastChosenIndex, candidates.size(), delta);
    lastChosenIndex = next;
    String picked = candidates.get(next);
    // 两阶段下这个字还没进 composing（要等确认才写），所以换候选只需要更新选择，界面不用动。
    // 用户靠这句播报确认换到了哪个字。
    Log.d(
        SPLIT_TAG,
        "candidate start=" + start + " index=" + next + " of " + candidates.size()
            + " -> " + picked);
    talkBack.speak(
        context.getString(R.string.bb_candidate_selected, next + 1, picked),
        TalkBackSpeaker.AnnounceType.INTERRUPT);
    return true;
  }

  /**
   * 中文按"格"删，不按输入框字符删。
   *
   * <p>父类的实现是"删一格 holdings + 给输入框发一次删除键"。这在一格等于一个字符的语言里成立，
   * 中文不成立：3 格凑成一个汉字，删 1 格却会把输入框里那 1 个汉字一并删掉，剩下的 2 格下次
   * 刷新 composing 时又会翻出别的字——用户看到的就是"删一下，字没了又冒出来一个"。
   *
   * <p>所以这里只动 holdings 再整体刷新 composing，输入框的删除只在 holdings 已经空了
   * （要删的是更早提交进文本的字）时才交给父类。
   */
  @Override
  public void deleteCharacterBackward(ImeConnection imeConnection) {
    if (holdings.isEmpty()) {
      super.deleteCharacterBackward(imeConnection);
      return;
    }
    holdings.remove(holdings.size() - 1);
    lastCommitIndexOfHoldings = holdings.size();
    refreshComposing(imeConnection);
    talkBack.speak(describeRemainingInput(), TalkBackSpeaker.AnnounceType.INTERRUPT);
  }

  /**
   * 删掉一格之后告诉用户还剩什么。
   *
   * <p>两阶段下被删的那一格如果属于待确认音节，它本来就没进输入框，删完输入框不会有任何变化。
   * 不给一句反馈，用户按了左滑却什么也没听到，只能判断成"删不掉"。
   */
  /** 删掉一格之后告诉用户还剩什么。 */
  private String describeRemainingInput() {
    if (lastCandidateStart >= 0 && lastCandidates.size() > 1) {
      return describeCandidates();
    }
    return context.getString(R.string.bb_deleted);
  }
}
