/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 *
 * 盲文文件解析：编码识别、ASCII 盲文转 Unicode、按点显器方数分页。
 * 纯 Kotlin 实现，不依赖 Android，便于 JVM 单元测试。
 */

package com.linxi.brailleback

import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.zip.ZipInputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.text.RegexOption

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
     * 点位值 -> NABCC ASCII 的惰性反查表，与 [NABCC_DOTS] 同一份源表反演，
     * 不在导出侧复制第二张表。0-63 返回字符；越界（八点/表外）返回 null。
     */
    private val dotsToAsciiMap: Map<Int, Char> by lazy {
        val map = HashMap<Int, Char>()
        NABCC_DOTS.forEachIndexed { idx, dots ->
            map.putIfAbsent(dotsValue(dots), (0x20 + idx).toChar())
        }
        map
    }

    /**
     * 点位值(0-63) -> ASCII 盲文字符；越界或表外点位返回 null。
     * 供「导出为盲文」把 Unicode 盲文回写成 BRF ASCII 使用。
     */
    fun dotsToAscii(dots: Int): Char? {
        if (dots !in 0..63) return null
        return dotsToAsciiMap[dots]
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

    // ------------------------------------------------------------------
    // 阳光盲文（中国盲文图书馆 docx，正文是私用区 PUA 字符）
    // ------------------------------------------------------------------

    /** 阳光盲文在此范围内（U+E234～U+E273）；E273 是空方。 */
    private const val SUN_MIN = 0xE234
    private const val SUN_MAX = 0xE273
    private const val SUN_BLANK = 0xE273

    /**
     * 阳光书重排的兜底文本列数：点显器未连接 / 未上报方数时使用。
     *
     * Metec 点显器共 42 格 = 40 文本列 + 2 状态格，状态格不参与正文排版；
     * 正常路径下文本列数取自驱动上报的 `numTextCells`（见 ReaderFileLoader
     * 调用处传入的 textCells），这里只做兜底，不在解码层写死设备方数。
     */
    internal const val SUNSHINE_DEFAULT_TEXT_CELLS = 40

    /** 文本列数的合理上限：超出视为驱动误报，回退兜底值。 */
    private const val SUNSHINE_MAX_CELLS = 256

    /**
     * 解析阳光重排宽度：[textCells] 来自点显器驱动上报的 `numTextCells`
     * （实际文本列数，不含状态格）。未连接 / 未上报（<=0）或越界时回退
     * [SUNSHINE_DEFAULT_TEXT_CELLS]，解码层不猜设备方数。
     */
    private fun sunshineWidth(textCells: Int): Int =
        if (textCells in 1..SUNSHINE_MAX_CELLS) textCells
        else SUNSHINE_DEFAULT_TEXT_CELLS

    /**
     * 中国盲文图书馆电子书的正文：每个字符是 Unicode 私用区的一个盲文方
     * （U+E234～U+E273），不是汉字。此函数把 docx（其实是 zip）里
     * `word/document.xml` 的 `<w:t>` 文本按段落提取出来，得到连续的 PUA 正文。
     *
     * 用标准库 [ZipInputStream] + `StringBuilder` 做轻量解析，不依赖 Android，
     * 便于 JVM 单测。只取 `<w:t>` 的文字节点，跳过 `<w:pPr>` 等样式标签。
     * 段落用 '\n' 分隔，与阅读器既有的段落处理保持一致。
     *
     * @return 提取出的 PUA 正文；不是有效 docx 或解析失败时返回 null。
     */
    fun decodeSunshineDocx(bytes: ByteArray): String? {
        return try {
            val xml = ByteArrayInputStream(bytes).use { input ->
                ZipInputStream(input).use { zip ->
                    var entry = zip.nextEntry
                    var found: String? = null
                    while (entry != null) {
                        if (entry.name == "word/document.xml") {
                            found = zip.readBytes().toString(StandardCharsets.UTF_8)
                            break
                        }
                        zip.closeEntry()
                        entry = zip.nextEntry
                    }
                    found
                }
            } ?: return null

            // 按 <w:p> 段落切分，每段内把所有 <w:t> 的文字拼起来。
            // docx 的 PUA 字符是直接的 UTF-16 字符（非 &#xE234; 实体），
            // 唯一要还原的是 XML 的 5 个预定义实体。
            val paragraphs = ArrayList<String>()
            val paraRegex = Regex("<w:p[ >][^>]*>(.*?)</w:p>", RegexOption.DOT_MATCHES_ALL)
            val textRegex = Regex("<w:t(?:\\s[^>]*)?>(.*?)</w:t>", RegexOption.DOT_MATCHES_ALL)
            for (m in paraRegex.findAll(xml)) {
                val sb = StringBuilder()
                for (tm in textRegex.findAll(m.groupValues[1])) {
                    sb.append(unescapeXml(tm.groupValues[1]))
                }
                paragraphs.add(sb.toString())
            }
            if (paragraphs.isEmpty()) return null
            paragraphs.joinToString("\n")
        } catch (e: Exception) {
            null
        }
    }

    /** 还原 XML 的 5 个预定义实体；docx 正文的 PUA 本身是裸字符，不涉及数值实体。 */
    private fun unescapeXml(s: String): String = s
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&amp;", "&")

    /**
     * 判断正文是否为阳光 PUA 盲文（非空方字符占绝大多数）。
     * 区别于 U+2800 系列：阳光书正文落在 U+E234～U+E273。
     */
    fun isSunshinePua(text: String): Boolean {
        var pua = 0
        var n = 0
        for (c in text) {
            if (c == '\n' || c == '\r' || c == '\u000C') continue
            n++
            if (c.code in SUN_MIN..SUN_MAX) pua++
        }
        return n > 0 && pua.toDouble() / n > 0.5
    }

    /**
     * 阳光书「30 方一行 → 点显器文本列宽」重排（code71：段落感知实验版）。
     *
     * 只针对从中国盲文图书馆下载的电子盲文：原书每行的正文被切成 30 方，
     * 用户点显器是 40 方。以空行分段，段内严格按原书空方（词边界）装词，
     * 能放进当前 [width] 方行的词绝不挪到下一行，不拆词；目录行原样保留。
     *
     * 接缝与特殊行规则（2026-11-22 用户确认的实验方案）：
     * - 下一行以 U+E257（36 点）开头：优先于短行判断，删掉标记后直接接，
     *   不插空方（即使整行很短，也绝不能当页码/标题处理，否则词会被拆开）；
     * - 正文行首恰好两个空方：原书的新段落缩进标记，结束当前段并插入空行
     *   （只有空行才能被 K1+K2 / K1+K3 段落跳转识别），新段保留两方缩进；
     * - 段内短行紧跟空行：视为标题，原样单独一行，由用户自己判断；
     * - 其余短行：视为页码，按「2 空方 + 页码 + 2 空方」内联到上一行后，
     *   再接下一行（前后共 4 个空方，页码不单独占行）；
     * - 下一行原文已以空方开头、或上一行结尾已有空方：不重复插；
     * - 其余正文接缝：插一个空方。
     *
     * 只读模拟《鹿比翁 3-1》显示本规则比 code70 多补约一倍空方、劈字风险
     * 更高，但行尾填充率基本不变。这是用户要试的方案：先做实验版装机试听，
     * 不行则回退 code70 的 4/5 点规则。其他格式（brf/bdo/txt）绝不调用本函数。
     */
    fun reflowSunshine(text: String, width: Int = SUNSHINE_DEFAULT_TEXT_CELLS): String {
        val cells = width.coerceAtLeast(1)
        val space = SUN_BLANK.toChar()
        val link = '\uE257'
        val guide = '\uE243'

        fun isBlankLine(line: String) = line.isEmpty() || line.all { it == space }
        fun isTocLine(line: String) = line.count { it == guide } >= 3
        fun isPageNumLine(line: String): Boolean {
            val s = line.trim(space)
            return s.isNotEmpty() && s.length <= 8
        }

        /** 一行里的非空方数（空方是 U+E273，换行不出现在这里）。 */
        fun nonBlankCount(line: String): Int = line.count { it != space }

        /**
         * code89：段尾若只剩 1～2 个非空方（30→40 重排把最后一个词挤成孤行），
         * 把上一行最后一个词连同其后的空方一起挪回来，避免：
         * - K1/K3 停在孤行上，下一屏又显示几乎相同的内容；
         * - 阅读器跳过孤行时，把原文最后一个字整个丢掉。
         *
         * 只挪「最后一个词 + 它后面的空方」，原词间空方原样保留，不拆词、不吞字。
         */
        fun mergeTinyTail(lines: ArrayList<String>, tail: String): String {
            if (lines.isEmpty()) return tail
            val tiny = nonBlankCount(tail)
            if (tiny < 1 || tiny > 2) return tail
            val prev = lines.last()
            var cursor = prev.length
            while (cursor > 0 && prev[cursor - 1] == space) cursor--
            val wordEnd = cursor
            while (cursor > 0 && prev[cursor - 1] != space) cursor--
            val wordStart = cursor
            if (wordStart <= 0 || wordEnd <= wordStart) return tail
            val moved = prev.substring(wordStart) // 最后一个词 + 其后的空方
            val kept = prev.substring(0, wordStart)
            val merged = moved + tail
            if (kept.isEmpty() || nonBlankCount(merged) <= 2) return tail
            lines[lines.size - 1] = kept
            return merged
        }

        /** 按原书空方切成「词 / 空方段」逐 token 装行；不拆词、不吞掉连续空方。 */
        fun reflowParagraph(text: String): List<String> {
            val tokens = ArrayList<Pair<Boolean, String>>() // true=空方段, false=词
            var i = 0
            while (i < text.length) {
                val start = i
                if (text[i] == space) {
                    while (i < text.length && text[i] == space) i++
                    tokens.add(true to text.substring(start, i))
                } else {
                    while (i < text.length && text[i] != space) i++
                    tokens.add(false to text.substring(start, i))
                }
            }
            if (tokens.isEmpty()) return emptyList()
            val lines = ArrayList<String>()
            val cur = StringBuilder()
            for ((blankRun, token) in tokens) {
                if (cur.isNotEmpty() && cur.length + token.length > cells) {
                    lines.add(cur.toString())
                    cur.setLength(0)
                }
                cur.append(token)
                if (blankRun && cur.length == cells) {
                    lines.add(cur.toString())
                    cur.setLength(0)
                }
            }
            if (cur.isNotEmpty()) lines.add(mergeTinyTail(lines, cur.toString()))
            return lines
        }

        val out = ArrayList<String>()
        val stream = StringBuilder()
        fun flush() {
            if (stream.isNotEmpty()) {
                out.addAll(reflowParagraph(stream.toString()))
                stream.setLength(0)
            }
        }

        var prevWasBlank = false
        for (raw in text.split('\n')) {
            if (isBlankLine(raw)) {
                flush(); out.add(raw); prevWasBlank = true
                continue
            }
            if (isTocLine(raw)) {
                flush(); out.add(raw); prevWasBlank = false
                continue
            }
            val stripped = raw.dropWhile { it == space }
            // 36 点续行优先于短行判断：即使整行很短，也必须删标记后直接接，
            // 否则会被当成页码/标题，词被空方拆开（code71 的漏网分支）。
            if (stripped.startsWith(link)) {
                stream.append(stripped.substring(1))
                prevWasBlank = false
                continue
            }
            // 原书新段落用「行首恰好两个空方」缩进标记（样例书 640 处）。
            // 必须插入空行而不只是换行：段落跳转快捷键（K1+K2 / K1+K3）
            // 在段落阅读模式下只把空行当段落断点。
            val leading = raw.indexOfFirst { it != space }.let { if (it < 0) raw.length else it }
            if (leading == 2) {
                flush()
                if (out.isNotEmpty()) out.add("")
                stream.append(raw) // 保留原书的两方缩进
                prevWasBlank = false
                continue
            }
            when {
                isPageNumLine(raw) -> {
                    if (prevWasBlank || stream.isEmpty()) {
                        // 空行后或正文开始前的短行是标题：原样单独一行，不参与合并。
                        flush(); out.add(raw)
                    } else {
                        // 接到上一行后；末尾两个空方顺带充当与下一行的边界。
                        val page = raw.trim(space)
                        val pad = space.toString().repeat(2)
                        stream.append(pad).append(page).append(pad)
                    }
                    prevWasBlank = false
                }
                else -> {
                    if (stream.isNotEmpty() && !raw.startsWith(space) &&
                        stream[stream.length - 1] != space
                    ) {
                        stream.append(space)
                    }
                    stream.append(raw)
                    prevWasBlank = false
                }
            }
        }
        flush()
        return out.joinToString("\n")
    }

    /**
     * 解码结果。成功与失败是不同类型，调用方必须 `when` 处理，
     * 不会再有「忘了判 null」的路径。
     */
    sealed interface DecodedDocument {
        /** 解码成功，[text] 可直接进入排版管线。 */
        data class Decoded(val text: String) : DecodedDocument

        /** 读取失败的具体原因。 */
        sealed interface Failure : DecodedDocument {
            /** 文件是空的（0 字节）。 */
            data object EmptyFile : Failure

            /** 后缀是 bdo/bdoc，但找不到 Bword 记录标记。 */
            data object NotBwordArchive : Failure

            /** 是 Bword 文档，但盲文方数 [cellCount] 低于可靠阈值。 */
            data class TooFewBrailleCells(val cellCount: Int) : Failure
        }
    }

    /**
     * 按文件名后缀分派解码。
     *
     * - `.bdo` / `.bdoc`：Bword 二进制盲文文档，走 [BdoCArchiveParser] 结构化解码，
     *   输出已是 Unicode 盲文（U+2800 系列），**不可**再做 ASCII 盲文转换。
     *   这是国内盲文图书馆/出版社的主力格式。
     * - `.docx` / `.docm`：先按阳光盲文图书馆电子书解 PUA 正文；解不出时
     *   回落到普通文本解码（普通 Word 明眼文字的提取由 EbookTextExtractor 链路处理）。
     * - 其余（`.brf` / `.txt` 等）：按文本解码，后续由 [toUnicodeBraille] 处理。
     *
     * @param textCells 点显器实际文本列数（驱动上报的 numTextCells），只用于
     *   阳光 docx 重排；<=0 或越界时由 [sunshineWidth] 回退默认 40。其余格式不用。
     */
    fun decodeDocument(
        bytes: ByteArray,
        fileName: String?,
        textCells: Int = SUNSHINE_DEFAULT_TEXT_CELLS,
    ): DecodedDocument {
        val ext = fileName?.substringAfterLast('.', "")?.lowercase()
        if (ext == "docx" || ext == "docm") {
            val sunshine = decodeSunshineDocx(bytes)
            if (sunshine != null) {
                return DecodedDocument.Decoded(
                    reflowSunshine(sunshine, width = sunshineWidth(textCells))
                )
            }
            return DecodedDocument.Decoded(decode(bytes))
        }
        if (ext != "bdo" && ext != "bdoc") {
            return DecodedDocument.Decoded(decode(bytes))
        }
        return when (val result = BdoCArchiveParser.parse(bytes)) {
            is BdoCArchiveParser.ParseResult.Decoded ->
                DecodedDocument.Decoded(result.text)
            BdoCArchiveParser.ParseResult.EmptyInput ->
                DecodedDocument.Failure.EmptyFile
            BdoCArchiveParser.ParseResult.NoRecords ->
                DecodedDocument.Failure.NotBwordArchive
            is BdoCArchiveParser.ParseResult.TooFewCells ->
                DecodedDocument.Failure.TooFewBrailleCells(result.cellCount)
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
        var pua = 0
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
                c.code in SUN_MIN..SUN_MAX -> pua++
                c in ' '..'_' -> {
                    ascii++
                    if (c in 'A'..'Z') letters++
                    else if (c != ' ') special++
                }
                c.code in 0x4E00..0x9FFF -> han++
            }
        }
        if (n == 0) return ContentKind.TEXT
        // 阳光 PUA 正文优先判定：占比 >50% 直接视为已是盲文，不送翻译服务。
        // PUA 三不沾 U+2800 盲文区 / 汉字 / Braille ASCII，不拦这一条会被
        // 落进 TEXT 当明眼文字翻译，结果是乱码或空白。
        if (pua.toDouble() / n > 0.5) return ContentKind.BRAILLE
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
     * 取字符对应的点位值（0-63，六点位掩码）。非盲文字符返回 0（空方）。
     * 供发送到点显器时构造 patterns 数组。
     *
     * 三类输入：
     * - U+2800..U+28FF：标准 Unicode 盲文，取低 6 位掩码；
     * - 阳光 PUA（U+E234..U+E273）：码位减 [SUN_MIN]-1（即 0xE233）即六点掩码
     *   1..63；空方 U+E273 归 0。编码口径与 liblouis 表 zhcn-cbs.ctb 的
     *   `noback sign` 序列逐条一致（六点制，不含点 7/8），也是
     *   BdoCArchiveParser.valuesToBraille 用的同一套公式。
     *   注意判定顺序：必须先判 [SUN_BLANK]（E273）返回 0，再走区间；
     *   区间包含 E273，若把它放进区间会算出 0xE273 - 0xE233 = 64，
     *   超出 0～63 点位范围，是错值。
     * - 其余：Braille ASCII 走 NABCC 表，表外返回 0。
     */
    fun toDots(c: Char): Int {
        if (c.code in 0x2800..0x28FF) return c.code - 0x2800
        if (c.code == SUN_BLANK) return 0
        if (c.code in SUN_MIN..SUN_MAX) return c.code - (SUN_MIN - 1)
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
            val limit = min(pos + width, newLine)
            // 行尾不劈字：回退到最近的词边界；整行都是同一个长词才硬切。
            val end = wrapEnd(stream, pos, limit)
            out.add(indentOf(hang) + stream.substring(pos, end))
            // 到达段落边界就跳过分隔符；连续的空段落会在下一轮输出空行
            pos = if (end >= newLine) newLine + 1 else end
            remaining--
        }
        return out.joinToString("\n")
    }

    /**
     * 行尾折行点：不超过 [limit] 的最后位置，且不把词从中间切开。
     * 从 [limit] 往回找词边界（词首，其前一格是空方或换行）；找不到
     * （整行只有一个超长词/长数字串）就按 [limit] 硬切。
     */
    private fun wrapEnd(stream: String, start: Int, limit: Int): Int {
        if (limit >= stream.length) return limit
        var k = limit
        while (k > start && !isBoundary(stream[k - 1])) k--
        return if (k > start) k else limit
    }

    /**
     * 把一段盲文串截到不超过 [limit] 方，且不在词中间断开。
     * 用于目录、书签这类「取一截做预览」的地方。
     */
    fun cutAtBoundary(s: String, limit: Int): String {
        if (limit <= 0 || s.isEmpty()) return ""
        if (s.length <= limit) return s
        return s.substring(0, wrapEnd(s, 0, limit))
    }

    /**
     * 屏幕折行后每一行的起点偏移（不越过 [endLimit]）。
     *
     * 与 [screenAt] 用同一套折行规则（段内按 [cellsPerLine] 折、回行缩进、
     * 段边界提前换行、行尾回退到词边界），所以「第几行」与屏幕上看到的行严格一致，
     * 供页内行导航（进度条 / 跳到第 N 行）使用。
     */
    fun lineStarts(
        stream: String,
        offset: Int,
        cellsPerLine: Int,
        maxLines: Int,
        endLimit: Int = stream.length,
    ): IntArray {
        if (offset < 0 || cellsPerLine <= 0 || maxLines <= 0 || offset >= stream.length) {
            return intArrayOf(offset)
        }
        val out = ArrayList<Int>()
        var pos = offset
        var remaining = maxLines
        val limitEnd = min(endLimit, stream.length)
        while (remaining > 0 && pos < limitEnd) {
            out.add(pos)
            var newLine = stream.indexOf('\n', pos)
            if (newLine < 0 || newLine > limitEnd) newLine = limitEnd
            val paraStart = if (pos == 0) 0 else stream.lastIndexOf('\n', pos - 1) + 1
            val hang = if (pos == paraStart) 0
            else hangingIndent(leadingBlanks(stream.substring(paraStart, newLine)))
            val width = max(1, cellsPerLine - hang)
            val limit = min(pos + width, newLine)
            val end = wrapEnd(stream, pos, limit)
            pos = if (end >= newLine) newLine + 1 else end
            remaining--
        }
        return out.toIntArray()
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
     * 增量续算页表：在 [existing]（同一份流、同一个 [unit] 下算出的旧表）尾部接着
     * [nextOffset] 走到新的 [stream.length]，旧表项一字不动，只补新增的页起点。
     *
     * 用于翻译边翻边追加：每来一块新内容不必再从第 0 字符走完整本书。
     *
     * 回落整表重建的情形（调用方/本函数都要兜住）：
     * - 流为空；
     * - 旧表为空；
     * - 旧表末位已不小于新流长度（流被截短/换书），无法在旧表上续；
     * - 调用方发现建表时的 unit 与当前 unit 不一致（转屏、换字号、点显器方数变化）。
     */
    fun appendPageOffsets(stream: String, unit: Int, existing: IntArray): IntArray {
        if (stream.isEmpty()) return intArrayOf(0)
        if (existing.isEmpty()) return pageOffsets(stream, unit)
        val last = existing[existing.size - 1]
        if (last >= stream.length) return pageOffsets(stream, unit)
        val out = ArrayList<Int>(existing.size + 16)
        out.addAll(existing.asList())
        var off = last
        while (off < stream.length) {
            val next = nextOffset(stream, off, unit)
            if (next <= off) break // 步进不动：防死锁，余下交整表口径
            off = next
            if (off < stream.length) out.add(off)
        }
        return out.toIntArray()
    }

    /**
     * 把 [offset] 回退到它所在那一段盲文串（词）的起点。
     *
     * 词内没有分隔标记：通用盲文一个汉字占 1–3 方、词内连续书写，
     * 只有词间空方 U+2800 和段间换行 '\n' 是可靠边界。所以「不从中间劈开一个字」
     * 在流上等价于「不从一个词中间开始」。
     *
     * [offset] 本身已是边界或落在词首时原样返回。
     */
    fun wordStartAtOrBefore(stream: String, offset: Int): Int {
        if (offset <= 0) return 0
        var k = min(offset, stream.length)
        if (k > 0 && isBoundary(stream[k - 1])) return k
        while (k > 0 && !isBoundary(stream[k - 1])) k--
        return k
    }

    /**
     * 上一屏的起点：从 [offset] 往前退约 [unit] 方，再对齐到词首。
     *
     * 后退时只能「取整到词首」：宁可少退几方，也不能让屏首落在词中间。
     */
    fun prevOffset(stream: String, offset: Int, unit: Int): Int {
        if (unit <= 0 || offset <= 0) return 0
        val target = (offset - unit).coerceAtLeast(0)
        return wordStartAtOrBefore(stream, target)
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
