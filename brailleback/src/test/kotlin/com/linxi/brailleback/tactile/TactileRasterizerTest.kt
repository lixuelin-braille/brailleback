/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 */

package com.linxi.brailleback.tactile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * 为什么需要它：栅格化内核是「图片 → 凸起栅格」的唯一翻译层，阈值边界、极性、尺寸换算、
 * 重采样方式任何一处歪掉，出来的触觉图要么断线、要么物理尺寸不对，而且肉眼在屏幕上看不出来。
 * 这里用纯 JVM 样本把契约逐条钉死：阈值等于边界值必须平坦、面积平均不能退化成最近邻、
 * 超限必须在分配数组前抛异常。
 */
class TactileRasterizerTest {

    // dpi = 254 时 pxPerMm 正好是 10.0，毫米→像素的期望值可以心算，不用被测代码反推。
    private fun profile(dpi: Int = 254) = TactileProfile(dpi = dpi)

    /**
     * 直读编码后 BMP 的像素字节（不经过任何图片工具）。BMP 底行在前：栅格行 y 对应文件第
     * height-1-y 行；行按 4 字节对齐。平坦 level=0 → 字节 0xFF，凸起 level=255 → 字节 0x00。
     */
    private fun bmpByte(bmp: ByteArray, width: Int, height: Int, x: Int, y: Int): Int {
        val rowSize = ((width + 3) / 4) * 4
        val fileRow = height - 1 - y
        val offset = TactileBmpEncoder.PIXEL_DATA_OFFSET + fileRow * rowSize + x
        return bmp[offset].toInt() and 0xFF
    }

    @Test
    fun blackImageIsFullyRaised() {
        val image = GrayImage.black(2, 2)
        val raster = TactileRasterizer.rasterize(
            image,
            TactileSettings(widthMm = 0.2, heightMm = 0.2),
            profile(),
        )
        assertEquals(2, raster.width)
        assertEquals(2, raster.height)
        for (y in 0 until 2) {
            for (x in 0 until 2) {
                assertEquals(255, raster.level(x, y))
            }
        }
    }

    @Test
    fun whiteImageIsCompletelyFlat() {
        val image = GrayImage.white(2, 2)
        val raster = TactileRasterizer.rasterize(
            image,
            TactileSettings(widthMm = 0.2, heightMm = 0.2),
            profile(),
        )
        for (y in 0 until 2) {
            for (x in 0 until 2) {
                assertEquals(0, raster.level(x, y))
            }
        }
    }

    @Test
    fun thresholdBoundaryIsExclusiveOnTheRaisedSide() {
        // 阈值 128：luma 正好等于 128 必须平坦；127（阈值 - 1）才抬升。
        val image = GrayImage(2, 1, intArrayOf(128, 127))
        val raster = TactileRasterizer.rasterize(
            image,
            TactileSettings(threshold = 128, widthMm = 0.2, heightMm = 0.1),
            profile(),
        )
        assertEquals(0, raster.level(0, 0))
        assertEquals(255, raster.level(1, 0))
    }

    @Test
    fun grayscaleMapsDarkInkToHighRaise() {
        // 深墨 = 高抬升：level = 255 - luma。纯黑 0 → 255，纯白 255 → 0。
        val image = GrayImage(2, 1, intArrayOf(0, 255))
        val raster = TactileRasterizer.rasterize(
            image,
            TactileSettings(mode = TactileMode.GRAYSCALE, widthMm = 0.2, heightMm = 0.1),
            profile(),
        )
        assertEquals(255, raster.level(0, 0))
        assertEquals(0, raster.level(1, 0))
    }

    @Test
    fun edgeModeLiftsOnlyTheContourColumn() {
        // 左列纯黑、右两列纯白：Sobel 竖直边缘落在 x=0/1 一侧，最右列内部无梯度。
        val luma = intArrayOf(
            0, 255, 255,
            0, 255, 255,
            0, 255, 255,
        )
        val image = GrayImage(3, 3, luma)
        val raster = TactileRasterizer.rasterize(
            image,
            TactileSettings(mode = TactileMode.EDGE, threshold = 128, widthMm = 0.3, heightMm = 0.3),
            profile(),
        )
        for (y in 0 until 3) {
            assertEquals(255, raster.level(0, y))
            assertEquals(255, raster.level(1, y))
            assertEquals(0, raster.level(2, y))
        }
    }

