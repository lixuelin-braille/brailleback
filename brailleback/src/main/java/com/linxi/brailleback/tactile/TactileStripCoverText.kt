package com.linxi.brailleback.tactile

/**
 * 封面 / 封底的中文文案。本类只产出中文行，盲文翻译交给
 * [TactileStripCaptionBridge] 走项目内 liblouis 链路，这里不自己实现盲文表。
 *
 * 排版策略：多行设备（`maxLines >= 2`）优先把封面六项 / 封底四项合并进
 * `maxLines` 行，使封面、封底各占一整屏；合并后的某行按翻译前字数估计若超出
 * `widthCells`，则回退为一字段一行，由会话层折成多屏，**不截断任何字段**。
 * 单行设备（`maxLines == 1`）物理上装不下全部字段，保持多屏。
 */
object TactileStripCoverText {

    /** 字段间分隔。 */
    private const val SEP = " "

    /**
     * 封面文案。
     *
     * @param widthCells 整图横向方数（cellsPerLine），用于合并后宽度估计。
     * @param bandHeightDots 每带点行数（8 点 = 行数×4，6 点 = 行数×3）。
     * @param totalBands 切片（剔除空带后）的带总数。
     * @param dotsPerCell 6 或 8。
     * @param rotated 原图是否已旋转 90°。
     * @param maxLines 一屏行数；≥2 时尝试合并成整屏，1 时保持多屏。
     */
    fun coverLines(
        widthCells: Int,
        bandHeightDots: Int,
        totalBands: Int,
        dotsPerCell: Int,
        rotated: Boolean,
        maxLines: Int = Int.MAX_VALUE,
    ): List<String> {
        val direction = if (rotated) "方向：已旋转 90°" else "方向：原图上方在前"
        val fields = listOf(
            "图形开始",
            "宽 ${widthCells} 方",
            "高 ${totalBands} 带（${dotsPerCell}点每带${bandHeightDots}点）",
            direction,
            "共 ${totalBands} 带",
            "按下一页开始",
        )
        val templates = when {
            maxLines >= 6 -> listOf(listOf(0), listOf(1), listOf(2), listOf(3), listOf(4), listOf(5))
            maxLines == 5 -> listOf(listOf(0), listOf(1), listOf(2), listOf(3, 4), listOf(5))
            maxLines == 4 -> listOf(listOf(0, 1), listOf(2), listOf(3, 4), listOf(5))
            maxLines == 3 -> listOf(listOf(0, 1), listOf(2), listOf(3, 4, 5))
            maxLines == 2 -> listOf(listOf(0, 1, 2), listOf(3, 4, 5))
            else -> null
        }
        return pack(fields, templates, widthCells)
    }

    /** 封底文案。 */
    fun backLines(
        widthCells: Int,
        totalBands: Int,
        dotsPerCell: Int,
        maxLines: Int = Int.MAX_VALUE,
    ): List<String> {
        val fields = listOf(
            "图形结束",
            "共 ${totalBands} 带",
            "宽 ${widthCells} 方",
            "返回或换图",
        )
        val templates = when {
            maxLines >= 4 -> listOf(listOf(0), listOf(1), listOf(2), listOf(3))
            maxLines == 3 -> listOf(listOf(0), listOf(1, 2), listOf(3))
            maxLines == 2 -> listOf(listOf(0, 1), listOf(2, 3))
            else -> null
        }
        return pack(fields, templates, widthCells)
    }

    /**
     * 按模板合并字段；模板任一行估计超宽则回退一字段一行（多屏，不截断）。
     * 字数估计按 1 字符 ≈ 1 方（中文二级缩约后实际可能更少），数字 / 标点同估 1 方。
     */
    private fun pack(
        fields: List<String>,
        templates: List<List<Int>>?,
        widthCells: Int,
    ): List<String> {
        if (templates == null) return fields
        val rows = templates.map { idx -> idx.joinToString(SEP) { fields[it] } }
        val fits = rows.all { line -> estimateCells(line) <= widthCells }
        return if (fits) rows else fields
    }

    private fun estimateCells(text: String): Int = text.length
}
