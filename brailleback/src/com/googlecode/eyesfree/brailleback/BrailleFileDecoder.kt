/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 *
 * 盲文文件解析：编码识别、ASCII 盲文转 Unicode、按点显器方数分页。
 * 纯 Kotlin 实现，不依赖 Android，便于 JVM 单元测试。
 */

package com.googlecode.eyesfree.brailleback

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import kotlin.math.max
import kotlin.math.min

object BrailleFileDecoder {

    /** BRF 标准换页符（Form Feed）。 */
    private const val FORM_FEED = '\u000C'

    /** 空白方：盲文里一切缩进都靠它表达。 */
    private const val BLANK = '\u2800'

    /**
     * North American Braille ASCII Code (NABCC) 点位表。
     *
     * 索引 = ASCII 码 - 0x20，值为 dot 编号串（"145" = 点 1、4、5）。
     * 取自 liblouis 权威表 service/jni/liblouiswrapper/liblouis/tables/en-us-brf.dis。
     *
     * 注意：该映射**非线性**，不能用 U+2800 + (c - 0x20) 计算。
     * 例如 'A' 是点 1（U+2801），线性公式会算成 U+2821（点 1、6），是错的。
     */
    private val NABCC_DOTS = arrayOf(
        "",       // 0x20 空格
        "2346",   // 0x21 !
        "5",      // 0x22 "
        "3456",   // 0x23 #
        "1246",   // 0x24 $
        "146",    // 0x25 %
        "12346",  // 0x26 &
        "3",      // 0x27 '
        "12356",  // 0x28 (
        "23456",  // 0x29 )
        "16",     // 0x2A *
        "346",    // 0x2B +
        "6",      // 0x2C ,
        "36",     // 0x2D -
        "46",     // 0x2E .
        "34",     // 0x2F /
        "356",    // 0x30 0
        "2",      // 0x31 1
        "23",     // 0x32 2
        "25",     // 0x33 3
        "256",    // 0x34 4
        "26",     // 0x35 5
        "235",    // 0x36 6
        "2356",   // 0x37 7
        "236",    // 0x38 8
        "35",     // 0x39 9
        "156",    // 0x3A :
        "56",     // 0x3B ;
        "126",    // 0x3C <
        "123456", // 0x3D =
        "345",    // 0x3E >
        "1456",   // 0x3F ?
        "4",      // 0x40 @
        "1",      // 0x41 A
        "12",     // 0x42 B
        "14",     // 0x43 C
        "145",    // 0x44 D
        "15",     // 0x45 E
        "124",    // 0x46 F
        "1245",   // 0x47 G
        "125",    // 0x48 H
        "24",     // 0x49 I
        "245",    // 0x4A J
        "13",     // 0x4B K
        "123",    // 0x4C L
        "134",    // 0x4D M
        "1345",   // 0x4E N
        "135",    // 0x4F O
        "1234",   // 0x50 P
        "12345",  // 0x51 Q
        "1235",   // 0x52 R
        "234",    // 0x53 S
        "2345",   // 0x54 T
        "136",    // 0x55 U
        "1236",   // 0x56 V
        "2456",   // 0x57 W
        "1346",   // 0x58 X
        "13456",  // 0x59 Y
        "1356",   // 0x5A Z
        "246",    // 0x5B [
        "1256",   // 0x5C backslash
        "12456",  // 0x5D ]
        "45",     // 0x5E ^
        "456"     // 0x5F _
    )

    /** 由 dot 编号串算点位值：dot1=bit0(1) ... dot6=bit5(32)，与 Unicode 盲文位序一致。 */
    private fun dotsValue(dots: String): Int {
        var v = 0
        for (d in dots) {
            if (d in '1'..'6') {
                v = v or (1 shl (d - '1'))
            }
        }
        return v
    }

    /** ASCII(0x20-0x5F) -> 点位值(0-63)；范围内必命中，故无需 -1 哨兵。 */
    private fun asciiToDots(c: Char): Int? {
        val idx = c.code - 0x20
        if (idx !in NABCC_DOTS.indices) return null
        return dotsValue(NABCC_DOTS[idx])
    }

