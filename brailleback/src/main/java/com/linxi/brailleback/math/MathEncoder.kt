/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 *
 * MathML 遍历器 + 盲文编码器（纯 JVM，不依赖 Android）。
 *
 * 支持标签：math / mrow / mi / mn / mo / mtext / mfrac / msqrt / msub / msup /
 * msubsup / mroot / mfenced / mtable / mtr / mtd / mover / munder / munderover。
 * 其余标签直接抛 [IllegalArgumentException]，不静默产出错盲文。
 *
 * 规则口径对齐 Brailix cn_current 后端（只读参考）：
 * - 字母：class 前缀（latin_lower ⠰ / latin_upper ⠠ / greek_lower ⠨ / greek_upper ⠸）+ 裸字母；
 * - 函数名：indicator.symbol ⠫ + functions 表缩写；
 * - 数字：⠼ 数字符号 + 基线数字；纯数字下标/指数用低位数字，不带 close；
 * - 分数：Antoine 单位数紧凑式 / 简化横杠式 / 复合括号式；
 * - 根号：open +（次根次数）+ indicator + 内容 + close；
 * - 矩阵：每行开合界符，单元内运算符前空方由 matrix.op_prefix ⠐ 取代；
 * - 方程组：cases.first/middle/last 逐行前缀 + 空方；
 * - 重音：accent.over/under 方向标记 + 箭头/横杠结构或符号表点位；
 * - 运算符空格：space_before / space_after 出空方 U+2800；分类标记由渲染层另发。
 */

package com.linxi.brailleback.math

import org.w3c.dom.Element

/** 受支持的 MathML 标签（小写，不含命名空间前缀）。 */
private val SUPPORTED_TAGS = setOf(
    "math", "mrow", "mi", "mn", "mo", "mtext",
    "mfrac", "msqrt", "msub", "msup", "msubsup", "mroot", "mfenced",
    "mtable", "mtr", "mtd", "mover", "munder", "munderover",
)

/** 一元上下文角色：上一个单元是运算符/关系符/大算子时，下一个 space_before 符号按负号处理，不发前空方。 */
private val UNARY_CONTEXT_ROLES = setOf("math_op", "math_rel", "math_big_op")

/** 开括号：其后紧跟的二元运算符不发前空方。 */
private val OPEN_DELIMS = setOf("(", "[", "{")

/** 矩阵开界符 / 闭界符（对齐 Brailix _MATRIX_FENCE_*）。 */
private val MATRIX_OPEN = setOf("(", "[", "|")
private val MATRIX_CLOSE = setOf(")", "]", "|")

