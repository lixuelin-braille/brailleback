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

package com.google.android.accessibility.braille.common.translate

import android.content.Context
import android.content.res.Resources
import android.text.TextUtils
import android.util.Log
import com.google.android.accessibility.braille.common.BrailleCommonUtils
import com.google.android.accessibility.braille.common.ImeConnection
import com.google.android.accessibility.braille.common.R
import com.google.android.accessibility.braille.common.TalkBackSpeaker
import com.google.android.accessibility.braille.interfaces.BrailleCharacter
import com.google.android.accessibility.braille.interfaces.BrailleWord
import com.google.android.accessibility.braille.translate.BrailleTranslator
import java.util.Optional

/**
 * 中文现行盲文（带声调）的 [EditBuffer]。
 *
 * 与通用的 [EditBufferUnContracted] 唯一区别是翻译策略：不把整串 holdings 一次性丢给
 * liblouis，而是先按合法音节切分，再逐段翻译拼接。
 *
 * 为什么必须切：中文一个音节 2~3 格，liblouis 整串反译按规则长度匹配，连续输入时
 * 2格+2格 会被切成 3格+1格（出"一个汉字 + 一个数字"），而且每追加一格都会让
 * 前面的字重新分词、在输入框里跳变。按音节切分后，已确定的前缀不会再被改写。
 *
 * 切分规则（从头开始，已确定部分不回溯）：
 *
 * 1. 遇到数字符 3456 → 进入数字模式，它本身不产生字符，后续逐格翻成 0-9
 * 2. 剩余 ≥3 格且这 3 格是表内合法汉字 → 取 3 格（现行盲文带声调，3 格占 81%）
 * 3. 否则剩余 ≥2 格且这 2 格是表内合法汉字 → 取 2 格（零声母字、无调字）
 * 4. 否则剩余 ≤2 格且首格能作为某音节第一格 → 挂起，不翻译
 * 5. 否则取 1 格，交给翻译表（标点、符号等）
 *
 * 第 4 条是"数字看着像已经上屏、后面拼不起来"的根因修复。中文表里 digit 1 1 是无条件
 * 定义，单格会被直接翻成数字；如果把它当成品显示出去，用户就会认为这一格已经定死，后续格自然
 * 拼不成汉字。挂起后输入框只保留已经凑满的音节，凑满那一刻整串替换成汉字并播报。
 *
 * 残留歧义：若某个 2 格字 A 恰好与后面一格拼成合法的 3 格字 B，会优先取 2 格。这类歧义
 * 只有语言模型能解，当前版本接受此限制。
 *
 * 两阶段：输入选字 → 上屏。中文现行盲文一个合法音节平均对应 15.6 个汉字（1343 个
 * 音节 / 20974 条定义），87.8% 的音节有多候选，"译出一个字"只是猜中最常见的那个。所以照标准
 * 输入法的模型分成两步：
 *
 * 1. 阶段 1（输入和选字）：音节凑满时不把汉字写进输入框，只把选中的候选播报
 *    出来（"某字，多少候选"）。此时 [moveToNextCandidate] 可以前后换字，用户听到的
 *    是"第 N 个，某字"，输入框一直是空的——这一点很关键：盲人看不见下划线，如果字提前
 *    出现，他无法分清"还没上屏"和"已经上屏"。
 * 2. 阶段 2（上屏）：按空格（或提交）时先把选中的字并进 composing，再提交成文本，
 *    并播报"上屏"。打下一格时，前一个音节因为不再是最后一个，自动确认并进入 composing，
 *    这就是连续输入时不必每字按空格的原因。
 *
 * 没有独立的"确认"手势是有意的：盲文键盘上没有空格键，"空格"就是单指右滑，而那是打字最高频
 * 的动作之一，不能拿来当确认键。确认复用空格，和标准中文输入法的"空格上屏"一致。
 */
