/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 */

package com.googlecode.eyesfree.brailleback.keymap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 自定义按键映射的静态契约。
 *
 * 纯 JVM 单测读不了 Android 资源，但「UI 里能选的动作」与「运行时真正能解析的命令」
 * 必须是同一套白名单；这两处一旦漂移，表现是「设置里改了却没反应」——盲用户很难自查。
 * 因此直接对 res/xml/源码文本做断言，项目已有同类契约测试的先例
 * （VersionCatalogTest / LargeClassConversionContractTest）。
 */
class KeyMapResourceContractTest {

    private fun projectFile(path: String): File {
        val candidates = listOf(File(path), File("..", path), File("../..", path))
        return candidates.firstOrNull { it.isFile }
            ?: error("找不到 $path（cwd=${System.getProperty("user.dir")}）")
    }

    private fun text(path: String) = projectFile(path).readText()

    private fun stringArray(xml: String, name: String): List<String> {
        val block = Regex(
            """<string-array name="$name"[^>]*>(.*?)</string-array>""",
            RegexOption.DOT_MATCHES_ALL
        ).find(xml)?.groupValues?.get(1)
            ?: error("找不到 string-array $name")
        return Regex("""<item>(.*?)</item>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(block)
            .map { it.groupValues[1].trim() }
            .toList()
    }

    private val base = "brailleback/"

    // ------------------------------------------------------------ 白名单一致性

    @Test
    fun sourceValuesMatchKotlinWhitelist() {
        val values = stringArray(text(base + "res/values/key_map.xml"), "key_map_source_values")
        assertEquals(
            "资源里的可自定义按键必须与 BrailleKeyMapPolicy.REMAPPABLE_COMMANDS 逐项一致",
            BrailleKeyMapPolicy.REMAPPABLE_COMMANDS,
            values
        )
    }

    @Test
    fun actionValuesMatchKotlinWhitelist() {
        val values = stringArray(text(base + "res/values/key_map.xml"), "key_map_action_values")
        assertEquals(
            "资源里的可选动作必须与 BrailleKeyMapPolicy.ASSIGNABLE_ACTIONS 逐项一致",
            BrailleKeyMapPolicy.ASSIGNABLE_ACTIONS,
            values
        )
    }

    @Test
    fun sourceValuesAreTheFirstActionValues() {
        val xml = text(base + "res/values/key_map.xml")
        val sources = stringArray(xml, "key_map_source_values")
        val actions = stringArray(xml, "key_map_action_values")
        assertEquals(
            "界面复用动作标签显示按键名，源清单必须是动作清单的前缀",
            sources,
            actions.take(sources.size)
        )
    }

    @Test
    fun labelsHaveSameLengthAndAreLocalized() {
        val english = stringArray(text(base + "res/values/key_map.xml"), "key_map_action_labels")
        val chinese = stringArray(
            text(base + "res/values-zh-rCN/key_map.xml"), "key_map_action_labels"
        )
        val actions = BrailleKeyMapPolicy.ASSIGNABLE_ACTIONS
        assertEquals(actions.size, english.size)
        assertEquals(actions.size, chinese.size)
        assertTrue("中文标签不得为空", chinese.all { it.isNotBlank() })
        assertFalse("中文标签必须真正翻译过", chinese == english)
    }

    @Test
    fun everyWhitelistedNameIsRegisteredInBrailleInputEvent() {
        val source = text(
            "client/src/com/googlecode/eyesfree/braille/display/BrailleInputEvent.kt"
        )
        val registered = Regex(
            """CMD_NAMES\.append\(\s*\w+\s*,\s*"([A-Z0-9_]+)"\s*\)"""
        ).findAll(source).map { it.groupValues[1] }.toSet()
        for (name in BrailleKeyMapPolicy.ASSIGNABLE_ACTIONS) {
            assertTrue(
                "$name 未在 BrailleInputEvent.CMD_NAMES 登记，stringToCommand 会解析成 CMD_NONE",
                registered.contains(name)
            )
        }
    }

    // -------------------------------------------------------------- 接入点契约

    @Test
    fun displayManagerAppliesMappingBeforeAnyDispatch() {
        val source = text(base + "src/com/googlecode/eyesfree/brailleback/DisplayManager.kt")
        val method = source.substringAfter("override fun onInputEvent(event: BrailleInputEvent)")
            .substringBefore("override fun onTablesChanged()")
        val applyIndex = method.indexOf("applyCustomKeyMap(event)")
        val dispatchIndex = method.indexOf("when (effectiveEvent.command)")
        assertTrue("onInputEvent 必须调用 applyCustomKeyMap", applyIndex >= 0)
        assertTrue("映射必须发生在分发之前", dispatchIndex > applyIndex)
        assertTrue(
            "panLeft/panRight 与 sendMappedEvent 都必须使用重映射后的事件",
            method.contains("else -> sendMappedEvent(effectiveEvent)")
        )
        assertTrue(
            "偏好变更必须触发运行时表重载",
            source.contains("CustomKeyMapStore.preferenceKey(context)")
        )
    }

    @Test
    fun settingsEntryAndActivityAreWired() {
        val settings = text(base + "res/xml/settings.xml")
        assertTrue(settings.contains("@string/pref_custom_key_map_key"))

        val donottranslate = text(base + "res/values/donottranslate.xml")
        assertTrue(
            donottranslate.contains(
                """<string name="pref_custom_key_map_key">custom_key_map</string>"""
            )
        )

        val manifest = text(base + "AndroidManifest.xml")
        assertTrue(manifest.contains("android:name=\".CustomKeyMapActivity\""))

        val settingsActivity = text(
            base + "src/com/googlecode/eyesfree/brailleback/SettingsActivity.kt"
        )
        assertTrue(
            settingsActivity.contains("Intent(requireActivity(), CustomKeyMapActivity::class.java)")
        )
    }

    @Test
    fun chineseStringsArePresent() {
        val strings = text(base + "res/values-zh-rCN/strings.xml")
        listOf(
            "pref_custom_key_map_title",
            "pref_custom_key_map_summary",
            "custom_key_map_title",
            "custom_key_map_instructions_title",
            "custom_key_map_instructions",
            "custom_key_map_default_action",
            "custom_key_map_dialog_title",
            "custom_key_map_current_summary",
            "custom_key_map_device_key",
            "custom_key_map_no_device",
            "custom_key_map_conflict",
            "custom_key_map_saved",
            "custom_key_map_reset_title",
            "custom_key_map_reset_summary",
            "custom_key_map_reset_confirm_title",
            "custom_key_map_reset_confirm_message",
            "custom_key_map_reset_done",
        ).forEach { name ->
            assertTrue(
                "中文 strings.xml 缺少 $name",
                strings.contains("""<string name="$name">""")
            )
        }
    }
}
