/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 *
 * 「导出为盲文」纯 JVM 编码层：折行、分页、点位回写、文件名清洗与两类计数
 * 全部在这里，不依赖 Android，便于单测直接打。Activity 侧只留薄封装：
 * 弹格式菜单、起 SAF 文档、后台线程编码写盘、主线程弹窗/播报。
 *
 * 导出源就是阅读器的全书 Unicode 盲文阅读流（mStream），不重跑翻译、不做分词。
 */

package com.linxi.brailleback

/**
 * 盲文导出格式。
 *
 * - [BRF]：BRF ASCII 盲文，40 方折行、CRLF 换行、每满 25 行插一个换页符 `\u000C`，
 *   写盘按 US-ASCII 编码。
 * - [BRL]：保持 U+2800–U+28FF 不退化 ASCII，40 方折行、CRLF，不插换页符。
 * - [UNICODE_TXT]：Unicode 盲文纯文本，不折行，保留原有段落换行。
 */
enum class BrailleExportFormat(val extension: String, val mime: String) {
    BRF("brf", "application/octet-stream"),
    BRL("brl", "text/plain"),
    UNICODE_TXT("txt", "text/plain"),
}

/** 导出结果：编码后的文本与两类计数。 */
data class BrailleExportResult(
    val content: String,
    val unmappableCells: Int,
    val droppedChars: Int,
)

object BrailleExporter {

    /** 每行方数（BRF / BRL 折行宽度）。 */
    const val CELLS_PER_LINE = 40

    /** 每满多少行插一个换页符（仅 BRF）。 */
    private const val LINES_PER_PAGE = 25

    private const val FORM_FEED = '\u000C'
    private const val CRLF = "\r\n"
    private const val BLANK = '\u2800'

    /**
     * 把阅读流 [stream] 按 [format] 编码。
     *
     * 计数口径：
     * - unmappableCells：八点（点位 ≥64）或 NABCC 表外的盲方；BRF 回写成 `?`，
     *   BRL / 文本格式保留原字符但仍计数。
     * - droppedChars：混入的明眼字符（非盲文、非结构符），导出时丢弃并计数。
     */
    fun export(stream: String, format: BrailleExportFormat): BrailleExportResult {
        return when (format) {
            BrailleExportFormat.BRF -> exportBrf(stream)
            BrailleExportFormat.BRL -> exportBrl(stream)
            BrailleExportFormat.UNICODE_TXT -> exportUnicodeTxt(stream)
        }
    }

    /** BRF：Unicode 盲文回写 NABCC ASCII，40 方折行、CRLF、每 25 行一个 FF。 */
    private fun exportBrf(stream: String): BrailleExportResult {
        val lines = ArrayList<String>()
        var unmappable = 0
        var dropped = 0
        val line = StringBuilder()

        fun flushLine() {
            lines.add(line.toString())
            line.clear()
        }

        for (c in stream) {
            when {
                c == '\n' -> flushLine()
                c == '\r' -> Unit
                c.code in 0x2800..0x28FF -> {
                    val dots = c.code - 0x2800
                    if (c == BLANK) {
                        line.append(' ')
                    } else {
                        val ascii = BrailleFileDecoder.dotsToAscii(dots)
                        if (ascii == null) {
                            unmappable++
                            line.append('?')
                        } else {
                            line.append(ascii)
                        }
                    }
                }
                else -> dropped++
            }
        }
        flushLine()

        val wrapped = ArrayList<String>()
        for (raw in lines) {
            var i = 0
            while (i < raw.length) {
                wrapped.add(raw.substring(i, minOf(i + CELLS_PER_LINE, raw.length)))
                i += CELLS_PER_LINE
            }
        }

        val sb = StringBuilder()
        wrapped.forEachIndexed { idx, l ->
            if (idx > 0) sb.append(CRLF)
            sb.append(l)
            // 末页不挂尾 FF；满 25 行且后面还有内容才插换页符。
            if ((idx + 1) % LINES_PER_PAGE == 0 && idx != wrapped.lastIndex) {
                sb.append(FORM_FEED)
            }
        }
        return BrailleExportResult(sb.toString(), unmappable, dropped)
    }

    /** BRL：保留 Unicode 盲文不退化 ASCII，40 方折行、CRLF，不插 FF。 */
    private fun exportBrl(stream: String): BrailleExportResult {
        val lines = ArrayList<String>()
        var unmappable = 0
        var dropped = 0
        val line = StringBuilder()

        fun flushLine() {
            lines.add(line.toString())
            line.clear()
        }

        for (c in stream) {
            when {
                c == '\n' -> flushLine()
                c == '\r' -> Unit
                c.code in 0x2800..0x28FF -> {
                    val dots = c.code - 0x2800
                    if (dots >= 64) unmappable++
                    line.append(c)
                }
                else -> dropped++
            }
        }
        flushLine()

        val wrapped = ArrayList<String>()
        for (raw in lines) {
            var i = 0
            while (i < raw.length) {
                wrapped.add(raw.substring(i, minOf(i + CELLS_PER_LINE, raw.length)))
                i += CELLS_PER_LINE
            }
        }
        return BrailleExportResult(wrapped.joinToString(CRLF), unmappable, dropped)
    }

    /** Unicode 文本：不折行，保留原有段落换行；明眼字符丢弃、八点计数。 */
    private fun exportUnicodeTxt(stream: String): BrailleExportResult {
        var unmappable = 0
        var dropped = 0
        val sb = StringBuilder()
        for (c in stream) {
            when {
                c == '\n' || c == '\r' -> sb.append(c)
                c.code in 0x2800..0x28FF -> {
                    if (c.code - 0x2800 >= 64) unmappable++
                    sb.append(c)
                }
                else -> dropped++
            }
        }
        return BrailleExportResult(sb.toString(), unmappable, dropped)
    }

    /**
     * 清洗导出文件名：去扩展名、非法字符替换为 `_`、去首尾空白；空结果兜底。
     * 不做截断（长度上限由 SAF 文件系统约束），只保证可作为文件名。
     */
    fun sanitizeFileName(name: String): String {
        var base = name.trim()
        val dot = base.lastIndexOf('.')
        if (dot > 0) base = base.substring(0, dot)
        val illegal = Regex("""[\\/:*?"<>|\p{Cntrl}]""")
        val cleaned = illegal.replace(base, "_").trim().trim('_')
        return cleaned.ifEmpty { "braille_export" }
    }
}
