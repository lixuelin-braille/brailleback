/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 *
 * 文件导入通道的可测纯逻辑：UTF-8（兼容 BOM / CRLF）解码、大小上限、空内容判定与
 * 公式类型嗅探。不含 Android / SAF 依赖，便于 JVM 单测；Activity 只负责打开 Uri /
 * 文件、读字节后交回本对象解码。
 */

package com.linxi.brailleback.math

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CharsetDecoder
import java.nio.charset.StandardCharsets

/** 公式类型：MathML 标记片段 / 受控线性写法。 */
internal enum class FormulaKind {
    /** 以 `<` 开头的 MathML 整段或片段。 */
    MATHML,

    /** 非标记开头的受控线性写法。 */
    LINEAR,

    /** 空内容。 */
    EMPTY,
}

/** 文件导入结果；与 UI 解耦，失败原因可读、不抛到界面层。 */
internal sealed interface ImportOutcome {
    /** 解码成功：[text] 已去 BOM、归一换行、首尾空白；[kind] 为嗅探结果。 */
    data class Ok(val text: String, val kind: FormulaKind) : ImportOutcome

    /** 0 字节或全空白。 */
    data object Empty : ImportOutcome

    /** 超过大小上限，整份拒绝读取（不截断，避免半条 MathML 标签）。 */
    data object TooLarge : ImportOutcome

    /** 解码 / 读取失败，[reason] 为可读原因。 */
    data class Failed(val reason: String) : ImportOutcome
}

internal object MathFileImport {

    /** 单份公式文件上限 256 KB；超限整份拒绝。 */
    const val MAX_FILE_BYTES: Int = 256 * 1024

    private const val BOM = '\uFEFF'

    private fun strictDecoder(): CharsetDecoder = StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
        .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)

    /**
     * 按字节解码：先查大小上限，再严格 UTF-8 解码（BOM 剥离、CRLF/CR 归一为 LF），
     * 最后嗅探类型。字节流来自 SAF Uri 或 DEBUG 钩子的本地文件。
     */
    fun decode(bytes: ByteArray): ImportOutcome {
        if (bytes.size > MAX_FILE_BYTES) return ImportOutcome.TooLarge
        val text = try {
            strictDecoder().decode(ByteBuffer.wrap(bytes)).toString()
        } catch (e: CharacterCodingException) {
            return ImportOutcome.Failed("UTF-8 解码失败：${e.message ?: "非法字节序列"}")
        }
        return fromText(text)
    }

    /** 纯文本入口：剥离 BOM、归一换行、判空并嗅探类型。 */
    fun fromText(raw: String): ImportOutcome {
        var text = raw
        if (text.startsWith(BOM)) text = text.substring(1)
        text = text.replace("\r\n", "\n").replace("\r", "\n")
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return ImportOutcome.Empty
        return ImportOutcome.Ok(trimmed, classify(trimmed))
    }

    /**
     * 类型嗅探规则（与 [MathBraille.encode] 既有分派保持等价，20 条 golden 不回退）：
     * 首个非空白字符为 `<` → MathML（整段 `<math>…</math>` 或裸标签片段）；
     * 否则 → 线性写法。线性比较式不以 `<` 开头；两者同时像 `<...>` 形态时，
     * 以「开头是 `<`」为准判 MathML。
     */
    fun classify(text: String): FormulaKind {
        val t = text.trim()
        return if (t.startsWith("<")) FormulaKind.MATHML else FormulaKind.LINEAR
    }
}
