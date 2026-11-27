/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 */

package com.linxi.brailleback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Compose 迁移第四批（声音与行为）的静态契约。
 *
 * 护栏要防四件事：
 * 1) 六个设置项在迁移中丢项，两个分组标题的 heading 语义丢失；
 * 2) 音效总开关 / 音量漂到另一套键或另一种数据类型（SoundSchemeManager 用 Boolean + Int）；
 * 3) 运动模式漏写 motion_mode_explicit —— 用户手动拨过的值会被服务按默认值覆盖，
 *    这是本屏唯一「看不见但会丢设置」的动态逻辑；
 * 4) 音量对话框退化成只能靠手势拖的滑杆，中文读屏用户无法精确调节。
 *
 * 断言一律锚定真实代码行；用 contains("词") 这类弱断言会被注释满足，
 * 第一批变异检验已经实测踩过这个坑。
 */
class SoundBehaviorContractTest {

    private fun projectFile(path: String): File {
        val candidates = listOf(File(path), File("..", path), File("../..", path))
        return candidates.firstOrNull { it.isFile }
            ?: error("找不到 $path（cwd=${System.getProperty("user.dir")}）")
    }

    private fun text(path: String) = projectFile(path).readText()

    private val base = "brailleback/"

    /** 旧五子屏冻结原文（归档，不参与编译）。 */
    private val ARCHIVE_PATH = "docs/归档-旧设置界面/settings-legacy.xml"
    private val composeDir = base + "src/main/java/com/linxi/brailleback/compose/"
    private fun compose(name: String) = text(composeDir + name)

    /** 兼容 translatable="false" 这类带属性的 string 资源。 */
    private fun stringValues(path: String): Map<String, String> =
        Regex("""<string name="([^"]+)"[^>]*>([^<]*)</string>""")
            .findAll(text(path))
            .associate { it.groupValues[1] to it.groupValues[2] }