class EditBufferChinese private constructor(
    private val context: Context,
    translator: BrailleTranslator,
    private val talkBack: TalkBackSpeaker,
    private val index: ChineseBrailleTableIndex,
) : EditBufferUnContracted(context, translator, talkBack) {

    /**
     * 最近一次 [translateHoldings] 的尾部是否为表内不存在的盲文组合。
     *
     * 与"尚未凑满音节"必须分开：前者是用户按错了格，必须立刻告知；后者是正常输入中间态，
     * 保持安静等下一格即可。合并处理就会变成"按错一格后键盘彻底失声"。
     */
    private var invalidTail = false

    /**
     * 最近一次切分时尾部剩余内容的结构状态。
     *
     * [invalidTail] 只说明"确实有问题"，这一个说明"问题出在哪一步"：是只差声调、
     * 声调打错，还是根本接不上。盲人听不到输入框，一句"组合无效"无法转化成下一步动作；
     * 按状态给出不同的提示（"还差声调"/"这个声调没有字"/"左滑退一格"）才能让他自己修好。
     */
    private var tailState: SyllableSplitter.TailState = SyllableSplitter.TailState.NONE

    /** 尾部还有几格没被翻译成字符（未完成的音节）。按空格提交时这部分会被丢弃，需要告知。 */
    private var pendingCells = 0

    /**
     * 用户在尾部音节上选定的候选，以及前后移动逻辑。
     *
     * 为什么按"起始格下标"记：每次落键都会把整串 holdings 重新切分一遍，如果不记住用户的
     * 选择，第二次切分就会退回候选 0，用户刚换的字当场跳回去。前缀音节的起始下标在追加输入
     * 时不会变，所以这个 key 是稳定的。
     */
    private val candidateCursor = CandidateCursor()

    /**
     * 已经确认过的最后一个音节的起始格下标；-1 表示一个字都还没确认。
     *
     * "确认"是阶段 1（输入选字）和阶段 2（上屏）的分界：确认之前的字只在候选阶段，不写进
     * 输入框；确认之后才进 composing，按空格或提交时落成文本。追加新格时，前一个音节因为不再是
     * 最后一个，会被自动确认——这就是"自动确认上一个字"。
     */
    private var lastConfirmedSyllableStart = -1

    /**
     * 当前输入框能不能做"选字"这一步。
     *
     * 密码框和非文本框没有 composing 可用，待确认的字根本无处安放，按空格时会被连同 holdings
     * 一起清掉，等于吞字。这两种场景直接退化成"凑满即落定"，不做两阶段。
     */
    private var supportsCandidateSelection = true

    /** 最近一次切分里最后一个完整音节的起始格下标；-1 表示尾部没有完整音节。 */
    private var lastCandidateStart = -1

    /** 待确认内容的格区间终点（不含）；-1 表示没有待确认内容。 */
    private var lastCandidateEnd = -1

    /** 待确认内容的点位串，用于绑定选定文本时校验这段格没被改过。 */
    private var lastCandidateDots = ""

    /** 上一个完整音节的候选列表（已按字频排序）；空列表表示尾部没有可换字的音节。 */
    private var lastCandidates: List<String> = emptyList()

    /** 上一个完整音节当前选中的候选序号。 */
    private var lastChosenIndex = 0

    companion object {
        private const val SPLIT_TAG = "BBSplit"

        /**
         * 创建中文 [EditBuffer]；音节索引不可用时降级到通用的整串翻译，保证仍能输入。
         */
        @JvmStatic
        fun create(
            context: Context,
            translator: BrailleTranslator,
            talkBack: TalkBackSpeaker,
        ): EditBufferUnContracted {
            val index = ChineseBrailleTableIndex.getInstance(context)
                ?: return EditBufferUnContracted(context, translator, talkBack)
            return EditBufferChinese(context, translator, talkBack, index)
        }

        /**
         * 这一格在中文盲文里"叫什么"。
         *
         * 盲文是记音文字：用户记的是"1-2 点是波"，不是"点 1 点 2"。所以能认出归属格时按教学读法
         * 播报（声母呼读音 / 韵母例字 / 几声），只有认不出来的格（标点、数字符、空格、按错的乱组合）
         * 才退回点位号，让用户知道自己按了哪几个点。
         *
         * 回落不能省：点位号是用户唯一的"我到底按了什么"的证据。全换成名称以后，按错了会听到
         * 一句无意义的话，反而更难自查。
         */
        private fun describeCell(
            resources: Resources,
            brailleCharacter: BrailleCharacter,
        ): String {
            val name = ChineseBrailleSignNames.describe(brailleCharacter.toString())
            return name ?: BrailleTranslateUtils.getDotsText(resources, brailleCharacter)
        }
    }

    protected override fun translateHoldings(word: BrailleWord): String {
        // 删字/提交让 holdings 变短后，落在已不存在区间里的候选选择要丢掉。
        candidateCursor.trimTo(word.size())
        if (lastConfirmedSyllableStart >= word.size()) {
            // holdings 已经短到不包括那个音节了，确认标记随之失效。
            lastConfirmedSyllableStart = -1
        }
        val result: SyllableSplitter.Result =
            SyllableSplitter.split(
                word,
                index,
                translator,
                candidateCursor,
                lastConfirmedSyllableStart,
                supportsCandidateSelection,
            )
        lastCandidateStart = result.pendingStart
        lastCandidateEnd = result.pendingEnd
        lastCandidateDots = result.pendingDots
        lastCandidates = result.pendingCandidates
        lastChosenIndex = result.pendingChoice
        pendingCells = result.pendingCells
        invalidTail = result.invalidTail
        tailState = result.tailState
        Log.d(
            SPLIT_TAG,
            "holdings=" +
                word +
                " split=" +
                result.trace +
                " out=[" +
                result.confirmedText +
                "] pending=" +
                result.pendingStart,
        )
        return result.confirmedText
    }

    /**
     * 右滑在中文里的语义是"完成当前输入"，不是"插入一个空格字符"。
     *
     * 上游 appendSpace 做两件事：commit() 提交 composing，再
     * commitText(" ", 1) 插一个空格。这在按词分写的语言里成立，中文不成立——每个字后面
     * 跟一个空格，打出来就是"的 是 我 的"，用户看不见却要为此付出一倍长度的文本。
     *
     * 所以按"手上有没有待上屏内容"分两种语义，规则只有一条：右滑 = 结束当前这一段输入。
     *
     * - 还有格或待选字 → 上屏，不插空格（这是中文最主要的用法）
     * - 什么都没有 → 这时才是真的想要一个空格（中英混排、分隔符）
     *
     * 半截音节会在提交时被丢弃，这条提示和"上屏"合并成一句播报：两次 INTERRUPT 会互相打断，
     * 后一句把前一句顶掉，用户反而只听到一半。
     */
    override fun appendSpace(imeConnection: ImeConnection) {
        // 必须在 confirmPendingSyllable() 之前判断：那次刷新会把 holdings 和候选状态都清掉。
        val hasInputInProgress = !holdings.isEmpty() || lastCandidateStart >= 0
        val announcement = StringBuilder()
        if (pendingCells > 0) {
            announcement.append(context.getString(R.string.bb_incomplete_braille_ignored)).append('，')
        }
        confirmPendingSyllable(imeConnection)
        if (hasInputInProgress) {
            commit(imeConnection)
            announcement.append(context.getString(R.string.bb_text_committed))
        } else {
            super.appendSpace(imeConnection)
            announcement.append(context.getString(R.string.bb_space_inserted))
        }
        talkBack.speak(announcement.toString(), TalkBackSpeaker.AnnounceType.INTERRUPT)
    }

    /**
     * 上屏前必须先确认待选字。
     *
     * 提交、移动光标、切换应用都会走 commit()，那里会清空 holdings 并结束 composing。
     * 待确认的字此时还没进 composing，不先并进去就等于被静默吞掉。
     */
    override fun commit(imeConnection: ImeConnection) {
        confirmPendingSyllable(imeConnection)
        super.commit(imeConnection)
    }

    /**
     * 把待确认音节的选字结果并进 composing（阶段 1 → 阶段 2 的分界线）。
     *
     * 只推进"确认到哪一格"并刷新 composing，不提交文本；提交交给调用方，这样"打下一格自动
     * 确认"和"按空格上屏"复用同一段逻辑。已经确认过时原样返回，可以安全重复调用。
     */
    private fun confirmPendingSyllable(imeConnection: ImeConnection) {
        if (lastCandidateStart < 0) {
            return
        }
        // 先把"这一步定下来的文本"绑到格区间上，再推进确认位置。
        //
        // 绑定必须在推进之前做：后面每按一格都会重新切分整串 holdings，词已经不在尾部，
        // 会被拆成独立音节按各自候选重算；有了绑定，那段格就直接按已经定下的文本输出，
        // 用户换过的候选不会在下一格被打回原样。
        if (lastCandidateEnd > lastCandidateStart &&
            lastChosenIndex >= 0 &&
            lastChosenIndex < lastCandidates.size
        ) {
            candidateCursor.bindWord(
                lastCandidateStart,
                lastCandidateEnd,
                lastCandidateDots,
                lastCandidates[lastChosenIndex],
            )
        }
        lastConfirmedSyllableStart = lastCandidateStart
        lastCandidateStart = -1
        lastCandidateEnd = -1
        lastCandidateDots = ""
        refreshComposing(imeConnection)
    }

    /** 用最新译文刷新 composing；密码框和非文本框没有 composing 可写，跳过。 */
    private fun refreshComposing(imeConnection: ImeConnection) {
        if (!BrailleCommonUtils.isTextField(imeConnection.editorInfo) ||
            BrailleCommonUtils.isPasswordField(imeConnection.editorInfo)
        ) {
            return
        }
        imeConnection.inputConnection.setComposingText(translateHoldings(holdings), 1)
    }

    /**
     * 记录当前输入框能不能走两阶段。
     *
     * 必须在 super.appendBraille 之前设好：[translateHoldings] 要靠它决定
     * 要不要把最后一个音节挂在"待确认"上，而密码框里挂起就等于吞字。
     */
    override fun appendBraille(
        imeConnection: ImeConnection,
        brailleCharacter: BrailleCharacter,
    ): String {
        supportsCandidateSelection =
            BrailleCommonUtils.isTextField(imeConnection.editorInfo) &&
                !BrailleCommonUtils.isPasswordField(imeConnection.editorInfo)
        return super.appendBraille(imeConnection, brailleCharacter)
    }

    /**
     * 落键后没有产出文本时播报什么：先念这一格的点位，再按尾部状态补一句"下一步"。
     *
     * 候选这条是两阶段的关键提示：字还在选字阶段、没进输入框，如果只播点位，用户会以为
     * 什么都没发生。播报内容必须是候选本身，不能只说"共 N 个"：盲人看不见候选栏，
     * 不知道能选什么就永远不会去换，那个"85 个候选"的提示等于零信息。这里按输入法候选栏的
     * 方式念一小段（见 [CandidateAnnouncer]），被截断时才补总数。
     *
     * 尾部没凑成音节时，原来一律播"盲文组合无效"。实测"合法首格 + 任意第二格"的 1092 种
     * 组合里有 79.4% 在表内不存在（声调位只有 1、2、3、23 四种），所以这句话出现的频率极高，
     * 却等于什么都没说：用户不知道是错在声调、错在前一格，还是只差最后一下。现在按结构分开说：
     *
     * - 只差声调 → 直接告诉他"还差声调"，这是正常进度提示，不是错误
     * - 声调不是该音节支持的 → "这个声调没有字"，退一格就能救回来
     * - 两格本来就接不上 → "这个组合没有字"
     * - 首格不能打头 → "这一格不能打头"（通常是几个点没同时按、被拆成了多格）
     */
    protected override fun getAppendBrailleTextToSpeak(
        resources: Resources,
        brailleCharacter: BrailleCharacter,
    ): Optional<String> {
        if (lastCandidateStart >= 0 && lastCandidates.size > 1) {
            return Optional.of(describeCandidates())
        }
        val dotsText = describeCell(resources, brailleCharacter)
        return when (tailState) {
            SyllableSplitter.TailState.AWAIT_TONE ->
                Optional.of(resources.getString(R.string.bb_await_tone, dotsText))
            SyllableSplitter.TailState.WRONG_TONE ->
                Optional.of(resources.getString(R.string.bb_wrong_tone, dotsText))
            SyllableSplitter.TailState.NO_SUCH_SYLLABLE ->
                Optional.of(resources.getString(R.string.bb_no_such_syllable, dotsText))
            SyllableSplitter.TailState.INVALID_START ->
                Optional.of(resources.getString(R.string.bb_invalid_start, dotsText))
            // INCOMPLETE 是正常输入中间态：只播这一格的名称，不要每格都提醒"还没打完"。
            // NONE 时同样播名称；只有当这一格连名称都认不出来时才由父类回落到点位号。
            // 注意不能在这里返回 empty 交给父类的兜底：父类兜底一律念点位号（"点 1 点 2"），
            // 那正是用户听不懂的东西。
            SyllableSplitter.TailState.INCOMPLETE, SyllableSplitter.TailState.NONE -> {
                if (invalidTail) {
                    Optional.of(resources.getString(R.string.bb_invalid_braille_combination))
                } else {
                    val base = super.getAppendBrailleTextToSpeak(resources, brailleCharacter)
                    if (base.isPresent()) base
                    else Optional.of(describeCell(resources, brailleCharacter))
                }
            }
        }
    }

    /** 拼出"候选 你，尼，呢"，候选被截断时补"共 N 个"。 */
    private fun describeCandidates(): String {
        val window =
            CandidateAnnouncer.window(
                lastCandidates,
                lastChosenIndex,
                CandidateAnnouncer.WINDOW,
                context.getString(R.string.bb_candidate_separator),
            )
        if (CandidateAnnouncer.hasMore(
                lastCandidates,
                lastChosenIndex,
                CandidateAnnouncer.WINDOW,
            )
        ) {
            return context.getString(R.string.bb_candidate_list_more, window, lastCandidates.size)
        }
        return context.getString(R.string.bb_candidate_list_all, window)
    }

    /**
     * 中文的播报一律自己负责。
     *
     * 上游用"译文有没有变长"来决定要不要播报，这个判断在中文盲文里不成立：追加一格时，即使
     * 这一格自己什么也没产出，前面的音节也会因为"不再是最后一个"而被自动确认进 composing，
     * 译文照样变长。按上游的条件，这种时候要么播错（念出上一个字，让人以为又打了一遍），
     * 要么完全不播（[shouldSpeakResolvedTranslation] 原来在有半截音节时返回 false，
     * 而它一旦为 false，点位播报那条分支也进不去，结果是彻底失声——用户按了下一格的
     * 第一个点，键盘毫无反应）。
     *
     * 所以这里始终接管，具体播什么由 [getResolvedTranslationAnnouncement] 判断。
     */
    protected override fun shouldSpeakResolvedTranslation(): Boolean {
        return true
    }

    /**
     * 决定译文变长时到底播什么。
     *
     * - 尾部还有半截音节或无效组合 → 这一格没有产出字符，产出的文本是"前一个音节被
     *   自动确认"。此时必须播报这一格自身的状态（"还差声调"/"这个声调没有字"/点位），
     *   否则用户按下去听不到任何东西。
     * - 否则 → 这一格确实凑成了一个字，念出新字。
     */
    protected override fun getResolvedTranslationAnnouncement(
        resolvedText: String,
        brailleCharacter: BrailleCharacter,
    ): String {
        if (pendingCells > 0 || invalidTail) {
            return getAppendBrailleTextToSpeak(context.resources, brailleCharacter)
                .orElseGet { describeCell(context.resources, brailleCharacter) }
        }
        return resolvedText
    }

    /**
     * 半截音节不产出任何文本，必须播报点位。
     *
     * 上游的"逐字符回显"开关在文本框 + announceType == SILENCE 时是关的（因为一格一
     * 字的语言里文本上屏就会被读出来）。中文盲文在凑满音节之前输入框根本不变，若再挡掉
     * 点位播报，用户按下去就既没有声音也没有字——只能判断成"键盘坏了"。实测 63 个单格里 55 个
     * 属于"音节首格"，都会被挂起，所以这条不是边缘情况，而是主路径。
     *
     * 有候选待确认时同理必须放行：那时 composing 里只有已经确认的字，这一格本身没有产出，
     * 挡掉播报就会变成"按下去了、什么也没说"。
     */
    protected override fun shouldEmitDotAnnouncement(
        imeConnection: ImeConnection,
        currentTranslation: String,
    ): Boolean {
        return lastCandidateStart >= 0 ||
            TextUtils.isEmpty(currentTranslation) ||
            super.shouldEmitDotAnnouncement(imeConnection, currentTranslation)
    }

    /**
     * 把连续点流切成已确定的音节，彻底不用等待时间。
     *
     * 原来的做法是"停顿多久算一格结束"，两头都会错：猜早了把 145（得）切成 14
     * 和 5，这个字就永远拼不出来；猜晚了把相邻两格粘成一格。用户报的"打慢一点就拼不成"
     * 就是这个。现在改成按音节整串识别：1451362 落在表里是"读"，与按得多慢无关，
     * 只要这串点还不能被判定成"一个不能再长的音节"，就一直等着，绝不切断。
     *
     * @return null 表示还得继续按；空列表表示这串点已经不可能成字（按错了），键盘层会
     *     退掉一个点；否则是已确定音节的格，键盘层逐个送进 [appendBraille]
     */
    override fun segmentDotStream(dotStream: String, force: Boolean): List<BrailleCharacter>? {
        if (TextUtils.isEmpty(dotStream) || index == null) {
            return null
        }
        val segmenter = index.getDotStreamSegmenter()
        val decision = segmenter.decide(dotStream, force)
        if (decision == null) {
            // 走不通就明确说走不通，不能让键盘一直挂着等一个永远不会来的点。
            return if (force || !segmenter.canStillGrow(dotStream)) emptyList()
            else null
        }
        val cells = if (decision.ambiguous) pickMostCommonSyllable(decision) else decision.cells
        val result: ArrayList<BrailleCharacter> = ArrayList(cells.size)
        for (cell in cells) {
            result.add(BrailleCharacter(cell))
        }
        return result
    }

    /**
     * 同一串点撞上多个音节时，挑首选字更常用的那个。
     *
     * 撞串是真实的语言现象，不是算法缺陷：12-35-2（拔）与 1235-2（儿）都对应
     * 点串 12352，因为"波"的声母格加"啊"的韵母格正好等于"儿"的韵母格。1343 个音节里
     * 有 143 个（10.6%）存在这种情况。按字频挑，绝大多数情况下挑中的就是用户想要的那个。
     */
    private fun pickMostCommonSyllable(
        decision: ChineseDotStreamSegmenter.Decision,
    ): Array<String> {
        var best = decision.cells
        var bestWeight = Integer.MAX_VALUE
        for (alternative in decision.alternatives) {
            val weight = index.topCandidateWeight(alternative.joinToString("-"))
            if (weight < bestWeight) {
                bestWeight = weight
                best = alternative
            }
        }
        return best
    }

    override fun moveToNextCandidate(imeConnection: ImeConnection): Boolean {
        return shiftCandidate(imeConnection, /* delta= */ 1)
    }

    override fun moveToPreviousCandidate(imeConnection: ImeConnection): Boolean {
        return shiftCandidate(imeConnection, /* delta= */ -1)
    }

    /**
     * 把尾部音节的候选前后移动一格，成功返回 true。
     *
     * 调用方是上下滑（原本是移动光标）：候选是一个列表，上下滑动就是列表上下滚动，方向符合
     * 直觉。刻意不占用左右滑——单指左滑是删字、单指右滑是空格上屏，候选态下这两个动作仍然必需
     * （删掉打错的格、确认上屏），借走它们会让用户在最需要的时候找不到。
     *
     * 返回 false 的三种情况都必须让调用方回落到原动作，否则用户会觉得手势丢了：
     *
     * - 不在普通文本框里（密码框/composing 会被直接提交，换字没有意义）
     * - 尾部不是完整音节（还在半路上，没有候选可换）
     * - 候选只有一个（唯一解，换了也是同一个字）
     */
    private fun shiftCandidate(imeConnection: ImeConnection, delta: Int): Boolean {
        if (!BrailleCommonUtils.isTextField(imeConnection.editorInfo) ||
            BrailleCommonUtils.isPasswordField(imeConnection.editorInfo)
        ) {
            return false
        }
        // 先重算一次：holdingsEndsWithPrefix() 之类的试探性翻译会把三元组留在别的子串上，
        // 不刷新就可能对着一个早已不属于尾部的音节换字。
        translateHoldings(holdings)
        if (lastCandidateStart < 0) {
            return false
        }
        if (lastCandidates.size <= 1) {
            return false
        }
        // 拷到局部：后续字段还可能被别的路径改写，播报和日志必须用"决定这一跳时"的那份数据，
        // 否则可能对着另一个音节念字，甚至下标越界。
        val start = lastCandidateStart
        val candidates = lastCandidates
        val next = candidateCursor.shift(start, lastChosenIndex, candidates.size, delta)
        lastChosenIndex = next
        val picked = candidates[next]
        // 两阶段下这个字还没进 composing（要等确认才写），所以换候选只需要更新选择，界面不用动。
        // 用户靠这句播报确认换到了哪个字。
        Log.d(
            SPLIT_TAG,
            "candidate start=" +
                start +
                " index=" +
                next +
                " of " +
                candidates.size +
                " -> " +
                picked,
        )
        talkBack.speak(
            context.getString(R.string.bb_candidate_selected, next + 1, picked),
            TalkBackSpeaker.AnnounceType.INTERRUPT,
        )
        return true
    }

    /**
     * 中文按"格"删，不按输入框字符删。
     *
     * 父类的实现是"删一格 holdings + 给输入框发一次删除键"。这在一格等于一个字符的语言里成立，
     * 中文不成立：3 格凑成一个汉字，删 1 格却会把输入框里那 1 个汉字一并删掉，剩下的 2 格下次
     * 刷新 composing 时又会翻出别的字——用户看到的就是"删一下，字没了又冒出来一个"。
     *
     * 所以这里只动 holdings 再整体刷新 composing，输入框的删除只在 holdings 已经空了
     * （要删的是更早提交进文本的字）时才交给父类。
     */
    override fun deleteCharacterBackward(imeConnection: ImeConnection) {
        if (holdings.isEmpty()) {
            super.deleteCharacterBackward(imeConnection)
            return
        }
        holdings.remove(holdings.size() - 1)
        lastCommitIndexOfHoldings = holdings.size()
        refreshComposing(imeConnection)
        talkBack.speak(describeRemainingInput(), TalkBackSpeaker.AnnounceType.INTERRUPT)
    }

    /**
     * 删掉一格之后告诉用户还剩什么。
     *
     * 两阶段下被删的那一格如果属于待确认音节，它本来就没进输入框，删完输入框不会有任何变化。
     * 不给一句反馈，用户按了左滑却什么也没听到，只能判断成"删不掉"。
     */
    /** 删掉一格之后告诉用户还剩什么。 */
    private fun describeRemainingInput(): String {
        if (lastCandidateStart >= 0 && lastCandidates.size > 1) {
            return describeCandidates()
        }
        return context.getString(R.string.bb_deleted)
    }
}
