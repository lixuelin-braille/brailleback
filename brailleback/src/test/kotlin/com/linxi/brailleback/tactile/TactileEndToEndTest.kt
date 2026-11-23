/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 */

package com.linxi.brailleback.tactile

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * 为什么需要它：
 * 栅格化内核（[TactileRasterizer]）和 BMP 编码器（[TactileBmpEncoder]）各自都有单测，
 * 但「极性只翻一次」这件事只有两端合起来才抓得到：栅格化按抬升等级存（255=抬起），
 * 编码器再做一次 255 - level（凸起写黑）。任何一侧多翻一次，单测可能各自全绿，
 * 合起来却等于没翻（黑底白线 / 白底黑线对调）。这里用程序化构造的线稿走完整链路，
 * 同时钉住栅格等级与落盘字节两个层面，防止以后有人在中间又加一道反转。
 */
class TactileEndToEndTest {

    private companion object {
        /** 线稿边长（像素）。 */
        const val IMAGE_SIZE: Int = 60

        /** 阈值：灰度 < 128 完全抬升。 */
        const val THRESHOLD: Int = 128

        /** 300 dpi 下 60 px = 60 × 25.4 / 300 = 5.08 mm，重采样是 1:1，亮度不被平均改动。 */
        const val TARGET_MM: Double = 5.08
    }

    /** 从当前工作目录向上找仓库根（与既有契约测试同一套兜底，单测 cwd 可能是模块目录或根目录）。 */
    private fun repoRoot(): File =
        listOf(File("."), File(".."), File("../.."))
            .first { File(it, "settings.gradle").isFile }

    /** 程序化线稿：白底 60×60，一条 2 px 宽竖直黑条 + 一个 20×20 黑色方框描边。 */
    private fun lineArt(): GrayImage {
        val luma = IntArray(IMAGE_SIZE * IMAGE_SIZE) { 255 }
        fun setBlack(x: Int, y: Int) { luma[y * IMAGE_SIZE + x] = 0 }

        // 竖直黑条 x=10、11，贯穿整幅高度。
        for (y in 0 until IMAGE_SIZE) {
            setBlack(10, y)
            setBlack(11, y)
        }
        // 方框 x 30..49、y 10..29，1 px 描边：上下边 + 左右列。
        for (x in 30..49) {
            setBlack(x, 10)
            setBlack(x, 29)
        }
        for (y in 10..29) {
            setBlack(30, y)
            setBlack(49, y)
        }
        return GrayImage(IMAGE_SIZE, IMAGE_SIZE, luma)
    }

    /** BMP 自下而上：数据第 i 行对应图像 y = height - 1 - y；行按 4 字节对齐。 */
    private fun pixelOffset(width: Int, height: Int, x: Int, y: Int): Int {
        val rowSize = ((width + 3) / 4) * 4
        return TactileBmpEncoder.PIXEL_DATA_OFFSET + (height - 1 - y) * rowSize + x
    }

    @Test
    fun rasterizeThenEncode_flipsPolarityExactlyOnce() {
        val profile = TactileProfile(dpi = 300)
        val settings = TactileSettings(
            mode = TactileMode.THRESHOLD,
            threshold = THRESHOLD,
            widthMm = TARGET_MM,
            heightMm = TARGET_MM,
        )
        val raster = TactileRasterizer.rasterize(lineArt(), settings, profile)
        assertEquals(IMAGE_SIZE, raster.width)
        assertEquals(IMAGE_SIZE, raster.height)

        val bytes = TactileBmpEncoder.encode(raster)

        // 抬升样本：黑条中心两列 + 方框四条边各取一点。
        val raised = listOf(
            10 to 30,
            11 to 30,
            40 to 10,
            30 to 20,
            49 to 20,
            40 to 29,
        )
        // 平坦样本：白底区域，含验收脚本要取样的左上 (0,0) 与中心 (29,29)。
        val flat = listOf(
            5 to 5,
            20 to 40,
            55 to 55,
            0 to 0,
            29 to 29,
        )

        for ((x, y) in raised) {
            assertEquals("抬升等级应为 255 @($x,$y)", 255, raster.level(x, y))
            assertEquals(
                "凸起像素应为 0x00（黑）@($x,$y)",
                0x00,
                bytes[pixelOffset(IMAGE_SIZE, IMAGE_SIZE, x, y)].toInt() and 0xFF,
            )
        }
        for ((x, y) in flat) {
            assertEquals("平坦等级应为 0 @($x,$y)", 0, raster.level(x, y))
            assertEquals(
                "平坦像素应为 0xFF（白）@($x,$y)",
                0xFF,
                bytes[pixelOffset(IMAGE_SIZE, IMAGE_SIZE, x, y)].toInt() and 0xFF,
            )
        }

        // 落盘一份，供独立验收脚本（temp/tactile-acceptance/verify-bmp.ps1）纯字节解析。
        val outDir = File(repoRoot(), "brailleback/build/tactile-e2e")
        outDir.mkdirs()
        File(outDir, "lineart-threshold.bmp").writeBytes(bytes)
    }

