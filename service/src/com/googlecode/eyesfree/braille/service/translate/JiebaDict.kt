/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 *
 * JiebaDict - 基于 jieba 词典的 DAG + 最大概率路径中文分词器（自实现，零第三方依赖）。
 * 算法与 jieba (HMM=False) 一致：词典加载 freq + 前缀表 -> DAG -> 动态规划最大概率路径。
 * （Kotlin 版，与原 Java 版逻辑完全一致）
 */

package com.googlecode.eyesfree.braille.service.translate

import android.util.Log
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader

class JiebaDict {

    private val freqMap = HashMap<String, Int>()
    private var logTotal = 0.0
    @Volatile
    private var initialized = false

    fun isInitialized(): Boolean {
        return initialized
    }

    /** 从 UTF-8 词典流初始化（每行：word freq [pos]）。 */
    @Synchronized
    fun init(dictStream: InputStream) {
        if (initialized) {
            return
        }
        try {
            val br = BufferedReader(InputStreamReader(dictStream, "UTF-8"))
            val rows = ArrayList<Array<String>>()
            var total = 0L
            var line: String?
            while (true) {
                line = br.readLine() ?: break
                line = line.trim()
                if (line.isEmpty()) {
                    continue
                }
                val p = line.split("\\s+".toRegex()).toTypedArray()
                if (p.size < 2) {
                    continue
                }
                val w = p[0]
                val f = p[1].toInt()
                rows.add(arrayOf(w, f.toString()))
                total += f
                // 前缀加入 freqMap（值为 0），供 DAG 前缀遍历
                for (i in 1 until w.length) {
                    val pre = w.substring(0, i)
                    if (!freqMap.containsKey(pre)) {
                        freqMap[pre] = 0
                    }
                }
            }
            logTotal = Math.log(total.toDouble())
            for (r in rows) {
                freqMap[r[0]] = r[1].toInt()
            }
            initialized = true
            Log.i(LOG_TAG, "dict loaded, words=" + freqMap.size)
        } catch (e: Exception) {
            Log.e(LOG_TAG, "init failed", e)
        }
    }

    /** 增加自定义词（高优先），用于专名/连写词。 */
    @Synchronized
    fun addWord(word: String, freq: Int) {
        if (word.isEmpty()) {
            return
        }
        freqMap[word] = freq
        for (i in 1 until word.length) {
            val pre = word.substring(0, i)
            if (!freqMap.containsKey(pre)) {
                freqMap[pre] = 0
            }
        }
    }

    /**
     * 加载补充词库（每行：词 词频，支持 # 注释）。
     * 只添加主词典中不存在的完整词，不覆盖主词典词频，也不改变主词典的全局词频总量。
     * 用于低频补充阳光读屏词库中的缺失词。
     *
     * @return 实际新增的词数
     */
    @Synchronized
    fun loadSupplement(supplementStream: InputStream): Int {
        if (!initialized) {
            Log.w(LOG_TAG, "supplement ignored: main dict not initialized")
            return 0
        }
        return loadWordList(supplementStream, false, "supplement")
    }

    /**
     * 加载高优先级覆盖词表（每行：词 词频，支持 # 注释）。
     * 与补充词库不同：同名主词典词会被覆盖词频，用于纠正明显偏低的词频，
     * 或补充专名/领域固定搭配。不改变主词典的全局词频总量。
     *
     * @return 实际加载的词数
     */
    @Synchronized
    fun loadOverrides(overrideStream: InputStream): Int {
        if (!initialized) {
            Log.w(LOG_TAG, "overrides ignored: main dict not initialized")
            return 0
        }
        return loadWordList(overrideStream, true, "overrides")
    }

    private fun loadWordList(stream: InputStream, overrideExisting: Boolean, label: String): Int {
        var added = 0
        try {
            BufferedReader(InputStreamReader(stream, "UTF-8")).use { br ->
                while (true) {
                    val line = br.readLine() ?: break
                    val l = line.trim()
                    if (l.isEmpty() || l.startsWith("#")) {
                        continue
                    }
                    val p = l.split("\\s+".toRegex()).toTypedArray()
                    if (p.size < 2) {
                        continue
                    }
                    val w = p[0]
                    val f = p[1].toIntOrNull() ?: continue
                    if (w.isEmpty() || f <= 0 || w.any { it.code !in 0x4E00..0x9FFF }) {
                        continue
                    }
                    val existing = freqMap[w]
                    if (!overrideExisting && existing != null && existing > 0) {
                        continue
                    }
                    freqMap[w] = f
                    for (i in 1 until w.length) {
                        val pre = w.substring(0, i)
                        if (!freqMap.containsKey(pre)) {
                            freqMap[pre] = 0
                        }
                    }
                    added++
                }
            }
        } catch (e: Exception) {
            Log.e(LOG_TAG, label + " load failed", e)
            return added
        }
        Log.i(LOG_TAG, label + " loaded, count=" + added)
        return added
    }

