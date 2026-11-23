/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 */

package com.linxi.brailleback.tactile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 为什么需要它：栅格化内核是「图片 → 凸起栅格」的唯一翻译层，阈值边界、极性、尺寸换算、
 * 重采样方式任何一处歪掉，出来的触觉图要么断线、要么物理尺寸不对，而且肉眼在屏幕上看不出来。
 * 这里用纯 JVM 样本把契约逐条钉死：阈值等于边界值必须平坦、面积平均不能退化成最近邻、
 * 超限必须在分配数组前抛异常。
 */
class TactileRasterizerTest {

    // dpi = 254 时 pxPerMm 正好是 10.0，毫米→像素的期望值可以心算，不用被测代码反推。
    private fun profile(dpi: Int = 254) = TactileProfile(dpi = dpi)

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
}