    @Test
    fun invertTrueSwapsWhichSideIsRaised() {
        val profile = TactileProfile(dpi = 300)
        val settings = TactileSettings(
            mode = TactileMode.THRESHOLD,
            threshold = THRESHOLD,
            invert = true,
            widthMm = TARGET_MM,
            heightMm = TARGET_MM,
        )
        val raster = TactileRasterizer.rasterize(lineArt(), settings, profile)
        val bytes = TactileBmpEncoder.encode(raster)

        // 翻转后黑条 / 方框变成平坦，白底反而完全抬起；字节极性随之对调。
        val blackSide = listOf(10 to 30, 40 to 10, 30 to 20, 49 to 20)
        val whiteSide = listOf(5 to 5, 20 to 40, 0 to 0, 29 to 29)

        for ((x, y) in blackSide) {
            assertEquals("翻转后黑侧等级应为 0 @($x,$y)", 0, raster.level(x, y))
            assertEquals(
                "翻转后黑侧字节应为 0xFF（白）@($x,$y)",
                0xFF,
                bytes[pixelOffset(IMAGE_SIZE, IMAGE_SIZE, x, y)].toInt() and 0xFF,
            )
        }
        for ((x, y) in whiteSide) {
            assertEquals("翻转后白侧等级应为 255 @($x,$y)", 255, raster.level(x, y))
            assertEquals(
                "翻转后白侧字节应为 0x00（黑）@($x,$y)",
                0x00,
                bytes[pixelOffset(IMAGE_SIZE, IMAGE_SIZE, x, y)].toInt() and 0xFF,
            )
        }
    }

    @Test
    fun widthSixtyOne_rowPaddingIsZero() {
        // 窄边界：61×1 单行，中心一个黑像素。254 dpi 时 pxPerMm=10，6.1 mm → 61 px。
        val luma = IntArray(61) { 255 }
        luma[30] = 0
        val image = GrayImage(61, 1, luma)
        val profile = TactileProfile(dpi = 254)
        val settings = TactileSettings(widthMm = 6.1, heightMm = 0.1)

        val raster = TactileRasterizer.rasterize(image, settings, profile)
        assertEquals(61, raster.width)
        assertEquals(1, raster.height)

        val bytes = TactileBmpEncoder.encode(raster)
        val rowSize = ((61 + 3) / 4) * 4 // 64，每行补 3 字节
        assertEquals(TactileBmpEncoder.PIXEL_DATA_OFFSET + rowSize, bytes.size)

        assertEquals(0x00, bytes[1078 + 30].toInt() and 0xFF)
        assertEquals(0xFF, bytes[1078 + 29].toInt() and 0xFF)
        assertEquals(0xFF, bytes[1078 + 31].toInt() and 0xFF)
        for (pad in 61 until rowSize) {
            assertEquals("对齐填充字节 $pad 必须为 0", 0x00, bytes[1078 + pad].toInt() and 0xFF)
        }
    }
}
