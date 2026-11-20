/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 */

package com.googlecode.eyesfree.brailleback

import android.text.Spanned
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.lang.reflect.Array as ReflectArray

/**
 * 内容指纹。
 *
 * 指纹决定了「框架重复下发的同一帧能否被丢掉」，所以它必须对每一项会影响显示的
 * 输入都敏感：漏掉任何一项，点显器就会停在旧位置不更新；多算任何一项（比如
 * 无关字段），重复帧就丢不掉、白白闪屏。
 */
class DisplayContentFingerprintTest {

    private fun content(text: String?) = Content(text)

    @Test
    fun `完全相同的两帧指纹相等`() {
        val a = content("你好盲文")
        val b = content("你好盲文")
        assertEquals(contentFingerprint(a), contentFingerprint(b))
    }

    @Test
    fun `文本不同指纹不同`() {
        assertNotEquals(
            contentFingerprint(content("第一行")),
            contentFingerprint(content("第二行")),
        )
    }

    @Test
    fun `平移策略变化指纹不同`() {
        // 同一个文本，只是 PAN_KEEP 变成 PAN_RESET，也必须重画。
        assertNotEquals(
            contentFingerprint(content("同一文本").setPanStrategy(Content.PAN_KEEP)),
            contentFingerprint(content("同一文本").setPanStrategy(Content.PAN_RESET)),
        )
    }

    @Test
    fun `收缩模式变化指纹不同`() {
        assertNotEquals(
            contentFingerprint(content("省略号abc")),
            contentFingerprint(
                content("省略号abc").setContractionMode(Content.CONTRACT_ALWAYS_ALLOW)
            ),
        )
    }

    @Test
    fun `排版标志变化指纹不同`() {
        val base = content("段落")
        val variants = listOf(
            base.setSplitParagraphs(true),
            content("段落").setParagraphIndent(true),
            content("段落").setFirstParagraphIndent(true),
            content("段落").setHeading(true),
        )
        val baseFingerprint = contentFingerprint(content("段落"))
        for (variant in variants) {
            assertNotEquals(
                "分段/缩进/首行缩进/标题都会改变换行结果，漏掉就会重复丢帧",
                baseFingerprint,
                contentFingerprint(variant),
            )
        }
    }

    @Test
    fun `可编辑标志变化指纹不同`() {
        assertNotEquals(
            contentFingerprint(content("abc")),
            contentFingerprint(content("abc").setEditable(true)),
        )
    }

    @Test
    fun `标记区间变化指纹不同`() {
        // 焦点在同一行内移动一格，文本没变，但光标位置必须跟着挪。
        // android.text.SpannableString 在 JVM 单测里是桩，所以这里用最小实现，
        // 只提供指纹真正会调用到的那几个方法。
        val focusAtThree = Content(
            MarkerText("光标在这里移动", listOf(DisplaySpans.FocusSpan() to (2 to 3)))
        )
        val focusAtFive = Content(
            MarkerText("光标在这里移动", listOf(DisplaySpans.FocusSpan() to (4 to 5)))
        )
        assertNotEquals(
            "焦点区间变了必须重画，否则盲文停在旧光标位置",
            contentFingerprint(focusAtThree),
            contentFingerprint(focusAtFive),
        )
    }

    @Test
    fun `同一区间不同标记类型指纹不同`() {
        // 选区与焦点都在 2..3，但语义不同，不能算同一帧。
        val selection = Content(
            MarkerText("光标在这里移动", listOf(DisplaySpans.SelectionSpan() to (2 to 3)))
        )
        val focus = Content(
            MarkerText("光标在这里移动", listOf(DisplaySpans.FocusSpan() to (2 to 3)))
        )
        assertNotEquals(contentFingerprint(selection), contentFingerprint(focus))
    }

    @Test
    fun `没有标记时文本相同指纹相同`() {
        val plain = MarkerText("无标记文本", emptyList())
        assertEquals(
            contentFingerprint(Content(plain)),
            contentFingerprint(Content(MarkerText("无标记文本", emptyList()))),
        )
    }

    /** 只实现指纹会用到的方法：length / getSpans / getSpanStart / getSpanEnd。 */
    private class MarkerText(
        private val text: String,
        private val markers: List<Pair<Any, Pair<Int, Int>>>,
    ) : Spanned {

        override val length: Int get() = text.length

        override fun get(index: Int): Char = text[index]

        override fun subSequence(startIndex: Int, endIndex: Int): CharSequence =
            text.subSequence(startIndex, endIndex)

        override fun toString(): String = text

        @Suppress("UNCHECKED_CAST")
        override fun <T> getSpans(start: Int, end: Int, type: Class<T>): Array<T> {
            val hits = markers.filter { (marker, range) ->
                type.isInstance(marker) && range.first < end && range.second > start
            }
            val array = ReflectArray.newInstance(type, hits.size) as Array<T>
            hits.forEachIndexed { index, (marker, _) -> array[index] = type.cast(marker) }
            return array
        }

        override fun getSpanStart(tag: Any?): Int =
            markers.firstOrNull { it.first === tag }?.second?.first ?: -1

        override fun getSpanEnd(tag: Any?): Int =
            markers.firstOrNull { it.first === tag }?.second?.second ?: -1

        override fun getSpanFlags(tag: Any?): Int = 0

        override fun nextSpanTransition(start: Int, limit: Int, type: Class<*>?): Int = limit
    }

    @Test
    fun `空文本指纹为空串`() {
        assertEquals("", contentFingerprint(content(null)))
    }
}
