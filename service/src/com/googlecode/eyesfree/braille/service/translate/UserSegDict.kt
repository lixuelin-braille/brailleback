/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 *
 * 用户分词词库 - 在 jieba 之上叠加的一层「词面 -> 分片」覆盖表。
 *
 * 一个词面映射到它应当被切成的分片：
 *   国家      -> (国家)          一个分片：这是一个词，把相邻的「国」「家」合并
 *   国家通用  -> (国家, 通用)    多个分片：在这里切开
 *
 * 匹配只在 jieba token 边界上进行，并且要求 token 在原文中真正相邻，
 * 因此「中国家庭」不会被「国家」词条误伤成「中 / 国家 / 庭」。最长匹配
 * 优先，后加载的同名词条覆盖先前的词条。
 *
 * 语义与 Brailix 核心仓库的 _user_dict（Apache-2.0）一致，本文件为独立实现。
 */

package com.googlecode.eyesfree.braille.service.translate

import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader

class UserSegDict {

    /** 命中结果：替换后的分片，以及被消耗 token 的排他结束下标。 */
    class Match internal constructor(
        val pieces: List<String>,
        val endIndex: Int
    )

    private class Node {
        val children = HashMap<Char, Node>()
        var pieces: List<String>? = null
    }

    private val root = Node()

    @Volatile
    private var entryCount = 0

    fun size(): Int = entryCount

    /**
     * 从 UTF-8 文本加载词库，返回成功应用的词条数。
     *
     * 每行一个词条，支持 # 注释与空行；制表符前是词面，制表符后是
     * 以空白分隔的分片。没有制表符的行表示「这个词整体连写」。
     * 非法词条（单字、空分片、分片拼不回词面、含空白）直接跳过，
     * 不会中断整份词库的加载。重复词面以后加载的为准。
     */
    @Synchronized
    fun load(inputStream: InputStream?): Int {
        if (inputStream == null) {
            return 0
        }
        var applied = 0
        try {
            BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8)).use { reader ->
                var firstLine = true
                while (true) {
                    var line = reader.readLine() ?: break
                    if (firstLine) {
                        if (line.isNotEmpty() && line[0] == '\uFEFF') {
                            line = line.substring(1)
                        }
                        firstLine = false
                    }
                    val text = line.trim()
                    if (text.isEmpty() || text.startsWith("#")) {
                        continue
                    }
                    // 用原始行定位制表符：行尾的制表符会被 trim() 吃掉，
                    // 那样本应判为非法的空分片会退化成「整体连写」词条。
                    val tab = line.indexOf('\t')
                    val surface: String
                    val pieces: List<String>
                    if (tab < 0) {
                        surface = text
                        pieces = listOf(text)
                    } else {
                        surface = line.substring(0, tab).trim()
                        val rhs = line.substring(tab + 1).trim()
                        pieces = if (rhs.isEmpty()) {
                            emptyList()
                        } else {
                            rhs.split(WHITESPACE).filter { it.isNotEmpty() }
                        }
                    }
                    if (!isValid(surface, pieces)) {
                        continue
                    }
                    put(surface, pieces)
                    applied++
                }
            }
        } catch (e: Exception) {
            // 词库损坏不阻塞翻译：保留已经成功加载的条目。
        }
        return applied
    }

    /**
     * 从第 [start] 个 token 起做最长匹配。
     *
     * [words] 的每个元素为 [word, start, end]（相对同一段文本）。
     * 只拼接原文相邻的完整 token；命中后返回替换分片和消耗到的下标。
     */
    @Synchronized
    fun match(words: List<Array<String>>, start: Int): Match? {
        if (entryCount == 0 || start < 0 || start >= words.size) {
            return null
        }
        var node: Node? = root
        var best: List<String>? = null
        var bestEnd = start
        var j = start
        while (j < words.size && node != null) {
            val token = words[j]
            if (token.size < 3) {
                break
            }
            if (j > start) {
                val prevEnd = words[j - 1][2].toIntOrNull() ?: break
                val curStart = token[1].toIntOrNull() ?: break
                // 原文中间有被分析器丢掉的内容（空格/标点）时不能强行拼接。
                if (prevEnd != curStart) {
                    break
                }
            }
            val surface = token[0]
            var cursor: Node? = node
            for (ch in surface) {
                cursor = cursor?.children?.get(ch)
                if (cursor == null) {
                    break
                }
            }
            if (cursor == null) {
                break
            }
            node = cursor
            val hit = cursor.pieces
            if (hit != null) {
                best = hit
                bestEnd = j + 1
            }
            j++
        }
        val pieces = best ?: return null
        return Match(pieces, bestEnd)
    }

    /** 该词面是否被用户显式要求切成多个分片（用于保护性合并时让路）。 */
    @Synchronized
    fun isSplit(surface: String): Boolean {
        if (entryCount == 0) {
            return false
        }
        var node: Node? = root
        for (ch in surface) {
            node = node?.children?.get(ch) ?: return false
        }
        val pieces = node?.pieces ?: return false
        return pieces.size > 1
    }

    private fun put(surface: String, pieces: List<String>) {
        var node = root
        for (ch in surface) {
            node = node.children.getOrPut(ch) { Node() }
        }
        if (node.pieces == null) {
            entryCount++
        }
        node.pieces = pieces.toList()
    }

    private fun isValid(surface: String, pieces: List<String>): Boolean {
        if (surface.length < MIN_SURFACE_LEN) {
            return false
        }
        if (surface.any { it.isWhitespace() }) {
            return false
        }
        if (pieces.isEmpty()) {
            return false
        }
        if (pieces.any { piece -> piece.isEmpty() || piece.any { it.isWhitespace() } }) {
            return false
        }
        return pieces.joinToString("") == surface
    }

    companion object {
        private const val MIN_SURFACE_LEN = 2
        private val WHITESPACE = Regex("\\s+")
    }
}
