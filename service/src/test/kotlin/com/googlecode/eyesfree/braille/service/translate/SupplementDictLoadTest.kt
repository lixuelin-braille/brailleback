package com.googlecode.eyesfree.braille.service.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

/**
 * JiebaDict.loadSupplement 的加载语义：
 * 跳过注释/坏行、不覆盖主词典、重复加载幂等、失败时不抛异常。
 */
class SupplementDictLoadTest {

    private fun dict(): JiebaDict {
        val d = JiebaDict()
        val mini = buildString {
            append("我们 5000\n")
            append("人工 3000\n")
            append("智能 3000\n")
            append("正确 4000\n")
        }
        d.init(ByteArrayInputStream(mini.toByteArray(Charsets.UTF_8)))
        return d
    }

    private fun stream(text: String) = ByteArrayInputStream(text.toByteArray(Charsets.UTF_8))

    @Test
    fun testLoadValidWords() {
        val d = dict()
        val added = d.loadSupplement(stream(
            "# comment\n" +
                "\n" +
                "人工智能\t100\n" +
                "下一步\t100\n" +
                "张伟\t10\n" +
                "bad-line\n" +
                "非法 0\n" +
                "英文abc 10\n"
        ))
        assertEquals(3, added)
        assertTrue(d.isWord("人工智能"))
        assertTrue(d.isWord("下一步"))
        assertTrue(d.isWord("张伟"))
        assertEquals(100, d.freq("人工智能"))
        assertEquals(10, d.freq("张伟"))
        assertFalse(d.isWord("英文abc"))
    }

    @Test
    fun testDoesNotOverrideMainDict() {
        val d = dict()
        val added = d.loadSupplement(stream(
            "我们\t999999\n" +
                "人工\t999999\n"
        ))
        assertEquals(0, added)
        assertEquals(5000, d.freq("我们"))
        assertEquals(3000, d.freq("人工"))
    }

    @Test
    fun testSecondLoadIsIdempotent() {
        val d = dict()
        val text = "下一步\t100\n张伟\t10\n"
        assertEquals(2, d.loadSupplement(stream(text)))
        assertEquals(0, d.loadSupplement(stream(text)))
    }

    @Test
    fun testSupplementAffectsSegmentation() {
        val d = dict()
        val added = d.loadSupplement(stream("人工智能\t100000\n张伟\t100000\n"))
        assertEquals(2, added)
        assertEquals(100000, d.freq("人工智能"))
        val s = BrailleSegmenter(d)
        // 四字词会走 2+2 语节规则，这里只验证 2 字补充词进入分词路径
        assertEquals("张伟", s.segment("张伟").text)
        // 不覆盖主词典：仍按主词典切分
        assertEquals("正确", s.segment("正确").text)
    }

    @Test
    fun testMalformedStreamDoesNotThrow() {
        val d = dict()
        val added = d.loadSupplement(stream("\u0000\u0001\n我们\n    \n#x\n"))
        assertEquals(0, added)
    }

    @Test
    fun testSupplementBeforeInitIsIgnored() {
        val d = JiebaDict()
        assertEquals(0, d.loadSupplement(stream("张伟\t10\n")))
        assertFalse(d.isWord("张伟"))
    }

    @Test
    fun testLoadOverridesReplacesExistingFreq() {
        val d = dict()
        assertEquals(4000, d.freq("正确"))
        val applied = d.loadOverrides(stream(
            "# override\n" +
                "正确\t20000\n" +
                "新词\t10\n"
        ))
        assertEquals(2, applied)
        assertEquals(20000, d.freq("正确"))
        assertTrue(d.isWord("新词"))
        // 补充词库仍然不覆盖主词典/覆盖表
        assertEquals(0, d.loadSupplement(stream("正确\t1\n")))
        assertEquals(20000, d.freq("正确"))
    }

    @Test
    fun testOverridesBeforeInitIsIgnored() {
        val d = JiebaDict()
        assertEquals(0, d.loadOverrides(stream("正确\t20000\n")))
        assertFalse(d.isWord("正确"))
    }
}
