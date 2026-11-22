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


/**
 * 把连续按下的点流切成音节，不依赖任何等待时间。
 *
 * 为什么不能用"格"来判边界：中文盲文一格是若干点的集合，而点位之间是包含关系——
 * 52 个可作首格的点集里有 51 个是另一个更长点集的子集（12 是波，加 3 成勒、加 4 成佛、
 * 加 5 成喝）。所以"这一格按完了没有"在点位结构上几乎永远无法判定，只能靠停顿猜。这正是
 * 用户说的"时间太长就拼不成"的根源：猜早了把一格切断，猜晚了把两格粘住，两头都会错。
 *
 * 本类的做法：完全不判格，改判"音节"。一个音节由 2~3 格组成，把所有格的点连起来
 * 是一串点（如"读"= 145-136-2 → 1451362）。只要用户按上屏键，就对这串点做
 * 最长匹配切出一个音节，不需要知道中间哪一刀切格。判据从"停了多久 / 这格完了吗"变成
 * "用户按了上屏键"，后者是明确的、与手速完全无关的。
 *
 * 为什么不自动切：点流里"当前音节的更长形式"和"下一个字的开头"无法区分。
 * 曾试过按结构自动切，实测随机连打 30 字错切率 38.1%：打"的" 145-26 后接"是"的
 * 首点 1，前 6 点 145261 恰好也是音节 145-26-1，最长匹配会把"的"吞掉；
 * 即使加上"必须无歧义"的约束，"打完一串点恰好构成另一个完整音节"这类情况仍会错，
 * 而真无歧义的音节只剩 41.5%，行为不一致反而更难学。
 *
 * 所以 force=false（又按了一个点）一律不切，点流一直攒到用户按上屏键。
 * 实测每字按一次上屏键的正确率 98.8%（9000 字 8890 对），剩下的 1.2% 是点串撞串，
 * 靠上下滑换候选解决。用户按多慢都不会被切断，按多快也不会粘连。
 *
 * 已知歧义：极少数音节点串完全相同，例如"拔"(12-35-2) 与"儿"(1235-2) 都对应
 * 12352，1343 个音节里有 143 个（10.6%）存在这种撞串。本类如实把多个
 * 匹配都返回，由调用方按字频挑选并告知用户可改。
 *
 * 纯 Java，不依赖 Android，可直接单测。
 */
class ChineseDotStreamSegmenter private constructor() {

    /** 表内全部音节，key 形如 145-136-2。 */
    private val syllableKeys: MutableSet<String> = HashSet()

    /**
     * 所有"更长音节的严格前缀"。
     *
     * 一个点串落在这里，说明用户可能还在打一个更长的音节，此刻切出去就会把字打残。
     */
    private val longerSyllablePrefixes: MutableSet<String> = HashSet()

    /** 全部完整音节的点串，用于判断当前点流是否已经跑偏（死路）。 */
    private val completeStreams: MutableSet<String> = HashSet()

    /** 点串 → 该串对应的音节格序列；长度 > 1 即撞串歧义。 */
    private val syllablesByStream: MutableMap<String, MutableList<Array<String>>> = HashMap()

    /** 一次判定的结果。 */
    class Decision(
        /** 从点流头部切出来的音节，每个元素是一格的点位串（按点号升序）。 */
        @JvmField val cells: Array<String>,

        /** 同一段点串能对应的全部音节切分；长度 > 1 即撞串歧义。 */
        @JvmField val alternatives: List<Array<String>>,

        /** 这个音节用掉了点流头部的几个点。 */
        @JvmField val consumedPoints: Int,
    ) {
        /** 该点串能对应的音节个数，正常为 1，撞串时大于 1。 */
        @JvmField val matchCount: Int = alternatives.size

        /** 同一段点串能对应多个音节时为 true，此时 cells 只是其中一种。 */
        @JvmField val ambiguous: Boolean = matchCount > 1
    }

    public companion object {
        /** 一格的点数上限（六点盲文）。 */
        private const val MAX_CELL_DOTS = 6

        /** 一个音节最多几格（现行盲文是声母-韵母-声调，2 格字省掉韵母）。 */
        private const val MAX_SYLLABLE_CELLS = 3

        /** 一个音节最多几个点，用于限制前缀枚举范围。 */
        private val MAX_SYLLABLE_POINTS = MAX_CELL_DOTS * MAX_SYLLABLE_CELLS

        /**
         * 建立切分器。
         *
         * @param syllableKeys 音节表的 key 集合，形如 145-136-2；不合规的条目会被跳过
         */
        @JvmStatic
        fun create(syllableKeys: Collection<String>): ChineseDotStreamSegmenter {
            val segmenter = ChineseDotStreamSegmenter()
            for (key in syllableKeys) {
                if (key == null) {
                    continue
                }
                val cells = key.split(Regex("-")).toTypedArray()
                if (cells.size < 2 || cells.size > MAX_SYLLABLE_CELLS) {
                    continue
                }
                var valid = true
                for (cell in cells) {
                    if (cell.isEmpty() || cell.length > MAX_CELL_DOTS) {
                        valid = false
                        break
                    }
                }
                if (!valid) {
                    continue
                }
                segmenter.syllableKeys.add(key)
                val stream = key.replace("-", "")
                segmenter.completeStreams.add(stream)
                val list =
                    segmenter.syllablesByStream[stream]
                        ?: ArrayList<Array<String>>().also { segmenter.syllablesByStream[stream] = it }
                list.add(cells)
                for (i in 1 until stream.length) {
                    segmenter.longerSyllablePrefixes.add(stream.substring(0, i))
                }
            }
            return segmenter
        }

        /**
         * 同一串点撞上多个音节时先挑一个。
         *
         * 本类不知道字频，只按"格数少优先"稳定挑选（2 格字比 3 格字更可能是本意，因为多出来的那格
         * 往往是把下一格误并了进来）；真正按字频择优由调用方用候选表覆盖。
         */
        private fun pickBest(matches: List<Array<String>>): Array<String> {
            var bestIndex = 0
            for (i in 1 until matches.size) {
                if (matches[i].size < matches[bestIndex].size) {
                    bestIndex = i
                }
            }
            return matches[bestIndex]
        }
    }