    @Test
    fun flatRegionHasNoEdgeRegardlessOfThreshold() {
        // 纯色内部梯度恒为 0，归一化幅值 0：阈值取 0 或 255 都必须平坦，与阈值高低无关。
        val whiteLow = TactileRasterizer.rasterize(
            GrayImage.white(3, 3),
            TactileSettings(mode = TactileMode.EDGE, threshold = 0, widthMm = 0.3, heightMm = 0.3),
            profile(),
        )
        val whiteHigh = TactileRasterizer.rasterize(
            GrayImage.white(3, 3),
            TactileSettings(mode = TactileMode.EDGE, threshold = 255, widthMm = 0.3, heightMm = 0.3),
            profile(),
        )
        val blackLow = TactileRasterizer.rasterize(
            GrayImage.black(3, 3),
            TactileSettings(mode = TactileMode.EDGE, threshold = 0, widthMm = 0.3, heightMm = 0.3),
            profile(),
        )
        for (y in 0 until 3) {
            for (x in 0 until 3) {
                assertEquals(0, whiteLow.level(x, y))
                assertEquals(0, whiteHigh.level(x, y))
                assertEquals(0, blackLow.level(x, y))
            }
        }
    }

    @Test
    fun verticalStrongEdgeNormalizesToCeilingAndIsStrictlyGreaterThanThreshold() {
        // 左列纯黑、右两列纯白：中心 (1,1) gx=1020、gy=0，幅值 1020 = 单轴上限，归一化正好 255。
        val image = GrayImage(3, 3, intArrayOf(
            0, 255, 255,
            0, 255, 255,
            0, 255, 255,
        ))
        val nearCeiling = TactileRasterizer.rasterize(
            image,
            TactileSettings(mode = TactileMode.EDGE, threshold = 254, widthMm = 0.3, heightMm = 0.3),
            profile(),
        )
        val ceiling = TactileRasterizer.rasterize(
            image,
            TactileSettings(mode = TactileMode.EDGE, threshold = 255, widthMm = 0.3, heightMm = 0.3),
            profile(),
        )
        // 255 > 254 → 抬升；255 不大于 255 → 平坦（严格大于边界）。
        assertEquals(255, nearCeiling.level(1, 1))
        assertEquals(0, ceiling.level(1, 1))
    }

    @Test
    fun weakEdgeSmallLumaDifferenceFollowsStrictThresholdBoundary() {
        // 左列 150、右两列 90，单列灰度差 60：gx = 4×(90-150) = -240，幅值 240；
        // 归一化 = round(240 / 1020 × 255) = round(60.0) = 60。
        val image = GrayImage(3, 3, intArrayOf(
            150, 90, 90,
            150, 90, 90,
            150, 90, 90,
        ))
        val belowThreshold = TactileRasterizer.rasterize(
            image,
            TactileSettings(mode = TactileMode.EDGE, threshold = 59, widthMm = 0.3, heightMm = 0.3),
            profile(),
        )
        val equalThreshold = TactileRasterizer.rasterize(
            image,
            TactileSettings(mode = TactileMode.EDGE, threshold = 60, widthMm = 0.3, heightMm = 0.3),
            profile(),
        )
        // 60 > 59 → 抬升；60 不大于 60 → 平坦（严格大于边界）。
        assertEquals(255, belowThreshold.level(1, 1))
        assertEquals(0, equalThreshold.level(1, 1))
    }

    @Test
    fun invertFlipsWhichSideIsRaised() {
        // 不翻转时全黑抬起、全白平坦；翻转后两侧对调。翻转只在栅格化这一处做。
        val black = TactileRasterizer.rasterize(
            GrayImage.black(2, 2),
            TactileSettings(invert = true, widthMm = 0.2, heightMm = 0.2),
            profile(),
        )
        val white = TactileRasterizer.rasterize(
            GrayImage.white(2, 2),
            TactileSettings(invert = true, widthMm = 0.2, heightMm = 0.2),
            profile(),
        )
        for (y in 0 until 2) {
            for (x in 0 until 2) {
                assertEquals(0, black.level(x, y))
                assertEquals(255, white.level(x, y))
            }
        }
    }

