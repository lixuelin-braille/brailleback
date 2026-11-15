/*
 * Copyright (C) 2026 The BrailleBack Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package com.googlecode.eyesfree.brailleback

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DefaultNavigationMode 拆分后的结构护栏。
 *
 * 2026-11-15：DefaultNavigationMode.kt 从 1302 行降到 937 行。滚动与可见性
 * 整块（autoScrollItem、scrollAtEdge、collectVisibleNodes、updateAfterScroll、
 * chooseVisibleTarget 等 15 个函数）搬到 DefaultNavigationModeScrolling.kt，
 * 以同包扩展函数承载，方法体一字未改。
 *
 * 这个测试防三件事：
 * 1) 主文件不许长回 1200 行以上；
 * 2) 搬走的函数不许在主文件里出现第二份（两份实现必然漂移）；
 * 3) 快照类与滚动常量不许搬回主文件。
 */
class DefaultNavigationModeSplitContractTest {

    private fun projectFile(path: String): File {
        val candidates = listOf(File(path), File("..", path), File("../..", path))
        return candidates.firstOrNull { it.isFile }
            ?: error("找不到 $path（cwd=${System.getProperty("user.dir")}）")
    }

    private val dir = "brailleback/src/com/googlecode/eyesfree/brailleback/"
    private fun src(name: String) = projectFile(dir + name).readText()

    private fun nonBlank(source: String) =
        source.lineSequence().count { it.isNotBlank() }

    @Test
    fun `主文件低于 950 非空行`() {
        val lines = nonBlank(src("DefaultNavigationMode.kt"))
        // 拆分前 1302 行（非空约 1210）。拆后 871 非空行，留一点余量。
        assertTrue("DefaultNavigationMode.kt 非空行数 $lines 已超出拆分预算", lines < 950)
    }

    @Test
    fun `滚动与可见性已搬进扩展函数文件`() {
        val scrolling = src("DefaultNavigationModeScrolling.kt")
        for (signature in listOf(
            "internal fun DefaultNavigationMode.autoScrollItem(",
            "internal fun DefaultNavigationMode.scrollAtEdge(",
            "internal fun DefaultNavigationMode.collectVisibleNodes(",
            "internal fun DefaultNavigationMode.collectVisibleNodesInternal(",
            "internal fun DefaultNavigationMode.recycleSnapshots(",
            "internal fun DefaultNavigationMode.boundsOf(",
            "internal fun DefaultNavigationMode.updateAfterScroll(",
            "internal fun DefaultNavigationMode.chooseVisibleTarget(",
            "internal fun DefaultNavigationMode.rememberScrollContainer(",
        )) {
            assertTrue("$signature 必须留在 DefaultNavigationModeScrolling.kt", scrolling.contains(signature))
        }
        // 走的是扩展函数，不是「持有导航器引用的类」：后者要把每个 mXxx
        // 改写成 navigator.mXxx，方法体被大面积改写，风险不成比例。
        assertFalse(
            "不许改成持有导航器引用的包装类",
            scrolling.contains("class DefaultNavigationModeScrolling"),
        )
    }

    @Test
    fun `搬走的函数不许抄回主文件`() {
        val main = src("DefaultNavigationMode.kt")
        for (signature in listOf(
            "private fun autoScrollItem(",
            "private fun scrollAtEdge(",
            "private fun collectVisibleNodes(",
            "private fun collectVisibleNodesInternal(",
            "private fun updateAfterScroll(",
            "private fun chooseVisibleTarget(",
            "private fun rememberScrollContainer(",
        )) {
            assertFalse("$signature 已搬走，不许在主文件里出现第二份", main.contains(signature))
        }
    }

    @Test
    fun `快照类与滚动常量也搬走了`() {
        val main = src("DefaultNavigationMode.kt")
        val scrolling = src("DefaultNavigationModeScrolling.kt")
        assertFalse("NodeSnapshot 应只在滚动文件里声明", main.contains("class NodeSnapshot("))
        assertTrue("NodeSnapshot 必须声明在滚动文件", scrolling.contains("internal class NodeSnapshot("))
        assertFalse(
            "MAX_VISIBLE_NODE_DEPTH 应只在滚动文件里",
            main.contains("MAX_VISIBLE_NODE_DEPTH ="),
        )
        assertTrue(
            "滚动常量必须留在滚动文件",
            scrolling.contains("SCROLL_UPDATE_DELAYS_MS = longArrayOf("),
        )
    }

    @Test
    fun `扩展函数文件自己也不许长成新的大文件`() {
        val lines = nonBlank(src("DefaultNavigationModeScrolling.kt"))
        assertTrue("DefaultNavigationModeScrolling.kt 非空行数 $lines 过大，该再按职责拆", lines < 500)
    }
}
