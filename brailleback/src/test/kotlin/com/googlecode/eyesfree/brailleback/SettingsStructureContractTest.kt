/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 *
 * 设置主屏信息架构的静态契约。
 *
 * 2026-11-18：主屏从 PreferenceFragmentCompat（settings.xml）改成 Compose
 * （SettingsHomeCatalog + SettingsHomeScreen），本测试跟着改成校验「目录数据」——
 * 分组层级、入口数量、key 稳定性、摘要齐全都仍然必须成立，只是载体从 XML 换成了
 * Kotlin 数据表。
 *
 * 无障碍视角没变：读屏用户线性浏览列表，分组层级与一屏节点数直接决定能否找到设置；
 * key 就是旧 app:key（也仍是服务端读的偏好键口径），一旦改动，老用户设置会被静默丢弃。
 */

package com.googlecode.eyesfree.brailleback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class SettingsStructureContractTest {

    private fun projectFile(path: String): File {
        val candidates = listOf(File(path), File("..", path), File("../..", path))
        return candidates.firstOrNull { it.isFile }
            ?: error("找不到 $path（cwd=${System.getProperty("user.dir")}）")
    }

    private fun text(path: String) = projectFile(path).readText()

    private val base = "brailleback/"

    /** 主屏目录数据。 */
    private val catalog get() =
        text(base + "src/main/java/com/googlecode/eyesfree/brailleback/compose/SettingsHomeCatalog.kt")

    /** 旧五子屏冻结原文的位置（相对仓库根，机读路径见 docs/归档-旧设置界面/README.md）。 */
    private val ARCHIVE_PATH get() = "docs/归档-旧设置界面/settings-legacy.xml"

    private fun Element.children(): List<Element> {
        val out = mutableListOf<Element>()
        var node: Node? = firstChild
        while (node != null) {
            if (node is Element) out.add(node)
            node = node.nextSibling
        }
        return out
    }

    private fun parse(path: String): Element {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
        }
        return factory.newDocumentBuilder().parse(projectFile(path)).documentElement
    }

    /** 旧五子屏的冻结原文：界面已归档，偏好键与出厂默认值仍以它为准核对。 */
    private val archivedXml: Element
        get() = parse(ARCHIVE_PATH)

    private val leafTags = setOf(
        "Preference", "ListPreference", "SwitchPreferenceCompat", "CheckBoxPreference"
    )

    /** 解析 @string/xxx 引用为 donottranslate.xml / strings.xml 里的实际值。 */
    private fun resolveKey(raw: String): String {
        if (!raw.startsWith("@string/")) return raw
        val name = raw.removePrefix("@string/")
        val resources = text(base + "src/main/res/values/donottranslate.xml") +
            text(base + "src/main/res/values/strings.xml")
        val value = Regex("""<string name="$name"[^>]*>([^<]*)</string>""")
            .find(resources)?.groupValues?.get(1)
        return value ?: error("app:key 引用了未定义的 $name")
    }

    private fun allElements(root: Element): List<Element> {
        val out = mutableListOf<Element>()
        fun walk(node: Element) {
            for (child in node.children()) {
                out.add(child)
                walk(child)
            }
        }
        walk(root)
        return out
    }

    // ------------------------------------------------- 主屏目录数据（新载体）

    /** 主屏上的一行入口。 */
    private data class CatalogEntry(
        val key: String,
        val section: String,
        val action: String,
        val screenKeyRes: String?,
        val dynamicSummary: Boolean,
        val block: String,
    )

    /**
     * 从 SettingsHomeCatalog 的 `HomeEntry(...)` 块里取字段。
     * 数据表是刻意写成「一行一个字段」的，正则才稳定；改格式要先改这里。
     */
    private fun catalogEntries(): List<CatalogEntry> {
        val blocks = Regex("""HomeEntry\(([\s\S]*?)\n {8}\),""")
            .findAll(catalog).map { it.groupValues[1] }.toList()
        require(blocks.isNotEmpty()) { "SettingsHomeCatalog 里没解析到任何 HomeEntry" }
        return blocks.map { block ->
            CatalogEntry(
                key = Regex("""key = "([^"]+)"""").find(block)?.groupValues?.get(1)
                    ?: error("HomeEntry 缺 key：$block"),
                section = Regex("""section = HomeSection\.(\w+)""").find(block)
                    ?.groupValues?.get(1)
                    ?: error("HomeEntry 缺 section：$block"),
                action = Regex("""action = HomeAction\.(\w+)""").find(block)
                    ?.groupValues?.get(1)
                    ?: error("HomeEntry 缺 action：$block"),
                screenKeyRes = Regex("""HomeAction\.Screen\(R\.string\.(\w+)\)""")
                    .find(block)?.groupValues?.get(1),
                dynamicSummary = block.contains("dynamicSummary = true"),
                block = block,
            )
        }
    }

    private fun sectionOrder(): List<String> =
        Regex("""\b(\w+)\("(category_[a-z_]+)""").findAll(catalog)
            .map { it.groupValues[1] }.toList()

    private fun sectionKeys(): List<String> =
        Regex("""\b(\w+)\("(category_[a-z_]+)""").findAll(catalog)
            .map { it.groupValues[2] }.toList()

    // ------------------------------------------------------------- 信息架构

    @Test
    fun mainScreenUsesThreeSemanticGroups() {
        val sections = sectionOrder()
        assertEquals(
            "主屏必须且只能有三个语义分组：开始使用 / 设置分类 / 功能与内容",
            listOf("START", "SETTINGS", "FEATURES"),
            sections,
        )
        val keys = sectionKeys()
        assertEquals(
            listOf("category_start", "category_settings", "category_features"),
            keys,
        )
    }

    @Test
    fun categoryEntriesAreFlatAndNeverSubScreens() {
        val entries = catalogEntries()
        val settingsEntries = entries.filter { it.section == "SETTINGS" }
        assertEquals("设置分类下应为六个平级入口", 6, settingsEntries.size)
        assertEquals(
            "六个入口的 key 必须稳定（与旧 app:key 同值）",
            setOf(
                "category_device", "category_keys", "category_braille",
                "category_sound_behavior", "category_developer", "category_stability",
            ),
            settingsEntries.map { it.key }.toSet(),
        )
        settingsEntries.forEach { entry ->
            assertTrue(
                "入口 ${entry.key} 必须以 Screen 动作打开独立 Activity，" +
                    "任何「子屏」都要单独一层（主屏不再有 back stack）",
                entry.action == "Screen",
            )
        }
    }

    @Test
    fun categoryEntriesResolveToTheirOwnPreferenceKeys() {
        val expected = mapOf(
            "category_device" to "pref_category_device_key",
            "category_keys" to "pref_category_keys_key",
            "category_braille" to "pref_category_braille_key",
            "category_sound_behavior" to "pref_category_sound_behavior_key",
            "category_developer" to "pref_category_developer_key",
            "category_stability" to "pref_category_stability_key",
        )
        catalogEntries().filter { it.section == "SETTINGS" }.forEach { entry ->
            assertEquals(
                "入口 ${entry.key} 引用的偏好键必须与旧 settings.xml 一致",
                expected[entry.key],
                entry.screenKeyRes,
            )
        }
    }

    @Test
    fun everyScreenHasBoundedNodeCount() {
        // 3 个分组标题 + 启用服务 + 6 个分类入口 + 3 个功能入口 = 13。
        val entries = catalogEntries()
        val sections = sectionOrder().size
        assertTrue(
            "主屏朗读节点不得超过 13 个，超过会让线性浏览重新变拥挤",
            sections + entries.size <= 13,
        )
        assertEquals("主屏入口应为十行", 10, entries.size)
    }

    @Test
    fun allLegacyPreferenceKeysStillResolveAndStayInTheArchive() {
        // 旧子屏内容已归档（界面不再渲染），但偏好键是 SharedPreferences 的键：
        // 服务端与 Compose 两侧仍在读写，键名一旦丢失，老用户设置会被静默丢弃。
        val legacyKeys = listOf(
            "setup_service", "bookshelf", "pref_braille_keyboard_key",
            "connection_status", "key_bindings", "custom_key_map",
            "connection_protocol", "auto_connect",
            "braille_table", "braille_word_wrap", "cursor_dots",
            "braille_contracted_mode", "chinese_segmentation",
            "volume_gestures_enabled", "volume_long_press_up",
            "volume_long_press_down", "volume_double_press",
            "pause_behavior", "idle_clear_timeout", "motion_mode", "quiet_navigation",
            "pref_sound_enabled", "pref_sound_volume",
            "opensource_license_notice",
            "log_level", "view_log", "share_log", "copy_log", "clear_log",
            "overlay_enabled", "show_overlay_tutorial",
        )
        // 归档里出现的键（@string 引用已解析成实际值）
        val archived = allElements(archivedXml)
            .filter { it.hasAttribute("app:key") }
            .map { resolveKey(it.getAttribute("app:key")) }
            .toSet()
        // 主屏目录里仍保留的 key
        val present = archived + catalogEntries().map { it.key }.toSet()
        val missing = legacyKeys.filter { it !in present }
        assertTrue("归档后丢失了这些偏好 key，老用户设置会被丢弃：$missing", missing.isEmpty())

        // 归档必须是可回退的完整原文：五个子屏、且每个子屏都还有内容项。
        val archivedScreens = allElements(archivedXml).filter { it.tagName == "PreferenceScreen" }
        assertEquals("归档应保留五个子屏原文", 5, archivedScreens.size)
        archivedScreens.forEach { screen ->
            assertTrue(
                "归档子屏 ${screen.getAttribute("app:key")} 是空的，回退后设置项会不见",
                allElements(screen).any { it.tagName in leafTags },
            )
        }
    }

    @Test
    fun everyEntryHasTitleAndSummaryExceptDynamicOne() {
        val dynamicKeys = setOf("setup_service")
        catalogEntries().forEach { entry ->
            assertTrue("${entry.key} 缺少 titleRes", entry.block.contains("titleRes = R.string."))
            assertTrue("${entry.key} 缺少 summaryRes", entry.block.contains("summaryRes = R.string."))
            if (entry.key in dynamicKeys) {
                assertTrue(
                    "${entry.key} 的摘要由实时状态决定，必须标 dynamicSummary",
                    entry.dynamicSummary,
                )
            } else {
                assertFalse(
                    "${entry.key} 的摘要是静态文案，不应标 dynamicSummary",
                    entry.dynamicSummary,
                )
            }
        }
    }

    @Test
    fun noLegacyPreferenceInfrastructureLeft() {
        // 主屏改成 Compose 后，旧的三件套必须彻底退场；只要还留一件，
        // 就会出现「同一屏两套 UI」或「键被两处写」的风险。
        assertFalse(
            "settings.xml 应已删除（主屏数据改由 SettingsHomeCatalog 提供）",
            File("brailleback/src/main/res/xml/settings.xml").exists() ||
                File("../brailleback/src/main/res/xml/settings.xml").exists(),
        )
        val source = text(base + "src/main/java/com/googlecode/eyesfree/brailleback/SettingsActivity.kt")
        listOf(
            "PreferenceFragmentCompat", "supportFragmentManager",
            "setPreferencesFromResource", "R.xml.settings", "OnPreferenceStartScreenCallback",
        ).forEach { legacy ->
            assertFalse("SettingsActivity 仍残留旧 preference 设施：$legacy", source.contains(legacy))
        }
        assertTrue(
            "主屏必须是 Compose 渲染",
            source.contains("setContent {") && source.contains("SettingsHomeScreen("),
        )
    }

    // --------------------------------------------------------------- 文案

    @Test
    fun newCategoryStringsExistInBothLocales() {
        val english = text(base + "src/main/res/values/strings.xml")
        val chinese = text(base + "src/main/res/values-zh-rCN/strings.xml")
        listOf(
            "pref_category_start_title", "pref_category_settings_title",
            "pref_category_features_title", "pref_category_keys_title",
            "pref_category_keys_summary", "pref_category_sound_behavior_title",
            "pref_category_sound_behavior_summary", "pref_category_display_keys_title",
            "pref_category_log_title", "pref_connection_status_summary",
            "pref_key_bindings_summary", "pref_braille_word_wrap_summary_on",
            "pref_braille_word_wrap_summary_off", "pref_os_license_summary",
            "pref_braille_overlay_summary", "pref_braille_overlay_tutorial_summary",
        ).forEach { name ->
            val pattern = """<string name="$name">""".toRegex()
            assertTrue("英文 strings.xml 缺少 $name", pattern.containsMatchIn(english))
            assertTrue("中文 strings.xml 缺少 $name", pattern.containsMatchIn(chinese))
        }
    }

    @Test
    fun englishSoundCategoryIsNoLongerChinese() {
        val english = text(base + "src/main/res/values/strings.xml")
        val value = Regex("""<string name="pref_sound_category_title">([^<]*)</string>""")
            .find(english)?.groupValues?.get(1)
            ?: error("找不到 pref_sound_category_title")
        assertFalse(
            "英文 locale 里曾经误写中文，英文用户会听到无法理解的标题",
            value.any { it.code in 0x4E00..0x9FFF },
        )
    }

    // ------------------------------------------------------------ 导航接线

    @Test
    fun categoryEntriesWireToComposeScreens() {
        // 入口行自己不带内容，靠接线表跳 Compose 页。这里锁的是真正的跳转路径：
        // 六个入口每一个都必须有一条 wireCategory 接线，且点击后真的 startActivity。
        val wiring = text(base + "src/main/java/com/googlecode/eyesfree/brailleback/SettingsActivity.kt")
            .substringAfter("// 六个分类入口跳各自的 Compose 页。")
            .substringBefore("// 屏幕盲文键盘")
        listOf(
            "pref_category_device_key" to "DeviceConnectionComposeActivity",
            "pref_category_keys_key" to "KeysGesturesComposeActivity",
            "pref_category_braille_key" to "BrailleDisplayComposeActivity",
            "pref_category_sound_behavior_key" to "SoundBehaviorComposeActivity",
            "pref_category_developer_key" to "DeveloperOptionsComposeActivity",
            "pref_category_stability_key" to "StabilityComposeActivity",
        ).forEach { (keyRes, activity) ->
            val pattern = Regex(
                """wireCategory\(\s*R\.string\.$keyRes,\s*$activity::class\.java"""
            )
            assertTrue(
                "入口 $keyRes 没有接线到 $activity：没有查表的入口点了不会有反应",
                pattern.containsMatchIn(wiring),
            )
        }
        assertEquals(
            "六个分类入口应各有一条 wireCategory 接线",
            6,
            Regex("""wireCategory\(""").findAll(wiring).count(),
        )
        val source = text(base + "src/main/java/com/googlecode/eyesfree/brailleback/SettingsActivity.kt")
        assertTrue(
            "点击后必须真的 startActivity，否则接线表只是装饰",
            source.contains("startActivity(Intent(this, it))"),
        )
    }
}
