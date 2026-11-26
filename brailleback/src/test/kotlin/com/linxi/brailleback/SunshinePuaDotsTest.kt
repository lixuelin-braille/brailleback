/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 *
 * 阳光 PUA（U+E234～U+E273）→ toDots 点位映射单测（纯 JVM）。
 * 运行：`gradle :brailleback:testDebugUnitTest`
 */

package com.linxi.brailleback

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * 覆盖 123.1 补的 `SUN_MIN..SUN_MAX` 分支：
 * - 区间两端点与若干中点对 liblouis 权威表；
 * - 区间外码点不误判（仍返回空方 0）；
 * - 原有 Unicode 盲文 / NABCC ASCII 路径零回归。
 */
class SunshinePuaDotsTest {

    private fun locate(rel: String): File? {
        // JUnit 工作目录为 brailleback 模块根，兼容从项目根运行
        for (c in listOf(rel, "../$rel", "brailleback/$rel")) {
            val f = File(c)
            if (f.isFile) return f
        }
        return null
    }

    /** 由 dot 编号串算点位值（测试侧独立实现，避免复用被测代码）。 */
    private fun dotsValue(dots: String): Int {
        var v = 0
        for (d in dots) {
            if (d in '1'..'6') v = v or (1 shl (d - '1'))
        }
        return v
    }

    // ------------------------------------------------------------------
    // 全区间对表：zhcn-cbs.ctb 的 `noback sign` 序列是黄金基准
    // ------------------------------------------------------------------

    @Test
    fun `sunshine PUA range matches liblouis table`() {
        val f = locate("service/jni/liblouiswrapper/liblouis/tables/zhcn-cbs.ctb")
            ?: run {
                println("[skip] zhcn-cbs.ctb 未找到，跳过 PUA 表比对")
                return
            }
        val lineRegex = Regex("^noback sign \\\\x([0-9A-Fa-f]{4})\\s+([0-9 ]+)$")
        var checked = 0
        for (line in f.readLines()) {
            val m = lineRegex.matchEntire(line.trim()) ?: continue
            val code = m.groupValues[1].toInt(16)
            if (code !in 0xE234..0xE273) continue
            val expected = dotsValue(m.groupValues[2].trim())
            val actual = BrailleFileDecoder.toDots(code.toChar())
            assertEquals(
                "PUA U+${code.toString(16).uppercase()} 点位不符",
                expected, actual
            )
            checked++
        }
        println("[SUN PUA] 比对 $checked 个码位，全部一致")
        assertEquals("阳光 PUA 共 64 个码位（含空方），应全部校验到", 64, checked)
    }

    // ------------------------------------------------------------------
    // 端点：下限点 1、上限全点位、空方
    // ------------------------------------------------------------------

    @Test
    fun `sunshine PUA endpoints map to dots`() {
        // U+E234：点 1（掩码 1）
        assertEquals(1, BrailleFileDecoder.toDots('\uE234'))
        // U+E272：123456 全点位（掩码 63）
        assertEquals(63, BrailleFileDecoder.toDots('\uE272'))
        // U+E273：空方（掩码 0）
        assertEquals(0, BrailleFileDecoder.toDots('\uE273'))
    }

    // ------------------------------------------------------------------
    // 中点抽查：单点位、组合点位、半区位置
    // ------------------------------------------------------------------

    @Test
    fun `sunshine PUA midpoints spot check`() {
        // 表内逐条取样：码位 -> 点位编号串
        val cases = mapOf(
            0xE236 to "12",   // 掩码 3
            0xE237 to "3",    // 掩码 4
            0xE240 to "134",  // 掩码 13
            0xE243 to "5",    // 掩码 16
            0xE253 to "6",    // 掩码 32
            0xE257 to "36",   // 掩码 36
            0xE26B to "456",  // 掩码 56
            0xE272 to "123456" // 掩码 63
        )
        for ((code, dots) in cases) {
            assertEquals(
                "U+${code.toString(16).uppercase()} 应为 $dots",
                dotsValue(dots), BrailleFileDecoder.toDots(code.toChar())
            )
        }
    }

    // ------------------------------------------------------------------
    // 区间外码点不误判：仍返回空方 0
    // ------------------------------------------------------------------

    @Test
    fun `codepoints outside sunshine range return blank`() {
        // 区间上下邻码
        assertEquals(0, BrailleFileDecoder.toDots(0xE233.toChar()))
        assertEquals(0, BrailleFileDecoder.toDots(0xE274.toChar()))
        // 其他 PUA 区段
        assertEquals(0, BrailleFileDecoder.toDots('\uE000'))
        // 明眼汉字 / 空格不属于盲文点位
        assertEquals(0, BrailleFileDecoder.toDots('中'))
        assertEquals(0, BrailleFileDecoder.toDots(' '))
    }

    // ------------------------------------------------------------------
    // 原有路径零回归：Unicode 盲文与 NABCC ASCII 不受新分支影响
    // ------------------------------------------------------------------

    @Test
    fun `original unicode and ascii paths unchanged`() {
        // 标准 Unicode 盲文
        assertEquals(0, BrailleFileDecoder.toDots('\u2800'))
        assertEquals(1, BrailleFileDecoder.toDots('\u2801'))
        assertEquals(63, BrailleFileDecoder.toDots('\u283F'))
        // NABCC ASCII 抽查
        assertEquals(dotsValue("1"), BrailleFileDecoder.toDots('A'))
        assertEquals(dotsValue("12"), BrailleFileDecoder.toDots('B'))
        assertEquals(dotsValue("1356"), BrailleFileDecoder.toDots('Z'))
        assertEquals(dotsValue("6"), BrailleFileDecoder.toDots(','))
    }
}
