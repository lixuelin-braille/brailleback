/*
 * Copyright (C) 2026 The BrailleBack Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package com.googlecode.eyesfree.braille.service.display

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DisplayService 拆分后的结构护栏。
 *
 * 2026-11-15：DisplayService.kt 从 1763 行降到 1321 行。协议判定、连接入口、
 * 自动重连与发现窗口整块搬到 DisplayServiceConnectionFlow.kt，以同包扩展函数
 * 承载（方法体一字未改，只把「类成员」改成「DisplayService 的扩展」）。
 *
 * 这个测试防两件事：
 * 1) 主文件不许再长回 1400 行——那正是这次拆分的阈值；
 * 2) 搬走的策略不许被抄回主文件：两份实现必然漂移，且没人知道哪份在跑。
 */
class DisplayServiceSplitContractTest {

    private fun projectFile(path: String): File {
        val candidates = listOf(File(path), File("..", path), File("../..", path))
        return candidates.firstOrNull { it.isFile }
            ?: error("找不到 $path（cwd=${System.getProperty("user.dir")}）")
    }

    private val dir = "service/src/com/googlecode/eyesfree/braille/service/display/"
    private fun src(name: String) = projectFile(dir + name).readText()

    private fun nonBlank(source: String) =
        source.lineSequence().count { it.isNotBlank() }

    @Test
    fun `主文件回到 1400 行以下且不再回涨`() {
        val lines = nonBlank(src("DisplayService.kt"))
        // 拆分前 1763 行（非空约 1680）。拆到 1321 行后留一点余量，
        // 一旦有人把新逻辑堆回来，先在这里断。
        assertTrue("DisplayService.kt 非空行数 $lines 已超出拆分预算", lines < 1300)
    }

    @Test
    fun `连接编排已搬进扩展函数文件`() {
        val flow = src("DisplayServiceConnectionFlow.kt")
        for (signature in listOf(
            "internal fun DisplayService.getConnectionProtocol(",
            "internal fun DisplayService.isUsbProtocol(",
            "internal fun DisplayService.autoConnectBraille(",
            "internal fun DisplayService.shouldAutoReconnect(",
            "internal fun DisplayService.startDiscoveryWindow(",
            "internal fun DisplayService.stopDiscoverySession(",
            "internal fun DisplayService.handleDiscoveredDevice(",
        )) {
            assertTrue("$signature 必须留在 DisplayServiceConnectionFlow.kt", flow.contains(signature))
        }
        // 走的是扩展函数而不是「持有 service 引用的类」：后者要把每个
        // mXxx 改成 service.mXxx，方法体被大面积改写，风险不成比例。
        assertFalse(
            "不许改成持有 service 引用的包装类",
            flow.contains("class DisplayServiceConnectionFlow"),
        )
    }

    @Test
    fun `搬走的策略不许抄回主文件`() {
        val main = src("DisplayService.kt")
        for (signature in listOf(
            "private fun startDiscoveryWindow(",
            "fun startDiscoveryWindow(",
            "private fun shouldStartDiscovery(",
            "private fun shouldAutoReconnect(",
            "private fun autoConnectBraille(",
            "private fun getConnectionProtocol(",
        )) {
            assertFalse("$signature 已搬走，不许在主文件里出现第二份", main.contains(signature))
        }
    }

    @Test
    fun `扩展函数文件自己也不许长成新的大文件`() {
        val lines = nonBlank(src("DisplayServiceConnectionFlow.kt"))
        assertTrue("DisplayServiceConnectionFlow.kt 非空行数 $lines 过大，该再按职责拆", lines < 600)
    }
}
