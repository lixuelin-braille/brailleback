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

/**
 * 折行方式。
 *
 * - [WORD]（默认）：只在空方处断行，行尾保留该空方；整行没有空方才按方数硬切。
 *   中文经分词后每两个词之间有一个空方，因此不会把词或音节劈开。
 * - [CELL]：改造前的纯方数硬切，每满列数一刀；保留它只为与旧基线逐字节对照。
 */
enum class BrailleWrapMode {
    WORD,
    CELL,
}

/**
 * 折行内核：把一段不含换行的盲文串切成不超过 [columns] 方的行。
 *
 * 纯逻辑、无依赖，BRF（空方已回写成空格）与 BRL（空方仍是 U+2800）共用，
 * 只把「哪个字符算空方」通过 [blank] 传进来。
 */
internal object BrailleLineWrap {

    /** 兼容旧行为的空格判定不在这里，调用方给 [blank]。 */
    fun wrap(raw: String, columns: Int, mode: BrailleWrapMode, blank: Char): List<String> {
        require(columns > 0) { "columns must be > 0" }
        val out = ArrayList<String>()
        var start = 0
        while (start < raw.length) {
            val limit = minOf(start + columns, raw.length)
            if (limit >= raw.length) {
                out.add(raw.substring(start))
                break
            }
            var cut = limit
            if (mode == BrailleWrapMode.WORD) {
                // 行首连续的空方（缩进）不算断点，否则会切出一行几乎空白的行。
                var firstReal = start
                while (firstReal < limit && raw[firstReal] == blank) firstReal++
                // 从窗口末端往回找最近的空方；找不到（整窗都是实方）就硬切。
                var blankAt = limit - 1
                while (blankAt > firstReal && raw[blankAt] != blank) blankAt--
                if (blankAt > firstReal && raw[blankAt] == blank) {
                    // 断点后连着几个空方就一起收进本行，下一行从实字符开始；
                    // 但不越过窗口右边界，保证每行不超过 columns 方。
                    cut = blankAt + 1
                    while (cut < limit && raw[cut] == blank) cut++
                }
            }
            out.add(raw.substring(start, cut))
            start = cut
        }
        return out
    }
}

/** 导出结果：编码后的文本与三类计数。 */
data class BrailleExportResult(
    val content: String,
    val unmappableCells: Int,
    val droppedChars: Int,
    val eightDotCells: Int = 0,
)

object BrailleExporter {

    private const val FORM_FEED = '\u000C'
    private const val CRLF = "\r\n"
    private const val BLANK = '\u2800'

    /**
     * 把阅读流 [stream] 按 [format] 编码，版面取 [geometry]。
     *
     * 折行方式 [wrapMode] 默认按词断：逐行在空方处断，行尾留一个空方；
     * 整行找不到空方（超长词、长英文数字串）才按方数硬切。
     * 传 [BrailleWrapMode.CELL] 可回到改造前的纯方数硬切，用于字节基线对照。
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
        cellSources: List<CellSource>? = null,
        wrapMode: BrailleWrapMode = BrailleWrapMode.WORD,
    ): BrailleExportResult {
        return when (format) {
            BrailleExportFormat.BRF -> exportBrf(stream, geometry, wrapMode)
            BrailleExportFormat.BRL -> exportBrl(stream, geometry, wrapMode)
            BrailleExportFormat.UNICODE_TXT -> exportUnicodeTxt(stream)
            BrailleExportFormat.JSON -> exportJson(stream, geometry, title, cellSources)
        }
    }

    /** 点位 JSON：逐格 dots/mask/line/column/role/source_span/source_text + 文档元信息。 */
    private fun exportJson(
        stream: String,
        geometry: PageGeometry,
        title: String,
        cellSources: List<CellSource>?,
    ): BrailleExportResult {
        var dropped = 0
        var eightDot = 0
        for (c in stream) {
            if (c == '\n' || c == '\r') continue
            if (c.code in 0x2800..0x28FF) {
                if (c.code - 0x2800 >= 64) eightDot++
            } else {
                dropped++
            }
        }
        val document = ExportDocument.build(stream, title, geometry, cellSources)
        return BrailleExportResult(document.toJson(), 0, dropped, eightDot)
    }

    /** BRF：Unicode 盲文回写 NABCC ASCII，按几何折行、CRLF、每 [linesPerPage] 行一个 FF。 */
    private fun exportBrf(
        stream: String,
        geometry: PageGeometry,
        wrapMode: BrailleWrapMode,
    ): BrailleExportResult {
        val lines = ArrayList<String>()
        var unmappable = 0
        var dropped = 0
        var eightDot = 0
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
                    if (dots >= 64) eightDot++
                    val mask6 = dots and 0x3F
                    if (c == BLANK || mask6 == 0) {
                        line.append(' ')
                    } else {
                        val ascii = BrailleFileDecoder.dotsToAscii(mask6)
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
            // NABCC 侧空方已经回写成 ASCII 空格，按词断就以空格为断点。
            wrapped += BrailleLineWrap.wrap(raw, geometry.columns, wrapMode, ' ')
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
        return BrailleExportResult(sb.toString(), unmappable, dropped, eightDot)
    }

    /** BRL：保留 Unicode 盲文不退化 ASCII，按几何折行、CRLF，不插 FF。 */
    private fun exportBrl(
        stream: String,
        geometry: PageGeometry,
        wrapMode: BrailleWrapMode,
    ): BrailleExportResult {
        val lines = ArrayList<String>()
        var dropped = 0
        var eightDot = 0
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
                    if (c.code - 0x2800 >= 64) eightDot++
                    line.append(c)
                }
                else -> dropped++
            }
        }
        flushLine()

        val wrapped = ArrayList<String>()
        for (raw in lines) {
            // BRL 保留原字符，空方仍是 U+2800，按词断就以它作断点。
            wrapped += BrailleLineWrap.wrap(raw, geometry.columns, wrapMode, BLANK)
        }
        return BrailleExportResult(wrapped.joinToString(CRLF), 0, dropped, eightDot)
    }

    /** Unicode 文本：不折行，保留原有段落换行；明眼字符丢弃、八点计数。 */
    private fun exportUnicodeTxt(stream: String): BrailleExportResult {
        var dropped = 0
        var eightDot = 0
        val sb = StringBuilder()
        for (c in stream) {
            when {
                c == '\n' || c == '\r' -> sb.append(c)
                c.code in 0x2800..0x28FF -> {
                    if (c.code - 0x2800 >= 64) eightDot++
                    sb.append(c)
                }
                else -> dropped++
            }
        }
        return BrailleExportResult(sb.toString(), 0, dropped, eightDot)
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
