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
import android.util.Log
import com.google.common.collect.ImmutableList
import com.google.common.collect.ImmutableMap
import com.google.common.collect.ImmutableSet
import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.util.Comparator
import java.util.regex.Pattern
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * 中文现行盲文（zhcn-g1.ctb）的音节索引。
 *
 * 汉字在现行盲文里不是"一格一字"：最小单位是音节，由「声母-韵母-声调」2~3 格组成。
 * zhcn-g1.ctb 的 20974 条 sign 定义里，2 格 3859 条、3 格 17054 条，
 * 没有任何一格汉字——这就是"点一格只会得到数字"的原因（单格 1~6 点的反译目标是
 * digit 定义）。
 *
 * liblouis 的整串反译按规则长度匹配，连续多字时 2格+2格 会被切成 3格+1格。
 * 本类把表里所有 2/3 格 key 提取出来，供 EditBufferChinese 做贪心音节切分，从而
 * 消除跨字歧义、避免已上屏的字被重新分词改写。
 *
 * 索引只在首次使用时构建一次（解析 676KB 文本，约 2 万行）。
 */
class ChineseBrailleTableIndex private constructor(
    candidatesInput: Map<String, List<String>>,
    threeCellPrefixesInput: Set<String>,
    /** 拼音侧索引（字频、点位串→拼音、拼音串→词）；资源缺失时为 null。 */
    val pinyinIndex: PinyinIndex?,
) {

    /** 点位串（如 125-35-1）→ 该编码对应的汉字候选，顺序为表内出现顺序。 */
    private val candidates: ImmutableMap<String, ImmutableList<String>>

    /** 存在 3 格延伸的 2 格前缀。落满 2 格时若命中这里，应等第 3 格再决定。 */
    private val threeCellPrefixes: ImmutableSet<String>

    /**
     * 能作为某个音节第 1 格的单格点位集合。
     *
     * 落下一格后若命中这里，说明它可能只是声母、后面还要接韵母和声调，此时不能把它当
     * 独立字符翻译（否则会被翻成数字，看起来像已经上屏），必须先挂着等后续格。
     */
    private val syllableFirstCells: ImmutableSet<String>

    /** 点流切分器，首次使用时构建，之后常驻。见 getDotStreamSegmenter。 */
    @Volatile
    private var dotStreamSegmenter: ChineseDotStreamSegmenter? = null

    init {
        val builder = ImmutableMap.Builder<String, ImmutableList<String>>()
        for (entry in candidatesInput.entries) {
            builder.put(entry.key, ImmutableList.copyOf(entry.value))
        }
        candidates = builder.build()
        threeCellPrefixes = ImmutableSet.copyOf(threeCellPrefixesInput)

        val firstCells: MutableSet<String> = HashSet()
        for (dots in candidatesInput.keys) {
            val dash = dots.indexOf('-')
            if (dash > 0) {
                firstCells.add(dots.substring(0, dash))
            }
        }
        syllableFirstCells = ImmutableSet.copyOf(firstCells)
    }

    /** 该点位串对应的汉字候选；没有则返回空列表。 */
    fun getCandidates(dots: String): ImmutableList<String> {
        return candidates[dots] ?: ImmutableList.of()
    }

    /** 该点位串是否是某个 3 格字的 2 格前缀。 */
    fun isThreeCellPrefix(twoCellDots: String): Boolean {
        return threeCellPrefixes.contains(twoCellDots)
    }

    /** 该点位串是否有直接对应的汉字（2 格或 3 格）。 */
    fun hasCandidate(dots: String): Boolean {
        return candidates.containsKey(dots)
    }

    /**
     * 该点位串最常用候选的常用度权重，越小越常用；没有候选时返回 Integer.MAX_VALUE。
     *
     * 只给点流切分用：同一串点撞上两个音节时（如 12-35-2 拔 与 1235-2 儿
     * 都对应点串 12352），用两边首选字的常用度择优，比"格数少优先"这种几何规则靠谱得多。
     */
    fun topCandidateWeight(dots: String): Int {
        val list = getCandidates(dots)
        if (list.isEmpty()) {
            return Integer.MAX_VALUE
        }
        return sortWeight(list[0][0], pinyinIndex)
    }

    /** 该单格是否是某个合法音节的第一格；是则说明后面还可能接韵母/声调，需挂起等待。 */
    fun canStartSyllable(singleCellDots: String): Boolean {
        return syllableFirstCells.contains(singleCellDots)
    }

    /** 表内全部音节的点位 key（形如 145-136-2），供点流切分器建索引。 */
    fun syllableKeys(): ImmutableSet<String> {
        return candidates.keys
    }

    /**
     * 点流切分器（不依赖时间、按音节整串识别）。
     *
     * 懒建：只在第一次真正需要时遍历两万个 key，避免拖慢输入法启动。
     */
    fun getDotStreamSegmenter(): ChineseDotStreamSegmenter {
        var local = dotStreamSegmenter
        if (local != null) {
            return local
        }
        synchronized(this) {
            if (dotStreamSegmenter == null) {
                dotStreamSegmenter = ChineseDotStreamSegmenter.create(candidates.keys)
            }
            local = dotStreamSegmenter!!
        }
        return local!!
    }

    // ---------------------------------------------------------------------------------------------
    // 解析

    /** 仅供测试统计。 */
    fun size(): Int {
        return candidates.size
    }

    /** 仅供测试。 */
    fun asMap(): Map<String, List<String>> {
        val copy: MutableMap<String, List<String>> = HashMap()
        candidates.forEach { key, value -> copy[key] = value }
        return copy
    }

    companion object {
        private const val TAG = "BBChinese"
        private const val TABLE_NAME = "zhcn-g1.ctb"
        private const val ZIP_ENTRY = "liblouis/tables/" + TABLE_NAME

        /** \x54C8 之类的 Unicode 转义。 */
        private val UNICODE_ESCAPE = Pattern.compile("\\\\x([0-9A-Fa-f]{4,6})")

        /**
         * 形如 125-35-1 的点位串。
         *
         * 声调位允许 0（如 sign \x554A 35-0），但 0 在 liblouis 的
         * parseDots 里是"空单元"（源码里该分支的注释就是 blank），点字键盘根本产生不了空单元，
         * 所以这类定义在输入链路上不可达，解析时由 hasBlankCell 排除。
         */
        private val DOTS = Pattern.compile("[0-6]+(-[0-6]+)*")

        @Volatile
        private var instance: ChineseBrailleTableIndex? = null

        /** 拿到进程内单例；表缺失或解析失败时返回 null，调用方应降级到整串翻译。 */
        @JvmStatic
        fun getInstance(context: Context): ChineseBrailleTableIndex? {
            var local = instance
            if (local != null) {
                return local
            }
            synchronized(ChineseBrailleTableIndex::class.java) {
                if (instance == null) {
                    try {
                        val loaded = load(context.applicationContext)
                        instance = loaded
                        Log.i(
                            TAG,
                            "index ready: keys=" +
                                loaded.candidates.size +
                                " threeCellPrefixes=" +
                                loaded.threeCellPrefixes.size,
                        )
                    } catch (e: IOException) {
                        Log.e(TAG, "failed to build Chinese index", e)
                        return null
                    } catch (e: RuntimeException) {
                        Log.e(TAG, "failed to build Chinese index", e)
                        return null
                    }
                }
                return instance
            }
        }

        @Throws(IOException::class)
        private fun load(context: Context): ChineseBrailleTableIndex {
            val pinyinIndex = PinyinIndex.getInstance(context)
            val raw: InputStream =
                context
                    .resources
                    .openRawResource(
                        com.google.android.accessibility.braille.translate.R.raw.translationtables,
                    )
            ZipInputStream(BufferedInputStream(raw)).use { zip ->
                var entry: ZipEntry?
                while (zip.getNextEntry().also { entry = it } != null) {
                    if (ZIP_ENTRY != entry!!.name) {
                        continue
                    }
                    val reader = BufferedReader(InputStreamReader(zip, StandardCharsets.UTF_8))
                    return parse(reader, pinyinIndex)
                }
            }
            throw IOException(ZIP_ENTRY + " not found in translation tables")
        }

        /** 解析 ctb 文本流，构建索引。包内可见以便单元测试直接喂字符串。 */
        @JvmStatic
        @Throws(IOException::class)
        fun parse(reader: BufferedReader): ChineseBrailleTableIndex {
            return parse(reader, null)
        }

        /**
         * 解析 ctb 文本流并用 pinyinIndex 的字频排序候选。
         *
         * 没有字频时退化成原有的 616 字常用表和 GB2312 一级字两级排序。
         */
        @JvmStatic
        @Throws(IOException::class)
        fun parse(reader: BufferedReader, pinyinIndex: PinyinIndex?): ChineseBrailleTableIndex {
            val collected: MutableMap<String, MutableList<String>> = LinkedHashMap<String, MutableList<String>>()

            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val trimmed = line!!.trim()
                if (trimmed.isEmpty() || trimmed[0] == '#') {
                    continue
                }
                val parts = trimmed.split(Regex("\\s+"))
                // 只认 "sign \xXXXX dots"，跳过 "noback sign ..."（反译不可用）等其它 opcode。
                if (parts.size < 3 || parts[0] != "sign") {
                    continue
                }
                val escape = UNICODE_ESCAPE.matcher(parts[1])
                if (!escape.matches()) {
                    continue
                }
                val dots = parts[2]
                if (!DOTS.matcher(dots).matches()) {
                    continue
                }
                if (hasBlankCell(dots)) {
                    // 含空单元（0）的定义输入不可达：点字键盘只会产生 1~6 点的格。

                    continue
                }
                val cells = countCells(dots)
                // 单格出不了汉字；4 格以上（表内仅 61 条）不参与音节切分。
                if (cells < 2 || cells > 3) {
                    continue
                }
                val character = toCharacter(escape.group(1)) ?: continue
                val list =
                    collected[dots] ?: ArrayList<String>().also { collected[dots] = it }
                list.add(character)
            }

            val prefixes: MutableSet<String> = HashSet()
            for (dots in collected.keys) {
                if (countCells(dots) == 3) {
                    prefixes.add(dots.substring(0, dots.lastIndexOf('-')))
                }
            }
            for (list in collected.values) {
                list.sortWith(commonFirst(pinyinIndex))
            }
            return ChineseBrailleTableIndex(collected, prefixes, pinyinIndex)
        }

        /** GBK 与 GB2312 兼容，一级汉字区（3755 个常用字）的编码范围是 0xB0A1–0xD7F9。 */
        private val GBK = Charset.forName("GBK")

        /**
         * 高频字表（按使用频率降序，616 字，全部落在 GB2312 内）。
         *
         * 只用"是否 GB2312 一级字"排序是不够的：一级字有 3755 个，同一个音节里常挤进十几个，
         * 稳定排序后仍然先吐出"士"而不是"是"——实测"是"在 156-23 的 85 个候选里排第 26 位，
         * "你"在 1345-24-3 里排第 3 位且前面是"聻、拟"。按真实字频排序，才能让"是/你/国"这类
         * 字排到同音字最前面，用户打完一个音节就直接得到想要的字。
         */
        private const val HIGH_FREQUENCY_CHARS =
            "的一是了我不人在他有这个上们来到时大地为子中你说生国年着就那和要她出也得里后自以会家" +
                "可下而过天去能对小多然于心学么之都好看起发当没成只如事把还用第样道想作种开美总从无情" +
                "己面最女但现前些所同日手又行意动方期它头经长儿回位分爱老因很给名法间斯知世什两次使身" +
                "者被高已亲其进此话常与活正感见明问力理尔点文几定本公特做外孩相西果走将月十实向声车全" +
                "信重三机工物气每并别真打太新比才便夫再书部水像眼等体却加电主界门利海受听表德少克代员" +
                "许先口由死安写性马光白或住难望教命花结乐色更拉东神记处让母父应直字场平报友关放至张认" +
                "接告入笑内英军候民岁往何度山觉路带万男边风解叫任金快原吃妈变通师立象数四失满战远格士" +
                "音轻目条呢病始达深完今提求清王化空业思切怎非找片罗钱吗语元喜曾离飞科言干流欢约各即指" +
                "合反题必该论交终林请医晚制球决传画保读运及则房早院量苦火布品近坐产答星精视五连司巴奇" +
                "管类未朋且婚台夜青北队久乎越观落尽形影红爸百令周吧识步希亚术留市半热送兴造谈容极随演" +
                "收首根讲整式取照办强石古华拿计您装似足双妻尼转诉米称丽客南领节衣站黑刻统断福城故历惊" +
                "脸选包紧争另建维绝树系伤示愿持千史谁准联妇纪基买志静阿诗独复痛消社算义竟确酒需单治卡" +
                "幸兰念举仅钟怕共毛句息功官待究跟穿室易游程号居考突皮哪费倒价图具刚脑永歌响商礼细专黄" +
                "块脚味灵改据般破引食仍存众注笔甚某沉血备习校默务土微娘须试怀料调广苏显赛查密议底列富" +
                "梦错座参八除跑亮假印设线温虽掉京初养香停际致阳纸李纳验助"

        /** 字符 → 在 HIGH_FREQUENCY_CHARS 中的名次。 */
        private val HIGH_FREQUENCY_RANK: Map<Char, Int> = buildFrequencyRank()

        private fun buildFrequencyRank(): MutableMap<Char, Int> {
            val rank: MutableMap<Char, Int> = HashMap(HIGH_FREQUENCY_CHARS.length * 2)
            for (i in 0 until HIGH_FREQUENCY_CHARS.length) {
                rank[HIGH_FREQUENCY_CHARS[i]] = i
            }
            return rank
        }

        /**
         * 候选排序：有真实字频的字按词频降序排在最前，其次是 616 字常用表，再次是 GB2312 一级字，
         * 最后是生僻字。
         *
         * 原来的两级排序只在"这个字够不够常见"上做粗分，同一个音节里十几个常用字仍然按表内
         * 顺序排——「的」能靠手工表排到第一，但「读」这类不在 616 表里的常用字只能和生僻字混在一起。
         * 接上 jieba 单字词频（的=318825）后，同音字直接按真实频次排。
         */
        private const val FREQ_BASE = 1_000_000

        private fun commonFirst(pinyinIndex: PinyinIndex?): Comparator<String> {
            return Comparator.comparingInt<String> { candidate ->
                sortWeight(candidate[0], pinyinIndex)
            }
        }

        private fun sortWeight(ch: Char, pinyinIndex: PinyinIndex?): Int {
            val frequency = pinyinIndex?.charFrequency(ch) ?: 0
            if (frequency > 0) {
                return FREQ_BASE - Math.min(frequency, FREQ_BASE - 1)
            }
            val rank = HIGH_FREQUENCY_RANK[ch]
            if (rank != null) {
                return FREQ_BASE + rank
            }
            // 一级字统一排在所有高频字之后，生僻字再往后。
            return FREQ_BASE + HIGH_FREQUENCY_CHARS.length + (if (isCommonChinese(ch)) 0 else 1)
        }

        /** 判断字符是否在 HIGH_FREQUENCY_CHARS 高频字表里。 */
        @JvmStatic
        fun isHighFrequency(ch: Char): Boolean {
            return HIGH_FREQUENCY_RANK.containsKey(ch)
        }

        /** 判断字符是否落在 GB2312 一级字库（3755 个常用汉字）内。 */
        @JvmStatic
        fun isCommonChinese(ch: Char): Boolean {
            if (ch.code < 0x4E00 || ch.code > 0x9FFF) {
                return false
            }
            val encoded = ch.toString().toByteArray(GBK)
            return encoded.size == 2 &&
                (encoded[0].toInt() and 0xFF) >= 0xB0 &&
                (encoded[0].toInt() and 0xFF) <= 0xD7
        }

        /** 点位串里是否含空单元（liblouis 的 0）。 */
        private fun hasBlankCell(dots: String): Boolean {
            for (cell in dots.split(Regex("-"))) {
                if ("0" == cell) {
                    return true
                }
            }
            return false
        }

        private fun countCells(dots: String): Int {
            var cells = 1
            for (i in 0 until dots.length) {
                if (dots[i] == '-') {
                    cells++
                }
            }
            return cells
        }

        private fun toCharacter(hex: String?): String? {
            return try {
                val codePoint = hex!!.toInt(16)
                if (codePoint <= 0 || codePoint > Character.MAX_CODE_POINT) {
                    return null
                }
                String(Character.toChars(codePoint))
            } catch (e: NumberFormatException) {
                null
            }
        }
    }
}
