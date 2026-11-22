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

import com.google.android.accessibility.braille.interfaces.BrailleCharacter
import com.google.android.accessibility.braille.interfaces.BrailleWord
import com.google.android.accessibility.braille.translate.BrailleTranslator
import kotlin.math.min

/**
 * 把中文盲文的 holdings 切成“已确认前缀”和“尾部待确认音节”。
 *
 * 这是 [EditBufferChinese] 两阶段模型的核心：
 * - **已确认前缀** → 写进 composing，用户按空格时落成文本；
 * - **待确认音节** → 不写进 composing，只在候选阶段播报；用户换字、确认后才进前缀。
 *
 * 为什么要分成两段：中文盲文一个音节平均 15.6 个同音字，译出来的字只是“猜得最像的那个”。
 * 如果立刻写进输入框，用户看不见下划线，就无法区分“这字还没定”和“这字已经进去了”。
 *
 * 刻意不依赖 Android（Context/Resources/InputConnection），只吃 [BrailleWord] 和索引，
 * 因为切分是整个输入链路里最容易出错的一环——吞字、重复字、前面已定的字被改写都发生在这里，
 * 必须在 JVM 单测里能直接验证。
 */
class SyllableSplitter private constructor() {

    /**
     * 尾部剩余内容的状态。
     *
     * 只区分“有效/无效”是不够的：实测“合法首格 + 任意第二格”的 1092 种组合里，79.4% 的二元
     * 组合在表里不存在（因为声调位只有 1、2、3、23 四种，第二格打错就必然无效）。一律播报
     * “盲文组合无效”，用户听到的是“打错了”却不知道错在哪一步、下一步该按什么。
     *
     * 所以按**结构**细分：声母韵母是不是已经齐了（齐了只差声调）、还是从第二格起就接不上、
     * 还是首格本身就不能打头。每一种给不同的可执行提示。
     */
    enum class TailState {
        /** 尾部没有挂起内容。 */
        NONE,

        /** 首格合法、内容还不够，正常输入中间态，保持安静等下一格即可。 */
        INCOMPLETE,

        /** 声母韵母已经齐了，只差一个声调格。 */
        AWAIT_TONE,

        /** 声母韵母齐了，但这一格不是它支持的声调。 */
        WRONG_TONE,

        /** 这个组合在翻译表里根本不存在。 */
        NO_SUCH_SYLLABLE,

        /** 首格既不能作音节开头，也不是能单独输出的符号。 */
        INVALID_START,
    }

    /** 一次切分的结果。字段全部只读，Java 调用方（含单测）按字段取用，故用 `@JvmField`。 */
    class Result(
        confirmedText: String,
        pendingStart: Int,
        pendingCandidates: List<String>,
        pendingChoice: Int,
        pendingEnd: Int,
        pendingDots: String,
        pendingCells: Int,
        invalidTail: Boolean,
        tailState: TailState,
        trace: String,
    ) {
        /** 已确认、可以写进 composing 的文本。 */
        @JvmField val confirmedText: String = confirmedText

        /** 待确认音节的起始格下标；`-1` 表示没有待确认音节。 */
        @JvmField val pendingStart: Int = pendingStart

        /** 待确认音节的候选列表（已按字频排序）；没有待确认音节时为空列表。 */
        @JvmField val pendingCandidates: List<String> = pendingCandidates

        /** 待确认音节当前选中的候选序号。 */
        @JvmField val pendingChoice: Int = pendingChoice

        /**
         * 待确认内容的格区间终点（不含）；`-1` 表示没有待确认内容。
         *
         * 词候选跨多格，确认时要连同格范围一起记下来，才能把“已选定的词”绑住、后续不再重算。
         */
        @JvmField val pendingEnd: Int = pendingEnd

        /** 待确认内容覆盖的点位串；绑定已选定的文本时用来确认这段格没被改过。 */
        @JvmField val pendingDots: String = pendingDots

        /** 尾部没凑成一个字的格数（半截音节）；按空格提交时这部分会被丢弃，需要告知用户。 */
        @JvmField val pendingCells: Int = pendingCells

        /** 尾部盲文组合在翻译表里根本不存在。 */
        @JvmField val invalidTail: Boolean = invalidTail

        /** 尾部剩余内容的结构状态，用来决定播报哪一句提示。 */
        @JvmField val tailState: TailState = tailState

        /** 切分过程的诊断轨迹，只在日志里用。 */
        @JvmField val trace: String = trace

        fun hasPending(): Boolean = pendingStart >= 0
    }

