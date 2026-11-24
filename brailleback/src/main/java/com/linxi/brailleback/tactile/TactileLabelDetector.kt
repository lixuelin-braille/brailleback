package com.linxi.brailleback.tactile

/**
 * 为什么需要它：
 * 示意图里的数字 / 字母标注与图形线条混在同一张点阵上，按图形点显会把小字符也刻成
 * 碎点，既摸不清字形又占带。理想做法是把标签区识别出来、改走文字盲文通道（Brailix
 * 就是靠 SVG `<text>` 元素身份在翻译期单独成盲文点，见 `_labels.py` 的 `LabelStamper`）。
 * 我们手里没有元素身份，只能在点阵上用连通域做**启发式**猜测：面积小、外接框小的
 * 连通域，候选为标签区；长线条 / 大轮廓不判为标签。
 *
 * 定位：纯 Kotlin 连通域分析，只输出候选框，不删墨、不改图形带；是否改走文字屏由上层
 * 后续决策。[Config.enabled] 默认 false，不影响默认链路。
 *
 * 已知限制（原型）：一个字形常由多个互不相连的笔画部件组成（如“A”的横杠与斜腿可能
 * 断开），本类按单连通域出框，暂不做邻近部件归并；误判 / 漏判都属预期，留待后续接
 * 字形模板或 OCR 通道时再收窄。
 */
object TactileLabelDetector {

    /** 识别出的候选标签区（全局点阵坐标）。 */
    data class LabelBox(
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int,
        val area: Int,
    )

    /**
     * @param enabled 总开关。false 时 [detect] 返回空列表。
     * @param maxWidth 候选框最大点列宽，超过则视为线条 / 轮廓。
     * @param maxHeight 候选框最大点行高，超过则视为线条 / 轮廓。
     * @param maxArea 候选连通域最大墨点数，超过则视为图形主体。
     */
    data class Config(
        val enabled: Boolean = false,
        val maxWidth: Int = 6,
        val maxHeight: Int = 5,
        val maxArea: Int = 20,
    ) {
        init {
            require(maxWidth > 0 && maxHeight > 0 && maxArea > 0) {
                "标签框尺寸 / 面积上限必须为正数：$maxWidth × $maxHeight, area=$maxArea"
            }
        }

        companion object {
            val OFF = Config()
        }
    }

    /** 扫描连通域，返回符合小标签启发式的候选框（按 y、x 排序）。 */
    fun detect(
        ink: BooleanArray,
        cols: Int,
        rows: Int,
        config: Config = Config.OFF,
    ): List<LabelBox> {
        require(cols > 0 && rows > 0) { "点阵宽高必须为正数，实际为 ${cols}×$rows" }
        require(ink.size == cols * rows) {
            "点阵长度 ${ink.size} 不等于 ${cols}×$rows"
        }
        if (!config.enabled) return emptyList()

        val visited = BooleanArray(cols * rows)
        val boxes = ArrayList<LabelBox>()

        for (y in 0 until rows) {
            for (x in 0 until cols) {
                val start = y * cols + x
                if (!ink[start] || visited[start]) continue
                val box = floodComponent(ink, visited, cols, rows, x, y) ?: continue
                if (box.width <= config.maxWidth &&
                    box.height <= config.maxHeight &&
                    box.area <= config.maxArea
                ) {
                    boxes.add(box)
                }
            }
        }
        boxes.sortWith(compareBy({ it.y }, { it.x }))
        return boxes
    }

    /** 4 邻域洪泛一个连通域，返回外接框与面积。 */
    private fun floodComponent(
        ink: BooleanArray,
        visited: BooleanArray,
        cols: Int,
        rows: Int,
        sx: Int,
        sy: Int,
    ): LabelBox? {
        val stack = ArrayDeque<Int>()
        stack.addLast(sy * cols + sx)
        visited[sy * cols + sx] = true
        var minX = sx
        var maxX = sx
        var minY = sy
        var maxY = sy
        var area = 0

        while (stack.isNotEmpty()) {
            val idx = stack.removeLast()
            area++
            val px = idx % cols
            val py = idx / cols
            if (px < minX) minX = px
            if (px > maxX) maxX = px
            if (py < minY) minY = py
            if (py > maxY) maxY = py

            val neighbors = intArrayOf(
                px - 1 + py * cols, px + 1 + py * cols,
                px + (py - 1) * cols, px + (py + 1) * cols,
            )
            val coords = arrayOf(
                px - 1 to py, px + 1 to py, px to py - 1, px to py + 1,
            )
            neighbors.forEachIndexed { i, nIdx ->
                val (nx, ny) = coords[i]
                if (nx < 0 || nx >= cols || ny < 0 || ny >= rows) return@forEachIndexed
                if (!visited[nIdx] && ink[nIdx]) {
                    visited[nIdx] = true
                    stack.addLast(nIdx)
                }
            }
        }
        return LabelBox(minX, minY, maxX - minX + 1, maxY - minY + 1, area)
    }
}
