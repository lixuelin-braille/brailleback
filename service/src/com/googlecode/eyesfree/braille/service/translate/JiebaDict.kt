/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 *
 * JiebaDict - 基于 jieba 词典的 DAG + 最大概率路径中文分词器（自实现，零第三方依赖）。
 * 算法与 jieba 一致：词典加载 freq + 前缀表 -> DAG -> 动态规划最大概率路径，
 * 再按 jieba `__cut_DAG` 的语义对未登录词缓冲区跑 finalseg HMM（可关闭）。
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
    // jieba finalseg HMM：未登录词识别。加载失败时保持关闭，分词退回纯 DAG。
    private val hmm = HmmSegmenter()
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

    /**
     * 加载 HMM 发射概率表（assets/hmm_emit.txt）。
     * 失败只告警并保持 HMM 关闭，不影响 DAG 分词与翻译。
     *
     * @return 读入的字符数，0 表示失败
     */
    @Synchronized
    fun loadHmm(stream: InputStream): Int {
        val n = hmm.load(stream)
        if (n <= 0) {
            Log.w(LOG_TAG, "hmm load failed, fallback to DAG only")
            return 0
        }
        Log.i(LOG_TAG, "hmm loaded, chars=" + n)
        return n
    }

    /** HMM 是否可用（发射概率表已成功加载）。 */
    fun isHmmReady(): Boolean {
        return hmm.isReady()
    }

    /**
     * 按 jieba `__cut_DAG` 的语义补做未登录词识别。
     *
     * 只有「连续单字 token 组成的缓冲区」才会被送进 HMM，且缓冲区整体
     * 命中词典时仍逐字输出——这与 jieba 的 `if not FREQ.get(buf)` 判断一致。
     *
     * [protectedBoundary] 中为 true 的下标 i 表示第 i 个 token 之前不允许合并，
     * 用于保护用户词典命中的词面边界。
     */
    fun withHmm(
        words: List<Array<String>>,
        protectedBoundary: BooleanArray? = null
    ): List<Array<String>> {
        if (!hmm.isReady() || words.size < 2) {
            return words
        }
        val out = ArrayList<Array<String>>(words.size)
        var bufFrom = -1
        var bufTo = -1
        for (i in words.indices) {
            val surface = words[i][0]
            val singleCjk = surface.length == 1 && isCjkChar(surface[0])
            if (!singleCjk) {
                flushHmmBuffer(out, words, bufFrom, bufTo)
                bufFrom = -1
                bufTo = -1
                out.add(words[i])
                continue
            }
            if (bufFrom < 0) {
                bufFrom = i
                bufTo = i
                continue
            }
            if (protectedBoundary != null && i < protectedBoundary.size
                && protectedBoundary[i]
            ) {
                flushHmmBuffer(out, words, bufFrom, bufTo)
                bufFrom = i
                bufTo = i
            } else {
                bufTo = i
            }
        }
        flushHmmBuffer(out, words, bufFrom, bufTo)
        return out
    }

    private fun flushHmmBuffer(
        out: MutableList<Array<String>>,
        words: List<Array<String>>,
        from: Int,
        to: Int
    ) {
        if (from < 0 || to < from) {
            return
        }
        if (from == to) {
            out.add(words[from])
            return
        }
        val sb = StringBuilder(to - from + 1)
        for (k in from..to) {
            sb.append(words[k][0])
        }
        val text = sb.toString()
        // 缓冲区本身是词典词：保持 jieba 的逐字输出，不交给 HMM。
        if (isWord(text)) {
            for (k in from..to) {
                out.add(words[k])
            }
            return
        }
        var pos = words[from][1].toIntOrNull() ?: 0
        for (piece in hmm.cut(text)) {
            // 盲文专用约束：虚词不领起未登录词，也不夹在词中。
            // 「而致」「之气」「所作」「可致」「阳而致」这类合并会把虚词和
            // 内容粘成一个盲文词；词典已确认的（如「可使」「可达」）照常合并。
            if (piece.length > 1 && !isWord(piece) && hasFunctionBoundary(piece)) {
                for (c in piece) {
                    out.add(arrayOf(c.toString(), pos.toString(), (pos + 1).toString()))
                    pos++
                }
            } else {
                out.add(arrayOf(piece, pos.toString(), (pos + piece.length).toString()))
                pos += piece.length
            }
        }
    }

    private fun isCjkChar(c: Char): Boolean {
        return c.code in 0x4E00..0x9FFF
    }

    /**
     * 未登录词里是否含「不该连写」的虚词位置。
     *
     * 词首虚词（而致、之气、所作）和词中虚词（阳而致、阴而腑）都会让盲文
     * 出现错误词块；词尾虚词不拦——「多因」「起于」「痛者」「甚则」是
     * 正常搭配，拦了反而把真词拆散。
     */
    private fun hasFunctionBoundary(piece: String): Boolean {
        if (FUNC_LEAD.indexOf(piece[0]) >= 0) {
            return true
        }
        var k = 1
        while (k < piece.length - 1) {
            if (FUNC_LEAD.indexOf(piece[k]) >= 0) {
                return true
            }
            k++
        }
        return false
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

        /**
         * 不允许领起 HMM 未登录词的虚词/介词/连词。
         *
         * 依据真实教材语料校准：HMM 合并里有 8.5% 以这些字开头且不在词典，
         * 基本都是「虚词 + 内容词」的错误粘连。而「无 / 不 / 过 / 向 / 对 / 会」
         * 会组成「无汗」「不节」「过多」「向外」「对侧」「会宗」等真词，故不列入。
         */
        private const val FUNC_LEAD =
            "的了在是为因使及与和或而就都也把被从由以于其之者所则若如虽但并且令让给替地得着可能有"
    }
}
