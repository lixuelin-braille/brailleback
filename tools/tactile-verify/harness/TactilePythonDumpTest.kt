package com.linxi.brailleback.tactile

import org.junit.Test
import java.io.File

/**
 * 临时用例（仅用于 Python 交叉验证，验证完删除，不进仓库）：
 * 把纯 JVM 内核的真实输出落盘，供 Python 侧独立实现逐字节比对。
 */
class TactilePythonDumpTest {

    private val outDir: File =
        File(System.getProperty("java.io.tmpdir"), "tactile-dump").apply { mkdirs() }

    private fun synthetic(w: Int, h: Int): GrayImage {
        val luma = IntArray(w * h)
        val r = minOf(w, h) / 5
        for (y in 0 until h) {
            for (x in 0 until w) {
                var v = if (w > 1) x * 255 / (w - 1) else 0
                val dx = x - w / 2
                val dy = y - h / 2
                if (dx * dx + dy * dy < r * r) v = 40
                val diag = kotlin.math.abs(y.toLong() * w - x.toLong() * h)
                if (diag < (w / 20).toLong()) v = 0
                luma[y * w + x] = v
            }
        }
        return GrayImage(w, h, luma)
    }

    private fun dump(name: String, img: GrayImage, s: TactileSettings, p: TactileProfile) {
        val raster = TactileRasterizer.rasterize(img, s, p)
        File(outDir, "$name.bmp").writeBytes(TactileBmpEncoder.encode(raster))
        File(outDir, "$name.src").writeBytes(ByteArray(img.width * img.height) { img.luma[it].toByte() })
        File(outDir, "$name.meta").writeText(
            buildString {
                appendLine("srcW=${img.width}")
                appendLine("srcH=${img.height}")
                appendLine("mode=${s.mode}")
                appendLine("threshold=${s.threshold}")
                appendLine("invert=${s.invert}")
                appendLine("widthMm=${s.widthMm}")
                appendLine("heightMm=${s.heightMm}")
                appendLine("dpi=${p.dpi}")
                appendLine("pageWmm=${p.pageWidthMm}")
                appendLine("pageHmm=${p.pageHeightMm}")
                appendLine("outW=${raster.width}")
                appendLine("outH=${raster.height}")
            }
        )
    }

    @Test
    fun dumpAll() {
        val g = synthetic(640, 480)
        val a4 = TactileProfile.A4
        dump("c1_thr_a4", g, TactileSettings(TactileMode.THRESHOLD, 128, false, 210.0, 297.0), a4)
        dump("c2_gray_a4", g, TactileSettings(TactileMode.GRAYSCALE, 128, false, 210.0, 297.0), a4)
        dump("c3_edge_a4", g, TactileSettings(TactileMode.EDGE, 128, false, 210.0, 297.0), a4)
        dump("c4_thr200_invert", g, TactileSettings(TactileMode.THRESHOLD, 200, true, 210.0, 297.0), a4)
        dump("c5_odd_50x40mm", synthetic(37, 23), TactileSettings(TactileMode.THRESHOLD, 128, false, 50.0, 40.0), a4)
        dump("c6_auto_160", g, TactileSettings(TactileMode.GRAYSCALE, 128, false, 0.0, 0.0), a4)
        dump("c7_thr0", g, TactileSettings(TactileMode.THRESHOLD, 0, false, 210.0, 297.0), a4)
        dump("c8_thr255", g, TactileSettings(TactileMode.THRESHOLD, 255, false, 210.0, 297.0), a4)
        dump("c9_edge200", g, TactileSettings(TactileMode.EDGE, 200, false, 210.0, 297.0), a4)
        println("DUMP_DIR=" + outDir.absolutePath)
        println("DUMP_FILES=" + outDir.listFiles()?.size)
    }
}
