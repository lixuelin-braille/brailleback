/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 */

package com.googlecode.eyesfree.brailleback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Compose 迁移第二批（按键与手势）的静态契约。
 *
 * 护栏要防三件事：
 * 1) 六个设置项在迁移中丢项、多出未经真机验证的新项；
 * 2) 读写漂到另一套 SharedPreferences key，导致老用户设置丢失或两代 UI 不同步；
 * 3) 读屏语义退化——键盘帮助不再按连接状态禁用、开关状态被念两遍、绕过组件底座。
 *
 * 断言一律锚定真实代码行；用 contains("词") 这类弱断言会被注释满足，
 * 第一批变异检验已经实测踩过这个坑。
 */
class KeysGesturesContractTest {

    private fun projectFile(path: String): File {
        val candidates = listOf(File(path), File("..", path), File("../..", path))
        return candidates.firstOrNull { it.isFile }
            ?: error("找不到 $path（cwd=${System.getProperty("user.dir")}）")
    }

    private fun text(path: String) = projectFile(path).readText()

    private val base = "brailleback/"

    /** 旧五子屏冻结原文（归档，不参与编译）。 */
    private val ARCHIVE_PATH = "docs/归档-旧设置界面/settings-legacy.xml"
    private val composeDir = base + "src/com/googlecode/eyesfree/brailleback/compose/"
    private fun compose(name: String) = text(composeDir + name)

    private fun stringValues(path: String): Map<String, String> =
        Regex("""<string name="([^"]+)">([^<]*)</string>""")
            .findAll(text(path))
            .associate { it.groupValues[1] to it.groupValues[2] }

