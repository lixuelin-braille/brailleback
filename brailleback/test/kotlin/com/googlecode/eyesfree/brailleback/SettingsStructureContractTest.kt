/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
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

/**
 * 设置页信息架构的静态契约。
 *
 * 无障碍视角：读屏用户是线性浏览列表的，分类层级与每屏项目数直接决定能否找到设置；
 * 而 Preference 的 key 就是 SharedPreferences 的键，一旦在重排中被改动，用户已有的
 * 设置会被静默丢弃。因此把「两层导航 / 单屏上限 / key 零迁移 / 摘要齐全」固化为测试。
 *
 * 纯 JVM 单测读不了 Android 资源，直接解析 res/xml 文本；项目已有同类契约测试先例
 * （VersionCatalogTest / KeyMapResourceContractTest）。
 */
class SettingsStructureContractTest {

    private fun projectFile(path: String): File {
        val candidates = listOf(File(path), File("..", path), File("../..", path))
        return candidates.firstOrNull { it.isFile }
            ?: error("找不到 $path（cwd=${System.getProperty("user.dir")}）")
    }

    private fun text(path: String) = projectFile(path).readText()

    private val base = "brailleback/"

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

    private val settingsXml: Element
        get() = parse(base + "res/xml/settings.xml")

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
        val resources = text(base + "res/values/donottranslate.xml") +
            text(base + "res/values/strings.xml")
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

    private fun screenAncestorCount(element: Element): Int {
        var count = 0
        var node: Node? = element.parentNode
        while (node is Element) {
            if (node.tagName == "PreferenceScreen") count++
            node = node.parentNode
        }
        return count
    }

    /** 一屏内读屏会朗读到的节点数：叶子项 + 分组标题。 */
    private fun audibleNodes(screen: Element): Int {
        var total = 0
        for (child in screen.children()) {
            when {
                child.tagName in leafTags -> total++
                child.tagName == "PreferenceScreen" -> total++
                child.tagName == "PreferenceCategory" -> {
                    total++ // 分组标题本身也是可聚焦的朗读节点
                    // 分组内既可能有叶子项，也可能有子屏入口（主屏的五个分类）。
                    total += child.children().count {
                        it.tagName in leafTags || it.tagName == "PreferenceScreen"
                    }
                }
            }
        }
        return total
    }

    // ------------------------------------------------------------- 信息架构

    @Test
    fun mainScreenUsesThreeSemanticGroups() {
        val groups = settingsXml.children().filter { it.tagName == "PreferenceCategory" }
        val keys = groups.map { resolveKey(it.getAttribute("app:key")) }
        assertEquals(
            "主屏必须且只能有三个语义分组：开始使用 / 设置分类 / 功能与内容",
            listOf("category_start", "category_settings", "category_features"),
            keys
        )
        assertEquals(
            "主屏不应再出现与分组混排的裸 Preference 或裸子屏",
            settingsXml.children().size,
            groups.size
        )
    }

    @Test
    fun fiveSubScreensAreFlatWithNoThirdLevel() {
        val screens = allElements(settingsXml).filter { it.tagName == "PreferenceScreen" }
        assertEquals("设置分类下应为五个平级子屏", 5, screens.size)
        screens.forEach { screen ->
            assertEquals(
                "子屏 ${screen.getAttribute("app:key")} 嵌套在另一个子屏里，" +
                    "读屏用户需要按两次返回才能回到主屏",
                1,
                screenAncestorCount(screen)
            )
        }
        assertEquals(
            "五个子屏的 key 必须稳定（Activity 用 rootKey 导航）",
            setOf(
                "category_device", "category_keys", "category_braille",
                "category_sound_behavior", "category_developer"
            ),
            screens.map { resolveKey(it.getAttribute("app:key")) }.toSet()
        )
    }

    @Test
    fun everyScreenHasBoundedNodeCount() {
        val screens = allElements(settingsXml).filter { it.tagName == "PreferenceScreen" }
        screens.forEach { screen ->
            val key = resolveKey(screen.getAttribute("app:key"))
            val count = audibleNodes(screen)
            // 开发者选项是唯一例外：5 个日志操作 + 2 个叠加层项 + 2 个分组标题 = 9，
            // 且这些操作不便再拆屏（拆了会让主屏入口变成 6 个）。其余子屏严格不超过 8。
            val limit = if (key == "category_developer") 9 else 8
            assertTrue(
                "子屏 $key 有 $count 个朗读节点，超过上限 $limit 会让线性浏览重新变拥挤",
                count <= limit
            )
        }
        assertTrue("主屏朗读节点不得超过 12 个", audibleNodes(settingsXml) <= 12)
    }

    @Test
    fun allLegacyPreferenceKeysStillResolveAndStayInTheArchive() {
        // 旧子屏内容已归档（界面不再渲染），但偏好键就是 SharedPreferences 的键：
        // 服务端与 Compose 两侧仍在读写，键名一旦丢失，老用户设置会被静默丢弃。
        // 因此这里对「归档原文」做完整性校验，同时确认每个 @string 键仍能解析。
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
            "overlay_enabled", "show_overlay_tutorial"
        )
        // 归档里出现的键（@string 引用已解析成实际值）
        val archived = allElements(archivedXml)
            .filter { it.hasAttribute("app:key") }
            .map { resolveKey(it.getAttribute("app:key")) }
            .toSet()
        // 主屏仍保留的叶子项（启用服务、书架、键盘、许可）也一起算
        val present = archived + allElements(settingsXml)
            .filter { it.hasAttribute("app:key") }
            .map { resolveKey(it.getAttribute("app:key")) }
            .toSet()
        val missing = legacyKeys.filter { it !in present }
        assertTrue("归档后丢失了这些偏好 key，老用户设置会被丢弃：$missing", missing.isEmpty())

