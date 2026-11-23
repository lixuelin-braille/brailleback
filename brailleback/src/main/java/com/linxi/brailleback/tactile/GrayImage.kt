package com.linxi.brailleback.tactile

/**
 * 为什么需要它：
 * 栅格化与 BMP 编码都要「不看 Android」才能在 JVM 上跑单测。Android 的 Bitmap 只有设备上才有，
 * 所以在进入纯逻辑之前，先把图降成一张最朴素的灰度平面：宽、高、每像素 0..255 的亮度。
 * 解码图片（Bitmap → GrayImage）是唯一允许碰 android.graphics 的地方，见 AndroidImageLoader。
 */
class GrayImage(
    /** 宽（像素）。 */
    val width: Int,
    /** 高（像素）。 */
    val height: Int,
    /** 逐像素亮度 0..255，行优先（y * width + x），长度必须是 width * height。 */
    val luma: IntArray,
) {

    init {
        require(width > 0) { "灰度图宽必须为正数，实际为 $width" }
        require(height > 0) { "灰度图高必须为正数，实际为 $height" }
        require(luma.size == width * height) {
            "亮度数组长度必须等于 width * height = ${width * height}，实际为 ${luma.size}"
        }
    }

    /** 取某点亮度。越界钳制到边缘，边缘检测时免去四角各写一套边界分支。 */
    fun at(x: Int, y: Int): Int {
        val cx = x.coerceIn(0, width - 1)
        val cy = y.coerceIn(0, height - 1)
        return luma[cy * width + cx].coerceIn(0, 255)
    }

    companion object {
        /** 造一张纯色图，单测里造边界样本用。 */
        fun solid(width: Int, height: Int, value: Int): GrayImage {
            require(value in 0..255) { "亮度必须在 0..255，实际为 $value" }
            return GrayImage(width, height, IntArray(width * height) { value })
        }

        /** 全白（0 深墨）。 */
        fun white(width: Int, height: Int): GrayImage = solid(width, height, 255)

        /** 全黑（满墨）。 */
        fun black(width: Int, height: Int): GrayImage = solid(width, height, 0)
    }
}
