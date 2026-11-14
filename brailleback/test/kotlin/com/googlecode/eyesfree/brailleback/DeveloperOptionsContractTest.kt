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
 * Compose 迁移第五批（开发者选项，收官）的静态契约。
 *
 * 护栏要防五件事：
 * 1) 七个界面项、两个分组标题在迁移中丢项，依赖启用态与 heading 语义丢失；
 * 2) 日志级别跳过 PreferenceUtils.updateLogLevel —— 诊断包里级别是写死的，
 *    直接写偏好会让设置页显示的值和实际日志级别不一致；
 * 3) 叠加层漏跑首次教程检查，或教程行丢掉 app:dependency；
 * 4) 日志查看 / 分享 / 复制 / 清除被复制成两套实现，两代 UI 导出行为漂移；
 * 5) 把最多 1MB 的日志全文交给读屏线性朗读。
 *
 * 断言一律锚定真实代码行；contains("词") 这类弱断言会被注释满足，
 * 第一批变异检验已经实测踩过这个坑。
 */
class DeveloperOptionsContractTest {

    private fun projectFile(path: String): File {
        val candidates = listOf(File(path), File("..", path), File("../..", path))
        return candidates.firstOrNull { it.isFile }
            ?: error("找不到 $path（cwd=${System.getProperty("user.dir")}）")
    }

    private fun text(path: String) = projectFile(path).readText()

    private val base = "brailleback/"
    private val composeDir = base + "src/com/googlecode/eyesfree/brailleback/compose/"
    private fun compose(name: String) = text(composeDir + name)

    /** 兼容 translatable="false" 这类带属性的 string 资源。 */
    private fun stringValues(path: String): Map<String, String> =
        Regex("""<string name="([^"]+)"[^>]*>([^<]*)</string>""")
            .findAll(text(path))
            .associate { it.groupValues[1] to it.groupValues[2] }