    @Test
    fun explicitMillimetresConvertToPixelsThroughDpi() {
        // 300 dpi：25.4 mm 正好是 1 英寸 = 300 px；记录的毫米必须与像素自洽。
        val raster = TactileRasterizer.rasterize(
            GrayImage.white(2, 2),
            TactileSettings(widthMm = 25.4, heightMm = 25.4),
            profile(dpi = 300),
        )
        assertEquals(300, raster.width)
        assertEquals(300, raster.height)
        assertEquals(25.4, raster.pageWidthMm, 0.0)
        assertEquals(25.4, raster.pageHeightMm, 0.0)
        assertEquals(300, profile(dpi = 300).mmToPx(raster.pageWidthMm))
        assertEquals(300, profile(dpi = 300).mmToPx(raster.pageHeightMm))
    }

    @Test
    fun missingHeightFollowsSourceAspectRatio() {
        // 原图 2×4（竖版，宽高比 0.5），只给宽 40 mm → 高 = 40 / 0.5 = 80 mm。
        val image = GrayImage(2, 4, IntArray(8) { 128 })
        val raster = TactileRasterizer.rasterize(
            image,
            TactileSettings(widthMm = 40.0, heightMm = 0.0),
            profile(),
        )
        assertEquals(40.0, raster.pageWidthMm, 0.0)
        assertEquals(80.0, raster.pageHeightMm, 0.0)
        assertEquals(400, raster.width)
        assertEquals(800, raster.height)
    }

    @Test
    fun autoSizePutsLongEdgeOnDefault160Mm() {
        // 两侧都为 0：竖版 2×4 的长边（高）取 160 mm，宽 = 160 × 0.5 = 80 mm。
        val image = GrayImage(2, 4, IntArray(8) { 200 })
        val raster = TactileRasterizer.rasterize(
            image,
            TactileSettings(),
            TactileProfile.A4,
        )
        assertEquals(160.0, raster.pageHeightMm, 0.0)
        assertEquals(80.0, raster.pageWidthMm, 0.01)
        assertEquals(TactileProfile.A4.mmToPx(160.0), raster.height)
        assertEquals(TactileProfile.A4.mmToPx(80.0), raster.width)
    }

    @Test
    fun areaAverageKeepsIntermediateGrayNotNearestNeighbor() {
        // 2×2 两黑两白缩到 1×1：面积平均 = (0+255+255+0)/4 = 127.5 → 128，
        // 灰度模式 level = 255 - 128 = 127；若是最近邻只会得到 0 或 255。
        val image = GrayImage(2, 2, intArrayOf(0, 255, 255, 0))
        val raster = TactileRasterizer.rasterize(
            image,
            TactileSettings(mode = TactileMode.GRAYSCALE, widthMm = 0.1, heightMm = 0.1),
            profile(),
        )
        assertEquals(1, raster.width)
        assertEquals(1, raster.height)
        assertEquals(127, raster.level(0, 0))
        assertNotEquals(0, raster.level(0, 0))
        assertNotEquals(255, raster.level(0, 0))
    }

    @Test
    fun oversizedRasterThrowsBeforeAllocating() {
        // 10000 mm × 10000 mm @ 300 dpi ≈ 118110 × 118110，远超 3000 万像素上限。
        val ex = assertThrows(TactileTooLargeException::class.java) {
            TactileRasterizer.rasterize(
                GrayImage.white(1, 1),
                TactileSettings(widthMm = 10000.0, heightMm = 10000.0),
                profile(dpi = 300),
            )
        }
        assertTrue(ex is IllegalArgumentException)
    }

    // ---------- 等比贴合页面 + 居中留白 ----------

