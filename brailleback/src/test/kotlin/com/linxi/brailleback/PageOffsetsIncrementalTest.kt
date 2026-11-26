package com.linxi.brailleback

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * 翻译期增量页表（[BrailleFileDecoder.appendPageOffsets]）单测（纯 JVM，无 Android 依赖）。
 *
 * 核心不变量：在同一份流、同一个 unit 下连续多次追加后，增量表必须与从第 0 字符
 * 整表重算出来的 [BrailleFileDecoder.pageOffsets] **逐项相等**；旧表已有起点一字不动。
 *
 * 运行：`gradle :brailleback:testDebugUnitTest`
 */
class PageOffsetsIncrementalTest {

    /** 盲文点位区（U+2800..U+283F）+ 段落换行；空方与换行都是 [BrailleFileDecoder] 的字边界。 */
    private fun randomStream(rng: Random, length: Int): String {
        val sb = StringBuilder(length)
        repeat(length) {
            val roll = rng.nextInt(20)
            sb.append(when {
                roll == 0 -> '\n'
                else -> (0x2800 + rng.nextInt(0x40)).toChar()
            })
        }
        return sb.toString()
    }

    @Test
    fun `incremental equals full rebuild across random streams units and successive appends`() {
        val units = listOf(40, 120, 337, 752)
        var streamsChecked = 0
        var appendsChecked = 0
        for (seed in 1..4) {
            val rng = Random(seed)
            val totalLen = 2000 + rng.nextInt(4000)
            val stream = randomStream(rng, totalLen)
            for (unit in units) {
                // 三段追加：L1 -> L2 -> L3 -> 全长，每段都在旧表尾部续算。
                val l1 = totalLen / 3
                val l2 = totalLen * 2 / 3
                var table = BrailleFileDecoder.pageOffsets(stream.substring(0, l1), unit)
                for (prefix in listOf(l2, totalLen)) {
                    table = BrailleFileDecoder.appendPageOffsets(
                        stream.substring(0, prefix), unit, table)
                    appendsChecked++
                }
                val expected = BrailleFileDecoder.pageOffsets(stream, unit)
                assertArrayEquals(
                    "随机流 seed=$seed unit=$unit 增量表与整表重算不符", expected, table)
                streamsChecked++
            }
        }
        assertTrue("应覆盖多个随机流 × 多 unit", streamsChecked >= 16)
        assertTrue("应发生多次连续追加", appendsChecked >= 32)
    }

    @Test
    fun `incremental preserves existing offsets byte for byte`() {
        val rng = Random(7)
        val stream = randomStream(rng, 1500)
        val unit = 96
        val first = BrailleFileDecoder.pageOffsets(stream.substring(0, 500), unit)
        val appended = BrailleFileDecoder.appendPageOffsets(stream, unit, first)
        // 旧表项必须原样出现在新表前缀里。
        assertArrayEquals(first, appended.copyOfRange(0, first.size))
    }

    @Test
    fun `empty stream falls back to single zero offset`() {
        val existing = intArrayOf(0, 120, 300)
        val table = BrailleFileDecoder.appendPageOffsets("", 96, existing)
        assertArrayEquals(BrailleFileDecoder.pageOffsets("", 96), table)
        assertEquals(1, table.size)
        assertEquals(0, table[0])
    }

    @Test
    fun `empty existing table rebuilds from scratch`() {
        val rng = Random(11)
        val stream = randomStream(rng, 800)
        val table = BrailleFileDecoder.appendPageOffsets(stream, 64, intArrayOf())
        assertArrayEquals(BrailleFileDecoder.pageOffsets(stream, 64), table)
    }

    @Test
    fun `stream shorter than old table falls back to full rebuild`() {
        val rng = Random(13)
        val longStream = randomStream(rng, 1200)
        val unit = 80
        val existing = BrailleFileDecoder.pageOffsets(longStream, unit)
        // 换书/截短：新流比旧表末位还短，旧表不可续，必须回落整表。
        val shortStream = longStream.substring(0, 200)
        val table = BrailleFileDecoder.appendPageOffsets(shortStream, unit, existing)
        assertArrayEquals(BrailleFileDecoder.pageOffsets(shortStream, unit), table)
    }

    @Test
    fun `unit mismatch must not reuse old table`() {
        // 增量入口在 Navigator 侧按 mPageUnit == 当前 unit 校验：unit 变了就走整表。
        // 这里锁住解码器口径：同一流在不同 unit 下页表确实不同，旧表不能被跨 unit 续用。
        val rng = Random(17)
        val stream = randomStream(rng, 1000)
        val tableUnitA = BrailleFileDecoder.pageOffsets(stream, 40)
        val tableUnitB = BrailleFileDecoder.pageOffsets(stream, 200)
        assertTrue("不同 unit 下页数应当不同", tableUnitA.size != tableUnitB.size)
        // 用 A 的旧表按 B 的 unit 续算，结果必然不等于 B 的整表——这正是 Navigator
        // 在 mPageUnit != unit 时回落 pageOffsets 的原因。
        val naive = BrailleFileDecoder.appendPageOffsets(stream, 200, tableUnitA)
        // 跨 unit 拿旧表续算对不上 B 的整表：Navigator 侧 mPageUnit != unit 时
        // 必须回落 pageOffsets，不能走 appendPageOffsets。
        assertNotEquals(
            "跨 unit 续算不应与整表一致",
            BrailleFileDecoder.pageOffsets(stream, 200).toList(), naive.toList())
    }
}