internal class MathEncoder(
    private val dotsPerCell: Int,
    private val chineseProvider: ChineseBrailleProvider?,
) {
    private val out = mutableListOf<Int>()
    private val roles = mutableListOf<String?>()
    private val sources = mutableListOf<String?>()

    /** 下一个数字运行前是否需要数字符号 ⠼。 */
    private var needNumberSign = true

    /** 当前字母连写的 class（子集实现：结构标记后即断开，不跨结构共享前缀）。 */
    private var letterRunClass: String? = null

    /** 是否处于矩阵/行列式单元内：单元内运算符前空方改发 matrix.op_prefix ⠐。 */
    private var inMatrixCell = false

    fun encode(root: Element): MathBrailleResult {
        visit(root)
        val cells = out.toIntArray()
        if (dotsPerCell == 6) {
            cells.forEach { mask ->
                require(mask and 0xC0 == 0) { "6 点位第 7/8 点必须为 0：$mask" }
            }
        }
        val unicode = cells.joinToString("") { (0x2800 + it).toChar().toString() }
        return MathBrailleResult(unicode = unicode, cells = cells, dotsPerCell = dotsPerCell)
    }

    // ------------------------------------------------------------------ 遍历

    private fun visit(elem: Element) {
        val tag = tagName(elem)
        if (tag !in SUPPORTED_TAGS) {
            throw IllegalArgumentException("不支持的 MathML 标签：<$tag>")
        }
        when (tag) {
            "math", "mrow" -> emitMrow(elem)
            "mi" -> emitMi(elem)
            "mn" -> emitMn(elem)
            "mo" -> emitMo(elem)
            "mtext" -> emitMtext(elem)
            "mfrac" -> emitMfrac(elem)
            "msqrt" -> emitMsqrt(elem)
            "msub", "msup", "msubsup" -> emitScript(elem)
            "mroot" -> emitMroot(elem)
            "mfenced" -> emitMfenced(elem)
            "mtable" -> emitMatrix(elem, "(", ")")
            "mtr", "mtd" -> children(elem)
            "mover", "munder", "munderover" -> emitAccent(elem)
        }
    }

    private fun children(elem: Element) {
        for (kid in elementChildren(elem)) visit(kid)
    }

    /**
     * mrow 子序列扫描：识别 Brailix 同款的围栏矩阵
     * `<mo>(</mo><mtable/><mo>)</mo>` 与方程组 `<mo>{</mo><mtable/>`（可带空 `\right.` mo）。
     */
    private fun emitMrow(elem: Element) {
        val kids = elementChildren(elem)
        var i = 0
        while (i < kids.size) {
            val k = kids[i]
            if (
                i + 2 < kids.size &&
                tagName(kids[i + 1]) == "mtable" &&
                isFence(kids[i], MATRIX_OPEN) &&
                isFence(kids[i + 2], MATRIX_CLOSE)
            ) {
                emitMatrix(kids[i + 1], moText(kids[i]), moText(kids[i + 2]))
                i += 3
                continue
            }
            if (
                i + 1 < kids.size &&
                tagName(kids[i + 1]) == "mtable" &&
                moText(kids[i]) == "{" &&
                (i + 2 >= kids.size || isEmptyMo(kids[i + 2]))
            ) {
                emitCases(kids[i + 1])
                i += if (i + 2 < kids.size) 3 else 2
                continue
            }
            visit(k)
            i++
        }
    }

    // ------------------------------------------------------------------ 叶子

    private fun emitMi(elem: Element) {
        val text = leafText(elem)
        if (text.isEmpty()) return
        if (text.length > 1) {
            MathTables.functions[text]?.let { cells ->
                structure("indicator.symbol")
                emitCells(cells, "math_function_name", text)
                breakLetterRun()
                needNumberSign = true
                return
            }
            // 未登记的多字母词：逐字母回退（同类共享前缀为已知简化项，见交付说明）。
            for (c in text) emitIdentifierChar(c.toString())
            needNumberSign = true
            return
        }
        emitIdentifierChar(text)
    }

    private fun emitIdentifierChar(ch: String) {
        val c = ch[0]
        when {
            MathTables.greekLower[c] != null ->
                emitClassLetter(c, "greek_lower", MathTables.greekLower.getValue(c))
            MathTables.greekUpper[c] != null ->
                emitClassLetter(c, "greek_upper", MathTables.greekUpper.getValue(c))
            MathTables.lettersLower[c] != null ->
                emitClassLetter(c, "latin_lower", MathTables.lettersLower.getValue(c))
            MathTables.lettersUpper[c] != null ->
                emitClassLetter(c, "latin_upper", MathTables.lettersUpper.getValue(c))
            else -> {
                breakLetterRun()
                val entry = symbolEntry(c)
                    ?: throw IllegalArgumentException("数学符号无表项：'$ch'")
                emitSymbol(entry, ch)
            }
        }
    }

    private fun emitClassLetter(c: Char, cls: String, bare: IntArray) {
        if (letterRunClass != cls) {
            structure("letter_prefix.$cls")
            letterRunClass = cls
        }
        emit(bare[0], "math_identifier", c.toString())
        needNumberSign = true
    }

    private fun emitMn(elem: Element) {
        val text = leafText(elem)
        if (text.isEmpty()) return
        breakLetterRun()
        if (needNumberSign) emitArray(MathTables.numberSign, "number_sign", null)
        for (ch in text) {
            val digit = MathTables.digitsUpper[ch]
                ?: throw IllegalArgumentException("数字无表项：'$ch'")
            emit(digit[0], "math_digit", ch.toString())
        }
        needNumberSign = false
    }

    private fun emitMo(elem: Element) {
        val text = leafText(elem)
        if (text.isEmpty()) return
        breakLetterRun()
        if (text.length > 1) {
            MathTables.functions[text]?.let { cells ->
                structure("indicator.symbol")
                emitCells(cells, "math_function_name", text)
                breakLetterRun()
                needNumberSign = true
                return
            }
        }
        val entry = symbolEntry(text[0])
            ?: throw IllegalArgumentException("数学符号无表项：'$text'")
        emitSymbol(entry, text)
    }

    private fun emitSymbol(entry: MathSymbolEntry, sourceText: String) {
        // 符号/括号/标点结束字母连写，下一个字母重新发 class 前缀。
        breakLetterRun()
        // 前空方：上一单元不是空方、且不在一元上下文（运算符/关系符/大算子/开括号）之后。
        if (entry.spaceBefore && out.isNotEmpty() && lastMask() != 0 && !previousSuppressesSpaceBefore()) {
            if (inMatrixCell) {
                // 矩阵单元内：前空方改发 matrix.op_prefix ⠐，避免被当成单元分隔空方。
                val mark = MathTables.structures["matrix.op_prefix"]
                if (mark != null) emitCells(mark, "math_op", null) else emitBlank()
            } else {
                emitBlank()
            }
        }
        entry.indicator?.let { structure("indicator.$it") }
        emitCells(entry.cells, roleFor(entry.role), sourceText)
        if (entry.spaceAfter) emitBlank()
        if (entry.role in NUMBER_BREAKING_ROLES) needNumberSign = true
    }

    private fun emitMtext(elem: Element) {
        breakLetterRun()
        val text = elem.textContent ?: ""
        val normalized = text.replace("\u00A0", " ")
        if (normalized.trim().isNotEmpty() && chineseProvider != null) {
            val unicode = chineseProvider.translateToUnicode(normalized)
            if (unicode.isNotEmpty()) {
                for (ch in unicode) {
                    val mask = ch.code - 0x2800
                    if (mask in 0..0xFF) emit(mask, "math_text", null)
                }
                needNumberSign = true
                return
            }
        }
        // 无注入提供者：逐字符数学表回退；空格/不换行空格出空方。
        for (ch in normalized) {
            if (ch == ' ' || ch == '\u00A0') {
                emitBlank()
                continue
            }
            val entry = symbolEntry(ch)
            if (entry != null) {
                emitCells(entry.cells, "math_text", ch.toString())
                continue
            }
            when {
                MathTables.greekLower[ch] != null ->
                    emitClassLetter(ch, "greek_lower", MathTables.greekLower.getValue(ch))
                MathTables.greekUpper[ch] != null ->
                    emitClassLetter(ch, "greek_upper", MathTables.greekUpper.getValue(ch))
                MathTables.lettersLower[ch] != null ->
                    emitClassLetter(ch, "latin_lower", MathTables.lettersLower.getValue(ch))
                MathTables.lettersUpper[ch] != null ->
                    emitClassLetter(ch, "latin_upper", MathTables.lettersUpper.getValue(ch))
                else -> throw IllegalArgumentException("文本字符无表项：'$ch'")
            }
        }
        needNumberSign = true
    }

    // ------------------------------------------------------------------ 结构

    private fun emitMfrac(elem: Element) {
        val kids = elementChildren(elem)
        val numerator = kids.getOrNull(0)
        val denominator = kids.getOrNull(1)

        if (tryEmitAntoine(numerator, denominator)) {
            needNumberSign = true
            return
        }

        val simplifiable = isSelfFenced(numerator) && isSelfFenced(denominator)
        if (!simplifiable) {
            structure("fraction.open")
            needNumberSign = true
        }
        if (numerator != null) visit(numerator)
        if (!simplifiable && lastMask() != 0) emitBlank()
        structure("fraction.bar")
        needNumberSign = true
        if (denominator != null) visit(denominator)
        if (!simplifiable) structure("fraction.close")
        needNumberSign = true
    }

    /** Antoine 紧凑式：分子分母都是单位数 <mn>，上位数 + 低位分母数，不开发合括号。 */
    private fun tryEmitAntoine(num: Element?, den: Element?): Boolean {
        if (!isSingleDigitMn(num) || !isSingleDigitMn(den)) return false
        val numText = leafText(num!!)
        val denText = leafText(den!!)
        val upper = MathTables.digitsUpper[numText[0]] ?: return false
        val lower = MathTables.digitsLower[denText[0]] ?: return false
        if (needNumberSign) emitArray(MathTables.numberSign, "number_sign", null)
        emit(upper[0], "math_digit", numText)
        emit(lower[0], "math_digit_lower", denText)
        return true
    }

    private fun emitMsqrt(elem: Element) {
        structure("sqrt.open")
        structure("sqrt.indicator")
        needNumberSign = true
        children(elem)
        structure("sqrt.close")
        needNumberSign = true
    }

    private fun emitScript(elem: Element) {
        val kids = elementChildren(elem)
        val base = kids.getOrNull(0)
        val sub = when (tagName(elem)) {
            "msub", "msubsup" -> kids.getOrNull(1)
            else -> null
        }
        val sup = when (tagName(elem)) {
            "msup", "msubsup" -> kids.getOrNull(if (tagName(elem) == "msubsup") 2 else 1)
            else -> null
        }

        if (base != null) visit(base)
        val savedRun = letterRunClass
        if (sub != null) {
            structure("script.sub")
            emitScriptBody(sub, savedRun)
        }
        if (sup != null) {
            structure("script.sup")
            emitScriptBody(sup, savedRun)
        }
        letterRunClass = savedRun
        needNumberSign = true
    }

    private fun emitScriptBody(content: Element, savedRun: String?) {
        letterRunClass = savedRun
        // 纯数字下标/指数：低位数字，不带数字符号与 close。
        if (isDigitsMn(content)) {
            val text = leafText(content)
            for (ch in text) {
                val lower = MathTables.digitsLower[ch]
                    ?: throw IllegalArgumentException("低位数字无表项：'$ch'")
                emit(lower[0], "math_digit_lower", ch.toString())
            }
            return
        }
        needNumberSign = true
        visit(content)
        if (!isAtomicMn(content)) structure("script.close")
    }

    /** `<mroot>`：open + 次数 + indicator + 底数 + close（次数在前，对齐 Brailix roots.py）。 */
    private fun emitMroot(elem: Element) {
        val kids = elementChildren(elem)
        val base = kids.getOrNull(0)
        val degree = kids.getOrNull(1)
        structure("sqrt.open")
        if (degree != null) {
            needNumberSign = true
            visit(degree)
        }
        structure("sqrt.indicator")
        needNumberSign = true
        if (base != null) visit(base)
        structure("sqrt.close")
        needNumberSign = true
    }

    private fun emitMfenced(elem: Element) {
        val open = elem.getAttribute("open").ifEmpty { "(" }
        val close = elem.getAttribute("close").ifEmpty { ")" }
        val separators = elem.getAttribute("separators").ifEmpty { "," }
        val kids = elementChildren(elem)

        val openEntry = symbolEntry(open[0])
            ?: throw IllegalArgumentException("括号无表项：'$open'")
        emitSymbol(openEntry, open)

        kids.forEachIndexed { index, kid ->
            visit(kid)
            if (index != kids.lastIndex) {
                val sepEntry = symbolEntry(separators[0])
                    ?: throw IllegalArgumentException("分隔符无表项：'$separators'")
                emitSymbol(sepEntry, separators)
            }
        }

        val closeEntry = symbolEntry(close[0])
            ?: throw IllegalArgumentException("括号无表项：'$close'")
        emitSymbol(closeEntry, close)
    }

    // ------------------------------------------------------- 矩阵 / 方程组

    private fun emitMatrix(table: Element, openChar: String, closeChar: String) {
        val rows = elementChildren(table).filter { tagName(it) == "mtr" }
        if (rows.isEmpty()) throw IllegalArgumentException("<mtable> 没有 <mtr> 行")
        val open = openChar.ifEmpty { "(" }
        val close = closeChar.ifEmpty { ")" }

        // hang_open 零宽哨兵：直出路径渲染为空方（对齐 Brailix Unicode 直出）。
        emitBlank()
        rows.forEachIndexed { idx, row ->
            if (idx > 0) emitBlank() // 行间 line_break 哨兵
            emitDelim(open)
            inMatrixCell = true
            emitRow(row)
            inMatrixCell = false
            emitDelim(close)
        }
        emitBlank() // hang_close 哨兵
    }

    /** 方程组：cases_open 哨兵 + 三段调色板 + 逐行 cases 段前缀 + 空方 + cases_close 哨兵。 */
    private fun emitCases(table: Element) {
        val rows = elementChildren(table).filter { tagName(it) == "mtr" }
        if (rows.isEmpty()) throw IllegalArgumentException("方程组 <mtable> 没有 <mtr> 行")

        emitBlank() // cases_open 哨兵
        structure("cases.first")
        structure("cases.middle")
        structure("cases.last")

        rows.forEachIndexed { idx, row ->
            if (idx > 0) emitBlank()
            val segment = when {
                idx == 0 -> "cases.first"
                idx == rows.lastIndex -> "cases.last"
                else -> "cases.middle"
            }
            structure(segment)
            emitBlank()
            emitRow(row)
        }
        emitBlank() // cases_close 哨兵
    }

    private fun emitRow(row: Element) {
        var first = true
        for (td in elementChildren(row)) {
            if (tagName(td) != "mtd") continue
            if (!first) {
                emitBlank()
                breakLetterRun() // 相邻单元字母不共享 class 前缀
            }
            first = false
            emitMrow(td)
        }
    }

    private fun emitDelim(ch: String) {
        val entry = symbolEntry(ch[0])
            ?: throw IllegalArgumentException("界符无表项：'$ch'")
        emitSymbol(entry, ch)
    }

    // ----------------------------------------------------------- 重音

    private fun emitAccent(elem: Element) {
        val kids = elementChildren(elem)
        val base = kids.getOrNull(0)
        val under: Element?
        val over: Element?
        when (tagName(elem)) {
            "munder" -> { under = kids.getOrNull(1); over = null }
            "mover" -> { under = null; over = kids.getOrNull(1) }
            else -> { under = kids.getOrNull(1); over = kids.getOrNull(2) }
        }
        if (base != null) visit(base)
        val multi = baseLetterCount(base) >= 2
        if (under != null) {
            structure("accent.under")
            emitAccentChar(under, multi)
        }
        if (over != null) {
            structure("accent.over")
            emitAccentChar(over, multi)
        }
        needNumberSign = true
    }

    private fun emitAccentChar(node: Element, multi: Boolean) {
        val text = leafText(node)
        if (text.isEmpty()) return
        val ch = text[0]
        val entry = symbolEntry(ch)
        val kind = entry?.accentMark
        if (kind != null) {
            val form = if (multi) "double" else "single"
            structure("accent.mark.$kind.$form")
            return
        }
        if (entry != null) {
            emitCells(entry.cells, "math_accent", text)
            return
        }
        when {
            MathTables.greekLower[ch] != null ->
                emitClassLetter(ch, "greek_lower", MathTables.greekLower.getValue(ch))
            MathTables.greekUpper[ch] != null ->
                emitClassLetter(ch, "greek_upper", MathTables.greekUpper.getValue(ch))
            MathTables.lettersLower[ch] != null ->
                emitClassLetter(ch, "latin_lower", MathTables.lettersLower.getValue(ch))
            MathTables.lettersUpper[ch] != null ->
                emitClassLetter(ch, "latin_upper", MathTables.lettersUpper.getValue(ch))
            else -> throw IllegalArgumentException("重音符无表项：'$text'")
        }
    }

    /** 基数里可识别字母数（判定单/双字母重音符形）。 */
    private fun baseLetterCount(base: Element?): Int {
        if (base == null) return 0
        val text = base.textContent ?: ""
        return text.count {
            MathTables.greekLower[it] != null || MathTables.greekUpper[it] != null ||
                MathTables.lettersLower[it] != null || MathTables.lettersUpper[it] != null
        }
    }

    // ------------------------------------------------------------------ 判定

    /** 剥掉单子级 mrow 透明包装；返回标签名。 */
    private fun peelMrow(elem: Element?): Element? {
        var cur = elem
        while (cur != null && tagName(cur) == "mrow") {
            val kids = elementChildren(cur)
            if (kids.size != 1) return cur
            cur = kids[0]
        }
        return cur
    }

    private fun isSelfFenced(elem: Element?): Boolean {
        val inner = peelMrow(elem) ?: return false
        if (tagName(inner) !in SINGLE_STRUCTURE_TAGS) return false
        if (tagName(inner) in setOf("mn", "mi") && leafText(inner).isEmpty()) return false
        return true
    }

    private fun isSingleDigitMn(elem: Element?): Boolean {
        if (elem == null || tagName(elem) != "mn" || elementChildren(elem).isNotEmpty()) return false
        val text = leafText(elem)
        return text.length == 1 && text[0].isDigit()
    }

    private fun isDigitsMn(elem: Element?): Boolean {
        if (elem == null || tagName(elem) != "mn" || elementChildren(elem).isNotEmpty()) return false
        val text = leafText(elem)
        return text.isNotEmpty() && text.all { it.isDigit() }
    }

    private fun isAtomicMn(elem: Element?): Boolean {
        if (elem == null || tagName(elem) != "mn" || elementChildren(elem).isNotEmpty()) return false
        return leafText(elem).isNotEmpty()
    }

    private fun isFence(elem: Element?, charset: Set<String>): Boolean =
        elem != null && tagName(elem) == "mo" && moText(elem) in charset

    private fun isEmptyMo(elem: Element): Boolean =
        tagName(elem) == "mo" && moText(elem).isEmpty()

    private fun moText(elem: Element): String = (elem.textContent ?: "").trim()

    // ------------------------------------------------------------------ 出方

    private fun emit(mask: Int, role: String?, source: String?) {
        out.add(mask and 0x3F)
        roles.add(role)
        sources.add(source)
    }

    private fun emitBlank() = emit(0, "space", null)

    private fun emitCells(cells: List<IntArray>, role: String?, source: String?) {
        for (cell in cells) emit(cell[0], role, source)
    }

    private fun emitArray(arr: IntArray, role: String?, source: String?) {
        emit(arr[0], role, source)
    }

    private fun structure(name: String) {
        val cells = MathTables.structures[name] ?: return
        breakLetterRun()
        emitCells(cells, "math_structure", name)
    }

    private fun breakLetterRun() {
        letterRunClass = null
    }

    private fun lastMask(): Int = out.lastOrNull() ?: 0

    private fun previousSuppressesSpaceBefore(): Boolean {
        val lastRole = roles.lastOrNull()
        if (lastRole in UNARY_CONTEXT_ROLES) return true
        if (lastRole == "math_delim" && sources.lastOrNull() in OPEN_DELIMS) return true
        return false
    }

    /** 表内减号规范字符为 U+2212；ASCII 连字符/全角短横作为别名解析，避免直输 `-` 报无表项。 */
    private fun symbolEntry(ch: Char): MathSymbolEntry? =
        MathTables.symbols[ch] ?: if (ch == '-') MathTables.symbols['\u2212'] else null

    private fun roleFor(role: String?): String? = when (role) {
        "op" -> "math_op"
        "rel" -> "math_rel"
        "delim" -> "math_delim"
        "punct" -> "math_punct"
        "shape" -> "math_shape"
        "big_op" -> "math_big_op"
        "accent" -> "math_accent"
        else -> "math_op"
    }

    private companion object {
        val NUMBER_BREAKING_ROLES = setOf("op", "rel", "shape", "big_op", "delim", "punct")
        val SINGLE_STRUCTURE_TAGS = setOf(
            "mn", "mi", "msqrt", "mroot", "mfrac",
            "msub", "msup", "msubsup", "munder", "mover", "munderover",
        )
    }
}

private fun tagName(elem: Element): String =
    elem.tagName.substringAfter(':').lowercase()

private fun leafText(elem: Element): String =
    (elem.textContent ?: "").trim()

private fun elementChildren(elem: Element): List<Element> {
    val kids = mutableListOf<Element>()
    val nodeList = elem.childNodes
    for (i in 0 until nodeList.length) {
        val node = nodeList.item(i)
        if (node is Element) kids.add(node)
    }
    return kids
}