    @Test
    fun portraitA4FitsLandscape4By3WithTopBottomMargins() {
        // 4:3 横图贴 A4 竖版 210×297@300：框 2480×3508；按宽贴合，内容 2480×1860，
        // 上下留白各 (3508-1860)/2 = 824 px，左右贴满。
        val image = GrayImage.black(4, 3)
        val raster = TactileRasterizer.rasterize(
            image,
            TactileSettings(widthMm = 210.0, heightMm = 297.0),
            TactileProfile(dpi = 300),
        )
        assertEquals(2480, raster.width)
        assertEquals(3508, raster.height)
        // 上下留白平坦（首行留白 0，末行留白 3507；内容下边界 2683，2684 起又是留白）。
        assertEquals(0, raster.level(0, 0))
        assertEquals(0, raster.level(0, 3507))
        assertEquals(0, raster.level(0, 2684))
        // 内容区居中：首行内容 824，末行内容 2683，中心行 1754。
        assertEquals(255, raster.level(0, 824))
        assertEquals(255, raster.level(0, 2683))
        assertEquals(255, raster.level(1240, 1754))
        // 左右贴满，无侧白。
        assertEquals(255, raster.level(0, 1754))
        assertEquals(255, raster.level(2479, 1754))
    }

    @Test
    fun landscapeA4Fits4By3WithSideMargins() {
        // A4 横版 profile 297×210@300：框 3508×2480；按高贴合，内容 3307×2480，
        // 左侧留白 (3508-3307)/2 = 100 px（右侧 101），上下贴满。
        val image = GrayImage.black(4, 3)
        val raster = TactileRasterizer.rasterize(
            image,
            TactileSettings(widthMm = 297.0, heightMm = 210.0),
            TactileProfile(dpi = 300, pageWidthMm = 297.0, pageHeightMm = 210.0),
        )
        assertEquals(3508, raster.width)
        assertEquals(2480, raster.height)
        assertEquals(0, raster.level(0, 10))
        assertEquals(255, raster.level(100, 10))
        assertEquals(255, raster.level(1754, 10))
        assertEquals(0, raster.level(3507, 10))
        // 上下贴满，无上下白。
        assertEquals(255, raster.level(1754, 0))
        assertEquals(255, raster.level(1754, 2479))
    }

    @Test
    fun squareSourceStaysSquareOnPortraitA4() {
        // 20×20 正方形贴 A4 竖版：按宽贴合成 2480×2480，上下留白 514 px；
        // 抬升区左右跨度与上下跨度必须相等（±1 px），不等就是被拉成椭圆。
        val image = GrayImage.black(20, 20)
        val raster = TactileRasterizer.rasterize(
            image,
            TactileSettings(widthMm = 210.0, heightMm = 297.0),
            TactileProfile(dpi = 300),
        )
        val midY = raster.height / 2
        var left = 0
        while (raster.level(left, midY) == 0) left++
        var right = raster.width - 1
        while (raster.level(right, midY) == 0) right--
        val midX = raster.width / 2
        var top = 0
        while (raster.level(midX, top) == 0) top++
        var bottom = raster.height - 1
        while (raster.level(midX, bottom) == 0) bottom--

        val spanX = right - left + 1
        val spanY = bottom - top + 1
        assertEquals(2480, spanX)
        assertEquals(2480, spanY)
        assertTrue(abs(spanX - spanY) <= 1)
    }

    @Test
    fun invertedRasterKeepsMarginsFlat() {
        // 白底 4:3 + invert：图形本身翻成凸起，四周留白边仍必须是 level=0，不跟着翻。
        val image = GrayImage.white(4, 3)
        val raster = TactileRasterizer.rasterize(
            image,
            TactileSettings(invert = true, widthMm = 210.0, heightMm = 297.0),
            TactileProfile(dpi = 300),
        )
        assertEquals(0, raster.level(0, 0))
        assertEquals(0, raster.level(0, 2684))
        assertEquals(255, raster.level(0, 824))
    }

    // ---------- BMP 字节级防回归：留白边框必须全 0xFF（直读编码字节，不查 levels） ----------

