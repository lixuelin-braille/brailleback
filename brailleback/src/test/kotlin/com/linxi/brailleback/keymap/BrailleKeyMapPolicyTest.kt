/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 */

package com.linxi.brailleback.keymap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 自定义按键映射纯逻辑层的行为测试。
 *
 * 变异检验（mutation testing）要求见项目约定：本测试的每个分支断言都对应
 * `BrailleKeyMapPolicy` 中一处可被单独改坏的判断（白名单过滤、自映射过滤、
 * 单层解析、排序、删除语义）；改动任一处都应让至少一个用例变红。
 */
class BrailleKeyMapPolicyTest {

    private companion object {
        const val PAN_LEFT = "CMD_NAV_PAN_LEFT"
        const val PAN_RIGHT = "CMD_NAV_PAN_RIGHT"
        const val ITEM_NEXT = "CMD_NAV_ITEM_NEXT"
        const val ITEM_PREVIOUS = "CMD_NAV_ITEM_PREVIOUS"
        const val LINE_NEXT = "CMD_NAV_LINE_NEXT"
        const val GLOBAL_BACK = "CMD_GLOBAL_BACK"
        const val ROUTE = "CMD_ROUTE"
        const val BRAILLE_KEY = "CMD_BRAILLE_KEY"
    }

    // ---------------------------------------------------------------- 白名单

    @Test
    fun whitelistSizesAndNoDuplicates() {
        assertEquals(24, BrailleKeyMapPolicy.REMAPPABLE_COMMANDS.size)
        // 24 条可改按键 + 5 条系统动作 + 1 条虚拟「无动作」。
        assertEquals(30, BrailleKeyMapPolicy.ASSIGNABLE_ACTIONS.size)
        assertEquals(
            BrailleKeyMapPolicy.REMAPPABLE_COMMANDS.size,
            BrailleKeyMapPolicy.REMAPPABLE_COMMANDS.toSet().size
        )
        assertEquals(
            BrailleKeyMapPolicy.ASSIGNABLE_ACTIONS.size,
            BrailleKeyMapPolicy.ASSIGNABLE_ACTIONS.toSet().size
        )
    }

    @Test
    fun globalCommandsAreTargetsButNotSources() {
        assertTrue(BrailleKeyMapPolicy.isAction(GLOBAL_BACK))
        assertFalse(BrailleKeyMapPolicy.isRemappable(GLOBAL_BACK))
        assertTrue(BrailleKeyMapPolicy.isAssignable(GLOBAL_BACK))
    }

    @Test
    fun commandsWithArgumentsAreRejectedOnBothSides() {
        // 位置参数命令重映射后 DisplayManager 会做错误的坐标换算。
        assertFalse(BrailleKeyMapPolicy.isRemappable(ROUTE))
        assertFalse(BrailleKeyMapPolicy.isAction(ROUTE))
        assertFalse(BrailleKeyMapPolicy.isRemappable("CMD_LONG_PRESS_ROUTE"))
        assertFalse(BrailleKeyMapPolicy.isAction("CMD_SELECTION_START"))
        // 点位参数命令改走别的动作会毁掉盲文输入。
        assertFalse(BrailleKeyMapPolicy.isRemappable(BRAILLE_KEY))
        assertFalse(BrailleKeyMapPolicy.isAction(BRAILLE_KEY))
    }

    @Test
    fun defaultActionIsAssignableButNotARealAction() {
        assertTrue(BrailleKeyMapPolicy.isAssignable(BrailleKeyMapPolicy.DEFAULT_ACTION))
        assertFalse(BrailleKeyMapPolicy.isAction(BrailleKeyMapPolicy.DEFAULT_ACTION))
        assertFalse(BrailleKeyMapPolicy.isRemappable(BrailleKeyMapPolicy.DEFAULT_ACTION))
    }

    // ------------------------------------------------------------------ parse

    @Test
    fun parseEmptyInputReturnsEmptyMap() {
        assertTrue(BrailleKeyMapPolicy.parse(null).isEmpty())
        assertTrue(BrailleKeyMapPolicy.parse("").isEmpty())
        assertTrue(BrailleKeyMapPolicy.parse("   ").isEmpty())
    }

    @Test
    fun parseValidEntriesAndTrimsWhitespace() {
        val parsed = BrailleKeyMapPolicy.parse(
            " $PAN_LEFT = $GLOBAL_BACK ; $ITEM_NEXT = $LINE_NEXT "
        )
        assertEquals(mapOf(PAN_LEFT to GLOBAL_BACK, ITEM_NEXT to LINE_NEXT), parsed)
    }

