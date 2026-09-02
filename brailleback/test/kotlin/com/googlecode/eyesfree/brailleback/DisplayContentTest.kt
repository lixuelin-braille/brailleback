package com.googlecode.eyesfree.brailleback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DisplayManager.Content 状态语义测试（迁移到 Kotlin 后的安全网）。
 *
 * Content 是显示内容分发的载体：文本、焦点节点、平移策略、缩写模式、
 * 分段与可编辑标志。DisplayManager 双线程模型都围绕它工作，
 * 这里验证纯状态行为（不涉及节点实例，避免触碰 stub 类）。
 */
class DisplayContentTest {

    @Test
    fun `default content has neutral state`() {
        val content = DisplayManager.Content()
        assertNull(content.getText())
        assertNull(content.getSpanned())
        assertNull(content.getFirstNode())
        assertNull(content.getLastNode())
        assertEquals(DisplayManager.Content.PAN_RESET, content.getPanStrategy())
        assertEquals(DisplayManager.Content.CONTRACT_DEFAULT, content.getContractionMode())
        assertFalse(content.isSplitParagraphs())
        assertFalse(content.isEditable())
    }

    @Test
    fun `setText is fluent and round-trips`() {
        val content = DisplayManager.Content()
        assertSame(content, content.setText("你好"))
        assertEquals("你好", content.getText())
    }

    @Test
    fun `shortcut constructor sets text`() {
        val content = DisplayManager.Content("盲文")
        assertEquals("盲文", content.getText())
    }

    @Test
    fun `non-string CharSequence is preserved as-is`() {
        val sb = StringBuilder("可变字符序列")
        val content = DisplayManager.Content(sb)
        assertSame(sb, content.getText())
        assertEquals("可变字符序列", content.getText().toString())
    }

    @Test
    fun `flags round-trip`() {
        val content = DisplayManager.Content("x")
        assertSame(content, content.setSplitParagraphs(true))
        assertTrue(content.isSplitParagraphs())
        assertSame(content, content.setEditable(true))
        assertTrue(content.isEditable())
        assertSame(content, content.setPanStrategy(DisplayManager.Content.PAN_CURSOR))
        assertEquals(DisplayManager.Content.PAN_CURSOR, content.getPanStrategy())
        assertSame(content, content.setContractionMode(DisplayManager.Content.CONTRACT_ALWAYS_ALLOW))
        assertEquals(DisplayManager.Content.CONTRACT_ALWAYS_ALLOW, content.getContractionMode())
    }

    @Test
    fun `pan strategy constants are stable`() {
        // 这些常量被 WrapStrategy / DisplayManager 双线程读取，
        // 不得在迁移中悄悄变化。
        assertEquals(0, DisplayManager.Content.PAN_RESET)
        assertEquals(1, DisplayManager.Content.PAN_CURSOR)
        assertEquals(2, DisplayManager.Content.PAN_KEEP)
        assertEquals(0, DisplayManager.Content.CONTRACT_DEFAULT)
        assertEquals(1, DisplayManager.Content.CONTRACT_ALWAYS_ALLOW)
    }
}
