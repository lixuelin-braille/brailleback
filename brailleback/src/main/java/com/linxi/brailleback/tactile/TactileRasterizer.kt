package com.linxi.brailleback.tactile

import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * 为什么需要它：
 * 一张灰度照片要变成可触摸的凸起图，中间有三步必须由我们自己控制：先按毫米和 dpi 定出目标像素
 * （面积平均重采样，不能最近邻，否则缩放线稿会断线）；再把每个亮度翻译成抬升等级
 * （阈值线稿 / 灰度层次 / 边缘轮廓三种策略）；最后按需整幅翻转。这里是纯 JVM 的栅格化内核，
 * 不碰 Android Bitmap，方便在 JVM 单测里把每条边界钉死；BMP 字节反转留给编码器一处做。
 *
 * 页面贴合口径（对冻结契约的补充）：
 * - 双侧毫米都明确给出时，原图按宽高比缩放到刚好装进这个毫米框（两轴缩放比取较小者），
 *   居中放置，四周多出的部分是平坦留白（level=0），不拉伸变形、不裁剪。
 * - 某一侧毫米为 0：另一侧按原图比例推算，缩放比唯一，不存在变形，沿用原行为。
 * - 双侧都为 0：长边取 160 mm，沿用原行为。
 * - 出图像素数严格等于毫米框换算结果；invert 只翻图形本身的等级，留白边永远平坦，不跟着翻。
 */
object TactileRasterizer {

    /** 目标栅格像素总数上限。超过就在分配数组前抛出，别等铺满堆才 OOM。 */
    const val MAX_PIXELS: Int = 30_000_000

    /** 完全平坦的抬升等级。 */
    private const val FLAT: Int = 0

    /** 完全抬起的抬升等级。 */
    private const val RAISED: Int = 255

    /** Sobel 核单列权重和：1 + 2 + 1 = 4，单方向最大差分 = 4 × 255。 */
    private const val SOBEL_AXIS_WEIGHT_SUM: Int = 4

    /**
     * 归一化上限：取单轴理论最大差分 4×255 = 1020。x、y 两轴对称，合成幅值也按这同一个上限截顶，
     * 不乘 √2——否则真实图案能达到的归一化幅值被压到 202 左右，阈值滑块 203..255 一段会整体失效。
     * 归一化后 0..255，与 THRESHOLD 模式共用同一把阈值尺子。
     */
    private val SOBEL_MAX_MAGNITUDE: Double = 255.0 * SOBEL_AXIS_WEIGHT_SUM

    fun rasterize(
        image: GrayImage,
        settings: TactileSettings,
        profile: TactileProfile = TactileProfile.A4,
    ): TactileRaster {
        // 设置只在这里统一钳制、补默认一次，下游不再各自猜边界。
        val s = settings.normalized()

        val (boxWidthMm, boxHeightMm) = derivePhysicalSizeMm(image, s)
        val pageWidth = profile.mmToPx(boxWidthMm).coerceAtLeast(1)
        val pageHeight = profile.mmToPx(boxHeightMm).coerceAtLeast(1)

        // 先按像素总数拦截，避免几千万级数组直接撑爆堆。
        val pixelCount = pageWidth.toLong() * pageHeight
        if (pixelCount > MAX_PIXELS) {
            throw TactileTooLargeException(
                "目标栅格 ${pageWidth} × ${pageHeight} = $pixelCount 像素，" +
                    "超过上限 $MAX_PIXELS（请缩小毫米尺寸或降低 dpi）"
            )
        }

        // 等比贴合：两轴缩放比取较小者，内容不拉伸；居中后四周留白，留白恒为平坦。
        val layout = fitContent(image, pageWidth, pageHeight)
        // 先面积平均缩放到内容尺寸，再按模式抬升；缩小后的线稿不会被最近邻抽断。
        val gray = resampleByArea(image, layout.contentWidth, layout.contentHeight)

        // 数组默认全 0：框内没被内容盖住的位置就是平坦留白，invert 也不翻这里。
        val levels = ByteArray(pageWidth * pageHeight)
        for (cy in 0 until layout.contentHeight) {
            for (cx in 0 until layout.contentWidth) {
                val luma = gray[cy * layout.contentWidth + cx]
                var level = when (s.mode) {
                    TactileMode.THRESHOLD -> if (luma < s.threshold) RAISED else FLAT
                    TactileMode.GRAYSCALE -> RAISED - luma
                    TactileMode.EDGE -> edgeLevel(
                        gray, layout.contentWidth, layout.contentHeight, cx, cy, s.threshold
                    )
                }
                // 图形本身按需翻转；留白边在数组初始化时已是 0，不参与翻转。
                if (s.invert) level = RAISED - level
                val px = (layout.offsetY + cy) * pageWidth + (layout.offsetX + cx)
                levels[px] = level.toByte()
            }
        }

        return TactileRaster(
            width = pageWidth,
            height = pageHeight,
            levels = levels,
            dpi = profile.dpi,
            pageWidthMm = boxWidthMm,
            pageHeightMm = boxHeightMm,
        )
    }