    /** settings.xml 中声音与行为子屏的片段（回退用的旧实现）。 */
    private fun legacyScreen(): String = text(ARCHIVE_PATH)
        .substringAfter("""app:key="@string/pref_category_sound_behavior_key"""")
        .substringBefore("""app:key="@string/pref_category_developer_key"""")

    /**
     * 主屏分类入口的接线区：五个入口现在是 Preference + 点击监听（wireCategory），
     * 不再是 PreferenceScreen 导航——空 PreferenceScreen 的 onClick 会直接 return。
     */
    private fun wiring(): String =
        text(base + "src/main/java/com/linxi/brailleback/HomeContainerActivity.kt")
            .substringAfter("// 六个分类入口跳各自的 Compose 页。")
            .substringBefore("// 屏幕盲文键盘")

    // -------------------------------------------------------------- 项与键

    @Test
    fun screenRendersExactlySixLegacyItemsUnderTwoSections() {
        val screen = compose("SoundBehaviorScreen.kt")
        assertEquals(
            "声音与行为子屏应正好有六个设置项（2 音效 + 4 显示行为）",
            6,
            Regex("""\bitem\(key =""").findAll(screen).count()
        )
        listOf(
            "item(key = SoundBehaviorLogic.KEY_SOUND_ENABLED)",
            "item(key = SoundBehaviorLogic.KEY_SOUND_VOLUME)",
            "item(key = SoundBehaviorLogic.KEY_PAUSE_BEHAVIOR)",
            "item(key = SoundBehaviorLogic.KEY_IDLE_CLEAR)",
            "item(key = SoundBehaviorLogic.KEY_MOTION_MODE)",
            "item(key = SoundBehaviorLogic.KEY_QUIET_NAVIGATION)",
        ).forEach { fragment ->
            assertTrue("界面缺少设置项：$fragment", screen.contains(fragment))
        }
        assertEquals(
            "两个分组标题（音效反馈 / 显示行为）都要保留 heading 语义",
            2,
            Regex("""settingsSectionHeader\(""").findAll(screen).count()
        )
        assertTrue(screen.contains("settingsSectionHeader(labels.sectionSound)"))
        assertTrue(screen.contains("settingsSectionHeader(labels.sectionBehavior)"))
    }

    @Test
    fun logicKeysEqualLegacyPreferenceKeyResourceValues() {
        val donottranslate = stringValues(base + "src/main/res/values/donottranslate.xml")
        val strings = stringValues(base + "src/main/res/values/strings.xml")
        val logic = compose("SoundBehaviorLogic.kt")
        val pairs = listOf(
            "pref_pause_behavior_key" to "KEY_PAUSE_BEHAVIOR",
            "pref_idle_clear_key" to "KEY_IDLE_CLEAR",
            "pref_motion_mode_key" to "KEY_MOTION_MODE",
            "pref_motion_mode_explicit_key" to "KEY_MOTION_MODE_EXPLICIT",
            "pref_quiet_navigation_key" to "KEY_QUIET_NAVIGATION",
        )
        pairs.forEach { (resource, constant) ->
            val value = donottranslate[resource] ?: error("donottranslate 缺失：$resource")
            assertTrue(
                "Logic.$constant 必须等于旧偏好键 $resource 的实际值 $value",
                logic.contains("""const val $constant = "$value"""")
            )
        }
        // 音效两个键不在 donottranslate.xml，而在 values/strings.xml（带 translatable 属性）。
        listOf(
            "pref_sound_enabled_key" to "KEY_SOUND_ENABLED",
            "pref_sound_volume_key" to "KEY_SOUND_VOLUME",
        ).forEach { (resource, constant) ->
            val value = strings[resource] ?: error("strings 缺失：$resource")
            assertTrue(
                "Logic.$constant 必须等于 $resource 的实际值 $value",
                logic.contains("""const val $constant = "$value"""")
            )
        }
    }

    @Test
    fun legacyXmlStillContainsAllSixKeysForRollback() {
        val xml = legacyScreen()
        listOf(
            "pref_sound_enabled_key",
            "pref_sound_volume_key",
            "pref_pause_behavior_key",
            "pref_idle_clear_key",
            "pref_motion_mode_key",
            "pref_quiet_navigation_key",
        ).forEach { key ->
            assertTrue("旧子屏必须完整保留以便回退：$key", xml.contains("""@string/$key"""))
        }
        assertFalse(
            "主屏已改为 Compose 目录数据（settings.xml 已删除），不应再引用旧 XML 资源",
            text(base + "src/main/java/com/linxi/brailleback/HomeContainerActivity.kt")
                .contains("R.xml.settings"),
        )
    }

    // ---------------------------------------------------------- 音效数据源

    @Test
    fun soundKeysAreOwnedBySoundSchemeManagerWithMatchingTypes() {
        val manager = text(base + "src/main/java/com/linxi/brailleback/sound/SoundSchemeManager.kt")
        assertTrue(
            manager.contains("""private const val KEY_ENABLED = "pref_sound_enabled"""")
        )
        assertTrue(
            manager.contains("""private const val KEY_VOLUME = "pref_sound_volume"""")
        )
        assertTrue(manager.contains("mPrefs.getBoolean(KEY_ENABLED, true)"))
        assertTrue(manager.contains("mPrefs.getInt(KEY_VOLUME, DEFAULT_VOLUME)"))
        assertTrue(manager.contains("Math.max(0, Math.min(100, volume))"))

        val activity = compose("SoundBehaviorComposeActivity.kt")
        listOf(
            "soundManager.isEnabled()",
            "soundManager.getVolume()",
            "soundManager.setEnabled(value)",
            "soundManager.setVolume(coerced)",
            "SoundBehaviorLogic.coerceVolume(value)",
        ).forEach { fragment ->
            assertTrue("Compose 页必须复用音效管理器：$fragment", activity.contains(fragment))
        }
        // 不能绕过管理器自己拼 key 写 String / 写 Int，否则与旧 UI 数据类型冲突。
        assertFalse(
            "音量不得由设置页直接写 SharedPreferences",
            activity.contains("putInt(") || activity.contains("pref_sound_volume_key")
        )
    }

    @Test
    fun volumeRangeMatchesManagerAndLegacySeekBar() {
        val logic = compose("SoundBehaviorLogic.kt")
        assertTrue(logic.contains("const val VOLUME_MIN = 0"))
        assertTrue(logic.contains("const val VOLUME_MAX = 100"))
        assertTrue(logic.contains("value.coerceIn(VOLUME_MIN, VOLUME_MAX)"))
        val screen = compose("SoundBehaviorScreen.kt")
        assertTrue(screen.contains("min = SoundBehaviorLogic.VOLUME_MIN"))
        assertTrue(screen.contains("max = SoundBehaviorLogic.VOLUME_MAX"))
        val activity = compose("SoundBehaviorComposeActivity.kt")
        assertTrue(activity.contains("SoundBehaviorLogic.coerceVolume(value)"))
    }

    // -------------------------------------------------- 运动模式显式标记

    @Test
    fun motionModeToggleWritesExplicitMarkerExactlyLikeLegacyFragment() {
        val logic = compose("SoundBehaviorLogic.kt")
        assertTrue(
            "手动拨动必须把显式标记置 true，否则服务会按默认值覆盖用户选择",
            logic.contains("KEY_MOTION_MODE_EXPLICIT to true,")
        )
        val activity = compose("SoundBehaviorComposeActivity.kt")
        assertTrue(
            activity.contains("SoundBehaviorLogic.motionModeWrite(value).forEach { (key, flag) ->")
        )
        assertTrue(activity.contains("editor.putBoolean(key, flag)"))
        assertTrue(activity.contains("editor.apply()"))

        // 旧界面已归档（docs/归档-旧设置界面/）：motion_mode_explicit 的写入只剩 Compose 页一处，
        // 双写会让「用户手动拨过开关」的判定出现两个真源。
        val oldFragment = text(base + "src/main/java/com/linxi/brailleback/HomeContainerActivity.kt")
        assertFalse(
            "旧设置页归档后不得再保留第二份运动模式写入",
            oldFragment.contains("getString(R.string.pref_motion_mode_explicit_key), true")
        )
        val service = text(base + "src/main/java/com/linxi/brailleback/BrailleBackService.kt")
        assertTrue(
            service.contains("getString(R.string.pref_motion_mode_explicit_key), false")
        )
        assertTrue(
            "服务必须监听 motion_mode 才能即时生效",
            service.contains("""getString(R.string.pref_motion_mode_key).equals(key)""")
        )
    }

    @Test
    fun activityReadsMotionModeWithLegacyCheckboxDefault() {
        val activity = compose("SoundBehaviorComposeActivity.kt")
        assertTrue(
            activity.contains("prefs.getBoolean(motionKey, SoundBehaviorLogic.MOTION_MODE_DEFAULT)")
        )
        val logic = compose("SoundBehaviorLogic.kt")
        assertTrue(logic.contains("const val MOTION_MODE_DEFAULT = false"))
        // XML 里这一项刻意没有 app:defaultValue。
        val motionBlock = legacyScreen().substringAfter("pref_motion_mode_key")
            .substringBefore("/>")
        assertFalse("旧 CheckBox 无 defaultValue，Compose 不得擅自加", motionBlock.contains("defaultValue"))
    }

    // ---------------------------------------------------------- 列表与默认值

    @Test
    fun listDefaultsAndArraysMatchLegacyXml() {
        val xml = legacyScreen()
        val defaults = Regex(
            """<ListPreference\s+app:key="@string/([^"]+)"[\s\S]*?app:defaultValue="@string/([^"]+)"\s*/>"""
        ).findAll(xml).associate { it.groupValues[1] to it.groupValues[2] }
        assertEquals(
            mapOf(
                "pref_pause_behavior_key" to "pref_pause_behavior_clear_value",
                "pref_idle_clear_key" to "pref_idle_clear_3min_value",
            ),
            defaults
        )
        val activity = compose("SoundBehaviorComposeActivity.kt")
        listOf(
            "getString(R.string.pref_pause_behavior_clear_value)",
            "getString(R.string.pref_idle_clear_3min_value)",
            "R.array.pref_pause_behavior_entries",
            "R.array.pref_pause_behavior_values",
            "R.array.pref_idle_clear_entries",
            "R.array.pref_idle_clear_values",
            "prefs.edit().putString(pauseKey, value).apply()",
            "prefs.edit().putString(idleKey, value).apply()",
        ).forEach { fragment ->
            assertTrue("列表偏好接线缺失：$fragment", activity.contains(fragment))
        }
        val donottranslate = text(base + "src/main/res/values/donottranslate.xml")
        assertEquals(
            "暂停行为应有 2 个选项",
            2,
            Regex("""<item>""").findAll(
                donottranslate.substringAfter("""<string-array name="pref_pause_behavior_entries">""")
                    .substringBefore("</string-array>")
            ).count()
        )
        assertEquals(
            "闲置清除应有 4 个选项",
            4,
            Regex("""<item>""").findAll(
                donottranslate.substringAfter("""<string-array name="pref_idle_clear_entries">""")
                    .substringBefore("</string-array>")
            ).count()
        )
    }

    @Test
    fun quietNavigationUsesLegacyBoolDefault() {
        val activity = compose("SoundBehaviorComposeActivity.kt")
        assertTrue(
            activity.contains("resources.getBoolean(R.bool.pref_quiet_navigation_default)")
        )
        assertTrue(activity.contains("prefs.edit().putBoolean(quietKey, value).apply()"))
        assertTrue(
            text(base + "src/main/res/values/donottranslate.xml")
                .contains("""<bool name="pref_quiet_navigation_default">true</bool>""")
        )
    }

    // -------------------------------------------------------------- 无障碍

    @Test
    fun volumeDialogProvidesClickOnlyPathAndAnnouncesValue() {
        val components = compose("SettingsComponents.kt")
        val dialogBody = components.substringAfter("fun SettingsVolumeDialog(")
        listOf(
            "stateDescription = valueText(value)",
            "TextButton(onClick = { value = (value - 1).coerceIn(min, max) })",
            "TextButton(onClick = { value = (value + 1).coerceIn(min, max) })",
            "steps = (max - min - 1).coerceAtLeast(0)",
            "TextButton(onClick = { onConfirm(value) })",
        ).forEach { fragment ->
            assertTrue("音量对话框缺少读屏/点击兜底：$fragment", dialogBody.contains(fragment))
        }
        assertTrue(
            "当前值必须以文字呈现，不能只靠滑块位置",
            dialogBody.contains("text = valueText(value)")
        )
        assertEquals(
            "新增组件不得引入第二个 contentDescription（返回按钮那一处是唯一例外）",
            1,
            Regex("""contentDescription\s*=""").findAll(components).count()
        )
        val screen = compose("SoundBehaviorScreen.kt")
        assertTrue(screen.contains("SettingsVolumeDialog("))
        assertTrue(screen.contains("clickLabel = labels.adjustHint"))
    }

    @Test
    fun screenReusesComponentBaseWithoutCustomRows() {
        val screen = compose("SoundBehaviorScreen.kt")
        listOf(
            "SettingsScreenScaffold(",
            "SettingsSwitchRow(",
            "SettingsListRow(",
            "SettingsActionRow(",
            "SettingsChoiceDialog(",
            "SettingsVolumeDialog(",
        ).forEach { fragment ->
            assertTrue("必须复用组件底座：$fragment", screen.contains(fragment))
        }
        listOf(
            "Modifier.",
            "contentDescription",
            ".clickable(",
            ".toggleable(",
        ).forEach { fragment ->
            assertFalse(
                "子屏不得自己写 Row/语义，否则读屏行为会与组件底座漂移：$fragment",
                screen.contains(fragment)
            )
        }
    }

    @Test
    fun allThreeSwitchesAnnounceStateWithoutDoublePrefix() {
        val screen = compose("SoundBehaviorScreen.kt")
        assertEquals(
            "三个开关都要用组件底座的 stateDescription 文案",
            3,
            Regex("""onLabel = labels\.on""").findAll(screen).count()
        )
        assertEquals(3, Regex("""offLabel = labels\.off""").findAll(screen).count())
        listOf(
            "labels.soundSummaryOn", "labels.soundSummaryOff",
            "labels.motionSummaryOn", "labels.motionSummaryOff",
            "labels.quietSummaryOn", "labels.quietSummaryOff",
        ).forEach { fragment ->
            assertTrue("缺少状态摘要：$fragment", screen.contains(fragment))
        }
    }

    @Test
    fun fourthBatchStringsAreLocalizedAndHaveNoStatePrefix() {
        val chinese = text(base + "src/main/res/values-zh-rCN/strings.xml")
        val english = text(base + "src/main/res/values/strings.xml")
        listOf(
            "compose_settings_sound_summary_on",
            "compose_settings_sound_summary_off",
            "compose_settings_adjust_hint",
            "compose_settings_volume_decrease",
            "compose_settings_volume_increase",
            "compose_settings_confirm",
        ).forEach { name ->
            val pattern = """<string name="$name">""".toRegex()
            assertTrue("英文缺少 $name", pattern.containsMatchIn(english))
            assertTrue("中文缺少 $name", pattern.containsMatchIn(chinese))
        }
        val values = stringValues(base + "src/main/res/values-zh-rCN/strings.xml")
        assertEquals("减 1", values["compose_settings_volume_decrease"])
        assertEquals("加 1", values["compose_settings_volume_increase"])
        assertEquals("确定", values["compose_settings_confirm"])
        assertFalse(
            "开启摘要不能带「已开启」前缀（stateDescription 已播报）",
            requireNotNull(values["compose_settings_sound_summary_on"]).contains("已开启")
        )
        assertFalse(
            "关闭摘要不能带「已关闭」前缀",
            requireNotNull(values["compose_settings_sound_summary_off"]).contains("已关闭")
        )
    }

    // -------------------------------------------------------------- 接线边界

    @Test
    fun wiringRoutesFiveScreensToComposeIncludingDeveloper() {
        val wiring = wiring()
        assertTrue(
            "声音与行为入口必须接线到自己的 Compose 页",
            Regex(
                """wireCategory\(\s*R\.string\.pref_category_sound_behavior_key,\s*"""
                    + """SoundBehaviorComposeActivity::class\.java"""
            ).containsMatchIn(wiring)
        )
        assertEquals(
            "六个分类入口各一条 wireCategory 接线",
            6,
            Regex("""wireCategory\(""").findAll(wiring).count()
        )
        listOf(
            "DeviceConnectionComposeActivity",
            "KeysGesturesComposeActivity",
            "BrailleDisplayComposeActivity",
            "SoundBehaviorComposeActivity",
            "DeveloperOptionsComposeActivity",
            "StabilityComposeActivity",
        ).forEach { activity ->
            assertTrue("接线缺少 $activity", wiring.contains(activity))
        }
        assertTrue(
            "第五批收官：开发者选项子屏也必须被拦截",
            wiring.contains("category_developer")
        )
        assertTrue(
            "主屏宿主必须 import 新 Activity",
            text(base + "src/main/java/com/linxi/brailleback/HomeContainerActivity.kt")
                .contains("import com.linxi.brailleback.compose.SoundBehaviorComposeActivity")
        )
    }

    @Test
    fun manifestRegistersFourthComposeActivityAndFifthIsRegistered() {
        val manifest = text(base + "src/main/AndroidManifest.xml")
        assertTrue(manifest.contains("""android:name=".compose.SoundBehaviorComposeActivity""""))
        assertTrue(
            manifest.contains("""android:label="@string/pref_category_sound_behavior_title"""")
        )
        assertTrue(manifest.contains("""android:theme="@style/BraillebackSettingsTheme""""))
        assertEquals(
            "Manifest 中应有七个 Compose 设置子屏",
            7,
            Regex("""android:name="\.compose\.\w+ComposeActivity"""").findAll(manifest).count()
        )
    }

    /**
     * 模拟器实测（2026-11-15，en-US）抓到：默认资源里 5 条音效文案被写成中文，
     * 英文界面会冒出「启用音效 / 音效音量 / 音效音量：50」。
     * 护栏锁定：默认资源必须是英文，中文文案只能放在 values-zh-rCN；
     * 两边的 %1$d 占位符都不能丢，否则摘要会显示成字面量或崩溃。
     */
    @Test
    fun soundFeedbackDefaultResourcesAreEnglishAndChineseLocaleStaysChinese() {
        val defaultValues = stringValues(base + "src/main/res/values/strings.xml")
        val zhValues = stringValues(base + "src/main/res/values-zh-rCN/strings.xml")
        val cjk = Regex("[一-龥]")
        listOf(
            "pref_sound_enabled_title",
            "pref_sound_enabled_summary_on",
            "pref_sound_enabled_summary_off",
            "pref_sound_volume_title",
            "sound_editor_volume",
        ).forEach { name ->
            val en = requireNotNull(defaultValues[name]) { "默认资源缺少 $name" }
            assertFalse("默认（英文）资源 $name 不得含中文：$en", cjk.containsMatchIn(en))
            val zh = requireNotNull(zhValues[name]) { "中文资源缺少 $name" }
            assertTrue("中文资源 $name 必须保持中文：$zh", cjk.containsMatchIn(zh))
        }
        assertTrue(
            "英文音量摘要必须保留 %1\$d 占位符",
            requireNotNull(defaultValues["sound_editor_volume"]).contains("%1\$d")
        )
        assertTrue(
            "中文音量摘要必须保留 %1\$d 占位符",
            requireNotNull(zhValues["sound_editor_volume"]).contains("%1\$d")
        )
    }
}
