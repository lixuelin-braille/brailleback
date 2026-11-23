/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 *
 * 搜索教程页 Compose 化的静态契约：界面交 Screen、偏好键与启动 flags、
 * 关闭后进入搜索模式的链路不变；旧 XML 已删除。
 */

package com.linxi.brailleback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SearchTutorialContractTest {

    private fun projectFile(path: String): File {
        val candidates = listOf(File(path), File("..", path), File("../..", path))
        return candidates.firstOrNull { it.isFile }
            ?: error("找不到 $path（cwd=${System.getProperty("user.dir")}）")
    }

    private val base = "brailleback/"
    private val activityPath = base + "src/main/java/com/linxi/brailleback/SearchTutorialActivity.kt"
    private val screenPath = base + "src/main/java/com/linxi/brailleback/compose/SearchTutorialScreen.kt"

    @Test
    fun activityIsComposeAndXmlLayoutIsGone() {
        val activity = projectFile(activityPath).readText()
        assertTrue("教程页必须用 setContent 承载界面", activity.contains("setContent {"))
        assertTrue("界面交给 SearchTutorialScreen", activity.contains("SearchTutorialScreen("))
        assertFalse("不该再 setContentView", activity.contains("setContentView"))
        assertFalse("不该再 findViewById", activity.contains("findViewById"))

        val layout = listOf(
            File(base + "src/main/res/layout/search_tutorial_activity.xml"),
            File("..", base + "src/main/res/layout/search_tutorial_activity.xml"),
            File("../..", base + "src/main/res/layout/search_tutorial_activity.xml"),
        )
        assertFalse("search_tutorial_activity.xml 应已删除", layout.any { it.exists() })
    }

    @Test
    fun keepsPreferenceKeyAndLaunchFlags() {
        val activity = projectFile(activityPath).readText()
        assertTrue("不再显示偏好键必须保留", activity.contains("\"search_tutorial_no_show\""))
        assertTrue("NEW_TASK 标志保留", activity.contains("FLAG_ACTIVITY_NEW_TASK"))
        assertTrue("CLEAR_TOP 标志保留", activity.contains("FLAG_ACTIVITY_CLEAR_TOP"))
        assertTrue("SINGLE_TOP 标志保留", activity.contains("FLAG_ACTIVITY_SINGLE_TOP"))
    }

    @Test
    fun keepsCloseToSearchModeChain() {
        val activity = projectFile(activityPath).readText()
        assertTrue("关闭后经服务进入搜索模式", activity.contains("startSearchMode()"))
        assertTrue("onStop 释放 DisplayClient", activity.contains("mDisplay?.shutdown()"))
        assertTrue("关闭时保存勾选偏好", activity.contains("putBoolean(PREF_DO_NOT_SHOW"))
    }

    @Test
    fun specificInstructionsHiddenWhenDisconnected() {
        val screen = projectFile(screenPath).readText()
        assertTrue("未连接时该段为 null 且不渲染/不进焦点树",
            screen.contains("if (specificInstructions != null)"))
        assertFalse("本页不得引入协程定时", screen.contains("postDelayed("))
        assertFalse("本页不得出现自调度 Runnable", screen.contains("object : Runnable"))
    }
}
