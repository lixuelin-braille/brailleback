package com.linxi.brailleback.tactile

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import kotlin.math.roundToInt

/**
 * 为什么需要它：
 * 纯 JVM 的栅格化内核（[TactileRasterizer] / [TactileBmpEncoder]）看不见 Android Bitmap，
 * 进入内核之前必须在这一层把系统解码出的图片降成最朴素的 [GrayImage]。
 * 全仓库只有本文件允许 import `android.graphics.Bitmap`，其余触觉图代码保持可在 JVM 上单测的形态。
 */
object AndroidImageLoader {

    /** 源文件大小上限：超过直接拒绝，不进解码流程，避免大文件先把堆吃掉。 */
    const val MAX_SOURCE_BYTES: Long = 32L * 1024 * 1024

    // 亮度加权系数（ITU-R BT.601）：人眼对绿光最敏感，权重最大。
    private const val WEIGHT_R = 0.299
    private const val WEIGHT_G = 0.587
    private const val WEIGHT_B = 0.114

    /**
     * 把已解码的 Bitmap 转成灰度平面。全仓库唯一读取 Bitmap 像素的地方。
     * 亮度 = 0.299·R + 0.587·G + 0.114·B，四舍五入夹到 0..255。
     */
    fun fromBitmap(bitmap: Bitmap): GrayImage {
        val width = bitmap.width
        val height = bitmap.height
        require(width > 0 && height > 0) {
            "Bitmap 宽高必须为正数，实际为 ${width}×$height"
        }
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val luma = IntArray(width * height)
        for (i in pixels.indices) {
            val color = pixels[i]
            val r = (color shr 16) and 0xFF
            val g = (color shr 8) and 0xFF
            val b = color and 0xFF
            luma[i] = (WEIGHT_R * r + WEIGHT_G * g + WEIGHT_B * b)
                .roundToInt()
                .coerceIn(0, 255)
        }
        return GrayImage(width, height, luma)
    }

    /**
     * 从文件解码成灰度图。
     *
     * - 文件不存在 / 不可读 / 超过 [MAX_SOURCE_BYTES] → [IllegalArgumentException]；
     * - 先用 `inJustDecodeBounds` 只读图片边界，按 2 的幂算 `inSampleSize` 降采样，
     *   防止整张大图一次性解码撑爆内存；
     * - 降采样只兜解码期 OOM，目标尺寸的 30 MP 上限仍由 [TactileRasterizer] 在栅格化时拦截，
     *   本方法不替代那道检查；
     * - 解码失败（损坏 / 格式不支持 / 内存不足）抛带文件名的明确异常文案，不返回空壳。
     */
    fun load(file: File): GrayImage {
        if (!file.exists() || !file.isFile) {
            throw IllegalArgumentException("图片文件不存在或不是普通文件：${file.name}")
        }
        val length = file.length()
        if (length > MAX_SOURCE_BYTES) {
            throw IllegalArgumentException(
                "图片大小 ${length / (1024 * 1024)} MB 超过 32 MB 上限，请先压缩后再导入"
            )
        }

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || bounds.outMimeType == null) {
            throw IllegalArgumentException("无法识别的图片格式（文件可能已损坏）：${file.name}")
        }

        val sampleSize = computeSampleSize(bounds.outWidth, bounds.outHeight)
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            // 解码出的像素立刻被 fromBitmap 压成单通道灰度；这里用 ARGB_8888 取色最稳，
            // 不用 RGB_565，避免红绿分量被量化后亮度偏色。
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }

        val bitmap = try {
            BitmapFactory.decodeFile(file.absolutePath, options)
        } catch (e: OutOfMemoryError) {
            throw IllegalArgumentException(
                "图片过大，解码时内存不足（可先在相册里压缩尺寸）：${file.name}", e
            )
        } catch (e: Exception) {
            throw IllegalArgumentException("图片解码失败（格式不支持或文件已损坏）：${file.name}", e)
        }
        if (bitmap == null) {
            throw IllegalArgumentException("图片解码失败（格式不支持或文件已损坏）：${file.name}")
        }
        return try {
            fromBitmap(bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * 按 2 的幂往上取 `inSampleSize`，直到解码后像素数不超过 [TactileRasterizer.MAX_PIXELS]。
     * BitmapFactory 只认 2 的幂，非 2 的幂会被向下取整，这里直接按幂次除。
     */
    private fun computeSampleSize(width: Int, height: Int): Int {
        var sample = 1
        var w = width.toLong()
        var h = height.toLong()
        while (w * h > TactileRasterizer.MAX_PIXELS) {
            sample *= 2
            w /= 2
            h /= 2
        }
        return sample
    }
}