    /**
     * 由设置与原图比例推出物理尺寸（毫米）。
     * 两侧都填了 → 原样使用；只填一侧 → 另一侧按原图宽高比推算；两侧都为 0 → 长边取 160 mm。
     */
    private fun derivePhysicalSizeMm(image: GrayImage, s: TactileSettings): Pair<Double, Double> {
        val srcRatio = image.width.toDouble() / image.height.toDouble()

        if (s.widthMm > 0.0 && s.heightMm > 0.0) return s.widthMm to s.heightMm

        if (s.widthMm == 0.0 && s.heightMm == 0.0) {
            return if (image.width >= image.height) {
                TactileProfile.DEFAULT_LONG_EDGE_MM to TactileProfile.DEFAULT_LONG_EDGE_MM / srcRatio
            } else {
                TactileProfile.DEFAULT_LONG_EDGE_MM * srcRatio to TactileProfile.DEFAULT_LONG_EDGE_MM
            }
        }
        if (s.heightMm == 0.0) {
            // 只给了宽：高按原图宽高比推算。
            return s.widthMm to s.widthMm / srcRatio
        }
        // 只给了高：宽按原图宽高比推算。
        return s.heightMm * srcRatio to s.heightMm
    }

    /**
     * 把原图等比塞进页面框：两轴缩放比取较小者，内容不拉伸；居中放置，返回内容尺寸与左上角偏移。
     * 单侧为 0 或双侧为 0 推出来的框本身就与原图同比例，此时内容铺满整框，偏移为 0。
     */
    private fun fitContent(image: GrayImage, pageWidth: Int, pageHeight: Int): ContentLayout {
        val scaleX = pageWidth.toDouble() / image.width
        val scaleY = pageHeight.toDouble() / image.height
        val scale = minOf(scaleX, scaleY)
        val contentWidth = (image.width * scale).roundToInt().coerceAtMost(pageWidth)
        val contentHeight = (image.height * scale).roundToInt().coerceAtMost(pageHeight)
        return ContentLayout(
            contentWidth = contentWidth,
            contentHeight = contentHeight,
            offsetX = (pageWidth - contentWidth) / 2,
            offsetY = (pageHeight - contentHeight) / 2,
        )
    }

    /** 内容在页面框内的尺寸与居中偏移（像素）。 */
    private data class ContentLayout(
        val contentWidth: Int,
        val contentHeight: Int,
        val offsetX: Int,
        val offsetY: Int,
    )