    /**
     * 编码识别：BOM(UTF-8/UTF-16LE/UTF-16BE) -> 严格 UTF-8 -> GBK 回退。
     *
     * 中文 BRF 多为 GBK。若不做严格 UTF-8 校验而直接按 UTF-8 解码，
     * GBK 字节会被静默替换为 U+FFFD，整篇乱码，故必须回退。
     */
    fun decode(bytes: ByteArray): String {
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte()
            && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return String(bytes, 3, bytes.size - 3, StandardCharsets.UTF_8)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte()
            && bytes[1] == 0xFE.toByte()) {
            return String(bytes, 2, bytes.size - 2, StandardCharsets.UTF_16LE)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte()
            && bytes[1] == 0xFF.toByte()) {
            return String(bytes, 2, bytes.size - 2, StandardCharsets.UTF_16BE)
        }
        // 严格 UTF-8：非法序列直接报错，避免把 GBK 误判成 UTF-8
        try {
            val decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            return decoder.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (e: CharacterCodingException) {
            // 落到 GBK
        }
        return try {
            String(bytes, charset("GBK"))
        } catch (e: Exception) {
            String(bytes, StandardCharsets.UTF_8)
        }
    }

    /**
     * 按文件名后缀分派解码。
     *
     * - `.bdo` / `.bdoc`：Bword 二进制盲文文档，走 [BdoCArchiveParser] 结构化解码，
     *   输出已是 Unicode 盲文（U+2800 系列），**不可**再做 ASCII 盲文转换。
     *   这是国内盲文图书馆/出版社的主力格式。
     * - 其余（`.brf` / `.txt` 等）：按文本解码，后续由 [toUnicodeBraille] 处理。
     *
     * @return 解码后的正文；bdo 解码失败时返回 null（交由调用方提示）。
     */
    fun decodeDocument(bytes: ByteArray, fileName: String?): String? {
        val ext = fileName?.substringAfterLast('.', "")?.lowercase()
        return if (ext == "bdo" || ext == "bdoc") {
            BdoCArchiveParser.parse(bytes)
        } else {
            decode(bytes)
        }
    }

    /**
     * 正文内容类型。决定阅读器如何处理解码后的文本：
     *
     * - [ContentKind.BRAILLE]：已是 Unicode 盲文点位（U+2800 系列），直接读，不翻译。
     * - [ContentKind.ASCII]：Braille ASCII（BRF 风格），按 NABCC 表转 Unicode 盲文。
     * - [ContentKind.TEXT]：明眼文字（中文/英文），需调用翻译服务转成盲文才能点显。
     */
    enum class ContentKind { BRAILLE, ASCII, TEXT }

    /**
     * 检测解码后文本的内容类型。
     *
     * 判定顺序（都是保守启发式，UI 层另提供手动覆盖）：
     * 1. Unicode 盲文点位 >50% → [BRAILLE]（主体已是盲文，直接读）
     * 2. 汉字占非空白字符 >20% → 明眼中文 → [TEXT]
     * 3. 其余（无汉字、无盲文点位）看 0x20-0x5F 的组成：
     *    - 若该范围覆盖 >85% 且「非字母特殊符号」（数字/标点/符号）占比 >12%
     *      → Braille ASCII → [ASCII]（盲文点数字均匀使用 64 个符号，且不含小写字母；
     *        明眼英文正文几乎只有小写字母和空格）
     * 4. 其余一律 [TEXT]
     */
    fun detectKind(text: String): ContentKind {
        var braille = 0
        var ascii = 0
        var han = 0
        var letters = 0
        var special = 0
        var n = 0
        for (c in text) {
            if (c == '\n' || c == '\r' || c == '\u000C') continue
            n++
            when {
                c.code in 0x2800..0x28FF -> braille++
                c in ' '..'_' -> {
                    ascii++
                    if (c in 'A'..'Z') letters++
                    else if (c != ' ') special++
                }
                c.code in 0x4E00..0x9FFF -> han++
            }
        }
        if (n == 0) return ContentKind.TEXT
        val brailleRatio = braille.toDouble() / n
        if (brailleRatio > 0.5) return ContentKind.BRAILLE
        val hanRatio = han.toDouble() / n
        if (hanRatio > 0.2) return ContentKind.TEXT
        val asciiRatio = ascii.toDouble() / n
        if (asciiRatio > 0.85 && braille == 0) {
            // 字母 + 特殊符号构成：盲文点位字里符号/数字多，明眼英文几乎纯字母+空格
            if (special.toDouble() / n > 0.12) return ContentKind.ASCII
        }
        return ContentKind.TEXT
    }

