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
 * 尾部音节的候选游标：记住用户在哪个候选上，并能前后循环移动。
 *
 * 为什么不直接把它在 [EditBufferChinese] 里存成字段：每落一格都会把整串 holdings 重新切分
 * 一遍，字段只在“本次切分的最后”有效，一旦用户继续输入就会丢。改按“音节起始格下标”记在
 * map 里，追加输入时前缀的起始下标不变，用户的选择才能跨重算保留下来。
 *
 * 删除、提交让 holdings 变短后必须 [trimTo]，否则“删掉重打”会意外复用上一次的选择——
 * 用户看不见输入框，只会听到一个字跳成了他刚才选过的另一个字。
 *
 * 刻意不依赖 Android：候选逻辑全是纯粹的算术和状态，抽出来才能在 JVM 单测里直接覆盖。
 */
class CandidateCursor {

    /** 一段已经定下来的格区间。字段用 `@JvmField` 暴露，Java 测试与调用方仍按字段读。 */
    class BoundWord(
        @JvmField val start: Int,
        @JvmField val end: Int,
        /** 绑定时的点位串，用于确认这段格没有被删改过。 */
        @JvmField val dots: String,
        @JvmField val text: String,
    )

    /** 音节起始格下标 → 用户给这个音节选定的候选序号。 */
    private val choices = HashMap<Int, Int>()

    /**
     * 音节起始格下标 → 已经定下来的文本（可能是一个词，跨多格）。
     *
     * 为什么需要它：待确认的词一旦被用户确认（或选了非首个候选），后续每按一格都会把整串
     * holdings 重新切分一遍，那时词已经不在尾部，切分器会把它们拆成独立音节、按各自的候选重算。
     * 如果用户之前把「计算机」换成了别的候选，那个序号会套到单字候选上，蹦出一个不对的字。
     * 把结果连同**格区间**与**点位签名**记下来，重新切分时先按绑定输出、再跳过那段格，
     * 已定的字就不会被改写。
     */
    private val words = HashMap<Int, BoundWord>()

    /**
     * 取 [start] 位置音节的选中序号。
     *
     * 没有记录（用户还没换过）或记录越界（候选列表比上次短）时归 0，也就是字频最高的那个，
     * 并写回 map，保证后续 [shift] 从一个确定的位置起算。
     */
    fun indexFor(start: Int, count: Int): Int {
        if (count <= 0) {
            return 0
        }
        var index = choices[start] ?: 0
        if (index < 0 || index >= count) {
            index = 0
        }
        choices[start] = index
        return index
    }

    /**
     * 从 [current] 前后移动一格并写回，返回新序号。
     *
     * 循环而不是夹紧：候选有几十个时夹紧会让用户滑到最后一个就再也回不去（只能反向滑），
     * 循环则一直同方向滑总能绕回来。候选只有一个时原样返回，此时调用方本来也不该进来。
     */
    fun shift(start: Int, current: Int, count: Int, delta: Int): Int {
        if (count <= 1) {
            return current
        }
        // 与 Java 的 Math.floorMod 同语义：负 delta 也绕到末尾，不会把序号夹成 0。
        val next = ((current + delta) % count + count) % count
        choices[start] = next
        return next
    }

    /** 丢弃起始下标已不存在的记录（holdings 被删短或清空后调用）。 */
    fun trimTo(holdingsSize: Int) {
        choices.keys.removeIf { start -> start >= holdingsSize }
        words.keys.removeIf { start -> start >= holdingsSize }
        words.values.removeIf { word -> word.end > holdingsSize }
    }

    /** 把一段格区间的文本定下来，之后重新切分时不再重算它。 */
    fun bindWord(start: Int, end: Int, dots: String, text: CharSequence?) {
        val resolved = text?.toString()
        if (start < 0 || end <= start || resolved.isNullOrEmpty()) {
            return
        }
        words[start] = BoundWord(start, end, dots, resolved)
    }

    /** 该起始格上已经定下来的文本；没有则返回 `null`。 */
    fun boundWordAt(start: Int): BoundWord? = words[start]

    /** 绑定条数，仅供测试与诊断。 */
    fun wordCount(): Int = words.size

    /** 当前记录的条目数，仅供测试与诊断。 */
    fun size(): Int = choices.size
}
