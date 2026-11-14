/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 *
 * HmmSegmenter - jieba finalseg 的 BMES 四状态 HMM（未登录词识别）。
 *
 * 概率表与状态转移取自 jieba 0.42.1 finalseg（MIT License）；发射概率由
 * assets/hmm_emit.txt 提供（每行：字符 + B M E S 四个对数概率，-inf 表示
 * 该字符在该状态下不可观测）。表外字符按 jieba 的 MIN_FLOAT 处理。
 *
 * 本类只负责把一段连续汉字切成词；是否对某段缓冲区跑 HMM 由 JiebaDict 判断。
 */

package com.googlecode.eyesfree.braille.service.translate

import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader

class HmmSegmenter {

    private val emit = HashMap<Char, DoubleArray>(EMIT_INITIAL_CAPACITY)

    @Volatile
    private var ready = false

    fun isReady(): Boolean {
        return ready
    }

    fun size(): Int {
        return emit.size
    }

    /** 加载发射概率表，返回读入的字符数；0 表示失败（HMM 保持关闭）。 */
    fun load(stream: InputStream): Int {
        var count = 0
        try {
            BufferedReader(InputStreamReader(stream, "UTF-8")).use { br ->
                while (true) {
                    val line = br.readLine() ?: break
                    if (line.isEmpty() || line[0] == '#') {
                        continue
                    }
                    val cols = line.split('\t')
                    if (cols.size < 5 || cols[0].length != 1) {
                        continue
                    }
                    val row = DoubleArray(4)
                    for (s in 0 until 4) {
                        row[s] = parseProb(cols[s + 1])
                    }
                    emit[cols[0][0]] = row
                    count++
                }
            }
        } catch (e: Exception) {
            return 0
        }
        ready = count > 0
        return count
    }

    private fun parseProb(text: String): Double {
        if (text == "-inf" || text == "-Infinity") {
            return Double.NEGATIVE_INFINITY
        }
        return text.toDoubleOrNull() ?: UNSEEN
    }

    /** 对一段连续汉字做 BMES 解码，返回词序列（拼接后等于输入）。 */
    fun cut(sentence: String): List<String> {
        val n = sentence.length
        if (n == 0) {
            return emptyList()
        }
        if (n == 1 || !ready) {
            return sentence.map { it.toString() }
        }
        val score = Array(4) { DoubleArray(n) }
        val back = Array(4) { ByteArray(n) }
        for (s in 0 until 4) {
            score[s][0] = START[s] + emitOf(sentence[0], s)
            back[s][0] = s.toByte()
        }
        for (t in 1 until n) {
            for (s in 0 until 4) {
                val e = emitOf(sentence[t], s)
                var best = Double.NEGATIVE_INFINITY
                var bestPrev = 0
                for (p in PREV_STATES[s]) {
                    val v = score[p][t - 1] + TRANS[p][s] + e
                    if (v > best) {
                        best = v
                        bestPrev = p
                    }
                }
                score[s][t] = best
                back[s][t] = bestPrev.toByte()
            }
        }
        // 终点只能是 E 或 S；并列时取 E，与 jieba max 取首元素一致。
        var state = if (score[STATE_E][n - 1] >= score[STATE_S][n - 1]) STATE_E else STATE_S
        val path = ByteArray(n)
        path[n - 1] = state.toByte()
        for (t in n - 1 downTo 1) {
            state = back[state][t].toInt()
            path[t - 1] = state.toByte()
        }
        val words = ArrayList<String>()
        var begin = 0
        var next = 0
        for (i in 0 until n) {
            when (path[i].toInt()) {
                STATE_B -> begin = i
                STATE_E -> {
                    words.add(sentence.substring(begin, i + 1))
                    next = i + 1
                }
                STATE_S -> {
                    words.add(sentence.substring(i, i + 1))
                    next = i + 1
                }
            }
        }
        if (next < n) {
            words.add(sentence.substring(next))
        }
        return words
    }

    private fun emitOf(ch: Char, state: Int): Double {
        val row = emit[ch] ?: return UNSEEN
        return row[state]
    }

    companion object {
        private const val EMIT_INITIAL_CAPACITY = 16384

        private const val STATE_B = 0
        private const val STATE_M = 1
        private const val STATE_E = 2
        private const val STATE_S = 3

        /** jieba finalseg 的 MIN_FLOAT，用于表外字符。 */
        private const val UNSEEN = -3.14e100

        /** 起始概率：B / M / E / S。 */
        private val START = doubleArrayOf(
            -0.26268660809250016,
            Double.NEGATIVE_INFINITY,
            Double.NEGATIVE_INFINITY,
            -1.4652633398537678
        )

        /** 转移概率：TRANS[from][to]。 */
        private val TRANS = arrayOf(
            doubleArrayOf(
                Double.NEGATIVE_INFINITY,
                -0.916290731874155,
                -0.51082562376599,
                Double.NEGATIVE_INFINITY
            ),
            doubleArrayOf(
                Double.NEGATIVE_INFINITY,
                -1.2603623820268226,
                -0.33344856811948514,
                Double.NEGATIVE_INFINITY
            ),
            doubleArrayOf(
                -0.5897149736854513,
                Double.NEGATIVE_INFINITY,
                Double.NEGATIVE_INFINITY,
                -0.8085250474669937
            ),
            doubleArrayOf(
                -0.7211965654669841,
                Double.NEGATIVE_INFINITY,
                Double.NEGATIVE_INFINITY,
                -0.6658631448798212
            )
        )

        /** 每个状态允许的前驱状态（jieba PrevStatus）。 */
        private val PREV_STATES = arrayOf(
            intArrayOf(STATE_E, STATE_S),
            intArrayOf(STATE_M, STATE_B),
            intArrayOf(STATE_B, STATE_M),
            intArrayOf(STATE_S, STATE_E)
        )
    }
}
