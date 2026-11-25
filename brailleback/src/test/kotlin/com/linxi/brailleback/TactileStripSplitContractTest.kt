/*
 * Copyright (C) 2026 The BrailleBack Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package com.linxi.brailleback

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TactileStripActivity 拆分后的结构护栏。
 *
 * 2026-11-25：TactileStripActivity.kt 从 1306 非空行降到 271 非空行。界面与文案、
 * 选图转换管线、参数档位、三段式翻页、视口导航分别搬到同包 internal 扩展函数文件，
 * 方法体逐字未改；状态仍由 Activity 持有。
 *
 * 这个测试防三件事：
 * 1) 主文件不许长回 800 行以上；
 * 2) 搬走的职责必须落在对应文件，签名不许抄回主文件出现第二份；
 * 3) 拆出的文件自己也不许长成新的大文件。
 */
class TactileStripSplitContractTest {

    private fun projectFile(path: String): File {
        val candidates = listOf(File(path), File("..", path), File("../..", path))
        return candidates.firstOrNull { it.isFile }
            ?: error("找不到 $path（cwd=${System.getProperty("user.dir")}）")
    }

    private val dir = "brailleback/src/main/java/com/linxi/brailleback/tactile/"
    private fun src(name: String) = projectFile(dir + name).readText()

    private fun nonBlank(source: String) =
        source.lineSequence().count { it.isNotBlank() }

    @Test
    fun `主文件低于 800 非空行`() {
        val lines = nonBlank(src("TactileStripActivity.kt"))
        assertTrue("TactileStripActivity.kt 非空行数 $lines 已超出拆分预算", lines < 800)
    }

    @Test
    fun `职责落在对应扩展文件`() {
        val labels = src("TactileStripLabels.kt")
        assertTrue(labels.contains("internal data class StripLabels("))
        assertTrue(labels.contains("internal fun TactileStripActivity.labels()"))

        val screen = src("TactileStripScreen.kt")
        assertTrue(screen.contains("internal fun StripScreen("))
        assertTrue(screen.contains("private fun StripRadioRow("))

        val pipeline = src("TactileStripPipeline.kt")
        for (signature in listOf(
            "internal fun TactileStripActivity.onImagePicked(",
            "internal fun TactileStripActivity.rebuild()",
            "internal fun TactileStripActivity.buildViewport(",
            "internal fun TactileStripActivity.prepareLegend(",
        )) {
            assertTrue("$signature 必须留在 TactileStripPipeline.kt", pipeline.contains(signature))
        }

        val params = src("TactileStripParams.kt")
        for (signature in listOf(
            "internal fun TactileStripActivity.onCellsChange(",
            "internal fun TactileStripActivity.applyDisplayCells(",
            "internal fun TactileStripActivity.onLinesChange(",
        )) {
            assertTrue("$signature 必须留在 TactileStripParams.kt", params.contains(signature))
        }

        val pager = src("TactileStripPagerController.kt")
        assertTrue(pager.contains("internal fun TactileStripActivity.renderCurrent("))
        assertTrue(pager.contains("internal fun TactileStripActivity.goFirst()"))

        val viewport = src("TactileStripViewportController.kt")
        for (signature in listOf(
            "internal fun TactileStripActivity.renderViewport(",
            "internal fun TactileStripActivity.vpNavigate(",
            "internal fun TactileStripActivity.syncDisplayHold()",
            "internal fun TactileStripActivity.buzz()",
        )) {
            assertTrue("$signature 必须留在 TactileStripViewportController.kt", viewport.contains(signature))
        }
    }

    @Test
    fun `搬走的函数不许抄回主文件`() {
        val main = src("TactileStripActivity.kt")
        for (signature in listOf(
            "private fun onImagePicked(",
            "private fun rebuild()",
            "private fun renderCurrent(",
            "private fun renderViewport(",
            "private fun vpNavigate(",
            "private fun syncDisplayHold()",
            "private fun onCellsChange(",
            "private fun applyDisplayCells(",
        )) {
            assertFalse("$signature 已搬走，不许在主文件里出现第二份", main.contains(signature))
        }
        assertFalse("StripLabels 声明应只在 Labels 文件", main.contains("data class StripLabels("))
        assertFalse("StripScreen 应只在 Screen 文件", main.contains("fun StripScreen("))
    }

    @Test
    fun `扩展文件自己也不许长成新的大文件`() {
        for (name in listOf(
            "TactileStripLabels.kt",
            "TactileStripScreen.kt",
            "TactileStripPipeline.kt",
            "TactileStripParams.kt",
            "TactileStripPagerController.kt",
            "TactileStripViewportController.kt",
        )) {
            val lines = nonBlank(src(name))
            assertTrue("$name 非空行数 $lines 过大，该再按职责拆", lines < 500)
        }
    }
}
