package com.linxi.brailleback.tactile

/** 书页类型：封面、图形带、封底。 */
enum class StripPageKind { COVER, GRAPHIC, BACK }

/**
 * 书中的一页。封面 / 封底的 bandIndex 与坐标无意义，填 0；图形页对应一条 [Band]。
 *
 * @param bandIndex 图形带序号（1 基）；封面 / 封底为 0。
 * @param totalBands 图形带总数。
 * @param x0 / y0 图形带在全局点阵中的原点格坐标；封面 / 封底为 0。
 */
data class StripPage(
    val kind: StripPageKind,
    val bytes: ByteArray,
    val bandIndex: Int,
    val totalBands: Int,
    val x0: Int,
    val y0: Int,
)

/** 三段式图形会话的成书：封面屏 → 图形带页 → 封底屏。 */
data class StripBook(
    val pages: List<StripPage>,
    val totalBands: Int,
    val cellsPerLine: Int,
    val linesPerScreen: Int,
    val dotsPerCell: Int,
)

/**
 * 把切片结果与排好版的封面 / 封底字节组装成书。
 *
 * 顺序固定：封面（1..k 屏）→ 图形带（1..N，bandIndex 从 1 起）→ 封底（1..k 屏）。
 * 封面 / 封底各占整屏，绝不复用图形行；totalBands 必须在切片完成后才可知，
 * 因此封面文案要在 [SliceResult] 出来之后再生成。
 */
object TactileStripBookBuilder {

    /**
     * 剔除字节全 0 的图形带（整带无墨的空白带不生成页，降低单行设备翻页成本）。
     * 元数据语义不变，只是不生成页；剔除后重算带号与 totalBands。
     * 全白图至少保留 1 个图形带，保证「封面 → 图形 → 封底」三段式结构成立。
     * 空带本身不含墨，剔除不会丢失任何含墨行。
     */
    fun stripEmpty(slice: SliceResult): SliceResult {
        val kept = slice.bands.filter { band -> band.bytes.any { it.toInt() != 0 } }
        val finalBands = if (kept.isEmpty()) listOf(slice.bands.first()) else kept
        val total = finalBands.size
        val reindexed = finalBands.mapIndexed { i, band ->
            band.copy(bandIndex = i + 1, total = total)
        }
        return slice.copy(bands = reindexed, totalBands = total)
    }

    /**
     * @param g1 G1 多行封面分层开关，默认 false。开启且 `linesPerScreen >= 2` 时：封面 /
     *   封底文字屏之外，图形带被压到屏幕第 2 行起（第 1 行留给封面），图形区每页行数 =
     *   `linesPerScreen - 1`；关闭时既有整屏三段式输出逐字节不变。详见 docs 73 §G1。
     */
    fun build(
        slice: SliceResult,
        spec: ImageStripEncoder.Spec,
        cover: List<ByteArray>,
        back: List<ByteArray>,
        g1: Boolean = false,
    ): StripBook {
        val prepared = stripEmpty(slice)
        val splitCover = g1 && spec.linesPerScreen >= 2
        val pages = ArrayList<StripPage>()
        cover.forEach { bytes ->
            val placed = if (splitCover) placeOnFirstLine(bytes, spec) else bytes
            pages.add(StripPage(StripPageKind.COVER, placed, 0, prepared.totalBands, 0, 0))
        }
        prepared.bands.forEach { band ->
            val placed = if (splitCover) placeInLowerLines(band.bytes, spec) else band.bytes
            pages.add(
                StripPage(
                    kind = StripPageKind.GRAPHIC,
                    bytes = placed,
                    bandIndex = band.bandIndex,
                    totalBands = prepared.totalBands,
                    x0 = band.x0,
                    y0 = band.y0,
                )
            )
        }
        back.forEach { bytes ->
            // 封底整屏保留：结束提示屏不向图形让出空间，四项文案可整屏铺开；不分层移位。
            pages.add(StripPage(StripPageKind.BACK, bytes, 0, prepared.totalBands, 0, 0))
        }
        return StripBook(
            pages = pages,
            totalBands = prepared.totalBands,
            cellsPerLine = spec.cellsPerLine,
            linesPerScreen = spec.linesPerScreen,
            dotsPerCell = spec.dotsPerCell,
        )
    }

    /** G1：封面文字只落在屏幕第 1 行，其余行字节清零。 */
    private fun placeOnFirstLine(bytes: ByteArray, spec: ImageStripEncoder.Spec): ByteArray {
        val out = ByteArray(spec.linesPerScreen * spec.cellsPerLine)
        System.arraycopy(bytes, 0, out, 0, minOf(bytes.size, spec.cellsPerLine))
        return out
    }

    /** G1：图形带从屏幕第 2 行起摆放，第 1 行留空；band 字节按少一行的版面打包。 */
    private fun placeInLowerLines(bandBytes: ByteArray, spec: ImageStripEncoder.Spec): ByteArray {
        val out = ByteArray(spec.linesPerScreen * spec.cellsPerLine)
        val offset = spec.cellsPerLine
        System.arraycopy(
            bandBytes, 0, out, offset,
            minOf(bandBytes.size, out.size - offset),
        )
        return out
    }
}
