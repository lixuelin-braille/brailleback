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
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.zip.GZIPInputStream

/**
 * 拼音侧索引：把「点位串」连到「拼音」，再把「拼音串」连到「词」。
 *
 * 中文盲文输入原来是「点位串 → 单字」一条直线，没有拼音这个中间量，所以仓库里 34.9 万词的
 * 词库（jiebadt.txt）永远够不着输入侧。本类补上这一层，数据来自两个离线产出的资源：
 *
 * - assets/cell_pinyin.txt.gz：点位串（145-136-2）→ 去调拼音（du），
 *   由 zhcn-g1.ctb 的 1318 个音节与 pypinyin 反推、多音字多数表决得到；
 * - assets/pinyin_index.txt.gz：去调拼音串（beijing）→ 词/字 + 词频，由
 *   34.9 万词的 jieba 词库转拼音、按词频截断（≥100）后倒排。
 *
 * 单字条目同时当字频表用：的=318825、得=134479，比原来 616 字的手工常用表细得多，
 * 用来给同音字排序。
 *
 * 资源缺失或解析失败时 getInstance 返回 null，调用方必须能退化到旧路径。
 */
class PinyinIndex private constructor(
    /** 去调拼音串 → 候选词（按词频降序，同频按字典序保证稳定）。 */
    private val words: Map<String, List<Word>>,

    /** 单字 → 字频。仅收录词库中出现、且词频 ≥ 阈值（≥100）的字。 */
    private val charFrequencies: Map<Char, Int>,

    /** 点位串 → 去调拼音，例如 145-136-2 → du。 */
    private val cellPinyin: Map<String, String>,
) {

    /** 一个词条：词本身与词频。 */
    class Word(
        @JvmField val text: String,
        @JvmField val frequency: Int,
    ) : Comparable<Word> {

        override fun compareTo(other: Word): Int {
            if (frequency != other.frequency) {
                return other.frequency - frequency
            }
            return text.compareTo(other.text)
        }

        override fun toString(): String {
            return text + "(" + frequency + ")"
        }
    }

    /** 字频；不在表里返回 0。 */
    fun charFrequency(ch: Char): Int {
        val frequency = charFrequencies[ch]
        return frequency ?: 0
    }

    /** 点位串对应的去调拼音；查不到返回 null。 */
    fun syllablePinyin(dots: String): String? {
        return cellPinyin[dots]
    }

    /**
     * 一串音节（按去调拼音给）恰好组成词时返回词候选，否则返回空列表。
     *
     * 调用方拿到的候选吃掉全部音节对应的格，不能只用一格。
     */
    fun wordsForSyllables(syllablePinyins: List<String>?): List<Word> {
        if (syllablePinyins == null || syllablePinyins.isEmpty()) {
            return emptyList()
        }
        return words[syllablePinyins.joinToString("")] ?: emptyList()
    }

    /** 同 wordsForSyllables，方便按可变参数调用。 */
    fun wordsFor(vararg syllablePinyins: String): List<Word> {
        val key = StringBuilder()
        for (syllable in syllablePinyins) {
            if (syllable == null || syllable.isEmpty()) {
                return emptyList()
            }
            key.append(syllable)
        }
        return words[key.toString()] ?: emptyList()
    }

    companion object {
        private const val TAG = "BBPinyin"

        /**
         * 资源名。
         *
         * 注意：.gz 结尾的输入资源在打包时会被 aapt 自动解压并去掉 .gz 后缀
         * （见 assets/pinyin_index.txt，627 KB 明文），所以这里按明文名读，同时保留对
         * .txt.gz 的兜底，避免哪天打包行为变了又静默失败。
         */
        private const val WORD_ASSET = "pinyin_index.txt"
        private const val WORD_ASSET_GZ = "pinyin_index.txt.gz"
        private const val CELL_ASSET = "cell_pinyin.txt"
        private const val CELL_ASSET_GZ = "cell_pinyin.txt.gz"

        @Volatile
        private var instance: PinyinIndex? = null

        @Volatile
        private var loadFailed = false

        /** 进程内单例；资源缺失时返回 null，且不再重试，避免每次按键都抛异常。 */
        @JvmStatic
        fun getInstance(context: Context): PinyinIndex? {
            var local = instance
            if (local != null || loadFailed) {
                return local
            }
            synchronized(PinyinIndex::class.java) {
                if (instance == null && !loadFailed) {
                    val started = System.currentTimeMillis()
                    try {
                        val loaded = load(context.applicationContext)
                        instance = loaded
                        Log.i(
                            TAG,
                            "ready in " +
                                (System.currentTimeMillis() - started) +
                                "ms: words=" +
                                loaded.words.size +
                                " chars=" +
                                loaded.charFrequencies.size +
                                " cells=" +
                                loaded.cellPinyin.size,
                        )
                    } catch (e: IOException) {
                        loadFailed = true
                        Log.e(TAG, "failed to load pinyin index", e)
                        return null
                    } catch (e: RuntimeException) {
                        loadFailed = true
                        Log.e(TAG, "failed to load pinyin index", e)
                        return null
                    }
                }
                return instance
            }
        }

        // ---------------------------------------------------------------------------------------------
        // 解析

        private fun load(context: Context): PinyinIndex {
            val words: MutableMap<String, List<Word>> = HashMap(40000)
            val charFrequencies: MutableMap<Char, Int> = HashMap(8192)
            val cellPinyin: MutableMap<String, String> = HashMap(2048)

            openAsset(context, WORD_ASSET, WORD_ASSET_GZ).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val current = line!!
                    val tab = current.indexOf('\t')
                    if (tab <= 0 || tab == current.length - 1) {
                        continue
                    }
                    val key = current.substring(0, tab)
                    val entries: MutableList<Word> = ArrayList(4)
                    for (item in current.substring(tab + 1).split(",")) {
                        val colon = item.lastIndexOf(':')
                        if (colon <= 0) {
                            continue
                        }
                        val text = item.substring(0, colon)
                        val frequency =
                            try {
                                item.substring(colon + 1).toInt()
                            } catch (e: NumberFormatException) {
                                continue
                            }
                        entries.add(Word(text, frequency))
                        if (text.length == 1) {
                            charFrequencies[text[0]] = frequency
                        }
                    }
                    if (entries.isNotEmpty()) {
                        Collections.sort(entries)
                        words[key] = Collections.unmodifiableList(entries)
                    }
                }
            }

            openAsset(context, CELL_ASSET, CELL_ASSET_GZ).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val current = line!!
                    val tab = current.indexOf('\t')
                    if (tab <= 0 || tab == current.length - 1) {
                        continue
                    }
                    cellPinyin[current.substring(0, tab)] = current.substring(tab + 1)
                }
            }

            return PinyinIndex(
                Collections.unmodifiableMap(words),
                Collections.unmodifiableMap(charFrequencies),
                Collections.unmodifiableMap(cellPinyin),
            )
        }

        /** 按明文资源读；明文不存在时退到同名 .gz（个别打包配置会保留压缩文件）。 */
        private fun openAsset(context: Context, plain: String, gzipped: String): BufferedReader {
            return try {
                val raw = context.assets.open(plain)
                BufferedReader(InputStreamReader(raw, StandardCharsets.UTF_8))
            } catch (e: IOException) {
                val gz = context.assets.open(gzipped)
                BufferedReader(InputStreamReader(GZIPInputStream(gz), StandardCharsets.UTF_8))
            }
        }
    }
}
