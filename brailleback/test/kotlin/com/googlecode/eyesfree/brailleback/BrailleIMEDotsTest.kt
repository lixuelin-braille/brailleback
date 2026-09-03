/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 */

package com.googlecode.eyesfree.brailleback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * 检测 liblouis 未定义点位回译标记（utils.c _lou_unknownDots，
 * 形如 "\4568/"）的正则防护测试。
 */
@RunWith(JUnit4::class)
class BrailleIMEDotsTest {

    @Test
    fun unknownDotsMarker_detected() {
        assertTrue(hasUnknownDots("\\4568/"))
        assertTrue(hasUnknownDots("\\0/"))
        assertTrue(hasUnknownDots("\\12/"))
        assertTrue(hasUnknownDots("abc\\456/def")) // 标记混在正文中
    }

    @Test
    fun normalText_notDetected() {
        assertFalse(hasUnknownDots("hello"))
        assertFalse(hasUnknownDots("你好世界"))
        assertFalse(hasUnknownDots(""))
        assertFalse(hasUnknownDots("a\\b")) // 孤立反斜杠不是标记
    }

    @Test
    fun invalidDotNumbers_notDetected() {
        // 点位编号只能是 1-8（0 表示空点位）；9 不是合法点位
        assertFalse(hasUnknownDots("\\9/"))
        assertFalse(hasUnknownDots("\\/"))
    }

    @Test
    fun nullText_notDetected() {
        // backTranslate 返回可空，检测函数须安全处理 null
        assertFalse(hasUnknownDots(null as CharSequence?))
    }
}
