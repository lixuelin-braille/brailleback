/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 *
 * 中文数学盲文通道（工作流 A）。
 *
 * 边界：
 * - 本包只做数学公式 → 盲文点位；中文 <mtext> 不自带中文字表，通过可注入的
 *   [ChineseBrailleProvider] 走项目既有 TranslatorService / BrailleSegmenter 链路
 *   （Android 实现在 Activity 层注入，JVM 单测注入假实现）。
 * - 不做公式 OCR、不读图片公式；输入只接受 MathML 文本或受控线性写法。
 * - 表源为 Brailix cn_current（《中国盲文》6 点），纯 Kotlin 常量，运行期不依赖 Python。
 */

package com.linxi.brailleback.math

import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory

/** 一段数学公式的编码结果。 */
data class MathBrailleResult(
    /** U+2800..U+28FF 盲文字符串，空方为 U+2800 字间隔。 */
    val unicode: String,
    /** 每方一个字节的点位掩码；dot1=bit0 … dot8=bit7，与既有 displayDots 契约一致。 */
    val cells: IntArray,
    /** 6 或 8；8 点设备复用同一套 6 点点位，第 7/8 点保持 0。 */
    val dotsPerCell: Int,
)

/**
 * 中文（自然语言）盲文提供者接口。
 *
 * 数学公式里的 `<mtext>`（如 `\text{三角形}`）是自然语言文本，不在数学符号表内；
 * Android 侧用 TranslatorService 桥接实现，JVM 单测用假实现替换，核心编码不碰 Android。
 */
fun interface ChineseBrailleProvider {
    /** 把一段自然语言文本翻成盲文 Unicode 串；不可用时返回空串（调用方按未知字符处理）。 */
    fun translateToUnicode(text: String): String
}

/** 编码入口。 */
object MathBraille {

    /**
     * 编码一段公式。
     *
     * @param input MathML 文本（`<math>…</math>`）或受控线性写法（见 [LinearMathParser]）。
     * @param dotsPerCell 6 或 8；6 点下高位必须为 0，8 点结构预留第 7/8 点。
     * @param chineseProvider `<mtext>` 中文翻译提供者；null 时 mtext 走数学表逐字符回退。
     * @throws IllegalArgumentException 含不支持的 MathML 标签、无表项的字符，或 6 点高位非 0。
     */
    fun encode(
        input: String,
        dotsPerCell: Int = 6,
        chineseProvider: ChineseBrailleProvider? = null,
    ): MathBrailleResult {
        require(dotsPerCell == 6 || dotsPerCell == 8) { "dotsPerCell 只支持 6 或 8：$dotsPerCell" }
        val trimmed = input.trim()
        val root: Element = if (trimmed.startsWith("<")) {
            val mathml = parseMathml(trimmed)
            normalizeTextNodes(mathml)
            mathml
        } else {
            LinearMathParser.parse(trimmed)
        }
        val encoder = MathEncoder(dotsPerCell, chineseProvider)
        return encoder.encode(root)
    }

    private fun parseMathml(text: String): Element {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = false
        factory.isValidating = false
        val builder = factory.newDocumentBuilder()
        val doc = builder.parse(org.xml.sax.InputSource(StringReader(text)))
        val root: Node = doc.documentElement
            ?: throw IllegalArgumentException("MathML 根节点为空")
        return root as Element
    }

    /** 只归一文本节点里的全角/Unicode 变体符号；标签名与属性保持原样。 */
    private fun normalizeTextNodes(node: Node) {
        val children = node.childNodes
        for (i in 0 until children.length) {
            val child = children.item(i) ?: continue
            if (child.nodeType == Node.TEXT_NODE) {
                child.nodeValue = MathFullwidth.normalize(child.nodeValue ?: "")
            } else {
                normalizeTextNodes(child)
            }
        }
    }
}