    @Test
    fun parseDropsMalformedAndIllegalEntries() {
        val raw = listOf(
            "$PAN_LEFT=$GLOBAL_BACK",     // 合法
            "no separator",                // 无等号
            "=CMD_GLOBAL_BACK",            // 空源
            "$ITEM_NEXT=",                 // 空目标
            "$GLOBAL_BACK=$ITEM_NEXT",     // 源是系统独占命令
            "$ITEM_PREVIOUS=$ROUTE",       // 目标是带位置参数命令
            "$ITEM_PREVIOUS=$ITEM_PREVIOUS", // 自映射无意义
            "$PAN_LEFT=$BRAILLE_KEY",      // 目标是点位输入命令
            "UNKNOWN_CMD=$LINE_NEXT",      // 不在白名单
        ).joinToString(";")
        assertEquals(mapOf(PAN_LEFT to GLOBAL_BACK), BrailleKeyMapPolicy.parse(raw))
    }

    @Test
    fun parseLaterDuplicateSourceWins() {
        val parsed = BrailleKeyMapPolicy.parse(
            "$PAN_LEFT=$ITEM_NEXT;$PAN_LEFT=$GLOBAL_BACK"
        )
        assertEquals(mapOf(PAN_LEFT to GLOBAL_BACK), parsed)
    }

    @Test
    fun parseIsCaseSensitive() {
        assertTrue(BrailleKeyMapPolicy.parse("cmd_nav_pan_left=$GLOBAL_BACK").isEmpty())
    }

    // -------------------------------------------------------------- serialize

    @Test
    fun serializeIsStableSortedAndDropsIllegalEntries() {
        // 故意用与字母序相反的插入顺序：去掉 sortedBy 后输出顺序会翻转。
        val map = mapOf(
            PAN_LEFT to ITEM_NEXT,
            LINE_NEXT to GLOBAL_BACK,
            GLOBAL_BACK to ITEM_NEXT,  // 非法源，应被丢弃
            ITEM_NEXT to ROUTE,        // 非法目标，应被丢弃
            ITEM_PREVIOUS to ITEM_PREVIOUS, // 自映射，应被丢弃
        )
        // 按源命令名字母序输出：CMD_NAV_LINE_NEXT 在 CMD_NAV_PAN_LEFT 之前。
        assertEquals(
            "$LINE_NEXT=$GLOBAL_BACK;$PAN_LEFT=$ITEM_NEXT",
            BrailleKeyMapPolicy.serialize(map)
        )
    }

    @Test
    fun parseSerializeRoundTrip() {
        val original = mapOf(
            PAN_LEFT to GLOBAL_BACK,
            ITEM_NEXT to LINE_NEXT,
            "CMD_KEY_DEL" to "CMD_GLOBAL_HOME",
        )
        assertEquals(original, BrailleKeyMapPolicy.parse(BrailleKeyMapPolicy.serialize(original)))
    }

    @Test
    fun serializeEmptyMapIsEmptyString() {
        assertEquals("", BrailleKeyMapPolicy.serialize(emptyMap()))
    }

    // ------------------------------------------------------------ withMapping

    @Test
    fun withMappingAddsAndOverwrites() {
        val first = BrailleKeyMapPolicy.withMapping(emptyMap(), PAN_LEFT, ITEM_NEXT)
        assertEquals(mapOf(PAN_LEFT to ITEM_NEXT), first)
        val second = BrailleKeyMapPolicy.withMapping(first, PAN_LEFT, GLOBAL_BACK)
        assertEquals(mapOf(PAN_LEFT to GLOBAL_BACK), second)
        // 入参表不被修改（不可变语义）
        assertEquals(mapOf(PAN_LEFT to ITEM_NEXT), first)
    }

    @Test
    fun withMappingDefaultOrIdentityRemovesEntry() {
        val original = mapOf(PAN_LEFT to ITEM_NEXT, ITEM_NEXT to GLOBAL_BACK)
        assertEquals(
            mapOf(ITEM_NEXT to GLOBAL_BACK),
            BrailleKeyMapPolicy.withMapping(original, PAN_LEFT, BrailleKeyMapPolicy.DEFAULT_ACTION)
        )
        assertEquals(
            mapOf(ITEM_NEXT to GLOBAL_BACK),
            BrailleKeyMapPolicy.withMapping(original, PAN_LEFT, PAN_LEFT)
        )
    }

    @Test
    fun withMappingRejectsIllegalInputWithoutThrowing() {
        val original = mapOf(PAN_LEFT to ITEM_NEXT)
        assertEquals(
            original,
            BrailleKeyMapPolicy.withMapping(original, GLOBAL_BACK, ITEM_NEXT)
        )
        assertEquals(
            original,
            BrailleKeyMapPolicy.withMapping(original, PAN_LEFT, ROUTE)
        )
        assertEquals(
            original,
            BrailleKeyMapPolicy.withMapping(original, PAN_LEFT, "NOT_A_COMMAND")
        )
    }

