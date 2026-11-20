package com.googlecode.eyesfree.braille.service.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream

/**
 * 规范连写词表（segment_connect.txt）的行为与不变量。
 *
 * 背景：国家通用盲文的分词连写方向是「多连少分」，
 * 而项目里的 sunshine_split.txt 是对标阳光读屏的强制分写库
 * （「一丘之貉 → 一丘 之 貉」「一瞬间 → 一瞬 间」）。
 * 两者冲突时以规范连写词表为准。
 */
class ConnectWordListTest {

    private fun localSegmenter(vararg entries: String): BrailleSegmenter {
        val d = JiebaDict()
        d.init(ByteArrayInputStream(entries.joinToString("\n").toByteArray(Charsets.UTF_8)))
        return BrailleSegmenter(d)
    }

    private fun stream(text: String) = ByteArrayInputStream(text.toByteArray(Charsets.UTF_8))

    @Test
    fun connectExtraRemovesConflictingSplitEntry() {
        val s = localSegmenter("一口 1000", "气 1000")
        s.loadSplitDict(stream("一口气\t一口 气\n"))
        val added = s.loadConnectExtra(stream("一口气\n"))
        assertEquals(1, added)
        // 分写库里的同名词条必须已被摘除，否则仍会命中「一口 气」
        assertEquals("一口气", s.segment("一口气").text)
    }

    @Test
    fun splitDictLoadedAfterConnectCannotUndoIt() {
        // 加载顺序不该决定结果：即使分写库后加载，连写表也必须赢。
        val s = localSegmenter("一丘 1000", "之 1000", "貉 1000")
        s.loadConnectExtra(stream("一丘之貉\n"))
        s.loadSplitDict(stream("一丘之貉\t一丘 之 貉\n"))
        assertEquals("一丘之貉", s.segment("一丘之貉").text)
    }

    @Test
    fun splitDictStillAppliesToWordsOutsideConnectList() {
        // 防越界：没被收进连写表的词，分写库照旧生效。
        val s = localSegmenter("一元 1000", "方程 1000")
        s.loadSplitDict(stream("一元方程\t一元 方程\n"))
        s.loadConnectExtra(stream("一口气\n"))
        assertEquals("一元 方程", s.segment("一元方程").text)
    }

    @Test
    fun connectExtraIgnoresCommentsAndNonHanzi() {
        val s = localSegmenter("分 1000", "钟 1000")
        val added = s.loadConnectExtra(stream("# 注释\n\n分钟\nABC\n单\n"))
        assertEquals(1, added)
        assertEquals("分钟", s.segment("分钟").text)
    }

    private companion object {
        fun locate(name: String): File? {
            val candidates = listOf(
                "assets/$name",
                "service/src/main/assets/$name",
                "../service/src/main/assets/$name",
                "src/main/assets/$name"
            )
            for (c in candidates) {
                val f = File(c)
                if (f.isFile) {
                    return f
                }
            }
            return null
        }
    }
}