    /** 删除词（用于应分写的整体词），使其被切分。 */
    @Synchronized
    fun delWord(word: String) {
        freqMap.remove(word)
    }

    /** 是否为词典中的完整词。 */
    fun isWord(word: String): Boolean {
        val f = freqMap[word]
        return f != null && f > 0
    }

    /**
     * 词频。非完整词（仅作为前缀登记、值为 0）返回 0。
     * 供分词规则比较「跨拆分点双字」与两侧半词的常见程度。
     */
    fun freq(word: String): Int {
        val f = freqMap[word]
        return if (f != null && f > 0) f else 0
    }

    /** 构建 DAG：每个位置 k -> 可达结束位置列表（含 k 自身）。 */
    private fun buildDag(sentence: String): Map<Int, List<Int>> {
        val dag = HashMap<Int, List<Int>>()
        val n = sentence.length
        for (k in 0 until n) {
            val list = ArrayList<Int>()
            var i = k
            var frag = sentence.substring(k, k + 1)
            while (i < n && freqMap.containsKey(frag)) {
                val f = freqMap[frag]
                if (f != null && f > 0) {
                    list.add(i)
                }
                i++
                if (i < n) {
                    frag = sentence.substring(k, i + 1)
                } else {
                    break
                }
            }
            if (list.isEmpty()) {
                list.add(k)
            }
            dag[k] = list
        }
        return dag
    }

    /** 分词并返回词及位置：[word, start, end]（相对输入字符串）。 */
    fun segmentWithPos(sentence: String): List<Array<String>> {
        val result = ArrayList<Array<String>>()
        if (sentence.isEmpty()) {
            return result
        }
        val dag = buildDag(sentence)
        val n = sentence.length
        val score = DoubleArray(n + 1)
        val end = IntArray(n + 1)
        score[n] = 0.0
        end[n] = n
        for (i in n - 1 downTo 0) {
            var best = Double.NEGATIVE_INFINITY
            var bestX = i
            for (x in dag[i]!!) {
                val w = sentence.substring(i, x + 1)
                var f = if (freqMap.containsKey(w)) freqMap[w]!! else 1
                if (f <= 0) {
                    f = 1
                }
                val s = (Math.log(f.toDouble()) - logTotal) + score[x + 1]
                if (s > best) {
                    best = s
                    bestX = x
                }
            }
            score[i] = best
            end[i] = bestX
        }
        var i = 0
        while (i < n) {
            val x = end[i]
            result.add(arrayOf(sentence.substring(i, x + 1), i.toString(), (x + 1).toString()))
            i = x + 1
        }
        return result
    }

    /** 分词：返回词列表（无 HMM）。 */
    fun segment(sentence: String): List<String> {
        val words = ArrayList<String>()
        if (sentence.isEmpty()) {
            return words
        }
        val dag = buildDag(sentence)
        val n = sentence.length
        val score = DoubleArray(n + 1)
        val end = IntArray(n + 1)
        score[n] = 0.0
        end[n] = n
        for (i in n - 1 downTo 0) {
            var best = Double.NEGATIVE_INFINITY
            var bestX = i
            for (x in dag[i]!!) {
                val w = sentence.substring(i, x + 1)
                var f = if (freqMap.containsKey(w)) freqMap[w]!! else 1
                if (f <= 0) {
                    f = 1
                }
                val s = (Math.log(f.toDouble()) - logTotal) + score[x + 1]
                if (s > best) {
                    best = s
                    bestX = x
                }
            }
            score[i] = best
            end[i] = bestX
        }
        var i = 0
        while (i < n) {
            val x = end[i]
            words.add(sentence.substring(i, x + 1))
            i = x + 1
        }
        return words
    }

    companion object {
        private const val LOG_TAG = "JiebaDict"
    }
}