        // 归档必须是可回退的完整原文：五个子屏、且每个子屏都还有内容项。
        val archivedScreens = allElements(archivedXml).filter { it.tagName == "PreferenceScreen" }
        assertEquals("归档应保留五个子屏原文", 5, archivedScreens.size)
        archivedScreens.forEach { screen ->
            assertTrue(
                "归档子屏 ${screen.getAttribute("app:key")} 是空的，回退后设置项会不见",
                allElements(screen).any { it.tagName in leafTags }
            )
        }
    }

    @Test
    fun subScreenEntriesOnlyPointAtComposeScreens() {
        // 主屏只留入口行：五个子屏自身不再带任何内容项，否则又会出现两套界面。
        val screens = allElements(settingsXml).filter { it.tagName == "PreferenceScreen" }
        assertEquals(5, screens.size)
        screens.forEach { screen ->
            assertTrue(
                "子屏 ${screen.getAttribute("app:key")} 仍带内容项，旧界面没有真正退出",
                screen.children().none { it.tagName in leafTags } &&
                    screen.children().none { it.tagName == "PreferenceCategory" }
            )
        }
        val wiring = text(base + "src/com/googlecode/eyesfree/brailleback/SettingsActivity.kt")
        listOf(
            "DeviceConnectionComposeActivity",
            "KeysGesturesComposeActivity",
            "BrailleDisplayComposeActivity",
            "SoundBehaviorComposeActivity",
            "DeveloperOptionsComposeActivity",
        ).forEach { activity ->
            assertTrue("入口行必须仍指向 $activity", wiring.contains(activity))
        }
    }

    @Test
    fun everyLeafHasTitleAndExceptDynamicOnesHasSummary() {
        // 这两项由代码在 onResume 里写入实时状态（连接状态 / 当前盲文表名）。
        val dynamicSummaryKeys = setOf("connection_status", "braille_table")
        allElements(settingsXml).filter { it.tagName in leafTags }.forEach { leaf ->
            val key = resolveKey(leaf.getAttribute("app:key"))
            assertTrue("$key 缺少 app:title", leaf.hasAttribute("app:title"))
            if (key in dynamicSummaryKeys) return@forEach
            val hasSummary = leaf.hasAttribute("app:summary") ||
                leaf.hasAttribute("app:summaryOn") ||
                leaf.hasAttribute("app:summaryOff")
            assertTrue("$key 缺少摘要，读屏用户听不到当前值或用途", hasSummary)
        }
    }

    // --------------------------------------------------------------- 文案

    @Test
    fun newCategoryStringsExistInBothLocales() {
        val english = text(base + "res/values/strings.xml")
        val chinese = text(base + "res/values-zh-rCN/strings.xml")
        listOf(
            "pref_category_start_title", "pref_category_settings_title",
            "pref_category_features_title", "pref_category_keys_title",
            "pref_category_keys_summary", "pref_category_sound_behavior_title",
            "pref_category_sound_behavior_summary", "pref_category_display_keys_title",
            "pref_category_log_title", "pref_connection_status_summary",
            "pref_key_bindings_summary", "pref_braille_word_wrap_summary_on",
            "pref_braille_word_wrap_summary_off", "pref_os_license_summary",
            "pref_braille_overlay_summary", "pref_braille_overlay_tutorial_summary"
        ).forEach { name ->
            val pattern = """<string name="$name">""".toRegex()
            assertTrue("英文 strings.xml 缺少 $name", pattern.containsMatchIn(english))
            assertTrue("中文 strings.xml 缺少 $name", pattern.containsMatchIn(chinese))
        }
    }

    @Test
    fun englishSoundCategoryIsNoLongerChinese() {
        val english = text(base + "res/values/strings.xml")
        val value = Regex("""<string name="pref_sound_category_title">([^<]*)</string>""")
            .find(english)?.groupValues?.get(1)
            ?: error("找不到 pref_sound_category_title")
        assertFalse(
            "英文 locale 里曾经误写中文，英文用户会听到无法理解的标题",
            value.any { it.code in 0x4E00..0x9FFF }
        )
    }

    // ------------------------------------------------------------ 导航接线

    @Test
    fun subScreenTitleAndUpNavigationAreWired() {
        val source = text(base + "src/com/googlecode/eyesfree/brailleback/SettingsActivity.kt")
        val resumeBody = source.substringAfter("override fun onResume()")
            .substringBefore("UsbDisplayConnection.allowPermissionPromptAgain()")
        assertTrue("onResume 必须刷新子屏标题", resumeBody.contains("updateScreenTitle()"))
        listOf(
            "private fun updateScreenTitle()",
            "supportActionBar?.setDisplayHomeAsUpEnabled(isSubScreen)",
            "host.announceSubScreenTitle(subScreenTitle)",
            "override fun onOptionsItemSelected(item: MenuItem)",
            "supportFragmentManager.popBackStack()"
        ).forEach { fragment ->
            assertTrue("SettingsActivity 缺少子屏导航代码：$fragment", source.contains(fragment))
        }
    }
}
