/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 *
 * 受控线性写法 → MathML DOM 的最小解析器。
 *
 * 支持的线性子集（与 MathML 通道同一套编码规则）：
 * - 标识符字母 / 数字；已登记函数名（sin、cos、log…）按函数名识别；
 *   标识符后紧跟括号按函数调用解析（实参逗号分隔，生成等价 MathML 树）；
 * - 二元运算符 + - = < > ≤ ≥ ± × ÷ · 与逗号、小数点；
 * - 括号 ( ) [ ] { }；根号 √ 作用于紧随的一个因子；线性别名 `sqrt`（大小写不敏感）
 *   与 √ 共用同一 msqrt 节点，支持 `sqrt(...)` 与 `sqrt x` 两种形态；
 * - 上标 ^、下标 _；分数 /。
 *
 * 不支持：分数横线以外的分式排版、矩阵、上下划线、重音、希腊字母直输、
 *   多行公式；遇到无法识别的字符直接抛 [IllegalArgumentException]。
 * 输入为 `<` 开头时走 MathML 解析，不经过本解析器。
 */

package com.linxi.brailleback.math

import org.w3c.dom.Document
import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory

/**
 * 全角 / Unicode 变体符号归一（线性与 MathML 两条通道共用）。
 *
 * 全角 ASCII（U+FF01–FF5E：数字、字母、括号、＋－＝／ 等）整段减 0xFEE0 归半角；
 * 之前只列了标点，全角**数字与字母**漏在外面，中文输入法全角模式下打出的
 * `１／２` 里的 `１２` 会原样进编码器。另收两种同形变体：∕（U+2215）、−（U+2212）。
 */
internal object MathFullwidth {
    private const val FULLWIDTH_ASCII_START = '\uFF01'
    private const val FULLWIDTH_ASCII_END = '\uFF5E'
    private const val FULLWIDTH_TO_ASCII_OFFSET = 0xFEE0

    /** 全角 ASCII 之外、中文输入法常见的同形变体。 */
    private val variants = mapOf('∕' to '/', '−' to '-')

    val replacements: Map<Char, Char> = buildMap {
        for (c in FULLWIDTH_ASCII_START..FULLWIDTH_ASCII_END) {
            put(c, c - FULLWIDTH_TO_ASCII_OFFSET)
        }
        putAll(variants)
    }

    fun normalize(text: String): String =
        buildString(text.length) {
            text.forEach { append(replacements[it] ?: it) }
        }
}

internal object LinearMathParser {

    fun parse(input: String): Element {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument()
        val tokens = tokenize(input)
        val parser = Parser(tokens, doc)
        val row = parser.parseRow()
        // 之前不看剩余记号：`(x))` 多打的右括号、`1/2)` 这类会被悄悄丢掉。
        parser.ensureConsumed()
        val math = doc.createElement("math")
        math.appendChild(row)
        return math
    }

    private sealed class Tok {
        data class Ident(val text: String) : Tok()
        data class Number(val text: String) : Tok()
        data class Op(val text: String) : Tok()
        data class LParen(val text: String) : Tok()
        data class RParen(val text: String) : Tok()
        data class LBracket(val text: String) : Tok()
        data class RBracket(val text: String) : Tok()
        data class LBrace(val text: String) : Tok()
        data class RBrace(val text: String) : Tok()
        object Sqrt : Tok()
        object Caret : Tok()
        object Underscore : Tok()
        object Slash : Tok()
    }

    /** 中文输入法常把半角符号转成全角/Unicode 变体；先归一到线性子集识别的字符。 */
    private fun normalize(input: String): String = MathFullwidth.normalize(input)

