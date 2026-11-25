/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 */

package com.linxi.brailleback.reader

import com.googlecode.eyesfree.braille.display.BrailleInputEvent
import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderDisplayKeyTest {

    @Test
    fun panKeysStepOneLine() {
        assertEquals(ReaderDisplayAction.PREV_LINE,
            ReaderDisplayKey.resolve(BrailleInputEvent.CMD_NAV_PAN_LEFT))
        assertEquals(ReaderDisplayAction.NEXT_LINE,
            ReaderDisplayKey.resolve(BrailleInputEvent.CMD_NAV_PAN_RIGHT))
    }

    @Test
    fun lineKeysTurnOneScreen() {
        assertEquals(ReaderDisplayAction.PREV_PAGE,
            ReaderDisplayKey.resolve(BrailleInputEvent.CMD_NAV_LINE_PREVIOUS))
        assertEquals(ReaderDisplayAction.NEXT_PAGE,
            ReaderDisplayKey.resolve(BrailleInputEvent.CMD_NAV_LINE_NEXT))
    }

    @Test
    fun topAndBottomJumpToFirstAndLastScreen() {
        assertEquals(ReaderDisplayAction.FIRST_PAGE,
            ReaderDisplayKey.resolve(BrailleInputEvent.CMD_NAV_TOP))
        assertEquals(ReaderDisplayAction.LAST_PAGE,
            ReaderDisplayKey.resolve(BrailleInputEvent.CMD_NAV_BOTTOM))
    }

    @Test
    fun enterAnnouncesPosition() {
        assertEquals(ReaderDisplayAction.ANNOUNCE_PAGE,
            ReaderDisplayKey.resolve(BrailleInputEvent.CMD_KEY_ENTER))
    }

    @Test
    fun unrelatedKeysAreNotSwallowed() {
        assertEquals(ReaderDisplayAction.NONE,
            ReaderDisplayKey.resolve(BrailleInputEvent.CMD_BRAILLE_KEY))
        assertEquals(ReaderDisplayAction.NONE,
            ReaderDisplayKey.resolve(BrailleInputEvent.CMD_NAV_WORD_NEXT))
        // 未定义/负数命令码同样放过，不崩。
        assertEquals(ReaderDisplayAction.NONE, ReaderDisplayKey.resolve(0))
        assertEquals(ReaderDisplayAction.NONE, ReaderDisplayKey.resolve(-1))
    }
}