    /** 切分过程中暂存的完整音节，用来判断它是“已确认”还是“等用户选完上屏”。 */
    private class HeldSyllable(
        val start: Int,
        val dots: String,
        val candidates: List<String>,
        val choice: Int,
        /** 这个音节占了几格（2 格或 3 格）。 */
        val cellCount: Int,
    ) {
        fun text(): String = candidates[choice]
    }

    companion object {
        /**
         * 中文表的数字符（第四五六点）。
         *
         * 不用 `BrailleTranslateUtils.DOTS3456`：那个类初始化时会加载 Android 资源，把它
         * 拖进来会让本类在 JVM 单测里直接 `ExceptionInInitializerError`，而切分逻辑恰恰是最
         * 需要能单测的一段。点位值本身就是表里写死的约定，另外有测试盯着它。
         */
        private val NUMBER_SIGN = BrailleCharacter("3456")

        /**
         * 最多几个音节并成一个词。
         *
         * 词库按 3 音节截断：常用双字词（北京）、三字词（计算机）都在 3 以内，再长的词（成语、
         * 专名）在实测语料里命中率只有 1.3%，收益抵不上把窗口撑大带来的歧义。
         */
        private const val MAX_WORD_SYLLABLES = 3

        /**
         * 切分 holdings。
         *
         * @param word 累积的盲文格
         * @param index 音节索引（点位串 → 同音字候选）
         * @param translator 兜底翻译器，用于标点、数字和索引里查不到的格
         * @param cursor 候选选择记录，读它决定每个音节取哪个字
         * @param lastConfirmedStart 已经确认过的最后一个音节起始下标；`-1` 表示还没确认过任何字
         * @param allowPending 是否允许把尾部音节挂在“待确认”上。密码框和非文本框没有 composing
         *   可用，挂起会导致按空格时字被连同 holdings 一起清掉，所以那里必须传 `false`
         */
        @JvmStatic
        fun split(
            word: BrailleWord,
            index: ChineseBrailleTableIndex,
            translator: BrailleTranslator,
            cursor: CandidateCursor,
            lastConfirmedStart: Int,
            allowPending: Boolean,
        ): Result {
            if (word.isEmpty()) {
                return Result("", -1, emptyList(), 0, -1, "", 0, false, TailState.NONE, "")
            }

            val resolved = StringBuilder()
            val trace = StringBuilder()
            var start = 0
            var numericMode = false
            var invalid = false
            var pendingCells = 0
            var tailState = TailState.NONE
            // 切分是单遍从前往后做的，走到某个音节时还不知道它后面还有没有内容，也就无法判断它
            // 是不是“最后一个、要等用户确认上屏”的那一个。把最近几个音节压在窗口里，等后面出现了
            // 别的内容（数字、标点、半截音节）或者循环结束，再决定哪些落定、哪些留给用户确认。
            //
            // 留 3 个而不是 1 个：词候选跨 2~3 个音节，“北京”两格要整体当候选，单个音节压不住。
            val window = ArrayList<HeldSyllable>(MAX_WORD_SYLLABLES)
            val size = word.size()

            while (start < size) {
                val remaining = size - start
                val cell = word.get(start)

                // 已经定下来的格区间（用户确认过、或挑选过的字与词）：直接输出绑定文本并跳过这段格。
                // 签名不符说明这段格被删改过，绑定作废，按正常流程重算。
                val bound = cursor.boundWordAt(start)
                if (bound != null && bound.end <= size) {
                    if (bound.dots == dots(word, start, bound.end - start)) {
                        resolved.append(bound.text)
                        trace.append("[bound:").append(bound.text).append(']')
                        start = bound.end
                        continue
                    }
                }

                // 数字符 3456：进入数字模式。它本身不产生字符（numsign 只切换状态），这里只置
                // 标志、不输出，避免把数字符翻成难以理解的符号。
                if (NUMBER_SIGN == cell) {
                    settleAll(window, resolved)
                    numericMode = true
                    trace.append("[3456=numsign]")
                    start++
                    continue
                }

                // 数字模式：逐格翻成 0-9，直到按空格提交（appendSpace 会清空 holdings）。
                if (numericMode) {
                    settleAll(window, resolved)
                    resolved.append(translator.translateToPrint(word.subword(start, start + 1)))
                    trace.append('[').append(dots(word, start, 1)).append("=digit]")
                    start++
                    continue
                }

                if (remaining >= 3 && index.hasCandidate(dots(word, start, 3))) {
                    pushSyllable(window, resolved, buildSyllable(word, start, 3, index, translator, cursor))
                    trace.append('[').append(dots(word, start, 3)).append("=3]")
                    start += 3
                    continue
                }
                if (remaining >= 2 && index.hasCandidate(dots(word, start, 2))) {
                    pushSyllable(window, resolved, buildSyllable(word, start, 2, index, translator, cursor))
                    trace.append('[').append(dots(word, start, 2)).append("=2]")
                    start += 2
                    continue
                }

                // 后面接的既不是音节也不是数字：压着的音节一定不是最后一个，先落定。
                settleAll(window, resolved)

                // 走到这里说明余下的格凑不成一个完整音节，只剩三种可能：还在半路上、声调打错了、
                // 或者这个组合根本不存在。三者必须分开，否则用户听到的永远是一句笼统的“无效”。
                if (index.canStartSyllable(dots(word, start, 1))) {
                    if (remaining == 1) {
                        // 只有一格、且它能作音节首格：挂起等后续格。此时绝不能翻译——中文表里
                        // digit 1 1 是无条件定义，单格会被翻成数字，看起来像已经上屏。
                        trace.append("[pending:").append(dots(word, start, 1)).append(']')
                        pendingCells = remaining
                        tailState = TailState.INCOMPLETE
                        break
                    }
                    if (remaining == 2 && index.isThreeCellPrefix(dots(word, start, 2))) {
                        // 这 2 格自身不是音节，但已是完整的声母韵母，只差一个声调格。
                        // 中文盲文的声调位只有 4 种（1 阴平 / 2 阳平 / 3 上声 / 23 去声），到这里
                        // 用户只差最后一下，必须明确告诉他“还差声调”，否则他不知道自己停在半路上。
                        trace.append("[await-tone:").append(dots(word, start, 2)).append(']')
                        pendingCells = remaining
                        tailState = TailState.AWAIT_TONE
                        break
                    }
                    // 声母韵母已经齐了、第三格却不是它支持的声调，与“这两格本来就接不上”是两种
                    // 完全不同的错误：前者只错最后一下，退一格就能救回来；后者要退两格。分开播报。
                    val stemComplete = remaining >= 3 && index.isThreeCellPrefix(dots(word, start, 2))
                    invalid = true
                    tailState = if (stemComplete) TailState.WRONG_TONE else TailState.NO_SUCH_SYLLABLE
                    trace.append("[invalid:")
                        .append(tailState)
                        .append(':')
                        .append(dots(word, start, remaining))
                        .append(']')
                    pendingCells = remaining
                    break
                }

                // 首格不能作音节首格：可能是标点/符号，也可能是用户把一格的几个点分开按了（成了多格）。
                // 单格能不能直接产出字符，必须严格判断，见 isStandaloneSingleCell()。
                val single = translator.translateToPrint(word.subword(start, start + 1))
                if (!isStandaloneSingleCell(single)) {
                    trace.append("[pending-single:").append(dots(word, start, 1)).append(']')
                    pendingCells = remaining
                    invalid = true
                    tailState = TailState.INVALID_START
                    break
                }
                resolved.append(single)
                trace.append('[').append(dots(word, start, 1)).append("=1]")
                start++
            }

            var pendingStart = -1
            var pendingEnd = -1
            var pendingDots = ""
            var pendingCandidates: List<String> = emptyList()
            var pendingChoice = 0
            if (window.isNotEmpty()) {
                // 词优先：把尾部 3、2 个音节并起来查词库，命中就整组当候选。
                //
                // 只查“贴着尾部”的组合：用户刚打完的这几个音节才可能有歧义，往前更远的音节已经
                // 在前面的刷新里落定了，再回头改写会让已经念过的字在输入框里跳变。
                var wordSyllables = 0
                var wordCandidates: List<String>? = null
                val pinyinIndex = index.pinyinIndex
                if (pinyinIndex != null) {
                    for (probe in min(MAX_WORD_SYLLABLES, window.size) downTo 2) {
                        val pinyins = pinyinsOf(window, window.size - probe, probe, pinyinIndex) ?: continue
                        val words = pinyinIndex.wordsForSyllables(pinyins)
                        if (words.isNotEmpty()) {
                            wordCandidates = words.map { it.text }
                            wordSyllables = probe
                            break
                        }
                    }
                }

                // 组的起点：命中词就从词的首音节算起，否则只留最后一个音节。
                val groupIndex = if (wordSyllables > 0) window.size - wordSyllables else window.size - 1
                val first = window[groupIndex]
                val groupCandidates = if (wordSyllables > 0) wordCandidates!! else first.candidates
                val groupStart = first.start
                val groupChoice =
                    if (wordSyllables > 0) cursor.indexFor(groupStart, groupCandidates.size) else first.choice

                // 组之前的音节确定不是尾部，先落定。
                for (i in 0 until groupIndex) {
                    resolved.append(window[i].text())
                }

                if (allowPending && groupStart > lastConfirmedStart && groupCandidates.size > 1) {
                    pendingStart = groupStart
                    pendingCandidates = groupCandidates
                    pendingChoice = groupChoice
                    // 组的终点取“最后一个音节 + 它自己的格数”：词的终点和单音节一样，都靠这个算。
                    val last = window[window.size - 1]
                    pendingEnd = last.start + last.cellCount
                    pendingDots = dots(word, pendingStart, pendingEnd - pendingStart)
                    trace.append(if (wordSyllables > 0) "[awaiting-word:" else "[awaiting-confirm:")
                        .append(first.dots)
                        .append(']')
                } else if (wordSyllables > 0) {
                    // 词是唯一解、或这一组已经确认过：整组按词落定。绝不能退化成一个音节一个字地拼，
                    // 否则“北京”会变成“北”+“京”，用户下次还要再改一遍。
                    resolved.append(groupCandidates[groupChoice])
                    trace.append("[word=").append(groupCandidates[groupChoice]).append(":settled]")
                } else {
                    resolved.append(first.text())
                    trace.append('[').append(first.dots).append("=settled]")
                }
            }
            return Result(
                resolved.toString(),
                pendingStart,
                pendingCandidates,
                pendingChoice,
                pendingEnd,
                pendingDots,
                pendingCells,
                invalid,
                tailState,
                trace.toString(),
            )
        }

        /**
         * 算出一个完整音节的候选和当前选择，暂不决定去留。
         *
         * 索引里查不到时退回翻译器，并包成单元素候选列表——候选数为 1 会让它走“唯一解直接
         * 落定”，不会挂在待确认上，保证不吞字。
         */
        private fun buildSyllable(
            word: BrailleWord,
            start: Int,
            count: Int,
            index: ChineseBrailleTableIndex,
            translator: BrailleTranslator,
            cursor: CandidateCursor,
        ): HeldSyllable {
            val dots = dots(word, start, count)
            var candidates: List<String> = index.getCandidates(dots)
            if (candidates.isEmpty()) {
                candidates = listOf(translator.translateToPrint(word.subword(start, start + count)))
            }
            val chosen = cursor.indexFor(start, candidates.size)
            return HeldSyllable(start, dots, candidates, chosen, count)
        }

        /**
         * 把一个新音节压进窗口，窗口满了就把最旧的那个落定。
         *
         * 最旧的音节此时已经不可能出现在尾部 3 音节里，词也并不到它头上，只能按它自己的候选落定。
         */
        private fun pushSyllable(
            window: MutableList<HeldSyllable>,
            resolved: StringBuilder,
            syllable: HeldSyllable,
        ) {
            if (window.size >= MAX_WORD_SYLLABLES) {
                resolved.append(window.removeAt(0).text())
            }
            window.add(syllable)
        }

        /** 落定窗口里全部音节（它们后面出现了别的内容，确定不是尾部）。 */
        private fun settleAll(window: MutableList<HeldSyllable>, resolved: StringBuilder) {
            for (syllable in window) {
                resolved.append(syllable.text())
            }
            window.clear()
        }

        /** 取窗口里 [from] 起的 [count] 个音节的去调拼音；有一个查不到就返回 `null`。 */
        private fun pinyinsOf(
            window: List<HeldSyllable>,
            from: Int,
            count: Int,
            pinyinIndex: PinyinIndex,
        ): List<String>? {
            val pinyins = ArrayList<String>(count)
            for (i in from until from + count) {
                val pinyin = pinyinIndex.syllablePinyin(window[i].dots)
                if (pinyin.isNullOrEmpty()) {
                    return null
                }
                pinyins.add(pinyin)
            }
            return pinyins
        }

        /** 把 `word[start, start + count)` 转成表内使用的点位串，如 `125-35-1`。 */
        private fun dots(word: BrailleWord, start: Int, count: Int): String =
            word.subword(start, start + count).toString()

        /**
         * 一个单格能不能直接产出字符。
         *
         * 中文表里单格的 `sign`（汉字）定义是 **0 条**，所有汉字都要 2~3 格；但表里同时带着
         * 26 个 `lowercase`（a=点 1、b=点 12、k=点 13…）、26 个 `uppercase` 和一个
         * `capsletter 6`（点 6 = 大写符）。这些是英文输入的残留定义，一旦有格落单，liblouis
         * 就会按它们反译，吐出全角字母或大写符。
         *
         * 把它写进输入框的后果是读屏念“大写”“全角 a”，用户完全无法理解发生了什么——他按的是
         * 一个汉字的头几个点，屏幕上却出现了别的东西。所以单格只允许产出标点/符号，其余一律挂起，
         * 等后续格把它补成完整音节。
         */
        private fun isStandaloneSingleCell(text: String): Boolean {
            if (text.isEmpty()) {
                return false
            }
            for (ch in text) {
                if (isCjk(ch)) {
                    // 汉字是正常内容（表里单格没有汉字定义，这里只是防御）。
                    continue
                }
                if (ch.isLetterOrDigit()) {
                    // 全角/半角字母、数字：前者来自 lowercase/uppercase，后者来自无条件 digit。
                    return false
                }
                if (ch in '\uE000'..'\uF8FF') {
                    // 私用区：表里的 \xE2xx 段是盲文内部字符，读屏会念成乱码。
                    return false
                }
                if (ch.isISOControl()) {
                    return false
                }
            }
            return true
        }

        private fun isCjk(ch: Char): Boolean =
            (ch in '\u4E00'..'\u9FFF') || (ch in '\u3400'..'\u4DBF')
    }
}
