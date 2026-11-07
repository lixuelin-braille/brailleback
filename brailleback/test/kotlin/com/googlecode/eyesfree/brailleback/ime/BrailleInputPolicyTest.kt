/*
 * 盲文输入统一层的路由契约测试。
 *
 * 这类分支判断一旦写反，后果是「退格吃掉上屏键」「候选键吃掉导航键」，
 * 而且读屏用户只能从语音反馈里察觉，极难定位。所以每个分支都单独锁死，
 * 并配套变异测试（见本次工作记录）：改动任一常量或比较符都必须有测试变红。
 */
package com.googlecode.eyesfree.brailleback.ime

import com.googlecode.eyesfree.brailleback.ime.BrailleInputPolicy.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BrailleInputPolicyTest {

    @Test
    fun dotMask_eachDotNumberMapsToBitNumberMinusOne() {
        val expected = listOf(0x01, 0x02, 0x04, 0x08, 0x10, 0x20, 0x40, 0x80)
        expected.forEachIndexed { index, mask ->
            assertEquals(listOf(index + 1), BrailleInputPolicy.dotMaskToDotNumbers(mask))
        }
    }

    @Test
    fun dotMask_combinedDotsAndEmptyCell() {
        assertEquals(listOf(1, 3), BrailleInputPolicy.dotMaskToDotNumbers(0x05))
        assertEquals(listOf(1, 2, 3, 4, 5, 6, 7, 8), BrailleInputPolicy.dotMaskToDotNumbers(0xFF))
        assertEquals(emptyList<Int>(), BrailleInputPolicy.dotMaskToDotNumbers(0))
    }

    @Test
    fun routeDots_emptyCellIsCommitNotSpace() {
        assertEquals(
            BrailleInputPolicy.Route(Action.COMMIT),
            BrailleInputPolicy.routeDots(BrailleInputPolicy.EMPTY_CELL),
        )
    }

    @Test
    fun routeDots_dot7DeletesAndDot8Enters() {
        assertEquals(Action.DELETE, BrailleInputPolicy.routeDots(BrailleInputPolicy.DOT7).action)
        assertEquals(Action.ENTER, BrailleInputPolicy.routeDots(BrailleInputPolicy.DOT8).action)
    }

    @Test
    fun routeDots_normalCellsAppendWithOriginalMask() {
        for (mask in listOf(0x01, 0x12, 0x36, 0x7F, 0xFF)) {
            assertEquals(
                BrailleInputPolicy.Route(Action.APPEND_CELL, mask),
                BrailleInputPolicy.routeDots(mask),
            )
        }
    }

    @Test
    fun routeDots_outOfRangeMasksStayOnLegacyPath() {
        for (mask in listOf(-1, 0x100, 0x1FF, Int.MAX_VALUE, Int.MIN_VALUE)) {
            assertEquals(Action.LEGACY, BrailleInputPolicy.routeDots(mask).action)
        }
    }

    @Test
    fun route_whenUnifiedDisabledEveryCommandIsLegacy() {
        for (command in listOf(
            BrailleInputPolicy.CMD_NAV_LINE_PREVIOUS,
            BrailleInputPolicy.CMD_NAV_LINE_NEXT,
            BrailleInputPolicy.CMD_BRAILLE_KEY,
            BrailleInputPolicy.CMD_KEY_ENTER,
            BrailleInputPolicy.CMD_KEY_DEL,
            BrailleInputPolicy.CMD_KEY_FORWARD_DEL,
        )) {
            assertEquals(
                Action.LEGACY,
                BrailleInputPolicy.route(command, 0x01, unified = false).action,
            )
        }
    }

    @Test
    fun route_brailleKeyUsesSameDotMapping() {
        assertEquals(
            Action.COMMIT,
            BrailleInputPolicy.route(BrailleInputPolicy.CMD_BRAILLE_KEY, 0, unified = true).action,
        )
        assertEquals(
            Action.DELETE,
            BrailleInputPolicy.route(BrailleInputPolicy.CMD_BRAILLE_KEY, 0x40, unified = true).action,
        )
        assertEquals(
            Action.ENTER,
            BrailleInputPolicy.route(BrailleInputPolicy.CMD_BRAILLE_KEY, 0x80, unified = true).action,
        )
        assertEquals(
            BrailleInputPolicy.Route(Action.APPEND_CELL, 0x15),
            BrailleInputPolicy.route(BrailleInputPolicy.CMD_BRAILLE_KEY, 0x15, unified = true),
        )
        assertEquals(
            Action.LEGACY,
            BrailleInputPolicy.route(BrailleInputPolicy.CMD_BRAILLE_KEY, 0x100, unified = true).action,
        )
    }

    @Test
    fun route_editKeysDeleteEnterAndLeaveForwardDeleteAlone() {
        assertEquals(
            Action.DELETE,
            BrailleInputPolicy.route(BrailleInputPolicy.CMD_KEY_DEL, 0, unified = true).action,
        )
        assertEquals(
            Action.ENTER,
            BrailleInputPolicy.route(BrailleInputPolicy.CMD_KEY_ENTER, 0, unified = true).action,
        )
        assertEquals(
            Action.LEGACY,
            BrailleInputPolicy.route(BrailleInputPolicy.CMD_KEY_FORWARD_DEL, 0, unified = true).action,
        )
    }

    @Test
    fun route_navigationKeysBecomeCandidateSwitchWithoutCellPayload() {
        val previous = BrailleInputPolicy.route(
            BrailleInputPolicy.CMD_NAV_LINE_PREVIOUS, 0, unified = true,
        )
        val next = BrailleInputPolicy.route(
            BrailleInputPolicy.CMD_NAV_LINE_NEXT, 0, unified = true,
        )
        assertEquals(Action.PREVIOUS_CANDIDATE, previous.action)
        assertEquals(Action.NEXT_CANDIDATE, next.action)
        assertEquals(0, previous.cell)
        assertEquals(0, next.cell)
    }

    @Test
    fun route_unknownCommandsStayOnLegacyPath() {
        for (command in listOf(-1, 3, 20, 50, 90, 999)) {
            assertEquals(
                Action.LEGACY,
                BrailleInputPolicy.route(command, 0x01, unified = true).action,
            )
        }
    }

    @Test
    fun route_everyLegacyRouteCarriesNoCell() {
        val legacy = BrailleInputPolicy.route(999, 0x7F, unified = true)
        assertTrue(legacy.cell == 0)
        assertEquals(Action.LEGACY, legacy.action)
    }

    @Test
    fun routeDots_eightDotKeepsDot7AndDot8AsRealCells() {
        // 八点电脑盲文里 7/8 点是正常点位，绝不能被借作退格/回车。
        for (mask in listOf(0x40, 0x80, 0xC0, 0xFF)) {
            assertEquals(
                BrailleInputPolicy.Route(Action.APPEND_CELL, mask),
                BrailleInputPolicy.routeDots(mask, eightDot = true),
            )
        }
    }

    @Test
    fun routeDots_eightDotEmptyCellIsNotCommit() {
        assertEquals(Action.LEGACY, BrailleInputPolicy.routeDots(0, eightDot = true).action)
    }

    @Test
    fun route_eightDotFlagIsPassedThroughToDotMapping() {
        assertEquals(
            BrailleInputPolicy.Route(Action.APPEND_CELL, BrailleInputPolicy.DOT8),
            BrailleInputPolicy.route(
                BrailleInputPolicy.CMD_BRAILLE_KEY,
                BrailleInputPolicy.DOT8,
                unified = true,
                eightDot = true,
            ),
        )
        // 六点表仍按原语义解释为回车，两个分支都要锁死。
        assertEquals(
            Action.ENTER,
            BrailleInputPolicy.route(
                BrailleInputPolicy.CMD_BRAILLE_KEY,
                BrailleInputPolicy.DOT8,
                unified = true,
                eightDot = false,
            ).action,
        )
    }
}
