/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 *
 * 「导出为盲文」纯 JVM 编码层：折行、分页、点位回写、文件名清洗与两类计数
 * 全部在这里，不依赖 Android，便于单测直接打。Activity 侧只留薄封装：
 * 弹格式/版面菜单、起 SAF 文档、后台线程编码写盘、主线程弹窗/播报。
 *
 * 导出源就是阅读器的全书 Unicode 盲文阅读流（mStream），不重跑翻译、不做分词。
 */

package com.linxi.brailleback

/**
 * 导出版面几何：每行方数、每页数行数，可选页边距。
 *
 * 默认 [Default] 40 方 / 25 行，与参数化之前的逐字节输出一致；
 * BRF 换页位置与 BRL 折行宽度都从这里取，导出层不再写死 40/25。
 */
data class PageGeometry(
    val columns: Int,
    val linesPerPage: Int,
    val margin: Int? = null,
) {
    init {
        require(columns > 0) { "columns must be > 0" }
        require(linesPerPage > 0) { "linesPerPage must be > 0" }
    }

    companion object {
        /** 默认版面：40 方 × 25 行（改造前基线口径）。 */
        val Default = PageGeometry(40, 25)

        /** 可选每行方数预设。 */
        val PRESET_COLUMNS = listOf(20, 30, 32, 40)

        /** 可选每页数行预设。 */
        val PRESET_LINES = listOf(20, 25, 28)
    }
}

/** 盲文导出格式。 */
enum class BrailleExportFormat(val extension: String, val mime: String) {
    BRF("brf", "application/octet-stream"),
    BRL("brl", "text/plain"),
    UNICODE_TXT("txt", "text/plain"),
    JSON("json", "application/json"),
}

/** 导出结果：编码后的文本与两类计数。 */
data class BrailleExportResult(
    val content: String,
    val unmappableCells: Int,
    val droppedChars: Int,
)

object BrailleExporter {

    private const val FORM_FEED = '\u000C'
    private const val CRLF = "\r\n"
    private const val BLANK = '\u2800'

    /**
     * 把阅读流 [stream] 按 [format] 编码，版面取 [geometry]。
     *
     * 计数口径：
     * - unmappableCells：八点（点位 ≥64）或 NABCC 表外的盲方；BRF 回写成 `?`，
     *   BRL / 文本格式保留原字符但仍计数。
     * - droppedChars：混入的明眼字符（非盲文、非结构符），导出时丢弃并计数。
     */
    fun export(
        stream: String,
        format: BrailleExportFormat,
        geometry: PageGeometry = PageGeometry.Default,
        title: String = "book",
    ): BrailleExportResult {
        return when (format) {
            BrailleExportFormat.BRF -> exportBrf(stream, geometry)
            BrailleExportFormat.BRL -> exportBrl(stream, geometry)
            BrailleExportFormat.UNICODE_TXT -> exportUnicodeTxt(stream)
            BrailleExportFormat.JSON -> exportJson(stream, geometry, title)
        }
    }

    /** 点位 JSON：逐格 dots/mask/line/column/role/source_span/source_text + 文档元信息。 */
    private fun exportJson(
        stream: String,
        geometry: PageGeometry,
        title: String,
    ): BrailleExportResult {
        var dropped = 0
        for (c in stream) {
            if (c == '\n' || c == '\r') continue
            if (c.code !in 0x2800..0x28FF) dropped++
        }
        val document = ExportDocument.build(stream, title, geometry)
        return BrailleExportResult(document.toJson(), 0, dropped)
    }

    /** BRF：Unicode 盲文回写 NABCC ASCII，按几何折行、CRLF、每 [linesPerPage] 行一个 FF。 */
    private fun exportBrf(stream: String, geometry: PageGeometry): BrailleExportResult {
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
                wrapped.add(raw.substring(i, minOf(i + geometry.columns, raw.length)))
                i += geometry.columns
            }
        }

        val sb = StringBuilder()
        wrapped.forEachIndexed { idx, l ->
            if (idx > 0) sb.append(CRLF)
            sb.append(l)
            // 末页不挂尾 FF；满 linesPerPage 行且后面还有内容才插换页符。
            if ((idx + 1) % geometry.linesPerPage == 0 && idx != wrapped.lastIndex) {
                sb.append(FORM_FEED)
            }
        }
        return BrailleExportResult(sb.toString(), unmappable, dropped)
    }

    /** BRL：保留 Unicode 盲文不退化 ASCII，按几何折行、CRLF，不插 FF。 */
    private fun exportBrl(stream: String, geometry: PageGeometry): BrailleExportResult {
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
                wrapped.add(raw.substring(i, minOf(i + geometry.columns, raw.length)))
                i += geometry.columns
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
     * 预估导出版面总页数：按几何折行后统计物理行数，再按 [PageGeometry.linesPerPage]
     * 切页。供导出前预览播报「每行 N 方、每页 M 行、共 K 页」，不写盘、不改流。
     */
    fun pageCount(stream: String, geometry: PageGeometry): Int {
        var lines = 0
        for (raw in stream.split('\n')) {
            val len = raw.trimEnd('\r').length
            lines += if (len == 0) 1 else (len + geometry.columns - 1) / geometry.columns
        }
        if (lines == 0) lines = 1
        return (lines + geometry.linesPerPage - 1) / geometry.linesPerPage
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
