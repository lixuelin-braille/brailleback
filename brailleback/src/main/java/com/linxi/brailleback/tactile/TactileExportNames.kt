package com.linxi.brailleback.tactile

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 为什么需要它：导出文件要从手机拷到电脑刻印，文件名必须读屏念得清、电脑也认得：
 * 带原图主名 + 时间戳，非法字符清洗掉，超长截断。这段逻辑不碰 Android 类型，
 * 可以在 JVM 单测里把边界钉死；Activity 只负责把显示名和毫秒值传进来。
 */
object TactileExportNames {

    /** 原图主名（去扩展名后）最长保留字符数。 */
    const val STEM_MAX = 40

    /** 整个文件名（前缀 + 主名 + 时间戳 + 后缀）最长字符数。 */
    const val TOTAL_MAX = 100

    private const val PREFIX = "tactile_"
    private const val SUFFIX = ".bmp"
    private const val FALLBACK_STEM = "image"

    /** Windows / Android 文件系统非法字符；控制字符与空白另算。 */
    private val ILLEGAL = Regex("[\\\\/:*?\"<>|]")
    private val CONTROL_OR_BLANK = Regex("[\\p{Cntrl}\\s]+")
    private val UNDERLINES = Regex("_+")

    private val timestampFormat = SimpleDateFormat("yyyyMMdd_HHmm", Locale.ROOT)

    /** 由毫秒值生成时间戳段，形如 `20261124_1530`；固定 ROOT 区域，避免本地化把数字改掉。 */
    fun timestamp(millis: Long): String = timestampFormat.format(Date(millis))

    /**
     * 清洗原图主名：去扩展名 → 非法字符 / 控制字符 / 空白转下划线 → 合并重复下划线 →
     * 去首尾下划线 → 截到 [STEM_MAX]；清洗后为空回落 [FALLBACK_STEM]。
     * 保留中文等非 ASCII 字符（读屏能念，电脑也能存）。
     */
    fun sanitizeStem(raw: String?): String {
        if (raw.isNullOrBlank()) return FALLBACK_STEM
        val noExt = stripExtension(raw.trim())
        var cleaned = ILLEGAL.replace(noExt, "_")
        cleaned = CONTROL_OR_BLANK.replace(cleaned, "_")
        cleaned = UNDERLINES.replace(cleaned, "_").trim('_').trim('.')
        if (cleaned.isEmpty()) return FALLBACK_STEM
        return cleaned.take(STEM_MAX)
    }

    /**
     * 拼最终文件名：`tactile_<主名>_<yyyyMMdd_HHmm>.bmp`。
     * [timestamp] 由调用方传入（便于单测钉死格式）；总长超过 [TOTAL_MAX] 时再压主名。
     */
    fun build(sourceDisplayName: String?, timestamp: String): String {
        var stem = sanitizeStem(sourceDisplayName)
        val overhead = PREFIX.length + 1 + timestamp.length + SUFFIX.length
        val stemBudget = (TOTAL_MAX - overhead).coerceAtLeast(1)
        if (stem.length > stemBudget) stem = stem.take(stemBudget)
        return "$PREFIX${stem}_$timestamp$SUFFIX"
    }

    /** 字节数人话化：≥1 MB 用 MB 一位小数，否则 KB 四舍五入取整。 */
    fun formatSize(bytes: Long): String {
        if (bytes >= 1024L * 1024L) {
            val mb = bytes / (1024.0 * 1024.0)
            return String.format(Locale.ROOT, "%.1f MB", mb)
        }
        val kb = Math.round(bytes / 1024.0)
        return "$kb KB"
    }

    /**
     * 挑选播报用名：同分钟连导两张时，系统会把第二份自动改名成 `原名 (1).bmp`，
     * 本地拼的名字与磁盘真实名不一致。[queried] 是从落盘 Uri 回读到的 `DISPLAY_NAME`；
     * 非空且非空白就用回读名，否则退回本地拼的 [local]。回读失败属于播报退让，不改文件本身。
     */
    fun resolveDisplay(local: String, queried: String?): String =
        queried?.takeIf { it.isNotBlank() } ?: local

    /** 去掉最后一个扩展名；点号在首位（隐藏文件）时不切。 */
    private fun stripExtension(name: String): String {
        val dot = name.lastIndexOf('.')
        return if (dot > 0) name.substring(0, dot) else name
    }
}