    /**
     * 把翻译服务返回的点位数组转成 Unicode 盲文文本流。
     *
     * 点显器的「一个方」就是 0-255 的 bitmask；U+2800 + 点位即该方的
     * Unicode 字符。转换后即可复用阅读器现有的阅读流/分页/点显逻辑。
     */
    fun cellsToUnicodeText(cells: ByteArray): String {
        val sb = StringBuilder(cells.size)
        for (b in cells) {
            sb.append((0x2800 + (b.toInt() and 0xFF)).toChar())
        }
        return sb.toString()
    }

    /**
     * 单行 ASCII 盲文(0x20-0x5F) 转 Unicode 盲文(U+2800-)。
     * 已是 Unicode 盲文或其他字符（中文、标点）原样保留。
     */
    fun toUnicodeBraille(line: String): String {
        val sb = StringBuilder(line.length)
        for (c in line) {
            sb.append(brailleChar(c))
        }
        return sb.toString()
    }

    /**
     * 单字符转 Unicode 盲文。
     * 已是 Unicode 盲文则原样返回；Braille ASCII 按 NABCC 表转换；
     * 其余字符（中文等）原样保留。
     */
    private fun brailleChar(c: Char): Char {
        if (c.code in 0x2800..0x28FF) return c
        val dots = asciiToDots(c)
        return if (dots != null) (0x2800 + dots).toChar() else c
    }

    /**
     * 取字符对应的点位值（0-63）。非盲文字符返回 0（空方）。
     * 供发送到点显器时构造 patterns 数组。
     */
    fun toDots(c: Char): Int {
        if (c.code in 0x2800..0x28FF) return c.code - 0x2800
        return asciiToDots(c) ?: 0
    }

    /**
     * 构建分页。
     *
     * - 先按 BRF 换页符 [FORM_FEED] 分段（尊重文件自带分页）
     * - 段内每行按 [cellsPerLine]（点显器方数）折行，长行不再溢出屏幕
     * - 再按 [linesPerPage] 行切页，并做孤行控制
     *
     * 排版优化（针对真实 bdo 文档实测，见 BdoCArchiveParserTest）：
     * - **连续空行折叠为 1 个**：实测封面区存在连续 10 个空行，直接占掉半屏
     * - **去除文档开头/结尾的空行**：首页不再空白
     * - **widow 控制**：段落首行不孤立在页尾（该段还有后续行时，推到下一页）
     */
    fun buildPages(
        text: String,
        cellsPerLine: Int,
        linesPerPage: Int
    ): List<String> {
        require(cellsPerLine > 0) { "cellsPerLine must be > 0" }
        require(linesPerPage > 0) { "linesPerPage must be > 0" }
        return paginate(buildRows(text, cellsPerLine), linesPerPage)
    }

    /**
     * 折行后的一个物理行。
     *
     * @param paraStart 是否为段落首行（用于 widow 控制）
     * @param paraEnd 是否为段落末行
     * @param pageBreakBefore 本行之前是否必须另起一页（BRF 换页符 \f 产生）
     */
    private class Row(
        val text: String,
        val paraStart: Boolean,
        val paraEnd: Boolean,
        val pageBreakBefore: Boolean = false
    )

    /** 空行：既是段落结尾也是段落开头，不参与孤行判定。 */
    private val BLANK_ROW = Row("", true, true)

    /** 折叠空行、去掉首尾空行后的一段正文。 */
    private class Block(
        val text: String,
        val pageBreakBefore: Boolean
    )

