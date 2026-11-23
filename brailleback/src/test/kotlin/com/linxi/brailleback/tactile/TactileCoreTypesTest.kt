/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 */

package com.linxi.brailleback.tactile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 为什么需要它：触觉图所有换算都长在这四个类上（毫米↔像素、阈值钳制、抬升等级存取、灰度平面），
 * 它们一旦悄悄变了，栅格化和 BMP 编码会一起错，而且错得不明显——图还是能导出，只是物理尺寸不对、
 * 或者该凸的地方没凸。这里把契约钉死：非正数必须抛、越界必须钳、等级 255 才算完全抬起。
 */
class TactileCoreTypesTest {

    // ---------- TactileProfile：物理单位是真源 ----------

    @Test
    fun a4ProfileIs210By297AtDefaultDpi() {
        val p = TactileProfile.A4
        assertEquals(210.0, p.pageWidthMm, 0.0)
        assertEquals(297.0, p.pageHeightMm, 0.0)
        assertEquals(300, p.dpi)
        // 每毫米像素：300 / 25.4
        assertEquals(11.811024, p.pxPerMm, 0.000001)
    }

    @Test
    fun pixelCountIsDerivedFromMillimetresAndDpi() {
        val p = TactileProfile.A4
        // 210 × 300 / 25.4 = 2480.31 → 2480；297 × 300 / 25.4 = 3507.87 → 3508
        assertEquals(2480, p.pageWidthPx)
        assertEquals(3508, p.pageHeightPx)
        assertEquals(2480, p.mmToPx(210.0))
        assertEquals(118, p.mmToPx(10.0))
    }

    @Test
    fun pxToMmIsTheInverseOfMmToPx() {
        val p = TactileProfile.A4
        assertEquals(10.0, p.pxToMm(118), 0.01)
        assertEquals(210.0, p.pxToMm(2480), 0.05)
    }

    @Test
    fun bmpHeaderPpmComesFromDpiNotFromMillimetres() {
        // BMP 头要的是「像素/米」：300 dpi = 11811.02 → 11811
        assertEquals(11811, TactileProfile.A4.pixelsPerMeter)
        assertEquals(7874, TactileProfile(dpi = 200).pixelsPerMeter)
    }

    @Test(expected = IllegalArgumentException::class)
    fun zeroDpiIsRejected() {
        TactileProfile(dpi = 0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun zeroPageWidthIsRejected() {
        TactileProfile(pageWidthMm = 0.0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun negativePageHeightIsRejected() {
        TactileProfile(pageHeightMm = -1.0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun zeroLineWidthIsRejected() {
        TactileProfile(minLineWidthMm = 0.0)
    }

    @Test
    fun brailleLabelKeepsTenMillimetreLinePitch() {
        // 行距与现有导出排版一致，是 10 mm；方距必须大于点距，否则两方会叠在一起。
        val m = BrailleTagMetrics()
        assertEquals(10.0, m.linePitchMm, 0.0)
        assertEquals(6.0, m.cellPitchMm, 0.0)
        assertEquals(2.5, m.dotPitchMm, 0.0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun cellPitchNotWiderThanDotPitchIsRejected() {
        BrailleTagMetrics(dotPitchMm = 6.0, cellPitchMm = 6.0)
    }

    // ---------- TactileSettings：一处钳制，下游不再猜 ----------

    @Test
    fun defaultsAreThreshold128NoInvertAutoSize() {
        val s = TactileSettings().normalized()
        assertEquals(TactileMode.THRESHOLD, s.mode)
        assertEquals(128, s.threshold)
        assertFalse(s.invert)
        assertEquals(0.0, s.widthMm, 0.0)
        assertEquals(0.0, s.heightMm, 0.0)
    }

    @Test
    fun thresholdIsClampedToByteRange() {
        assertEquals(255, TactileSettings(threshold = 300).normalized().threshold)
        assertEquals(0, TactileSettings(threshold = -20).normalized().threshold)
    }

    @Test
    fun nonPositiveNanAndInfiniteSizesFallBackToAuto() {
        assertEquals(0.0, TactileSettings(widthMm = -5.0).normalized().widthMm, 0.0)
        assertEquals(0.0, TactileSettings(heightMm = Double.NaN).normalized().heightMm, 0.0)
        assertEquals(0.0, TactileSettings(widthMm = Double.POSITIVE_INFINITY).normalized().widthMm, 0.0)
        // 合法值要原样保留，别被"补默认"顺手清掉
        assertEquals(160.0, TactileSettings(widthMm = 160.0).normalized().widthMm, 0.0)
    }

    // ---------- TactileRaster：存的是抬升等级，不是 BMP 字节 ----------

    @Test(expected = IllegalArgumentException::class)
    fun levelArrayMustMatchPixelCount() {
        TactileRaster(2, 2, ByteArray(3), 300, 1.0, 1.0)
    }

    @Test
    fun outOfBoundsLevelReadsAsFlat() {
        val r = TactileRaster(1, 1, byteArrayOf(255.toByte()), 300, 1.0, 1.0)
        assertEquals(0, r.level(1, 0))
        assertEquals(0, r.level(-1, 0))
        assertFalse(r.contains(1, 0))
    }

    @Test
    fun raisedStartsAtHalfLevel() {
        val r = TactileRaster(4, 1, byteArrayOf(0.toByte(), 127.toByte(), 128.toByte(), 255.toByte()), 300, 1.0, 1.0)
        assertFalse(r.raisedPixel(0, 0))
        assertFalse(r.raisedPixel(1, 0))
        assertTrue(r.raisedPixel(2, 0))
        assertTrue(r.raisedPixel(3, 0))
        assertEquals(255, r.level(3, 0))
        assertEquals(0, r.level(0, 0))
    }

    // ---------- GrayImage：纯数据，边缘自己钳 ----------

    @Test
    fun edgeCoordinatesClampInsteadOfThrow() {
        val g = GrayImage(2, 2, intArrayOf(10, 20, 30, 40))
        assertEquals(10, g.at(-1, -1))
        assertEquals(20, g.at(5, 0))
        assertEquals(30, g.at(0, 5))
        assertEquals(40, g.at(5, 5))
    }

    @Test
    fun solidWhiteAndBlackAgreeOnLuma() {
        assertEquals(255, GrayImage.white(2, 1).at(0, 0))
        assertEquals(0, GrayImage.black(2, 1).at(1, 0))
    }

    @Test(expected = IllegalArgumentException::class)
    fun outOfRangeSolidValueIsRejected() {
        GrayImage.solid(1, 1, 300)
    }
}