    /** settings.xml 中开发者选项子屏的片段（回退用的旧实现）。 */
    private fun legacyScreen(): String = text(base + "res/xml/settings.xml")
        .substringAfter("""app:key="@string/pref_category_developer_key"""")
        .substringBefore("""app:key="@string/pref_category_features_key"""")

    private fun wiring(): String =
        text(base + "src/com/googlecode/eyesfree/brailleback/SettingsActivity.kt")
            .substringAfter("override fun onPreferenceStartScreen(")
            .substringBefore("val args = Bundle()")

    private fun settingsSource(): String =
        text(base + "src/com/googlecode/eyesfree/brailleback/SettingsActivity.kt")

    // -------------------------------------------------------------- 项与键

    @Test
    fun screenRendersExactlySevenLegacyItemsUnderTwoSections() {
        val screen = compose("DeveloperOptionsScreen.kt")
        assertEquals(
            "开发者选项应正好有七个界面项（5 日志 + 2 叠加层）",
            7,
            Regex("""\bitem\(key =""").findAll(screen).count()
        )
        listOf(
            "item(key = DeveloperOptionsLogic.KEY_LOG_LEVEL)",
            "item(key = DeveloperOptionsLogic.KEY_VIEW_LOG)",
            "item(key = DeveloperOptionsLogic.KEY_SHARE_LOG)",
            "item(key = DeveloperOptionsLogic.KEY_COPY_LOG)",
            "item(key = DeveloperOptionsLogic.KEY_CLEAR_LOG)",
            "item(key = DeveloperOptionsLogic.KEY_OVERLAY)",
            "item(key = DeveloperOptionsLogic.KEY_OVERLAY_TUTORIAL)",
        ).forEach { fragment ->
            assertTrue("界面缺少设置项：$fragment", screen.contains(fragment))
        }
        assertEquals(
            "两个分组标题（日志 / 屏幕叠加层）都要保留 heading 语义",
            2,
            Regex("""settingsSectionHeader\(""").findAll(screen).count()
        )
        assertTrue(screen.contains("settingsSectionHeader(labels.sectionLog)"))
        assertTrue(screen.contains("settingsSectionHeader(labels.sectionOverlay)"))
    }

    @Test
    fun logicKeysEqualLegacyPreferenceKeyResourceValues() {
        val resources = stringValues(base + "res/values/donottranslate.xml")
        val logic = compose("DeveloperOptionsLogic.kt")
        val pairs = listOf(
            "pref_log_level_key" to "KEY_LOG_LEVEL",
            "pref_view_log_key" to "KEY_VIEW_LOG",
            "pref_share_log_key" to "KEY_SHARE_LOG",
            "pref_copy_log_key" to "KEY_COPY_LOG",
            "pref_clear_log_key" to "KEY_CLEAR_LOG",
            "pref_braille_overlay_key" to "KEY_OVERLAY",
            "pref_braille_overlay_tutorial_key" to "KEY_OVERLAY_TUTORIAL",
        )
        pairs.forEach { (resource, constant) ->
            val value = resources[resource] ?: error("donottranslate 缺失：$resource")
            assertTrue(
                "Logic.$constant 必须等于旧偏好键 $resource 的实际值 $value",
                logic.contains("""const val $constant = "$value"""")
            )
        }
        assertEquals("log_level", resources["pref_log_level_key"])
        assertEquals("overlay_enabled", resources["pref_braille_overlay_key"])
    }

    @Test
    fun legacyXmlStillContainsAllSevenKeysAndDependencyForRollback() {
        val xml = legacyScreen()
        listOf(
            "pref_log_level_key", "pref_view_log_key", "pref_share_log_key",
            "pref_copy_log_key", "pref_clear_log_key",
            "pref_braille_overlay_key", "pref_braille_overlay_tutorial_key",
        ).forEach { key ->
            assertTrue("旧子屏必须完整保留以便回退：$key", xml.contains("""@string/$key"""))
        }
        assertTrue(
            "教程行的 app:dependency 不能丢",
            xml.contains("""app:dependency="@string/pref_braille_overlay_key"""")
        )
        assertTrue(
            "日志级别默认值必须留在 XML 里",
            xml.contains("""app:defaultValue="@string/pref_log_level_default"""")
        )
        assertEquals(
            "主屏仍应是五个平级子屏（入口层级不变）",
            5,
            Regex("""<PreferenceScreen""").findAll(text(base + "res/xml/settings.xml")).count() - 1
        )
    }

    // --------------------------------------------------- 日志级别数据源

    @Test
    fun logLevelChangeGoesThroughPreferenceUtilsAndRespectsDiagnosticBuild() {
        val activity = compose("DeveloperOptionsComposeActivity.kt")
        listOf(
            "val parsed = DeveloperOptionsLogic.parseLogLevel(value)",
            "val accepted = parsed?.let { PreferenceUtils.updateLogLevel(it) } ?: false",
            "if (DeveloperOptionsLogic.shouldPersistLogLevel(parsed, accepted)) {",
            "prefs.edit().putString(logLevelKey, value).apply()",
            "R.string.compose_settings_log_level_locked",
        ).forEach { fragment ->
            assertTrue("日志级别接线缺失：$fragment", activity.contains(fragment))
        }
        val logic = compose("DeveloperOptionsLogic.kt")
        assertTrue(
            "诊断包返回 false 时不得保存新值",
            logic.contains("parsed != null && updateAccepted")
        )
        val utils = text(base + "src/com/googlecode/eyesfree/brailleback/utils/PreferenceUtils.kt")
        assertTrue(
            "这个约束来自 PreferenceUtils 的 DIAG_LOG_LEVEL 分支，不是凭空加的",
            utils.contains("if (BuildConfig.DIAG_LOG_LEVEL > 0) {")
        )
        assertTrue(utils.contains("return false"))
    }

    @Test
    fun logLevelDefaultsAndArraysMatchLegacyXml() {
        val donottranslate = text(base + "res/values/donottranslate.xml")
        assertEquals(
            "7 档日志级别",
            7,
            Regex("""<item>""").findAll(
                donottranslate.substringAfter("""<string-array name="pref_log_level_entries">""")
                    .substringBefore("</string-array>")
            ).count()
        )
        assertEquals(
            "7 个级别取值",
            7,
            Regex("""<item>""").findAll(
                donottranslate.substringAfter("""<string-array name="pref_log_level_values">""")
                    .substringBefore("</string-array>")
            ).count()
        )
        assertTrue(donottranslate.contains("""<string name="pref_log_level_default">4</string>"""))
        val activity = compose("DeveloperOptionsComposeActivity.kt")
        listOf(
            "R.array.pref_log_level_values",
            "R.array.pref_log_level_entries",
            "getString(R.string.pref_log_level_default)",
            "prefs.getString(logLevelKey, null)",
        ).forEach { fragment ->
            assertTrue("日志级别列表接线缺失：$fragment", activity.contains(fragment))
        }
        assertTrue(compose("DeveloperOptionsLogic.kt").contains("value?.toIntOrNull()"))
    }

    // --------------------------------------------- 日志动作单一真源

    @Test
    fun debugLogActionsIsTheSingleSourceForBothUis() {
        val helper = text(base + "src/com/googlecode/eyesfree/brailleback/utils/DebugLogActions.kt")
        listOf(
            "fun readAll(context: Context): String = LogFileWriter.readAll(context)",
            "LogFileWriter.clear(context)",
            "ClipData.newPlainText(",
            "Intent(Intent.ACTION_SEND)",
            "fun shareCurrentLog(context: Context)",
            "fun copyCurrentLog(context: Context)",
            "fun copyLogToClipboard(context: Context, text: String)",
            "fun shareLog(context: Context, text: String)",
            "fun clear(context: Context)",
        ).forEach { fragment ->
            assertTrue("日志动作真源缺失：$fragment", helper.contains(fragment))
        }

        val oldFragment = settingsSource()
        listOf(
            "DebugLogActions.shareCurrentLog(requireActivity())",
            "DebugLogActions.copyCurrentLog(requireActivity())",
            "DebugLogActions.copyLogToClipboard(requireActivity(), text)",
            "DebugLogActions.shareLog(requireActivity(), text)",
            "DebugLogActions.clear(requireActivity())",
        ).forEach { fragment ->
            assertTrue("旧 Fragment 必须委托给同一真源：$fragment", oldFragment.contains(fragment))
        }
        listOf(
            "ClipData.newPlainText", "Intent.ACTION_SEND", "ClipboardManager"
        ).forEach { fragment ->
            assertFalse(
                "旧 Fragment 不得再保留第二份日志导出实现：$fragment",
                oldFragment.contains(fragment)
            )
        }

        val activity = compose("DeveloperOptionsComposeActivity.kt")
        listOf(
            "LogFileWriter.init(this)",
            "DebugLogActions.readAll(this)",
            "DebugLogActions.shareCurrentLog(this)",
            "DebugLogActions.copyCurrentLog(this)",
            "DebugLogActions.clear(this)",
        ).forEach { fragment ->
            assertTrue("Compose 页必须复用同一真源：$fragment", activity.contains(fragment))
        }
        assertTrue(oldFragment.contains("LogFileWriter.init(requireActivity())"))
    }

    // --------------------------------------------- 叠加层与首次教程

    @Test
    fun overlayFirstTimeTutorialAndDependencyArePreserved() {
        val activity = compose("DeveloperOptionsComposeActivity.kt")
        listOf(
            "prefs.edit().putBoolean(overlayKey, value).apply()",
            "if (DeveloperOptionsLogic.shouldCheckFirstTimeTutorial(value)) {",
            "OverlayTutorialActivity.startIfFirstTime(this)",
            "startActivity(Intent(this, OverlayTutorialActivity::class.java))",
            "resources.getBoolean(R.bool.pref_braille_overlay_default)",
        ).forEach { fragment ->
            assertTrue("叠加层接线缺失：$fragment", activity.contains(fragment))
        }
        val logic = compose("DeveloperOptionsLogic.kt")
        assertTrue(logic.contains("fun shouldCheckFirstTimeTutorial(newValue: Boolean): Boolean = newValue"))
        assertTrue(logic.contains("const val OVERLAY_DEFAULT = false"))
        assertTrue(
            settingsSource().contains("OverlayTutorialActivity.startIfFirstTime(requireActivity())")
        )
        // 「首次」不是我们编的：教程 Activity 自己有 PREF_HAS_RUN 守卫。
        val tutorial = text(base + "src/com/googlecode/eyesfree/brailleback/OverlayTutorialActivity.kt")
        assertTrue(tutorial.contains("prefs.getBoolean(PREF_HAS_RUN, false)"))
        assertTrue(
            text(base + "res/values/donottranslate.xml")
                .contains("""<bool name="pref_braille_overlay_default">false</bool>""")
        )
    }

    @Test
    fun tutorialRowIsDisabledWithWrittenReasonWhenOverlayIsOff() {
        val screen = compose("DeveloperOptionsScreen.kt")
        assertTrue(screen.contains("enabled = DeveloperOptionsLogic.tutorialEnabled(overlayEnabled)"))
        assertTrue(screen.contains("labels.tutorialDisabled"))
        assertTrue(
            "禁用原因必须有文字说明，不能只靠置灰",
            compose("DeveloperOptionsComposeActivity.kt")
                .contains("getString(R.string.compose_settings_overlay_tutorial_disabled)")
        )
    }

    // ------------------------------------------------------ 日志预览与导出

    @Test
    fun logPreviewKeepsReadableLengthAndFullExportPath() {
        val logic = compose("DeveloperOptionsLogic.kt")
        listOf(
            "const val LOG_PREVIEW_MAX_CHARS = 4000",
            "return truncatedHint + \"\\n\" + raw.takeLast(maxChars)",
            "fun hasLog(raw: String): Boolean = raw.isNotBlank()",
        ).forEach { fragment ->
            assertTrue("日志预览逻辑缺失：$fragment", logic.contains(fragment))
        }
        val activity = compose("DeveloperOptionsComposeActivity.kt")
        assertTrue(activity.contains("DeveloperOptionsLogic.logPreview("))
        assertTrue(activity.contains("R.string.compose_settings_log_preview_hint"))
        assertTrue(activity.contains("R.string.debug_log_empty"))
        val screen = compose("DeveloperOptionsScreen.kt")
        listOf(
            "SettingsLogViewDialog(",
            "onCopy = {",
            "onShare = {",
            "logViewBody = onViewLog()",
        ).forEach { fragment ->
            assertTrue("日志查看对话框接线缺失：$fragment", screen.contains(fragment))
        }
        // 导出走的是全量日志，不是被裁短的预览。
        val helper = text(base + "src/com/googlecode/eyesfree/brailleback/utils/DebugLogActions.kt")
        assertTrue(helper.contains("val log = readAll(context)"))
        assertTrue(helper.contains("if (log.isBlank())"))
    }

    // -------------------------------------------------------------- 无障碍

    @Test
    fun screenReusesComponentBaseWithoutCustomRows() {
        val screen = compose("DeveloperOptionsScreen.kt")
        listOf(
            "SettingsScreenScaffold(",
            "SettingsSwitchRow(",
            "SettingsListRow(",
            "SettingsActionRow(",
            "SettingsChoiceDialog(",
            "SettingsConfirmDialog(",
            "SettingsLogViewDialog(",
            "BackHandler(enabled = anyDialogOpen)",
        ).forEach { fragment ->
            assertTrue("必须复用组件底座：$fragment", screen.contains(fragment))
        }
        listOf(
            "Modifier.", "contentDescription", ".clickable(", ".toggleable("
        ).forEach { fragment ->
            assertFalse(
                "子屏不得自己写 Row/语义，否则读屏行为会与组件底座漂移：$fragment",
                screen.contains(fragment)
            )
        }
    }

    @Test
    fun newDialogComponentsDoNotAddContentDescriptions() {
        val components = compose("SettingsComponents.kt")
        assertTrue(components.contains("fun SettingsConfirmDialog("))
        assertTrue(components.contains("fun SettingsLogViewDialog("))
        listOf(
            "SelectionContainer {",
            ".verticalScroll(rememberScrollState())",
            ".heightIn(max = 320.dp)",
            "TextButton(onClick = onCopy) { Text(copyLabel) }",
            "TextButton(onClick = onShare) { Text(shareLabel) }",
            "TextButton(onClick = onConfirm) { Text(confirmLabel) }",
        ).forEach { fragment ->
            assertTrue("对话框组件缺少读屏/点击兜底：$fragment", components.contains(fragment))
        }
        assertEquals(
            "新增组件不得引入第二个 contentDescription（返回按钮那一处是唯一例外）",
            1,
            Regex("""contentDescription\s*=""").findAll(components).count()
        )
    }

    @Test
    fun singleSwitchAnnouncesStateWithoutDoublePrefix() {
        val screen = compose("DeveloperOptionsScreen.kt")
        assertEquals(1, Regex("""onLabel = labels\.on""").findAll(screen).count())
        assertEquals(1, Regex("""offLabel = labels\.off""").findAll(screen).count())
        assertTrue(screen.contains("summary = labels.overlaySummary"))
    }

    @Test
    fun fifthBatchStringsAreLocalizedAndChinese() {
        val english = text(base + "res/values/strings.xml")
        val chinese = text(base + "res/values-zh-rCN/strings.xml")
        listOf(
            "compose_settings_log_action_hint",
            "compose_settings_log_level_locked",
            "compose_settings_log_preview_hint",
            "compose_settings_overlay_tutorial_disabled",
        ).forEach { name ->
            val pattern = """<string name="$name">""".toRegex()
            assertTrue("英文缺少 $name", pattern.containsMatchIn(english))
            assertTrue("中文缺少 $name", pattern.containsMatchIn(chinese))
        }
        val values = stringValues(base + "res/values-zh-rCN/strings.xml")
        assertEquals("双击执行", values["compose_settings_log_action_hint"])
        assertEquals(
            "此诊断包已固定日志级别，无法在这里修改",
            values["compose_settings_log_level_locked"]
        )
        assertEquals(
            "需要先开启屏幕叠加层",
            values["compose_settings_overlay_tutorial_disabled"]
        )
        assertTrue(
            "预览提示必须说明完整日志的导出方式",
            values["compose_settings_log_preview_hint"]!!.contains("复制或分享")
        )
    }

    // -------------------------------------------------------------- 接线边界

    @Test
    fun wiringRoutesAllFiveScreensToCompose() {
        val wiring = wiring()
        assertTrue(
            wiring.contains("""getString(R.string.pref_category_developer_key) -> {""")
        )
        assertTrue(
            wiring.contains("Intent(this, DeveloperOptionsComposeActivity::class.java)")
        )
        assertEquals(
            "第五批收官后共有五条 Compose 接线分支",
            5,
            Regex("""startActivity\(""").findAll(wiring).count()
        )
        listOf(
            "DeviceConnectionComposeActivity",
            "KeysGesturesComposeActivity",
            "BrailleDisplayComposeActivity",
            "SoundBehaviorComposeActivity",
            "DeveloperOptionsComposeActivity",
        ).forEach { activity ->
            assertTrue("接线缺少 $activity", wiring.contains(activity))
        }
        assertTrue(
            "SettingsActivity 必须 import 新 Activity",
            settingsSource().contains(
                "import com.googlecode.eyesfree.brailleback.compose.DeveloperOptionsComposeActivity"
            )
        )
    }

    @Test
    fun manifestRegistersFifthComposeActivity() {
        val manifest = text(base + "AndroidManifest.xml")
        assertTrue(manifest.contains("""android:name=".compose.DeveloperOptionsComposeActivity""""))
        assertTrue(manifest.contains("""android:label="@string/pref_category_developer_title""""))
        assertTrue(manifest.contains("""android:theme="@style/BraillebackSettingsTheme""""))
        assertEquals(
            "Manifest 中应有五个 Compose 设置子屏",
            5,
            Regex("""android:name="\.compose\.\w+ComposeActivity"""").findAll(manifest).count()
        )
    }

    /**
     * 模拟器实测（2026-11-15，zh-CN）抓到：Google 原翻译把 "Launch tutorial"
     * 译成了「发布教程」，读屏用户会误以为要发布内容。锁定中文为「打开教程」。
     */
    @Test
    fun chineseOverlayTutorialTitleMeansOpenNotPublish() {
        val en = stringValues(base + "res/values/strings.xml")
        val zh = stringValues(base + "res/values-zh-rCN/strings.xml")
        val name = "pref_braille_overlay_tutorial_title"
        assertEquals("英文标题应保持不变", "Launch tutorial", en[name])
        val title = zh[name]
        assertTrue("中文资源缺少 $name", title != null)
        assertTrue("中文标题必须含「教程」：$title", title!!.contains("教程"))
        assertFalse("中文教程标题不得误译成「发布教程」：$title", title.contains("发布"))
    }

    @Test
    fun allFiveLegacySubScreensRemainInXmlForRollback() {
        val xml = text(base + "res/xml/settings.xml")
        listOf(
            "pref_category_device_key",
            "pref_category_keys_key",
            "pref_category_braille_key",
            "pref_category_sound_behavior_key",
            "pref_category_developer_key",
        ).forEach { key ->
            assertTrue("子屏 XML 必须完整保留以便回退：$key", xml.contains("@string/$key"))
        }
    }
}