    /**
     * 把正文整理为段落块列表：连续空行折叠为 1 个、去掉首尾空行、
     * 标记 BRF 换页符产生的强制分页点。供 [buildRows] 与 [toReadingStream] 共用。
     */
    private fun collapseBlocks(text: String): List<Block> {
        val out = ArrayList<Block>()
        var pendingBlank = false
        val sections = text.split(FORM_FEED)
        for (sectionIndex in sections.indices) {
            // 换页符之后另起一段（首个分段除外）
            var pendingBreak = sectionIndex > 0
            for (rawLine in sections[sectionIndex].split('\n')) {
                val line = toUnicodeBraille(rawLine.trimEnd('\r'))
                if (line.isEmpty()) {
                    // 连续空行只记一次，等到遇到实际内容时才落到结果里，
                    // 由此自然实现「折叠 + 不产生开头空行」。
                    pendingBlank = true
                    continue
                }
                if (pendingBlank) {
                    if (out.isNotEmpty()) {
                        out.add(Block("", false))
                    }
                    pendingBlank = false
                }
                out.add(Block(line, pendingBreak))
                pendingBreak = false
            }
        }
        // 去掉结尾空段
        while (out.isNotEmpty() && out[out.size - 1].text.isEmpty()) {
            out.removeAt(out.size - 1)
        }
        return out
    }

    /** 行首空白方数量——盲文的缩进就是靠它表达的，属于内容而非格式。 */
    private fun leadingBlanks(s: String): Int {
        var n = 0
        while (n < s.length && s[n] == BLANK) n++
        return n
    }

    /** 由 [n] 个空白方拼出的缩进前缀。 */
    private fun indentOf(n: Int): String =
        if (n <= 0) "" else BLANK.toString().repeat(n)

    /**
     * 回行缩进，依据 GB/T 15720-2008 第 7 章「中国盲文的一般书写格式」：
     *
     * - 首行空两方（7.2 正文抬头空两方）→ 回行**顶格**；
     *   真实语料支持：缩进 2 之后接缩进 0 占 87%（862 次）
     * - 首行空四方（7.5 夹在文内的诗歌空四方，换行空六方）→ 回行**空六方**；
     *   真实语料支持：缩进 4 之后接缩进 6 共 410 次
     * - 其余缩进（3、5、6 …）含义随书而异（乐谱声部、目录层级等），
     *   **保持与首行一致**：信息不足时不猜测，避免破坏原有层次。
     */
    private fun hangingIndent(firstIndent: Int): Int = when (firstIndent) {
        2 -> 0
        4 -> 6
        else -> firstIndent
    }

    /** 把正文折成物理行，供屏幕分页使用。 */
    private fun buildRows(text: String, cellsPerLine: Int): List<Row> {
        val rows = ArrayList<Row>()
        for (block in collapseBlocks(text)) {
            if (block.text.isEmpty()) {
                rows.add(BLANK_ROW)
                continue
            }
            val indent = leadingBlanks(block.text)
            val body = block.text.substring(indent)
            // 整行只有缩进、或一行放得下：原样保留，一个方都不改
            if (body.isEmpty() || block.text.length <= cellsPerLine) {
                rows.add(Row(block.text, true, true, block.pageBreakBefore))
                continue
            }
            val hang = hangingIndent(indent)
            val firstWidth = max(1, cellsPerLine - indent)
            val hangWidth = max(1, cellsPerLine - hang)
            var i = 0
            var first = true
            while (i < body.length) {
                val width = if (first) firstWidth else hangWidth
                val end = min(i + width, body.length)
                rows.add(Row(indentOf(if (first) indent else hang) + body.substring(i, end),
                    first, end >= body.length, block.pageBreakBefore && first))
                first = false
                i = end
            }
        }
        return rows
    }

