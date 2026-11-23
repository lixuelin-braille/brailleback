package com.linxi.brailleback.tactile

/**
 * 为什么需要它：
 * 栅格化之后、编码成 BMP 之前，中间结果需要一份「只谈抬升高度」的载体。
 * 这里存的等级一律是「抬升等级」：255 = 完全抬起，0 = 完全平坦。
 * 注意它和 BMP 里的字节是反的——编码时才做 255 - level 的反转（凸起写黑），
 * 反转只发生在 [TactileBmpEncoder] 一处，别在别处提前翻。
 */
class TactileRaster(
    /** 宽（像素）。 */
    val width: Int,
    /** 高（像素）。 */
    val height: Int,
    /** 逐像素抬升等级，行优先（y * width + x），长度必须是 width * height。 */
    val levels: ByteArray,
    /** 输出分辨率，决定 BMP 头的 ppm。 */
    val dpi: Int,
    /** 这张栅格对应的物理宽（毫米）。 */
    val pageWidthMm: Double,
    /** 这张栅格对应的物理高（毫米）。 */
    val pageHeightMm: Double,
) {

    init {
        require(width > 0) { "栅格宽必须为正数，实际为 $width" }
        require(height > 0) { "栅格高必须为正数，实际为 $height" }
        require(levels.size == width * height) {
            "等级数组长度必须等于 width * height = ${width * height}，实际为 ${levels.size}"
        }
        require(dpi > 0) { "栅格 dpi 必须为正数，实际为 $dpi" }
        require(pageWidthMm > 0.0) { "物理宽必须为正数，实际为 $pageWidthMm mm" }
        require(pageHeightMm > 0.0) { "物理高必须为正数，实际为 $pageHeightMm mm" }
    }

    /** 坐标是否落在图内。越界访问一律当平坦，调用方不必自己判。 */
    fun contains(x: Int, y: Int): Boolean = x in 0 until width && y in 0 until height

    /** 取某点的抬升等级，0..255。越界返回 0（平坦）。 */
    fun level(x: Int, y: Int): Int =
        if (contains(x, y)) levels[y * width + x].toInt() and 0xFF else 0

    /**
     * 该点是否算「凸起」。半抬升以上算凸起：阈值模式只有 0 / 255，灰度模式也能得到一个直观判定。
     */
    fun raisedPixel(x: Int, y: Int): Boolean = level(x, y) >= RAISED_THRESHOLD

    companion object {
        /** 判定「凸起」的等级下限。 */
        const val RAISED_THRESHOLD: Int = 128
    }
}