    private fun tokenize(input: String): List<Tok> {
        val out = mutableListOf<Tok>()
        val text = normalize(input)
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            when {
                ch.isWhitespace() -> i++
                ch.isLetter() -> {
                    var j = i
                    while (j < text.length && text[j].isLetter()) j++
                    out.add(Tok.Ident(text.substring(i, j)))
                    i = j
                }
                ch.isDigit() -> {
                    var j = i
                    while (j < text.length && text[j].isDigit()) j++
                    out.add(Tok.Number(text.substring(i, j)))
                    i = j
                }
                ch == '√' -> { out.add(Tok.Sqrt); i++ }
                ch == '^' -> { out.add(Tok.Caret); i++ }
                ch == '_' -> { out.add(Tok.Underscore); i++ }
                ch == '/' -> { out.add(Tok.Slash); i++ }
                ch == '(' -> { out.add(Tok.LParen("(")); i++ }
                ch == ')' -> { out.add(Tok.RParen(")")); i++ }
                ch == '[' -> { out.add(Tok.LBracket("[")); i++ }
                ch == ']' -> { out.add(Tok.RBracket("]")); i++ }
                ch == '{' -> { out.add(Tok.LBrace("{")); i++ }
                ch == '}' -> { out.add(Tok.RBrace("}")); i++ }
                ch in "+-=<>≤≥±×÷·.," -> {
                    out.add(Tok.Op(ch.toString())); i++
                }
                else -> throw IllegalArgumentException("线性写法含无法识别的字符：'$ch'")
            }
        }
        return out
    }

    private class Parser(private val tokens: List<Tok>, private val doc: Document) {
        private var pos = 0

        private fun peek(): Tok? = tokens.getOrNull(pos)
        private fun next(): Tok = tokens[pos++]

        fun parseRow(): Element = parseRow(stopOnComma = false)

        /** 整条表达式读完后再有剩余记号，就是多打了东西，不能悄悄丢掉。 */
        fun ensureConsumed() {
            val left = peek() ?: return
            throw IllegalArgumentException("公式到这里已经完整，后面多出了${describe(left)}")
        }

        /**
         * 解析一行表达式；[stopOnComma] 为 true 时在逗号处断停（函数实参列表用），
         * 逗号留给上层实参循环消费，不包进当前表达式的 mrow。
         */
        private fun parseRow(stopOnComma: Boolean): Element {
            val children = mutableListOf<Element>()
            var left = parseScript()
            while (peek() is Tok.Slash) {
                next()
                val right = parseScript()
                val frac = doc.createElement("mfrac")
                frac.appendChild(left)
                frac.appendChild(right)
                left = frac
            }
            children.add(left)
            while (peek() is Tok.Op) {
                val opTok = peek() as Tok.Op
                if (stopOnComma && opTok.text == ",") break
                next()
                val mo = doc.createElement("mo")
                mo.textContent = opTok.text
                children.add(mo)
                children.add(parseScript())
            }
            if (children.size == 1) return children.first()
            val row = doc.createElement("mrow")
            children.forEach { row.appendChild(it) }
            return row
        }

        private fun parseScript(): Element {
            var base = parseFactor()
            while (peek() is Tok.Caret || peek() is Tok.Underscore) {
                val kind = next()
                val child = parseFactor()
                val tag = if (kind is Tok.Caret) "msup" else "msub"
                val node = doc.createElement(tag)
                node.appendChild(base)
                node.appendChild(child)
                base = node
            }
            return base
        }

        private fun parseFactor(): Element = when (val t = peek()) {
            is Tok.LParen -> {
                next()
                val inner = parseRow()
                expectClose(")")
                fenced("(", ")", inner)
            }
            is Tok.LBracket -> {
                next()
                val inner = parseRow()
                expectClose("]")
                fenced("[", "]", inner)
            }
            is Tok.LBrace -> {
                next()
                val inner = parseRow()
                expectClose("}")
                fenced("{", "}", inner)
            }
            is Tok.Sqrt -> {
                next()
                val root = doc.createElement("msqrt")
                root.appendChild(parseFactor())
                root
            }
            is Tok.Number -> {
                next()
                leaf("mn", t.text)
            }
            is Tok.Ident -> {
                next()
                when {
                    t.text.equals("sqrt", ignoreCase = true) -> parseSqrt()
                    peek() is Tok.LParen -> parseFunctionCall(t.text)
                    // 已登记函数名走 mi 函数路径；未登记的多字母词也进 mi，由编码器逐字母回退。
                    else -> leaf("mi", t.text)
                }
            }
            // 说人话：读屏用户听到「Op(text=-)」无从判断怎么改。
            else -> throw IllegalArgumentException(unexpectedFactor(t))
        }

        /**
         * 此处本该是数字、字母、括号或根号，却遇到了别的（或什么都没有）。
         * 常见于连续两个运算符（`1+-`）、`=` 后面直接结束、括号没配对。
         */
        private fun unexpectedFactor(t: Tok?): String {
            if (t == null) return "公式不完整：结尾缺少数字或字母"
            if (t is Tok.Op) return "运算符“${t.text}”前后缺少操作数"
            return "此处不该出现${describe(t)}，请检查是否多打了符号或括号没配对"
        }

        /** 记号的人话名称，用于错误播报。 */
        private fun describe(t: Tok): String = when (t) {
            is Tok.Op -> "运算符“${t.text}”"
            is Tok.LParen -> "左括号“(”"
            is Tok.RParen -> "右括号“)”"
            is Tok.LBracket -> "左方括号“[”"
            is Tok.RBracket -> "右方括号“]”"
            is Tok.LBrace -> "左花括号“{”"
            is Tok.RBrace -> "右花括号“}”"
            is Tok.Ident -> "字母“${t.text}”"
            is Tok.Number -> "数字“${t.text}”"
            Tok.Sqrt -> "根号"
            Tok.Caret -> "上标符号“^”"
            Tok.Underscore -> "下标符号“_”"
            Tok.Slash -> "除号“/”"
        }

        /**
         * `sqrt` 线性别名（大小写不敏感），与 `√`/`<msqrt>` 共用同一根号节点。
         * 支持 `sqrt(...)` 与 `sqrt x` 两种形态；括号只作分组：包单个原子因子时
         * 不保留界符，包复合表达式（含运算符）时保留括号，对齐 Brailix 根号口径。
         */
        private fun parseSqrt(): Element {
            val body: Element = if (peek() is Tok.LParen) {
                next()
                val inner = parseRow()
                expectClose(")")
                if (isAtomicFactor(inner)) inner else fenced("(", ")", inner)
            } else {
                parseFactor()
            }
            val root = doc.createElement("msqrt")
            root.appendChild(body)
            return root
        }

        /** 标识符后紧跟括号：按函数调用解析，实参逗号分隔，生成等价 MathML 树。 */
        private fun parseFunctionCall(name: String): Element {
            next() // 消费 LParen
            if (peek() is Tok.RParen) {
                throw IllegalArgumentException("函数调用 '$name' 实参为空")
            }
            val args = mutableListOf<Element>()
            args.add(parseRow(stopOnComma = true))
            while (peek() is Tok.Op && (peek() as Tok.Op).text == ",") {
                next()
                if (peek() is Tok.RParen) {
                    throw IllegalArgumentException("函数调用 '$name' 逗号后缺实参")
                }
                args.add(parseRow(stopOnComma = true))
            }
            expectClose(")")
            val call = doc.createElement("mrow")
            call.appendChild(leaf("mi", name))
            val fenced = doc.createElement("mfenced")
            args.forEach { fenced.appendChild(it) }
            call.appendChild(fenced)
            return call
        }

        /** 分组括号可省略的原子因子：单个数字 / 字母 / 上下标 / 根号 / 已围栏表达式。 */
        private fun isAtomicFactor(el: Element): Boolean {
            val tag = el.tagName.substringAfter(':').lowercase()
            return tag in setOf(
                "mn", "mi", "msub", "msup", "msubsup", "mroot", "msqrt", "mfenced",
            )
        }

        private fun fenced(open: String, close: String, inner: Element): Element {
            val node = doc.createElement("mfenced")
            node.setAttribute("open", open)
            node.setAttribute("close", close)
            node.appendChild(inner)
            return node
        }

        private fun leaf(tag: String, text: String): Element {
            val el = doc.createElement(tag)
            el.textContent = text
            return el
        }

        private fun expectClose(char: String) {
            val tok = peek()
            val match = when (char) {
                ")" -> tok is Tok.RParen
                "]" -> tok is Tok.RBracket
                "}" -> tok is Tok.RBrace
                else -> false
            }
            if (!match) throw IllegalArgumentException("括号不匹配，期望 '$char'，实际 $tok")
            next()
        }
    }
}