    /** 面积平均重采样到目标尺寸。每个目标像素回映到原图一块矩形区域，按重叠面积加权平均。 */
    private fun resampleByArea(image: GrayImage, targetWidth: Int, targetHeight: Int): IntArray {
        val out = IntArray(targetWidth * targetHeight)
        val scaleX = image.width.toDouble() / targetWidth
        val scaleY = image.height.toDouble() / targetHeight

        for (ty in 0 until targetHeight) {
            val y0 = ty * scaleY
            val y1 = (ty + 1) * scaleY
            val rowStart = ty * targetWidth
            for (tx in 0 until targetWidth) {
                val x0 = tx * scaleX
                val x1 = (tx + 1) * scaleX
                out[rowStart + tx] = areaAverage(image, x0, x1, y0, y1)
            }
        }
        return out
    }

    /** 对原图矩形区域 [x0, x1) × [y0, y1) 做重叠面积加权平均，返回 0..255。 */
    private fun areaAverage(
        image: GrayImage,
        x0: Double,
        x1: Double,
        y0: Double,
        y1: Double,
    ): Int {
        val ix0 = x0.toInt().coerceIn(0, image.width - 1)
        val ix1 = (x1 - 1.0).toInt().coerceIn(0, image.width - 1)
        val iy0 = y0.toInt().coerceIn(0, image.height - 1)
        val iy1 = (y1 - 1.0).toInt().coerceIn(0, image.height - 1)

        var weightedLuma = 0.0
        var weight = 0.0
        for (iy in iy0..iy1) {
            val overlapY = minOf(y1, iy + 1.0) - maxOf(y0, iy.toDouble())
            if (overlapY <= 0.0) continue
            for (ix in ix0..ix1) {
                val overlapX = minOf(x1, ix + 1.0) - maxOf(x0, ix.toDouble())
                if (overlapX <= 0.0) continue
                val w = overlapX * overlapY
                weightedLuma += image.at(ix, iy) * w
                weight += w
            }
        }
        return if (weight > 0.0) {
            (weightedLuma / weight).roundToInt().coerceIn(0, 255)
        } else {
            FLAT
        }
    }

    /**
     * 3×3 Sobel 边缘检测。合成幅值先按单轴最大差分上限 [SOBEL_MAX_MAGNITUDE] 截顶，再缩成 0..255，
     * 与阈值做严格大于比较（与 THRESHOLD 的严格 `<` 同一套边界口径）。边界坐标钳到边缘。
     */
    private fun edgeLevel(
        gray: IntArray,
        width: Int,
        height: Int,
        x: Int,
        y: Int,
        threshold: Int,
    ): Int {
        fun sample(ix: Int, iy: Int): Int {
            val cx = ix.coerceIn(0, width - 1)
            val cy = iy.coerceIn(0, height - 1)
            return gray[cy * width + cx]
        }

        val gx = (sample(x + 1, y - 1) + 2 * sample(x + 1, y) + sample(x + 1, y + 1)) -
            (sample(x - 1, y - 1) + 2 * sample(x - 1, y) + sample(x - 1, y + 1))
        val gy = (sample(x - 1, y + 1) + 2 * sample(x, y + 1) + sample(x + 1, y + 1)) -
            (sample(x - 1, y - 1) + 2 * sample(x, y - 1) + sample(x + 1, y - 1))
        val magnitude = hypot(gx.toDouble(), gy.toDouble())
        // 截顶后按单轴最大差分上限缩放到 0..255，让阈值滑块整段 0..255 都有区分度。
        val normalized = (magnitude.coerceAtMost(SOBEL_MAX_MAGNITUDE) / SOBEL_MAX_MAGNITUDE * 255.0)
            .roundToInt()
            .coerceIn(0, 255)
        return if (normalized > threshold) RAISED else FLAT
    }
}

/**
 * 为什么需要它：目标尺寸由毫米 + dpi 推出来可能大到几千万像素，直接分配数组会把堆撑爆。
 * 在栅格化入口处单独抛一个语义明确的异常，让入口层能回退到「缩小尺寸再试」，
 * 而不是落一个笼统的 OOM。它沿用 IllegalArgumentException 的既有异常体系。
 */
class TactileTooLargeException(message: String) : IllegalArgumentException(message)