    /** settings.xml 中按键与手势子屏的片段（回退用的旧实现）。 */
    private fun legacyScreen(): String = text(ARCHIVE_PATH)
        .substringAfter("""app:key="@string/pref_category_keys_key"""")
        .substringBefore("""app:key="@string/pref_category_braille_key"""")

    // -------------------------------------------------------------- 项与键

    @Test
    fun screenRendersExactlySixLegacyItemsUnderTwoSections() {
        val screen = compose("KeysGesturesScreen.kt")
        assertEquals(
            "按键与手势子屏应正好有六个设置项（2 入口 + 1 开关 + 3 动作槽位）",
            6,
            Regex("""\bitem\(key =""").findAll(screen).count()
        )
        listOf(
            "KeysGesturesLogic.KEY_KEY_BINDINGS",
            "KeysGesturesLogic.KEY_CUSTOM_KEY_MAP",
            "KeysGesturesLogic.KEY_VOLUME_GESTURES_ENABLED",
            "item(key = up.key)",
            "item(key = down.key)",
            "item(key = doublePress.key)",
        ).forEach { fragment ->
            assertTrue("界面缺少设置项：$fragment", screen.contains(fragment))
        }
        assertEquals(
            "两个分组标题（点显器按键 / 音量键）都要保留 heading 语义",
            2,
            Regex("""settingsSectionHeader\(""").findAll(screen).count()
        )
    }

    @Test
    fun logicKeysEqualLegacyPreferenceKeyResourceValues() {
        val values = stringValues(base + "res/values/donottranslate.xml")
        val logic = compose("KeysGesturesLogic.kt")
        val pairs = listOf(
            "pref_key_bindings_key" to "KEY_KEY_BINDINGS",
            "pref_custom_key_map_key" to "KEY_CUSTOM_KEY_MAP",
            "pref_volume_gestures_enabled_key" to "KEY_VOLUME_GESTURES_ENABLED",
            "pref_volume_long_press_up_key" to "KEY_LONG_PRESS_UP",
            "pref_volume_long_press_down_key" to "KEY_LONG_PRESS_DOWN",
            "pref_volume_double_press_key" to "KEY_DOUBLE_PRESS",
        )
        pairs.forEach { (resource, constant) ->
            val value = values[resource] ?: error("资源缺失：$resource")
            assertTrue(
                "Logic.$constant 必须等于旧偏好键 $resource 的实际值 $value",
                logic.contains("""const val $constant = "$value"""")
            )
        }
    }

    @Test
    fun activityReadsAndWritesTheSameLegacyKeys() {
        val activity = compose("KeysGesturesComposeActivity.kt")
        listOf(
            "getString(R.string.pref_volume_gestures_enabled_key)",
            "getString(R.string.pref_volume_long_press_up_key)",
            "getString(R.string.pref_volume_long_press_down_key)",
            "getString(R.string.pref_volume_double_press_key)",
        ).forEach { fragment ->
            assertTrue("Activity 必须读原偏好 key：$fragment", activity.contains(fragment))
        }
        assertTrue(
            "手势开关必须回写原 key",
            activity.contains("prefs.edit().putBoolean(gesturesKey, value).apply()")
        )
        assertTrue(
            "动作槽位必须回写原 key",
            activity.contains("prefs.edit().putString(key, value).apply()")
        )
        assertTrue(
            "选中后要同步更新 Compose 状态",
            activity.contains("volumeValues = volumeValues + (key to value)")
        )
    }

    @Test
    fun slotDefaultsMatchLegacyXmlAndLogic() {
        val xml = legacyScreen()
        val defaults = Regex(
            """<ListPreference\s+app:key="@string/([^"]+)"[\s\S]*?app:defaultValue="@string/([^"]+)"\s*/>"""
        ).findAll(xml).associate { it.groupValues[1] to it.groupValues[2] }
        assertEquals(
            mapOf(
                "pref_volume_long_press_up_key" to "pref_volume_action_pause_value",
                "pref_volume_long_press_down_key" to "pref_volume_action_none_value",
                "pref_volume_double_press_key" to "pref_volume_action_grade_value",
            ),
            defaults
        )
        val logic = compose("KeysGesturesLogic.kt")
        listOf(
            "KEY_LONG_PRESS_UP -> pause",
            "KEY_DOUBLE_PRESS -> grade",
            "KEY_LONG_PRESS_DOWN -> none",
        ).forEach { fragment ->
            assertTrue("纯逻辑默认映射必须与 XML 一致：$fragment", logic.contains(fragment))
        }
    }

    // -------------------------------------------------------------- 入口与状态

    @Test
    fun bothEntriesLaunchLegacyActivities() {
        val activity = compose("KeysGesturesComposeActivity.kt")
        assertTrue(
            activity.contains("Intent(this@KeysGesturesComposeActivity, KeyBindingsActivity::class.java)")
        )
        assertTrue(
            activity.contains("Intent(this@KeysGesturesComposeActivity, CustomKeyMapActivity::class.java)")
        )
        val manifest = text(base + "AndroidManifest.xml")
        assertTrue(manifest.contains("android:name=\".KeyBindingsActivity\""))
        assertTrue(manifest.contains("android:name=\".CustomKeyMapActivity\""))
    }

    @Test
    fun helpAvailabilityFollowsConnectionState() {
        val screen = compose("KeysGesturesScreen.kt")
        assertTrue(
            "未连接时键盘帮助必须禁用（与旧 Fragment 行为一致）",
            screen.contains("enabled = helpAvailable,")
        )
        val activity = compose("KeysGesturesComposeActivity.kt")
        assertTrue(
            activity.contains("helpAvailable = KeysGesturesLogic.isHelpAvailable(connectionState)")
        )
        assertTrue(activity.contains("Display.OnConnectionStateChangeListener"))
        assertTrue(activity.contains("override fun onConnectionStateChanged(state: Int)"))
        assertTrue(activity.contains("connectionState = state"))
        assertTrue("退出必须释放点显器客户端", activity.contains("displayClient?.shutdown()"))
    }

    // -------------------------------------------------------------- 无障碍

    @Test
    fun screenReusesComponentBaseWithoutCustomRows() {
        val screen = compose("KeysGesturesScreen.kt")
        listOf(
            "SettingsScreenScaffold(",
            "SettingsActionRow(",
            "SettingsSwitchRow(",
            "SettingsListRow(",
            "SettingsChoiceDialog(",
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
    fun switchSummaryOmitsStatePrefixToAvoidDoubleAnnouncement() {
        val chinese = text(base + "res/values-zh-rCN/strings.xml")
        val english = text(base + "res/values/strings.xml")
        listOf(chinese, english).forEach { locale ->
            listOf(
                "compose_settings_open_hint",
                "compose_settings_gestures_summary_on",
                "compose_settings_gestures_summary_off",
            ).forEach { name ->
                assertTrue(
                    "缺少第二批文案 $name",
                    Regex("""<string name="$name">""").containsMatchIn(locale)
                )
            }
        }
        assertTrue(
            "中文开启摘要不能带「已开启。」前缀（stateDescription 已播报）",
            chinese.contains("""<string name="compose_settings_gestures_summary_on">慢一点的普通按压""")
        )
        assertTrue(
            "中文关闭摘要不能带「已关闭。」前缀",
            chinese.contains("""<string name="compose_settings_gestures_summary_off">音量键完全交还系统""")
        )
        val screen = compose("KeysGesturesScreen.kt")
        assertTrue(screen.contains("labels.gesturesSummaryOn"))
        assertTrue(screen.contains("labels.gesturesSummaryOff"))
        assertTrue(screen.contains("onLabel = labels.on"))
        assertTrue(screen.contains("offLabel = labels.off"))
    }

    // -------------------------------------------------------------- 接线边界

    @Test
    fun wiringRoutesFiveScreensToCompose() {
        val settings = text(base + "src/com/googlecode/eyesfree/brailleback/SettingsActivity.kt")
        val wiring = settings.substringAfter("// 六个分类入口跳各自的 Compose 页。")
            .substringBefore("// 屏幕盲文键盘")
        assertTrue(
            "按键与手势入口必须接线到自己的 Compose 页",
            Regex(
                """wireCategory\(\s*R\.string\.pref_category_keys_key,\s*"""
                    + """KeysGesturesComposeActivity::class\.java"""
            ).containsMatchIn(wiring)
        )
        assertTrue(
            wiring.contains("DeveloperOptionsComposeActivity::class.java")
        )
        assertEquals(
            "六个分类入口各一条 wireCategory 接线",
            6,
            Regex("""wireCategory\(""").findAll(wiring).count()
        )
        assertTrue(
            "第五批收官：开发者选项子屏也必须被拦截",
            wiring.contains("category_developer")
        )
    }

    @Test
    fun manifestRegistersSecondComposeActivity() {
        val manifest = text(base + "AndroidManifest.xml")
        assertTrue(
            manifest.contains("""android:name=".compose.KeysGesturesComposeActivity"""")
        )
        assertTrue(
            manifest.contains("""android:label="@string/pref_category_keys_title"""")
        )
        assertTrue(manifest.contains("""android:theme="@style/BraillebackSettingsTheme""""))
    }

    @Test
    fun legacyXmlScreenRemainsForRollback() {
        val xml = legacyScreen()
        listOf(
            "pref_key_bindings_key",
            "pref_custom_key_map_key",
            "pref_volume_gestures_enabled_key",
            "pref_volume_long_press_up_key",
            "pref_volume_long_press_down_key",
            "pref_volume_double_press_key",
        ).forEach { key ->
            assertTrue("旧子屏必须完整保留以便回退：$key", xml.contains("""@string/$key"""))
        }
        assertFalse(
            "主屏已改为 Compose 目录数据（settings.xml 已删除），不应再引用旧 XML 资源",
            text(base + "src/com/googlecode/eyesfree/brailleback/SettingsActivity.kt")
                .contains("R.xml.settings"),
        )
    }
}
