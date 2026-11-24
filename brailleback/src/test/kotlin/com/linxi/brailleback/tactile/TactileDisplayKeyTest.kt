/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 */

package com.linxi.brailleback.tactile

import com.googlecode.eyesfree.braille.display.BrailleInputEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 视口态点显器按键映射单测：锁住命令码，避免改了常量却没人发现。 */
class TactileDisplayKeyTest {

    @Test
    fun panKeysMapToHorizontalMoves() {
        assertEquals(
            TactileDisplayKey.Action.Left,
            TactileDisplayKey.actionFor(BrailleInputEvent.CMD_NAV_PAN_LEFT),
        )
        assertEquals(
            TactileDisplayKey.Action.Right,
            TactileDisplayKey.actionFor(BrailleInputEvent.CMD_NAV_PAN_RIGHT),
        )
    }

    @Test
    fun lineKeysMapToVerticalMoves() {
        assertEquals(
            TactileDisplayKey.Action.Up,
            TactileDisplayKey.actionFor(BrailleInputEvent.CMD_NAV_LINE_PREVIOUS),
        )
        assertEquals(
            TactileDisplayKey.Action.Down,
            TactileDisplayKey.actionFor(BrailleInputEvent.CMD_NAV_LINE_NEXT),
        )
    }

    @Test
    fun enterKeyTogglesLegend() {
        assertEquals(
            TactileDisplayKey.Action.Legend,
            TactileDisplayKey.actionFor(BrailleInputEvent.CMD_KEY_ENTER),
        )
    }

    @Test
    fun unmappedCommandsArePassedThrough() {
        // 打字用的点位键、未知命令一律不接管，不能吞掉点显器的正常输入。
        assertNull(TactileDisplayKey.actionFor(BrailleInputEvent.CMD_NONE))
        assertNull(TactileDisplayKey.actionFor(BrailleInputEvent.CMD_ACTIVATE_CURRENT))
        assertNull(TactileDisplayKey.actionFor(9999))
    }

    @Test
    fun fiveActionsOnly() {
        // 视口只有五个动作：图例 + 四向；多出来说明映射表被改歪了。
        assertEquals(5, TactileDisplayKey.Action.entries.size)
    }
}
