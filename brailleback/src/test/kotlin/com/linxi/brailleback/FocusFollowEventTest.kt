/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 */

package com.linxi.brailleback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 系统无障碍焦点事件该不该跟。
 *
 * 钉住用户真机上摸到的两个现象：
 * 1. 翻页/滚屏时系统把焦点重置到新一屏第一行，跟它会「跳两下」；
 * 2. 盲文导航自己维护逻辑焦点（quiet）时，系统焦点属于别的读屏，抢过来会把
 *    盲文拽回第一行。
 */
class FocusFollowEventTest {

    @Test
    fun `滚动更新未落定时任何模式都不跟`() {
        assertFalse(
                "跟了会先闪一帧错内容，settle 后再闪一帧，摸起来是跳两下",
                shouldFollowFocusEvent(scrollPending = true, quiet = false, hasLogicalFocus = false))
        assertFalse(
                "quiet 模式在滚动期间同样要压住",
                shouldFollowFocusEvent(scrollPending = true, quiet = true, hasLogicalFocus = true))
    }

    @Test
    fun `quiet 模式下已有逻辑焦点就不跟`() {
        assertFalse(
                "系统焦点可能属于别的读屏，抢过来会跳回第一行",
                shouldFollowFocusEvent(scrollPending = false, quiet = true, hasLogicalFocus = true))
    }

    @Test
    fun `quiet 模式下还没有逻辑焦点时跟一次`() {
        assertTrue(
                "刚启用时没有自己的焦点，先跟系统的把屏填上",
                shouldFollowFocusEvent(scrollPending = false, quiet = true, hasLogicalFocus = false))
    }

    @Test
    fun `跟随模式下滚动外正常跟`() {
        assertTrue(
                "非 quiet 且不在滚动，焦点事件必须照常显示",
                shouldFollowFocusEvent(scrollPending = false, quiet = false, hasLogicalFocus = false))
    }
}
