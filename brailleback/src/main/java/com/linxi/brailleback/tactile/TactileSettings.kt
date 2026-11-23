package com.linxi.brailleback.tactile

/**
 * 为什么需要它：
 * 一张图片要变成触觉图，用户实际能调的只有几项：怎么把灰度变成高低（模式）、黑到多深才算凸起
 * （阈值）、黑白反过来（invert）、图印多大（毫米）。这些值可能来自界面、偏好或文件，
 * 都可能越界或缺失，所以统一由 [normalized] 一处钳制、补默认，别让下游各自猜。
 */
data class TactileSettings(
    /** 抬升策略。默认阈值线稿，因为线条图与带字示意图摸起来最清楚。 */
    val mode: TactileMode = TactileMode.THRESHOLD,
    /** 阈值，0..255。灰度小于它就完全抬升。越界在 [normalized] 里钳制。 */
    val threshold: Int = DEFAULT_THRESHOLD,
    /** 反转：让本应平坦的一侧凸起。 */
    val invert: Boolean = false,
    /** 目标宽（毫米）。0 表示按图片比例自动推算。 */
    val widthMm: Double = 0.0,
    /** 目标高（毫米）。0 表示按图片比例自动推算。 */
    val heightMm: Double = 0.0,
) {

    /**
     * 钳制并补默认，返回一份可以安全交给栅格化的设置：
     * 阈值进 0..255；负的毫米按 0（自动）处理；NaN / 无穷视为未填。
     */
    fun normalized(): TactileSettings = copy(
        threshold = threshold.coerceIn(MIN_THRESHOLD, MAX_THRESHOLD),
        widthMm = sanitizeMm(widthMm),
        heightMm = sanitizeMm(heightMm),
    )

    companion object {
        const val MIN_THRESHOLD: Int = 0
        const val MAX_THRESHOLD: Int = 255
        const val DEFAULT_THRESHOLD: Int = 128

        /** 非正数、NaN、无穷一律当「没填」，交给比例推算。 */
        private fun sanitizeMm(mm: Double): Double =
            if (mm.isNaN() || mm.isInfinite() || mm <= 0.0) 0.0 else mm
    }
}

/**
 * 为什么需要它：
 * 三种抬升策略对应三类源图，混淆会得到摸不出信息的纸：线条图和带字示意图要阈值化才干净；
 * 灰阶照片要保留层次喂高度调制打印机；纯照片直接阈值只会糊成一团，得先取边缘。
 */
enum class TactileMode {
    /** 灰度 < 阈值 → 完全抬升，否则平坦。适合线条图、带字的示意图。 */
    THRESHOLD,

    /** 保留 0..255 层次，深墨 = 高抬升。喂支持高度调制的打印机。 */
    GRAYSCALE,

    /** 边缘检测，照片的轮廓变成凸线。 */
    EDGE,
}