    // ---------------------------------------------------------- resolvedTarget

    @Test
    fun resolvedTargetFallsBackToDefault() {
        assertEquals(ITEM_NEXT, BrailleKeyMapPolicy.resolvedTarget(emptyMap(), ITEM_NEXT))
    }

    @Test
    fun resolvedTargetIsSingleLevelAndDoesNotChain() {
        val map = mapOf(PAN_LEFT to ITEM_NEXT, ITEM_NEXT to LINE_NEXT)
        assertEquals(ITEM_NEXT, BrailleKeyMapPolicy.resolvedTarget(map, PAN_LEFT))
        assertEquals(LINE_NEXT, BrailleKeyMapPolicy.resolvedTarget(map, ITEM_NEXT))
    }

    // -------------------------------------------------------- duplicateTargets

    @Test
    fun duplicateTargetsListsOnlySharedTargets() {
        val map = mapOf(
            PAN_LEFT to GLOBAL_BACK,
            ITEM_NEXT to GLOBAL_BACK,
            ITEM_PREVIOUS to LINE_NEXT,
        )
        val duplicates = BrailleKeyMapPolicy.duplicateTargets(map)
        assertEquals(1, duplicates.size)
        assertEquals(listOf(ITEM_NEXT, PAN_LEFT), duplicates[GLOBAL_BACK])
    }

    @Test
    fun duplicateTargetsIgnoresIllegalEntries() {
        val map = mapOf(
            PAN_LEFT to GLOBAL_BACK,
            ROUTE to GLOBAL_BACK,
            ITEM_NEXT to ROUTE,
        )
        assertTrue(BrailleKeyMapPolicy.duplicateTargets(map).isEmpty())
    }

    // ----------------------------------------------------------- 虚拟「无动作」

    @Test
    fun noActionIsAssignableButNeverASource() {
        assertTrue(BrailleKeyMapPolicy.isAssignable(BrailleKeyMapPolicy.NO_ACTION))
        assertTrue(BrailleKeyMapPolicy.isAction(BrailleKeyMapPolicy.NO_ACTION))
        assertFalse(BrailleKeyMapPolicy.isRemappable(BrailleKeyMapPolicy.NO_ACTION))
        assertEquals("NONE", BrailleKeyMapPolicy.NO_ACTION)
    }

    @Test
    fun noActionRoundTripsThroughPersistence() {
        // 用户把「向左平移」（V3 的滚平键）设为无动作，必须能存下来、读回来。
        val map = BrailleKeyMapPolicy.withMapping(
            emptyMap(), PAN_LEFT, BrailleKeyMapPolicy.NO_ACTION
        )
        assertEquals(BrailleKeyMapPolicy.NO_ACTION, map[PAN_LEFT])
        val raw = BrailleKeyMapPolicy.serialize(map)
        assertEquals("$PAN_LEFT=NONE", raw)
        assertEquals(map, BrailleKeyMapPolicy.parse(raw))
    }

    @Test
    fun noActionCanBeRevertedByDefault() {
        // 「保持默认」必须能撤销「无动作」，否则用户被自己的设置锁死。
        val mapped = mapOf(PAN_LEFT to BrailleKeyMapPolicy.NO_ACTION)
        val reverted = BrailleKeyMapPolicy.withMapping(
            mapped, PAN_LEFT, BrailleKeyMapPolicy.DEFAULT_ACTION
        )
        assertTrue(reverted.isEmpty())
        assertEquals(PAN_LEFT, BrailleKeyMapPolicy.resolvedTarget(reverted, PAN_LEFT))
    }

    @Test
    fun noActionDoesNotCountAsDuplicateTarget() {
        // 多个键都空转是刻意选择，不该弹「N 组按键被设为同一动作」。
        val map = mapOf(
            PAN_LEFT to BrailleKeyMapPolicy.NO_ACTION,
            PAN_RIGHT to BrailleKeyMapPolicy.NO_ACTION,
            LINE_NEXT to BrailleKeyMapPolicy.NO_ACTION,
        )
        assertTrue(BrailleKeyMapPolicy.duplicateTargets(map).isEmpty())
    }

    @Test
    fun noActionIsAnAcceptableMappingButNotACommand() {
        // NONE 不是真实命令：它能作为目标值存在，但用它当源按键必须被拒绝。
        val original = mapOf(PAN_LEFT to ITEM_NEXT)
        assertEquals(
            original,
            BrailleKeyMapPolicy.withMapping(original, BrailleKeyMapPolicy.NO_ACTION, ITEM_NEXT)
        )
    }
}