    /**
     * 按 [linesPerPage] 切页。
     *
     * widow 控制：若某页最后一行是段落首行、且该段落还有后续内容，
     * 说明把一个段落的开头单独甩在了页尾，将其推到下一页，让段落完整开始。
     */
    private fun paginate(rows: List<Row>, linesPerPage: Int): List<String> {
        if (rows.isEmpty()) return listOf("")
        val pages = ArrayList<String>()
        var i = 0
        while (i < rows.size) {
            var end = min(i + linesPerPage, rows.size)
            // 换页符优先：本页内若存在强制分页点，就在这里切断
            for (k in i + 1 until end) {
                if (rows[k].pageBreakBefore) {
                    end = k
                    break
                }
            }
            if (end < rows.size && end - i > 1 && !rows[end].pageBreakBefore) {
                val last = rows[end - 1]
                if (last.paraStart && !last.paraEnd) {
                    end--
                }
            }
            val sb = StringBuilder()
            for (k in i until end) {
                if (k > i) sb.append('\n')
                sb.append(rows[k].text)
            }
            pages.add(sb.toString())
            i = end
        }
        return pages
    }

    /**
     * 取第 [page] 页的首行预览（最多 [maxLen] 个字符，去掉首尾空白方）。
     * 供目录、书签列表显示「这页讲的是什么」。
     */
    fun pagePreview(pages: List<String>, page: Int, maxLen: Int): String {
        if (pages.isEmpty() || page < 0 || page >= pages.size) return ""
        for (line in pages[page].split('\n')) {
            val trimmed = line.trim('\u2800')
            if (trimmed.isNotEmpty()) {
                return if (trimmed.length <= maxLen) trimmed else trimmed.substring(0, maxLen)
            }
        }
        return ""
    }

    // ------------------------------------------------------------------
    // 偏移式阅读（适配单行点显器）
    // ------------------------------------------------------------------

    /**
     * 把正文整理为连续阅读流：段落之间用 '\n' 分隔，空行已折叠、首尾空行已去除。
     *
     * 点显器是单行的，一次只能显示十几到几十个方，所以阅读位置必须以
     * **字符偏移**表示，而不能以「屏幕页」为单位——屏幕一页可达数百方，
     * 若按页步进，20 方的点显器只能读到每页开头的一小段，其余内容永远读不到。
     */
    fun toReadingStream(text: String): String =
        collapseBlocks(text).joinToString("\n") { it.text }

    /**
     * 取从 [offset] 开始、发给点显器的一屏内容（最多 [count] 个方）。
     * 段落换行按盲文空格 U+2800 展平：点显器是单行的，没有换行概念。
     */
    fun cellsAt(stream: String, offset: Int, count: Int): String {
        if (offset < 0 || count <= 0 || offset >= stream.length) return ""
        val end = min(offset + count, stream.length)
        val sb = StringBuilder(end - offset)
        for (i in offset until end) {
            val c = stream[i]
            sb.append(if (c == '\n') '\u2800' else c)
        }
        return sb.toString()
    }

    /**
     * 取从 [offset] 开始的屏幕一屏内容：每行至多 [cellsPerLine] 方，
     * 遇到段落边界提前换行，最多 [lines] 行。
     *
     * 屏幕只是上下文预览，其起点与点显器一致（同为 [offset]），
     * 这样点显器读到哪里，屏幕上就能看到对应的位置及后续内容。
     */
    fun screenAt(stream: String, offset: Int, cellsPerLine: Int, lines: Int): String {
        if (offset < 0 || offset >= stream.length || cellsPerLine <= 0 || lines <= 0) return ""
        val out = ArrayList<String>()
        var pos = offset
        var remaining = lines
        while (remaining > 0 && pos < stream.length) {
            var newLine = stream.indexOf('\n', pos)
            if (newLine < 0) newLine = stream.length
            // 定位本段起点（可能早于 offset，此处仅为读取段首缩进，不做切片）
            val paraStart = if (pos == 0) 0 else stream.lastIndexOf('\n', pos - 1) + 1
            // 首行：段首缩进已包含在内容里，原样输出；
            // 回行：按标准补回行缩进，并把这部分宽度从本行扣除。
            val hang = if (pos == paraStart) 0
            else hangingIndent(leadingBlanks(stream.substring(paraStart, newLine)))
            val width = max(1, cellsPerLine - hang)
            val end = min(pos + width, newLine)
            out.add(indentOf(hang) + stream.substring(pos, end))
            // 到达段落边界就跳过分隔符；连续的空段落会在下一轮输出空行
            pos = if (end >= newLine) newLine + 1 else end
            remaining--
        }
        return out.joinToString("\n")
    }

