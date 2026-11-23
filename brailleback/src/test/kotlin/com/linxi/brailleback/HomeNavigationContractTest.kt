/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 *
 * 主屏底部导航契约：Tab 顺序与首项、阅读 Tab 绑定书架、设置 Tab 保留原九行三组、
 * 新文案双侧齐全且英文不含中文、选中态走 stateDescription、launcher 已迁到容器、
 * 书架导入文案未被改动。
 *
 * 纯 JVM 单测读不了 Android 资源，直接对源码 / Manifest / 文案做文本断言。
 */

package com.linxi.brailleback

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeNavigationContractTest {

    private fun projectFile(path: String): File {
        val candidates = listOf(File(path), File("..", path), File("../..", path))
        return candidates.firstOrNull { it.isFile }
            ?: error("找不到 $path（cwd=${System.getProperty("user.dir")}）")
    }

    private fun text(path: String) = projectFile(path).readText()

    private val base = "brailleback/"
    private val composeDir = base + "src/main/java/com/linxi/brailleback/compose/"

    private fun compose(name: String) = text(composeDir + name)

    // -------------------------------------------------------------- Tab 目录

    @Test
    fun tabOrderIsReadingFirstThenSettings() {
        val catalog = compose("HomeNavigationCatalog.kt")
        val reading = catalog.indexOf("READING(")
        val settings = catalog.indexOf("SETTINGS(")
        assertTrue("目录里缺少 READING Tab", reading >= 0)
        assertTrue("目录里缺少 SETTINGS Tab", settings >= 0)
        assertTrue("READING 必须排第一", reading < settings)
        assertTrue(
            "首项必须绑定 home_tab_reading_title",
            catalog.contains("READING(R.string.home_tab_reading_title)"),
        )
        assertTrue(
            "第二项必须绑定 home_tab_settings_title",
            catalog.contains("SETTINGS(R.string.home_tab_settings_title)"),
        )
    }

    @Test
    fun readingTabBindsBookshelfScreen() {
        val screen = compose("HomeContainerScreen.kt")
        assertTrue("阅读 Tab 必须内嵌 BookshelfScreen", screen.contains("BookshelfScreen("))
    }

    @Test
    fun settingsTabReusesHomeScreenWithNineRowsThreeGroups() {
        val screen = compose("HomeContainerScreen.kt")
        assertTrue("设置 Tab 必须复用 SettingsHomeScreen", screen.contains("SettingsHomeScreen("))

        val catalog = compose("SettingsHomeCatalog.kt")
        assertEquals(
            "设置主屏必须仍是九行",
            9,
            Regex("""HomeEntry\(([\s\S]*?)\n {8}\),""").findAll(catalog).count(),
        )
        assertEquals(
            "设置主屏必须仍是三组",
            3,
            Regex("""\b(\w+)\("(category_[a-z_]+)""").findAll(catalog).count(),
        )
    }

    @Test
    fun selectedStateUsesStateDescriptionNotColorAlone() {
        val screen = compose("HomeContainerScreen.kt")
        assertTrue("Tab 选中态必须用 stateDescription 播报", screen.contains("stateDescription"))
        assertTrue("选中/未选中文案必须接入", screen.contains("selectedLabel"))
        assertTrue("选中/未选中文案必须接入", screen.contains("unselectedLabel"))
    }

    // -------------------------------------------------------------- Manifest

    @Test
    fun launcherMigratedToContainerAndAliasKept() {
        val manifest = text(base + "src/main/AndroidManifest.xml")
        assertTrue("容器 Activity 必须注册", manifest.contains("android:name=\".HomeContainerActivity\""))
        assertTrue("服务 settingsActivity 必须指向容器",
            manifest.contains("android:settingsActivity=\".HomeContainerActivity\""))

        val blocks = manifest.split("<activity").drop(1)
        val containerBlock = blocks.first { it.contains(".HomeContainerActivity") }
        assertTrue("容器必须挂 MAIN+LAUNCHER",
            containerBlock.contains("android.intent.category.LAUNCHER"))
        assertTrue("容器必须挂 SERVICE_SETTINGS",
            containerBlock.contains("android.accessibilityservice.SERVICE_SETTINGS"))

        val aliasBlock = blocks.first { it.contains(".SettingsActivity\"") }
        assertFalse("兼容别名不得再挂 LAUNCHER",
            aliasBlock.contains("android.intent.category.LAUNCHER"))
        assertFalse("兼容别名不得再挂 SERVICE_SETTINGS",
            aliasBlock.contains("android.accessibilityservice.SERVICE_SETTINGS"))
    }

    // -------------------------------------------------------------- 文案

    @Test
    fun homeStringsLocalizedInBothLocalesAndEnglishHasNoCjk() {
        val english = text(base + "src/main/res/values/strings.xml")
        val chinese = text(base + "src/main/res/values-zh-rCN/strings.xml")
        listOf(
            "home_tab_reading_title",
            "home_tab_settings_title",
            "home_tab_switched_announce",
        ).forEach { name ->
            val pattern = """<string name="$name">""".toRegex()
            assertTrue("英文缺少 $name", pattern.containsMatchIn(english))
            assertTrue("中文缺少 $name", pattern.containsMatchIn(chinese))

            val enValue = Regex("""<string name="$name">([^<]*)</string>""")
                .find(english)?.groupValues?.get(1).orEmpty()
            assertFalse(
                "英文 $name 不得混入中文：$enValue",
                enValue.contains(Regex("[\u4e00-\u9fff]")),
            )
        }
    }

    @Test
    fun bookshelfImportStringUnchanged() {
        val english = text(base + "src/main/res/values/strings.xml")
        val chinese = text(base + "src/main/res/values-zh-rCN/strings.xml")
        assertTrue(
            "bookshelf_import 英文文案不得改动（ImportFlowSmokeTest 按它找按钮）",
            english.contains("""<string name="bookshelf_import">Import</string>"""),
        )
        assertTrue(
            "bookshelf_import 中文文案不得改动",
            chinese.contains("""<string name="bookshelf_import">导入</string>"""),
        )
    }
}