    @Test
    fun portraitA4BmpBorderBytesAreFlatOutsideContent() {
        // 1600×1200 全黑，THRESHOLD 默认阈值；A4 竖版 210×297@300 → 2480×3508。
        // scale = min(2480/1600, 3508/1200) = 1.55 → 内容 2480×1860，offsetY = 824，
        // 内容行 824..2683，上留白 824 行、下留白 824 行。
        val raster = TactileRasterizer.rasterize(
            GrayImage.black(1600, 1200),
            TactileSettings(widthMm = 210.0, heightMm = 297.0),
            TactileProfile.A4,
        )
        assertEquals(2480, raster.width)
        assertEquals(3508, raster.height)
        val bmp = TactileBmpEncoder.encode(raster)

        // 首行（栅格 y=0，BMP 文件最末像素行）与末行（栅格 y=3507，文件第一像素行）全平坦 0xFF。
        for (x in 0 until raster.width) {
            assertEquals(0xFF, bmpByte(bmp, 2480, 3508, x, 0))
            assertEquals(0xFF, bmpByte(bmp, 2480, 3508, x, 3507))
        }
        // 首列、末列：上下留白行必须 0xFF。
        for (y in 0 until 824) {
            assertEquals(0xFF, bmpByte(bmp, 2480, 3508, 0, y))
            assertEquals(0xFF, bmpByte(bmp, 2480, 3508, 2479, y))
        }
        for (y in 2684 until 3508) {
            assertEquals(0xFF, bmpByte(bmp, 2480, 3508, 0, y))
            assertEquals(0xFF, bmpByte(bmp, 2480, 3508, 2479, y))
        }
        // 内容带边界钉死：823 还是留白，824 首行内容；2683 末行内容，2684 起又是留白。
        assertEquals(0xFF, bmpByte(bmp, 2480, 3508, 0, 823))
        assertEquals(0x00, bmpByte(bmp, 2480, 3508, 0, 824))
        assertEquals(0x00, bmpByte(bmp, 2480, 3508, 0, 2683))
        assertEquals(0xFF, bmpByte(bmp, 2480, 3508, 0, 2684))
    }

    @Test
    fun landscapeA4BmpSideMarginsAreFlatContentColumnsRaised() {
        // 同一 4:3 全黑源，横版 profile 297×210@300 → 3508×2480。
        // scale = min(3508/1600, 2480/1200) = 2.0667 → 内容 3307×2480，offsetX = 100，
        // 内容列 100..3406，左留白 100 列、右留白 101 列；上下贴边，首末行本来就有内容。
        val landscape = TactileProfile(dpi = 300, pageWidthMm = 297.0, pageHeightMm = 210.0)
        val raster = TactileRasterizer.rasterize(
            GrayImage.black(1600, 1200),
            TactileSettings(widthMm = 297.0, heightMm = 210.0),
            landscape,
        )
        assertEquals(3508, raster.width)
        assertEquals(2480, raster.height)
        val bmp = TactileBmpEncoder.encode(raster)

        // 左右留白列：所有行都必须 0xFF。
        for (y in 0 until 2480) {
            for (x in 0 until 100) {
                assertEquals(0xFF, bmpByte(bmp, 3508, 2480, x, y))
            }
            for (x in 3407 until 3508) {
                assertEquals(0xFF, bmpByte(bmp, 3508, 2480, x, y))
            }
        }
        // 首末行上下贴边：留白列 0xFF，内容列 0x00。
        for (x in 0 until 100) {
            assertEquals(0xFF, bmpByte(bmp, 3508, 2480, x, 0))
            assertEquals(0xFF, bmpByte(bmp, 3508, 2480, x, 2479))
        }
        for (x in 100 until 3407) {
            assertEquals(0x00, bmpByte(bmp, 3508, 2480, x, 0))
            assertEquals(0x00, bmpByte(bmp, 3508, 2480, x, 2479))
        }
        // 内容左右边界：99 留白、100 首列内容；3406 末列内容、3407 留白。
        assertEquals(0xFF, bmpByte(bmp, 3508, 2480, 99, 1240))
        assertEquals(0x00, bmpByte(bmp, 3508, 2480, 100, 1240))
        assertEquals(0x00, bmpByte(bmp, 3508, 2480, 3406, 1240))
        assertEquals(0xFF, bmpByte(bmp, 3508, 2480, 3407, 1240))
    }
}