    /** 按阅读单元 [unit]（点显器方数或屏幕一屏）换算的总页数。 */
    fun unitCount(streamLength: Int, unit: Int): Int {
        if (streamLength <= 0 || unit <= 0) return 1
        return (streamLength + unit - 1) / unit
    }

    /** 空方与段落换行都算「字边界」：切断它们不会割裂一个字。 */
    private fun isBoundary(c: Char): Boolean = c == BLANK || c == '\n'

    /**
     * 下一屏的起始偏移——**不把字切成两半，也不丢弃排版缩进**。
     *
     * 点显器是单行的，一屏就是连续若干方。若严格按 [unit] 步进，实测约
     * **74%** 的屏会把一个字从中间劈开（上一屏半个字、下一屏另外半个），
     * 既无法摸读，也违反 GB/T 15720-2008 7.2「换行时保持分词的完整性」。
     *
     * 规则：
     * - 第 [unit] 方落在字中间 → 回退到该字的起点
     *   （代价：这一屏少显示 1–2 方，实测平均每屏少 3–5 方、总屏数多 15–20%）
     * - 第 [unit] 方落在段落换行 `\n` → 前进跳过换行，下一屏从新段落开头开始。
     *   `\n` 是纯结构边界，点显器上没有换行概念（[cellsAt] 会把它展平为空方），
     *   落在它上面说明上一段恰好读完，跳过不丢任何信息。
     * - 落在空方 U+2800 → **保持不动**，下一屏从该空方开始。
     *   行首缩进（标题空一/两/四方、正文抬头空两方、诗歌空四方）正是靠空方
     *   表达的，跳过会让缩进层次和标题层级丢失；字间空格同理。\n
     *   代价是缩进较深时下一屏开头有几个空方，但这正是原书排版。
     * - 连续盲文串本身长于 [unit]（乐谱长音型等）→ 切不断，只能照常前进
     */
    fun nextOffset(stream: String, offset: Int, unit: Int): Int {
        if (unit <= 0 || offset < 0 || offset >= stream.length) return stream.length
        var end = min(offset + unit, stream.length)
        if (end >= stream.length) return stream.length
        if (!isBoundary(stream[end])) {
            var k = end
            while (k > offset && !isBoundary(stream[k - 1])) k--
            end = if (k > offset) k else min(offset + unit, stream.length)
        } else {
            // 只跳过段落换行，保留空方（缩进/字间空格是内容）
            while (end < stream.length && stream[end] == '\n') end++
        }
        return end
    }

    /**
     * 预计算每一屏的起始偏移。
     *
     * 改为边界对齐步进后，步进长度不再固定，页码无法再用
     * 「偏移 / 单元」换算，故打开文件时一次性算出全部页起点，
     * 供翻页、跳页、页码显示与目录使用。
     */
    fun pageOffsets(stream: String, unit: Int): IntArray {
        if (stream.isEmpty()) return intArrayOf(0)
        val out = ArrayList<Int>()
        var off = 0
        while (off < stream.length) {
            out.add(off)
            off = nextOffset(stream, off, unit)
        }
        return out.toIntArray()
    }

    /**
     * 取第 [page] 页展平后、用于发送点显器的一屏内容（长度不超过 [cellsPerLine]）。
     * 换行按空格处理：点显器一屏是连续的方，不含换行概念。
     */
    fun pageToCells(pages: List<String>, page: Int, cellsPerLine: Int): String {
        if (pages.isEmpty() || page < 0 || page >= pages.size) return ""
        // 换行按盲文空格(U+2800)展平：点显器一屏是连续的方，无换行概念，
        // 且需与正文中已转换的空格保持一致（同为 U+2800，而非 ASCII 0x20）。
        val flat = pages[page].replace('\n', '\u2800')
        return if (flat.length <= cellsPerLine) flat else flat.substring(0, cellsPerLine)
    }
}
