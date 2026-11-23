/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 */

package com.linxi.brailleback.tactile

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 为什么需要它：
 * BMP 字节布局是和外部看图/刻印软件对接的硬契约：头偏移、调色板顺序、4 字节行对齐、
 * 底行在前、凸起写黑，任何一处错位都能导出文件却打开成反图或花图。这里不调用编码器结果
 * 反推自己（循环论证），所有期望尺寸、头字段与像素字节都按规范手算成常量逐字节断言。
 */
class TactileBmpEncoderTest {

    /** 像素数据偏移：14 + 40 + 256×4 = 1078（手算常量，不取自编码器）。 */
    private val expectedOffBits = 1078

    /** 300 dpi → ppm = 300 / 0.0254 ≈ 11811.02 → 四舍五入 11811（手算常量）。 */
    private val expectedPpm = 11811

    private fun raster(width: Int, height: Int, levels: IntArray, dpi: Int = 300): TactileRaster =
        TactileRaster(
            width = width,
            height = height,
            levels = ByteArray(levels.size) { levels[it].toByte() },
            dpi = dpi,
            pageWidthMm = 210.0,
            pageHeightMm = 297.0,
        )

    private fun leU16(b: ByteArray, offset: Int): Int =
        (b[offset].toInt() and 0xFF) or ((b[offset + 1].toInt() and 0xFF) shl 8)

    private fun leU32(b: ByteArray, offset: Int): Int =
        (b[offset].toInt() and 0xFF) or
            ((b[offset + 1].toInt() and 0xFF) shl 8) or
            ((b[offset + 2].toInt() and 0xFF) shl 16) or
            ((b[offset + 3].toInt() and 0xFF) shl 24)

    private fun headerCommon(b: ByteArray, width: Int, height: Int, rowSize: Int) {
        // 魔数 "BM"
        assertEquals(0x42, b[0].toInt() and 0xFF)
        assertEquals(0x4D, b[1].toInt() and 0xFF)
        // 文件头其余字段
        assertEquals(expectedOffBits, leU32(b, 10))
        // 信息头：biSize / 宽 / 高 / planes / 位深 / 压缩 / 图像大小 / 双 ppm
        assertEquals(40, leU32(b, 14))
        assertEquals(width, leU32(b, 18))
        assertEquals(height, leU32(b, 22))
        assertEquals(1, leU16(b, 26))
        assertEquals(8, leU16(b, 28))
        assertEquals(0, leU32(b, 30))
        assertEquals(rowSize * height, leU32(b, 34))
        assertEquals(expectedPpm, leU32(b, 38))
        assertEquals(expectedPpm, leU32(b, 42))
    }

    private fun paletteCommon(b: ByteArray) {
        // 调色板首项 v=0：(B,G,R,0) = (0,0,0,0)，位于偏移 54
        assertArrayEquals(
            byteArrayOf(0x00, 0x00, 0x00, 0x00),
            b.copyOfRange(54, 58),
        )
        // 调色板末项 v=255：(255,255,255,0)，位于偏移 54 + 4×255 = 1074
        assertArrayEquals(
            byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x00),
            b.copyOfRange(1074, 1078),
        )
    }

    @Test
    fun onePixelFullyRaised_isSingleBlackPixelWithThreePaddingBytes() {
        // 1×1，level=255（完全抬起）→ 像素 0x00；行宽对齐到 4，补 3 个 0 字节。
        val b = TactileBmpEncoder.encode(raster(1, 1, intArrayOf(255)))

        // 行大小 rowSize = ((1+3)/4)*4 = 4；文件大小 = 1078 + 4×1 = 1082
        assertEquals(1082, b.size)
        headerCommon(b, width = 1, height = 1, rowSize = 4)
        paletteCommon(b)

        assertEquals(0x00, b[expectedOffBits].toInt() and 0xFF)
        // 对齐填充字节必须为 0
        assertEquals(0x00, b[expectedOffBits + 1].toInt() and 0xFF)
        assertEquals(0x00, b[expectedOffBits + 2].toInt() and 0xFF)
        assertEquals(0x00, b[expectedOffBits + 3].toInt() and 0xFF)
    }

    @Test
    fun twoByTwo_isBottomRowFirst_andTwoPaddingBytesPerRow() {
        // 行优先：y=0（顶行）= [0, 255]，y=1（底行）= [255, 0]
        val b = TactileBmpEncoder.encode(raster(2, 2, intArrayOf(0, 255, 255, 0)))

        // 行大小 rowSize = ((2+3)/4)*4 = 4；文件大小 = 1078 + 4×2 = 1086
        assertEquals(1086, b.size)
        headerCommon(b, width = 2, height = 2, rowSize = 4)
        paletteCommon(b)

        // BMP 自下而上：文件里第一行是栅格底行 y=1：[255, 0] → 像素 [0x00, 0xFF]，后接 2 填充
        val bottomRowOffset = expectedOffBits
        assertEquals(0x00, b[bottomRowOffset].toInt() and 0xFF)
        assertEquals(0xFF, b[bottomRowOffset + 1].toInt() and 0xFF)
        assertEquals(0x00, b[bottomRowOffset + 2].toInt() and 0xFF)
        assertEquals(0x00, b[bottomRowOffset + 3].toInt() and 0xFF)

        // 第二行是栅格顶行 y=0：[0, 255] → 像素 [0xFF, 0x00]，后接 2 填充
        val topRowOffset = expectedOffBits + 4
        assertEquals(0xFF, b[topRowOffset].toInt() and 0xFF)
        assertEquals(0x00, b[topRowOffset + 1].toInt() and 0xFF)
        assertEquals(0x00, b[topRowOffset + 2].toInt() and 0xFF)
        assertEquals(0x00, b[topRowOffset + 3].toInt() and 0xFF)
    }

    @Test
    fun widthThreeNonAlignedRow_hasOnePaddingByte() {
        // 3×1，levels = [0, 255, 0] → 像素 [0xFF, 0x00, 0xFF]，行对齐到 4，补 1 个 0 字节。
        val b = TactileBmpEncoder.encode(raster(3, 1, intArrayOf(0, 255, 0)))

        // 行大小 rowSize = ((3+3)/4)*4 = 4；文件大小 = 1078 + 4×1 = 1082
        assertEquals(1082, b.size)
        headerCommon(b, width = 3, height = 1, rowSize = 4)
        paletteCommon(b)

        assertEquals(0xFF, b[expectedOffBits].toInt() and 0xFF)
        assertEquals(0x00, b[expectedOffBits + 1].toInt() and 0xFF)
        assertEquals(0xFF, b[expectedOffBits + 2].toInt() and 0xFF)
        assertEquals(0x00, b[expectedOffBits + 3].toInt() and 0xFF)
    }

    @Test
    fun polarityRaisedIsBlackFlatIsWhite() {
        // level=255（完全抬起）→ 像素 0x00（黑）
        val raised = TactileBmpEncoder.encode(raster(1, 1, intArrayOf(255)))
        assertEquals(0x00, raised[expectedOffBits].toInt() and 0xFF)

        // level=0（完全平坦）→ 像素 0xFF（白）
        val flat = TactileBmpEncoder.encode(raster(1, 1, intArrayOf(0)))
        assertEquals(0xFF, flat[expectedOffBits].toInt() and 0xFF)
    }
}
