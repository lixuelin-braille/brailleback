/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 *
 * 点位 JSON 导出的纯 JVM 模型：与 Brailix renderer/cells.py 的单元格字段对齐
 * （dots / role / source_span / source_text），本模块额外带 mask 整数、1 起的
 * line / column，以及文档级 schemaVersion / 书名 / 几何 / 总页数。
 *
 * 不依赖 Android：JSON 手写序列化，附带一个最小递归下降解析器供往返校验，
 * 不引入 org.json / kotlinx.serialization 等外部依赖。
 */

package com.linxi.brailleback

/**
 * 一个盲文格。
 *
 * @param dots 点号列表（1..6，升序）；空方为空列表
 * @param mask 六点位掩码整数（dot N → 1 << (N-1)，0..63）
 * @param line 1 起的物理行号（按几何 columns 折行后计）
 * @param column 1 起的行内方位列号
 * @param role 语义角色：空方 [ROLE_SPACE]，其余 [ROLE_INK]
 * @param sourceSpan 源区间 [start, end]；暂无映射时为 null（F4 逐格回填）
 * @param sourceText 对应源文字；暂无映射时为 null
 */
data class ExportCell(
    val dots: List<Int>,
    val mask: Int,
    val line: Int,
    val column: Int,
    val role: String,
    val sourceSpan: List<Int>?,
    val sourceText: String?,
)

/** 点位 JSON 文档。 */
data class ExportDocument(
    val schemaVersion: Int,
    val title: String,
    val geometry: PageGeometry,
    val pageCount: Int,
    val cells: List<ExportCell>,
) {

    fun toJson(): String = ExportJson.writeDocument(this)

    companion object {
        const val SCHEMA_VERSION = 1
        const val ROLE_SPACE = "space"
        const val ROLE_INK = "ink"

        /** 从阅读流按几何排版构建文档；不改流、不碰偏移。 */
        fun build(stream: String, title: String, geometry: PageGeometry): ExportDocument {
            val cells = ArrayList<ExportCell>()
            var line = 1
            var column = 1
            for (rawLine in stream.split('\n')) {
                val lineText = rawLine.trimEnd('\r')
                for (ch in lineText) {
                    if (ch.code !in 0x2800..0x28FF) continue
                    val mask6 = sixBitMask(ch)
                    val dots = (1..6).filter { mask6 and (1 shl (it - 1)) != 0 }
                    val role = if (mask6 == 0) ROLE_SPACE else ROLE_INK
                    cells.add(
                        ExportCell(dots, mask6, line, column, role, null, null)
                    )
                    column++
                    if (column > geometry.columns) {
                        column = 1
                        line++
                    }
                }
                if (column > 1) {
                    column = 1
                    line++
                }
            }
            return ExportDocument(
                schemaVersion = SCHEMA_VERSION,
                title = title,
                geometry = geometry,
                pageCount = BrailleExporter.pageCount(stream, geometry),
                cells = cells,
            )
        }

        fun fromJson(json: String): ExportDocument = ExportJson.parseDocument(json)
    }
}

/** 取字符的六点掩码；八点位（dot7/8）剥到 1..6。 */
private fun sixBitMask(ch: Char): Int {
    if (ch.code !in 0x2800..0x28FF) return 0
    return (ch.code - 0x2800) and 0x3F
}

/**
 * 最小 JSON 写/读。只覆盖本文档用到的子集：对象、数组、字符串、整数、
 * null、布尔；写入保证合法 JSON，读取递归下降还原为 Map/List/原始值。
 */
private object ExportJson {

    fun writeDocument(doc: ExportDocument): String {
        val sb = StringBuilder()
        sb.append('{')
        sb.append("\"schemaVersion\":").append(doc.schemaVersion)
        sb.append(",\"title\":").append(escaped(doc.title))
        sb.append(",\"geometry\":").append(geometryJson(doc.geometry))
        sb.append(",\"pageCount\":").append(doc.pageCount)
        sb.append(",\"cells\":[")
        doc.cells.forEachIndexed { index, cell ->
            if (index > 0) sb.append(',')
            sb.append(cellJson(cell))
        }
        sb.append("]}")
        return sb.toString()
    }

    private fun geometryJson(g: PageGeometry): String {
        val margin = g.margin?.let { it.toString() } ?: "null"
        return "{\"columns\":${g.columns},\"linesPerPage\":${g.linesPerPage},\"margin\":$margin}"
    }

