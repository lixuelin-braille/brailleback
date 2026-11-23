/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 */

package com.linxi.brailleback.tactile

import kotlin.math.roundToInt

/**
 * 为什么需要它：
 * 触觉栅格（抬升等级）要能被普通看图软件 / 刻印软件识别，得落成一份标准 8 位灰度 BMP。
 * 这里纯手写字节，不依赖 java.awt / ImageIO（安卓上没有这些类），方便纯 JVM 单测逐字节钉死布局。
 * 极性反转（凸起=黑 0、平坦=白 255）只发生在本编码器一处，别处不要提前翻等级。
 */
object TactileBmpEncoder {

    /** 文件头长度（字节）。 */
    private const val FILE_HEADER_SIZE = 14

    /** 信息头 BITMAPINFOHEADER 长度（字节）。 */
    private const val INFO_HEADER_SIZE = 40

    /** 8 位灰度调色板项数。 */
    private const val PALETTE_ENTRY_COUNT = 256

    /** 调色板总长度：256 项 × 4 字节（B,G,R,0）。 */
    private const val PALETTE_SIZE = PALETTE_ENTRY_COUNT * 4

    /** 像素数据在文件中的偏移：14 + 40 + 1024 = 1078。 */
    const val PIXEL_DATA_OFFSET = FILE_HEADER_SIZE + INFO_HEADER_SIZE + PALETTE_SIZE

    /** 位深：8 位灰度。 */
    private const val BIT_COUNT = 8

    /** BMP 平面数，规范固定为 1。 */
    private const val PLANES = 1

    /** BI_RGB，无压缩。 */
    private const val COMPRESSION_RGB = 0

    /**
     * 把一张触觉栅格编码成 8 位灰度 BMP 字节。
     *
     * 布局：14 字节文件头 + 40 字节信息头 + 256 项灰度调色板 + 像素数据；
     * 行按 4 字节对齐（填充字节写 0）；像素自下而上排列；像素字节 = 255 - level。
     */
    fun encode(raster: TactileRaster): ByteArray {
        val rowSize = ((raster.width + 3) / 4) * 4
        val pixelDataSize = rowSize * raster.height
        val totalSize = PIXEL_DATA_OFFSET + pixelDataSize
        val ppm = (raster.dpi / 0.0254).roundToInt()

        val buf = ByteArray(totalSize)

        // 文件头
        buf[0] = 'B'.code.toByte()
        buf[1] = 'M'.code.toByte()
        writeUInt32(buf, 2, totalSize)
        // 6..9 两个保留位已是 0
        writeUInt32(buf, 10, PIXEL_DATA_OFFSET)

        // 信息头
        writeUInt32(buf, 14, INFO_HEADER_SIZE)
        writeInt32(buf, 18, raster.width)
        writeInt32(buf, 22, raster.height)
        writeUInt16(buf, 26, PLANES)
        writeUInt16(buf, 28, BIT_COUNT)
        writeUInt32(buf, 30, COMPRESSION_RGB)
        writeUInt32(buf, 34, pixelDataSize)
        writeInt32(buf, 38, ppm)
        writeInt32(buf, 42, ppm)
        // 46..53 biClrUsed / biClrImportant 留 0：调色板 256 项全部使用

        // 灰度调色板：第 v 项 = (B=v, G=v, R=v, 0)
        var paletteOffset = FILE_HEADER_SIZE + INFO_HEADER_SIZE
        for (v in 0 until PALETTE_ENTRY_COUNT) {
            buf[paletteOffset] = v.toByte()
            buf[paletteOffset + 1] = v.toByte()
            buf[paletteOffset + 2] = v.toByte()
            buf[paletteOffset + 3] = 0
            paletteOffset += 4
        }

        // 像素数据：底行在前（BMP 自下而上），每行 4 字节对齐，填充字节由 ByteArray 默认 0 保证
        var cursor = PIXEL_DATA_OFFSET
        for (y in raster.height - 1 downTo 0) {
            for (x in 0 until raster.width) {
                buf[cursor + x] = (255 - raster.level(x, y)).toByte()
            }
            cursor += rowSize
        }
        return buf
    }

    private fun writeUInt16(buf: ByteArray, offset: Int, value: Int) {
        buf[offset] = (value and 0xFF).toByte()
        buf[offset + 1] = ((value ushr 8) and 0xFF).toByte()
    }

    private fun writeUInt32(buf: ByteArray, offset: Int, value: Int) {
        buf[offset] = (value and 0xFF).toByte()
        buf[offset + 1] = ((value ushr 8) and 0xFF).toByte()
        buf[offset + 2] = ((value ushr 16) and 0xFF).toByte()
        buf[offset + 3] = ((value ushr 24) and 0xFF).toByte()
    }

    private fun writeInt32(buf: ByteArray, offset: Int, value: Int) = writeUInt32(buf, offset, value)
}
