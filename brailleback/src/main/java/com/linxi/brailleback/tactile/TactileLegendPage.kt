package com.linxi.brailleback.tactile

/**
 * 图形标签的独立图例页：图内疑似标注不擦除、仍作为图形墨迹保留在点阵里，图例屏只把
 * 检测到的候选**位置**汇总成独立一屏，由 UI 把待翻译文本经 [TactileStripCaptionBridge]
 * 翻成 Unicode 盲文后，在这里整屏打包成一方一字节的点位，不占用图形页。
 *
 * 墨迹处置决策（本轮固定）：**保留图形墨迹，不做擦除**。理由：本轮没有 OCR / 字形识别，
 * 无法判断一处小连通域是标注还是图形细节；贸然擦除会丢图。图例页只说明位置，原图
 * 完整留在点阵中。
 *
 * 措辞边界：本轮**不识别标签内容**（无 OCR），图例文案只能报「标注位置」（第几带 /
 * 第几方），不得声称识别出「标注文字」。
 *
 * 纯 Kotlin，不依赖 android.*，字节位序与项目其余部分一致：dot1=bit0 … dot8=bit7，
 * 6 点只用低 6 位。
 */
object TactileLegendPage {

    /** 图例页的 TTS / 文本标记，不写进点位字节。 */
    const val LEGEND_LABEL: String = "图例"

    /** 空检测时的图例文案（未检出任何图内标注）。 */
    const val EMPTY_MESSAGE: String = "未检测到图内标注"

    /** 说明行：本轮只报位置、不识别文字内容。 */
    const val POSITION_ONLY_NOTE: String = "以下为标注位置，未识别文字内容"

    /**
     * 由标签候选框合成待翻译的中文源行（盲文翻译由上层桥完成，本类不碰翻译服务）。
     *
     * 位置口径：带号按纵向视口带高 [bandRows] 折算（1 基），方号按每方 2 点列折算
     * （1 基），方号钳制在 1..[cellsPerLine]。
     *
     * @param boxes [TactileLabelDetector.detect] 返回的候选框（全局点阵坐标）。
     * @param bandRows 一条纵向带 / 一屏细节窗占用的点行数。
     * @param cellsPerLine 一行方数，用于方号钳制。
     * @return 待翻译源行；空检测时返回单条 [EMPTY_MESSAGE]，不崩。
     */
    fun labelSourceLines(
        boxes: List<TactileLabelDetector.LabelBox>,
        bandRows: Int,
        cellsPerLine: Int,
    ): List<String> {
        if (boxes.isEmpty()) return listOf(EMPTY_MESSAGE)
        val lines = ArrayList<String>()
        lines.add("检测到 ${boxes.size} 处图内标注")
        lines.add(POSITION_ONLY_NOTE)
        boxes.forEach { box ->
            val band = box.y / bandRows.coerceAtLeast(1) + 1
            val cell = (box.x / 2).coerceIn(0, cellsPerLine - 1) + 1
            lines.add("第 $band 带 第 $cell 方")
        }
        return lines
    }

    /**
     * 图例共几屏。单行设备一行就是一屏，候选一多必然超屏，所以图例本身也要能翻页。
     *
     * @param lineCount 盲文行数
     * @param linesPerScreen 一屏行数（<1 按 1 处理）
     */
    fun pageCount(lineCount: Int, linesPerScreen: Int): Int {
        val per = linesPerScreen.coerceAtLeast(1)
        return if (lineCount <= 0) 1 else (lineCount + per - 1) / per
    }

    /**
     * 取图例第 [page] 屏（0 基）的行；页码越界时钳制到最后一屏，不抛异常。
     * 一屏装得下全部行时原样返回，不做多余切分。
     */
    fun pageLines(brailleLines: List<String>, page: Int, linesPerScreen: Int): List<String> {
        val per = linesPerScreen.coerceAtLeast(1)
        if (brailleLines.size <= per) return brailleLines
        val lastPage = pageCount(brailleLines.size, per) - 1
        val start = page.coerceIn(0, lastPage) * per
        return brailleLines.drop(start).take(per)
    }

    /**
     * 把若干行 Unicode 盲文（U+2800..U+28FF）排进一屏。
     *
     * @param brailleLines 每行一个盲文字符串；行数不得超过一屏行数。
     * @param spec 点显版面。
     * @return 整屏字节，长度恒为 linesPerScreen × cellsPerLine。
     */
    fun build(brailleLines: List<String>, spec: ImageStripEncoder.Spec): ByteArray =
        TactileStripCaption.page(
            brailleLines,
            cellsPerLine = spec.cellsPerLine,
            linesPerScreen = spec.linesPerScreen,
            dotsPerCell = spec.dotsPerCell,
        )
}
