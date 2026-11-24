/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 *
 * 文件导入通道纯逻辑单测：类型嗅探（线性 / MathML 片段 / 整段 math / 空内容）、
 * BOM 剥离、CRLF 归一、超限拒绝、非法 UTF-8 错误映射。SAF / UI 不在单测范围。
 */

package com.linxi.brailleback.math

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MathFileImportTest {

    @Test
    fun `线性公式嗅探为 LINEAR`() {
        val outcome = MathFileImport.fromText("a^2+b^2=c^2")
        assertTrue(outcome is ImportOutcome.Ok)
        val ok = outcome as ImportOutcome.Ok
        assertEquals(FormulaKind.LINEAR, ok.kind)
        assertEquals("a^2+b^2=c^2", ok.text)
    }

    @Test
    fun `裸 MathML 标签片段嗅探为 MATHML`() {
        val outcome = MathFileImport.fromText("<mrow><mi>x</mi></mrow>")
        val ok = outcome as ImportOutcome.Ok
        assertEquals(FormulaKind.MATHML, ok.kind)
    }

    @Test
    fun `整段 math 文档嗅探为 MATHML`() {
        val mathml = "<math><msqrt><mn>2</mn></msqrt></math>"
        assertEquals(FormulaKind.MATHML, MathFileImport.classify(mathml))
        val ok = MathFileImport.fromText(mathml) as ImportOutcome.Ok
        assertEquals(mathml, ok.text)
    }

    @Test
    fun `空串与全空白判为 Empty`() {
        assertTrue(MathFileImport.fromText("") is ImportOutcome.Empty)
        assertTrue(MathFileImport.fromText(" \t\r\n ") is ImportOutcome.Empty)
    }

    @Test
    fun `BOM 被剥离且不影响类型判定`() {
        val outcome = MathFileImport.fromText("\uFEFF<math><mi>x</mi></math>")
        val ok = outcome as ImportOutcome.Ok
        assertFalse(ok.text.startsWith("\uFEFF"))
        assertEquals("<math><mi>x</mi></math>", ok.text)
        assertEquals(FormulaKind.MATHML, ok.kind)
    }

    @Test
    fun `CRLF 与单独 CR 归一为 LF`() {
        val outcome = MathFileImport.fromText(
            "<math>\r\n<mi>x</mi>\r\n</math>\r",
        )
        val ok = outcome as ImportOutcome.Ok
        assertFalse(ok.text.contains("\r"))
        assertTrue(ok.text.contains("\n"))
        assertEquals(FormulaKind.MATHML, ok.kind)
    }

    @Test
    fun `超过 256KB 整份拒绝不截断`() {
        val tooMany = ByteArray(MathFileImport.MAX_FILE_BYTES + 1)
        assertTrue(MathFileImport.decode(tooMany) is ImportOutcome.TooLarge)
    }

    @Test
    fun `上限内合法字节解码成功`() {
        val bytes = "sqrt(2)".toByteArray(Charsets.UTF_8)
        val ok = MathFileImport.decode(bytes) as ImportOutcome.Ok
        assertEquals(FormulaKind.LINEAR, ok.kind)
    }

    @Test
    fun `非法 UTF-8 字节映射为 Failed 不抛异常`() {
        val outcome = MathFileImport.decode(byteArrayOf(0xFF.toByte(), 0xFE.toByte()))
        assertTrue(outcome is ImportOutcome.Failed)
        assertTrue((outcome as ImportOutcome.Failed).reason.isNotEmpty())
    }
}
