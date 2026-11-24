/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 *
 * DEBUG-only 触觉页参数注入：把 Intent extra 文本解析成参数状态覆盖值，供批量测试用例
 * 通过 `am start --es …` 驱动，免去在参数页手工点击。纯函数、无 Android 依赖，可 JVM 单测。
 *
 * 约定：
 * - 所有 null 字段表示该 extra 缺失或非法，Activity 回落既有默认值，不崩；
 * - 取值既兼容 `--es` 字符串，也兼容 `--ei` 整型（Activity 侧先归一为文本再传入）；
 * - 本对象只做解析，不碰 rebuild / 切片 / 分页 / 视口 / 会话的任何判断逻辑。
 */

package com.linxi.brailleback.tactile

/**
 * 解析结果；字段非 null 表示该档位被 extra 合法覆盖。
 *
 * @param cellsPerLine 一行方数；debug 额外允许 4（复现 4×3 档），常规档位 20 / 30 / 40。
 * @param linesPerScreen 屏内行数，取值 1–8。
 * @param dots 每格点数，仅 6 或 8。
 * @param normalizer 线宽归一三态，大小写不敏感。
 * @param g1SplitCover G1 多行封面分层，接受 true/false（也认 1/0、on/off、yes/no）。
 */
data class TactileDebugParams(
    val cellsPerLine: Int? = null,
    val linesPerScreen: Int? = null,
    val dots: Int? = null,
    val normalizer: StockNormalizerMode? = null,
    val g1SplitCover: Boolean? = null,
) {

    /** 是否有任意档位被合法覆盖；全空时 Activity 不做任何赋值。 */
    val anySet: Boolean
        get() = cellsPerLine != null || linesPerScreen != null || dots != null ||
            normalizer != null || g1SplitCover != null

    companion object {

        /** debug 可接受的一行方数（4 仅供复现窄档，UI 单选行不展示该值）。 */
        val CELL_CHOICES_DEBUG: Set<Int> = setOf(4, 20, 30, 40)

        const val MIN_LINES = 1
        const val MAX_LINES = 8

        /**
         * 从「extra 键 → 文本值」映射解析；键缺失或值非法时对应字段为 null。
         * 一个合法档位都没有时返回 null。
         */
        fun parse(extras: Map<String, String?>): TactileDebugParams? {
            val params = TactileDebugParams(
                cellsPerLine = parseCells(extras["cells_per_line"]),
                linesPerScreen = parseLines(extras["lines_per_screen"]),
                dots = parseDots(extras["dots"]),
                normalizer = parseNormalizer(extras["normalizer"]),
                g1SplitCover = parseBool(extras["g1_split_cover"]),
            )
            return if (params.anySet) params else null
        }

        internal fun parseInt(raw: String?): Int? {
            val text = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            return text.toIntOrNull()
        }

        internal fun parseCells(raw: String?): Int? =
            parseInt(raw)?.takeIf { it in CELL_CHOICES_DEBUG }

        internal fun parseLines(raw: String?): Int? =
            parseInt(raw)?.takeIf { it in MIN_LINES..MAX_LINES }

        internal fun parseDots(raw: String?): Int? =
            parseInt(raw)?.takeIf { it == 6 || it == 8 }

        internal fun parseNormalizer(raw: String?): StockNormalizerMode? =
            when (raw?.trim()?.uppercase()) {
                "OFF" -> StockNormalizerMode.OFF
                "AUTO" -> StockNormalizerMode.AUTO
                "FORCE" -> StockNormalizerMode.FORCE
                else -> null
            }

        internal fun parseBool(raw: String?): Boolean? = when (raw?.trim()?.lowercase()) {
            "true", "1", "on", "yes" -> true
            "false", "0", "off", "no" -> false
            else -> null
        }
    }
}