    private fun cellJson(cell: ExportCell): String {
        val dots = cell.dots.joinToString(",", "[", "]")
        val span = cell.sourceSpan?.joinToString(",", "[", "]") ?: "null"
        val text = cell.sourceText?.let { escaped(it) } ?: "null"
        return "{" +
            "\"dots\":$dots," +
            "\"mask\":${cell.mask}," +
            "\"line\":${cell.line}," +
            "\"column\":${cell.column}," +
            "\"role\":${escaped(cell.role)}," +
            "\"source_span\":$span," +
            "\"source_text\":$text" +
            "}"
    }

    private fun escaped(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c.code < 0x20) {
                    sb.append("\\u%04x".format(c.code))
                } else {
                    sb.append(c)
                }
            }
        }
        return sb.append('"').toString()
    }

    // ------------------------------------------------------------ 解析

    @Suppress("UNCHECKED_CAST")
    fun parseDocument(json: String): ExportDocument {
        val root = Parser(json).parse() as Map<String, Any?>
        val geometryMap = root["geometry"] as Map<String, Any?>
        val geometry = PageGeometry(
            (geometryMap["columns"] as Number).toInt(),
            (geometryMap["linesPerPage"] as Number).toInt(),
            (geometryMap["margin"] as? Number)?.toInt(),
        )
        val cells = (root["cells"] as List<Any?>).map { entry ->
            val c = entry as Map<String, Any?>
            val span = (c["source_span"] as List<Any?>?)?.map { (it as Number).toInt() }
            ExportCell(
                dots = (c["dots"] as List<Any?>).map { (it as Number).toInt() },
                mask = (c["mask"] as Number).toInt(),
                line = (c["line"] as Number).toInt(),
                column = (c["column"] as Number).toInt(),
                role = c["role"] as String,
                sourceSpan = span,
                sourceText = c["source_text"] as String?,
            )
        }
        return ExportDocument(
            schemaVersion = (root["schemaVersion"] as Number).toInt(),
            title = root["title"] as String,
            geometry = geometry,
            pageCount = (root["pageCount"] as Number).toInt(),
            cells = cells,
        )
    }

    private class Parser(private val text: String) {
        private var pos = 0

        fun parse(): Any? {
            skipWs()
            val value = readValue()
            skipWs()
            return value
        }

        private fun readValue(): Any? {
            return when (text[pos]) {
                '{' -> readObject()
                '[' -> readArray()
                '"' -> readString()
                't' -> readLiteral(true)
                'f' -> readLiteral(false)
                'n' -> readLiteral(null)
                else -> readNumber()
            }
        }

        private fun readObject(): Map<String, Any?> {
            val map = LinkedHashMap<String, Any?>()
            pos++ // '{'
            skipWs()
            if (text[pos] == '}') { pos++; return map }
            while (true) {
                skipWs()
                val key = readString()
                skipWs(); pos++ // ':'
                map[key] = readValue()
                skipWs()
                when (text[pos]) {
                    ',' -> pos++
                    '}' -> { pos++; break }
                }
            }
            return map
        }

        private fun readArray(): List<Any?> {
            val list = ArrayList<Any?>()
            pos++ // '['
            skipWs()
            if (text[pos] == ']') { pos++; return list }
            while (true) {
                list.add(readValue())
                skipWs()
                when (text[pos]) {
                    ',' -> pos++
                    ']' -> { pos++; break }
                }
            }
            return list
        }

        private fun readString(): String {
            pos++ // '"'
            val sb = StringBuilder()
            while (text[pos] != '"') {
                val c = text[pos]
                if (c == '\\') {
                    pos++
                    when (text[pos]) {
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> {
                            val hex = text.substring(pos + 1, pos + 5)
                            sb.append(hex.toInt(16).toChar())
                            pos += 4
                        }
                    }
                } else {
                    sb.append(c)
                }
                pos++
            }
            pos++ // closing '"'
            return sb.toString()
        }

        private fun readNumber(): Number {
            val start = pos
            while (pos < text.length && text[pos] in "-0123456789.eE+") pos++
            val token = text.substring(start, pos)
            return if (token.contains('.')) token.toDouble() else token.toInt()
        }

        private fun readLiteral(expected: Any?): Any? {
            val literal = if (expected == null) "null" else expected.toString()
            require(text.startsWith(literal, pos))
            pos += literal.length
            return expected
        }

        private fun skipWs() {
            while (pos < text.length && text[pos].isWhitespace()) pos++
        }
    }
}
