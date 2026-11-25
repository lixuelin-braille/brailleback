/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 *
 * 目录的章节切分：从明眼原文里认「第X章/回/卷」「序言」「尾声」这类标题行。
 * 纯 JVM，不碰 Android：识别规则可单测，阅读器只负责把章节起始字映射到盲文流偏移。
 *
 * 为什么不再按页分组：一本 300 万字的书按 10 页一组能拆出上万条「第 1-10 页」，
 * 盲人用户听下来全是页码、没有任何可用来找内容的线索。章节标题是书自己给的
 * 结构，标书名、卷名比页码有用得多；识别不到时才退回分组。
 */

package com.linxi.brailleback.reader

object ReaderChapters {

    /** 一章：[title] 是给用户听的短标题，[startChar] 是它在全文里的字符下标。 */
    data class Chapter(val title: String, val startChar: Int)

    /** 单章标题的最大长度：超过就该是正文了（长句里也会出现「第三章」）。 */
    private const val MAX_TITLE_CHARS = 32

    /** 目录最多给这么多条：再多听不过来，也点不过来。 */
    private const val MAX_ENTRIES = 300

    /** 章节标题：第X章/节/回/卷/部/篇/集，或序言、尾声这类固定名。 */
    private val NUMBERED = Regex(
        "^\\s*第[0-9０-９零〇一二三四五六七八九十百千万两]+\\s*[章节回卷部篇集]")
    private val NAMED = listOf(
        "序章", "序言", "自序", "代序", "前言", "引子", "楔子",
        "尾声", "终章", "后记", "跋", "附录", "番外", "外传", "大结局",
    )

    /**
     * 扫描全文，返回章节列表（至少两条才有意义，调用方自己判）。
     *
     * @param text 明眼原文（翻译前的文本）
     */
    fun detect(text: String): List<Chapter> {
        if (text.isEmpty()) return emptyList()
        val out = ArrayList<Chapter>()
        var offset = 0
        for (line in text.split('\n')) {
            val start = offset
            offset += line.length + 1
            val title = line.trim().trim('\r')
            if (title.isEmpty() || title.length > MAX_TITLE_CHARS) continue
            if (!isHeading(title)) continue
            // 同一标题连着出现（小说目录页 + 正文标题）只留第一条
            if (out.isNotEmpty() && out.last().title == title) continue
            if (out.isNotEmpty() && out.last().startChar == start) continue
            out.add(Chapter(title, start))
            if (out.size >= MAX_ENTRIES) break
        }
        return out
    }

    /** 标题判定：编号式必须紧跟分隔（「第三章 落难」），固定名必须整行就是它。 */
    private fun isHeading(title: String): Boolean {
        if (NUMBERED.containsMatchIn(title)) {
            // 「第三章的内容如下」这种正文也会命中编号正则，用长度与结尾兜一层：
            // 标题一般不长，也不以句号结尾。
            return !title.endsWith("。") && !title.endsWith("，")
        }
        return NAMED.any { title == it || title.startsWith("$it ") || title.startsWith("$it：") }
    }
}