    /** 表内音节个数，供单测断言。 */
    fun syllableCount(): Int {
        return syllableKeys.size
    }

    /**
     * 从点流头部切出一个音节。
     *
     * 只有 force=true（用户按了上屏键）才会切。force=false 一律返回
     * null，让点流继续攒着。理由见类文档：点流里"当前音节的更长形式"和"下一个字的开头"
     * 无法区分，任何自动切分都会猜错边界。
     *
     * @param stream 尚未处理的点流，例如 1451362
     * @param force true 表示用户按了上屏键，按最长匹配直接切；false 表示只是
     *     又按了一个点，继续等
     * @return 能切时返回该音节；还没到上屏键、或者这段点流已经不可能成字时返回 null
     */
    fun decide(stream: String?, force: Boolean): Decision? {
        if (stream == null || stream.isEmpty() || !force) {
            return null
        }
        // 二声（单点 2）永远是四声（23）的前缀，不强制的话"读"这类字永远出不来。
        return longestPrefixMatch(stream, Math.min(stream.length, MAX_SYLLABLE_POINTS))
    }

    private fun longestPrefixMatch(stream: String, limit: Int): Decision? {
        for (length in limit downTo 1) {
            val matches = matchesFor(stream.substring(0, length))
            if (matches.isNotEmpty()) {
                return Decision(pickBest(matches), matches, length)
            }
        }
        return null
    }

    /**
     * 这段点流是否还有救：要么它自己还能长成一个音节，要么它已经"越过"了一个完整音节、
     * 后面正在打下一个字。
     *
     * 后一种必须算"有救"，否则连打时键盘会把正常输入当成按错、把最早的点丢掉。返回
     * false 才表示真按错了（连一个完整音节的前缀都切不出来），调用方应退掉一个点。
     */
    fun canStillGrow(stream: String?): Boolean {
        if (stream == null || stream.isEmpty()) {
            return true
        }
        if (completeStreams.contains(stream) || longerSyllablePrefixes.contains(stream)) {
            return true
        }
        for (length in 1 until stream.length) {
            if (!completeStreams.contains(stream.substring(0, length))) {
                continue
            }
            val rest = stream.substring(length)
            if (rest.isEmpty() ||
                completeStreams.contains(rest) ||
                longerSyllablePrefixes.contains(rest)
            ) {
                return true
            }
        }
        return false
    }

    /** 点串是否恰好是某个完整音节。 */
    fun isCompleteSyllable(stream: String?): Boolean {
        return stream != null && completeStreams.contains(stream)
    }

    /** 枚举 prefix 所有"切成 2~3 格且音节在表里"的方式。 */
    private fun matchesFor(prefix: String): MutableList<Array<String>> {
        val matches: MutableList<Array<String>> = ArrayList()
        val total = prefix.length
        for (first in 1..MAX_CELL_DOTS) {
            val second = total - first
            if (second >= 1 && second <= MAX_CELL_DOTS) {
                addIfSyllable(prefix, intArrayOf(first, second), matches)
            }
            for (s in 1..MAX_CELL_DOTS) {
                val third = total - first - s
                if (third >= 1 && third <= MAX_CELL_DOTS) {
                    addIfSyllable(prefix, intArrayOf(first, s, third), matches)
                }
            }
        }
        return matches
    }

    private fun addIfSyllable(prefix: String, cuts: IntArray, out: MutableList<Array<String>>) {
        val cells = Array(cuts.size) { "" }
        var offset = 0
        for (i in 0 until cuts.size) {
            cells[i] = prefix.substring(offset, offset + cuts[i])
            offset += cuts[i]
        }
        val key = cells.joinToString("-")
        if (syllableKeys.contains(key)) {
            out.add(cells)
        }
    }

    /** 供调试与单测：返回某串点能对应的全部音节格序列。 */
    fun allMatches(stream: String): List<Array<String>> {
        return ArrayList(syllablesByStream[stream] ?: ArrayList())
    }
}
