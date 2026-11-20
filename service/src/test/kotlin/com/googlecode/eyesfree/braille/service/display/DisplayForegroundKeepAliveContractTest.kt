/*
 * Copyright (C) 2026 The BrailleBack Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.googlecode.eyesfree.braille.service.display

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 前台保活的结构护栏。
 *
 * 四个约束，任何一个被改掉都会让「无障碍服务没开时退出界面即断连」复发：
 * 1) DisplayService 在清单里声明 connectedDevice 前台类型；
 * 2) 连接成功后提升前台（onDisplayConnected → promoteDisplayForeground）；
 * 3) onUnbind 在保活生效时不许断开；
 * 4) 断开路径撤销前台状态（disconnectBraille → dropDisplayForeground）。
 */
class DisplayForegroundKeepAliveContractTest {

    private fun projectFile(path: String): File {
        val candidates = listOf(File(path), File("..", path), File("../..", path))
        return candidates.firstOrNull { it.isFile }
                ?: error("找不到 $path（cwd=${System.getProperty("user.dir")}）")
    }

    private val dir = "service/src/main/java/com/googlecode/eyesfree/braille/service/display/"
    private fun src(name: String) = projectFile(dir + name).readText()

    private fun nonBlank(source: String) =
            source.lineSequence().count { it.isNotBlank() }

    @Test
    fun `DisplayService 在清单里声明 connectedDevice 前台类型`() {
        val manifest = projectFile("brailleback/src/main/AndroidManifest.xml").readText()
        val start = manifest.indexOf(
                "com.googlecode.eyesfree.braille.service.display.DisplayService")
        assertTrue("清单里找不到 DisplayService 声明", start >= 0)
        val block = manifest.substring(start, minOf(manifest.length, start + 400))
        assertTrue(
                "DisplayService 缺少 android:foregroundServiceType=\"connectedDevice\"",
                block.contains("foregroundServiceType=\"connectedDevice\""),
        )
    }

    @Test
    fun `连接成功提升前台断开撤销`() {
        val main = src("DisplayService.kt")
        assertTrue(
                "onDisplayConnected 必须调用 promoteDisplayForeground",
                main.contains("promoteDisplayForeground()"),
        )
        assertTrue(
                "disconnectBraille 必须调用 dropDisplayForeground",
                main.contains("dropDisplayForeground()"),
        )
        assertTrue(
                "onStartCommand 必须补 startForeground",
                main.contains("postDisplayKeepAliveNotification()"),
        )
    }

    @Test
    fun `保活生效时 onUnbind 不许断开`() {
        val main = src("DisplayService.kt")
        assertTrue(
                "onUnbind 必须先看 mDisplayKeepAliveActive",
                main.contains("!mDisplayKeepAliveActive"),
        )
    }

    @Test
    fun `保活实现单独成文件且不再膨胀`() {
        val keepAlive = src("DisplayForegroundKeepAlive.kt")
        assertTrue(
                "缺少 promoteDisplayForeground",
                keepAlive.contains("internal fun DisplayService.promoteDisplayForeground("),
        )
        assertTrue(
                "缺少 dropDisplayForeground",
                keepAlive.contains("internal fun DisplayService.dropDisplayForeground("),
        )
        assertFalse(
                "不许把保活逻辑抄回主文件",
                src("DisplayService.kt").contains("KEEP_ALIVE_NOTIFICATION_ID"),
        )
        val lines = nonBlank(keepAlive)
        assertTrue("保活文件非空行数 $lines 过大，该再拆", lines < 200)
    }
}
