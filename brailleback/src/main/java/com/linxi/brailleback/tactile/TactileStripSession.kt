package com.linxi.brailleback.tactile

import android.content.Context
import android.net.Uri
import android.util.Log
import com.linxi.brailleback.BuildConfig

/**
 * 三段式图形会话的组合入口：读图 → 灰度 → 二值化点阵 → 切片 → 按 totalBands 生成
 * 封面 / 封底盲文 → 排版成书。
 *
 * 顺序约束：必须先完成切片拿到 [SliceResult.totalBands]，再用这个 N 生成封面文案，
 * 禁止先写死带数。封面 / 封底各占整屏，排在图形带之前 / 之后，不复用图形行。
 *
 * 线程：本方法会绑翻译服务并做同步翻译，必须在后台线程调用（IO 线程）；
 * 读图、解码、切片失败抛 [IllegalStateException] 并在消息里说明原因，不返回空书。
 * 封面 / 封底翻译失败不抛：降级为空封面 / 封底，图形带照常成书（2026-11-24 修 P2-2）。
 */
object TactileStripSession {

    /**
     * @param g1SplitCover G1 多行封面分层开关，默认 false（回归既有整屏三段式）。开启且
     *   `linesPerScreen >= 2` 时图形带占屏幕第 2 行起、封面只占第 1 行；开关名与默认值
     *   见 docs 73 §G1。
     * @param normalizerMode 线宽归一三态，默认 [StockNormalizerMode.AUTO]。在 dotGrid 之后、
     *   切片之前作用于二值点阵；AUTO 仅在纯线条图且检出断裂迹象时加固，门控不命中逐字节
     *   不变；OFF 完全不跑；FORCE 绕过判定，仅调试用。
     */
    fun build(
        context: Context,
        uri: Uri,
        spec: ImageStripEncoder.Spec,
        rotated: Boolean = false,
        g1SplitCover: Boolean = false,
        normalizerMode: StockNormalizerMode = StockNormalizerMode.AUTO,
    ): StripBook {
        if (BuildConfig.DEBUG) Log.d("TactileStrip", "stage=load session")
        val gray = try {
            TactileStripSource.loadGrayImage(context, uri)
        } catch (e: IllegalStateException) {
            throw e
        } catch (e: Exception) {
            throw IllegalStateException("读图失败：${e.message}", e)
        }

        val cols = spec.dotColumns
        val rows = maxOf(
            1,
            Math.round(cols.toFloat() * gray.height.toFloat() / gray.width).toInt()
        )
        if (BuildConfig.DEBUG) Log.d("TactileStrip", "stage=decode session gray=${gray.width}x${gray.height}")
        val rawInk = ImageStripEncoder.dotGrid(gray, cols, rows, spec.threshold)
        // 归一在切片之前作用于全局点阵；AUTO 门控不命中时原样返回 rawInk。
        if (BuildConfig.DEBUG) Log.d("TactileStrip", "stage=normalize session mode=$normalizerMode")
        val ink = TactileStrokeNormalizer.normalize(
            rawInk, cols, rows,
            TactileStrokeNormalizer.Config(mode = normalizerMode),
        )

        // G1 开启时图形区每页少一行：切片按少一行的版面切，成书时再摆回整屏第 2 行起。
        val g1 = g1Effective(g1SplitCover, spec)
        val graphicSpec = if (g1) spec.copy(linesPerScreen = spec.linesPerScreen - 1) else spec
        if (BuildConfig.DEBUG) Log.d("TactileStrip", "stage=slice session g1=$g1")
        val rawSlice = TactileStripSlicer.slice(ink, cols, rows, graphicSpec)
        // 先剔除全空图形带，封面「共 N 带」按剔除后的带数生成。
        val slice = TactileStripBookBuilder.stripEmpty(rawSlice)

        val bridge = TactileStripCaptionBridge(context)
        return try {
            if (BuildConfig.DEBUG) Log.d("TactileStrip", "stage=cover session bands=${slice.totalBands}")
            val coverMaxLines = if (g1) 1 else spec.linesPerScreen
            // 封面 / 封底只是方位提示，翻译服务不可用时不能连带丢掉图形本身：
            // 失败就降级为「封面 / 封底为空」，图形带照常成书，界面状态另报带号。
            val coverScreens = try {
                wrapIntoScreens(
                    TactileStripCoverText.coverLines(
                        widthCells = spec.cellsPerLine,
                        bandHeightDots = slice.bandRows,
                        totalBands = slice.totalBands,
                        dotsPerCell = spec.dotsPerCell,
                        rotated = rotated,
                        maxLines = coverMaxLines,
                    ),
                    bridge,
                    spec,
                )
            } catch (e: Exception) {
                Log.w("TactileStrip", "封面文字未翻译，降级为空封面：${e.message}", e)
                emptyList()
            }
            val backScreens = try {
                wrapIntoScreens(
                    TactileStripCoverText.backLines(
                        widthCells = spec.cellsPerLine,
                        totalBands = slice.totalBands,
                        dotsPerCell = spec.dotsPerCell,
                        maxLines = spec.linesPerScreen,
                    ),
                    bridge,
                    spec,
                )
            } catch (e: Exception) {
                Log.w("TactileStrip", "封底文字未翻译，降级为空封底：${e.message}", e)
                emptyList()
            }
            if (BuildConfig.DEBUG) Log.d("TactileStrip", "stage=book session")
            TactileStripBookBuilder.build(slice, spec, coverScreens, backScreens, g1 = g1)
        } finally {
            bridge.close()
        }
    }

    /**
     * G1 在该版面是否实际生效：开关打开且设备为多行。纯函数，供开关透传断言复用。
     */
    fun g1Effective(g1SplitCover: Boolean, spec: ImageStripEncoder.Spec): Boolean =
        g1SplitCover && spec.linesPerScreen >= 2

    /**
     * 把界面选择的归一三态解析为归一器配置；纯函数，供开关透传断言复用。
     */
    fun normalizerConfig(mode: StockNormalizerMode): TactileStrokeNormalizer.Config =
        TactileStrokeNormalizer.Config(mode = mode)

    /** 逐行翻译盲文，按 cellsPerLine 折行，再按 linesPerScreen 切成整屏。 */
    private fun wrapIntoScreens(
        lines: List<String>,
        bridge: TactileStripCaptionBridge,
        spec: ImageStripEncoder.Spec,
    ): List<ByteArray> {
        val brailleRows = ArrayList<String>()
        for (line in lines) {
            val braille = bridge.toBraille(line)
            var rest = braille
            while (rest.isNotEmpty()) {
                brailleRows.add(rest.take(spec.cellsPerLine))
                rest = rest.drop(spec.cellsPerLine)
            }
        }
        val screens = ArrayList<ByteArray>()
        var i = 0
        while (i < brailleRows.size) {
            val chunk = brailleRows.subList(i, minOf(i + spec.linesPerScreen, brailleRows.size))
            screens.add(
                TactileStripCaption.page(
                    chunk,
                    spec.cellsPerLine,
                    spec.linesPerScreen,
                    spec.dotsPerCell,
                )
            )
            i += spec.linesPerScreen
        }
        return screens
    }
}
