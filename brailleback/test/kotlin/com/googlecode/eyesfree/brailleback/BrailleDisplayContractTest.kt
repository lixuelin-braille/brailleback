/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 */

package com.googlecode.eyesfree.brailleback

import com.googlecode.eyesfree.brailleback.compose.BrailleTableCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Compose 迁移第三批（盲文显示 + 按国家/地区分组的盲文表）静态契约。
 *
 * 护栏要防四件事：
 * 1) 五个设置项在迁移中丢项，或读写漂到另一套 SharedPreferences key；
 * 2) 动态表清单被写死、服务回调与偏好监听被漏掉（这会让换表不生效）；
 * 3) 分组退化回平铺、分组标题失去 heading 语义、出现「下一页」按钮；
 * 4) 中国台湾/中国香港/中国澳门被系统 Locale 渲染成不合规表述。
 *
 * 断言一律锚定真实代码行，不用会被注释满足的弱断言
 * （第一批变异检验实测踩过 `contains("Role.Switch")` 的坑）。
 */
class BrailleDisplayContractTest {

    private fun projectFile(path: String): File {
        val candidates = listOf(File(path), File("..", path), File("../..", path))
        return candidates.firstOrNull { it.isFile }
            ?: error("找不到 $path（cwd=${System.getProperty("user.dir")}）")
    }

    private fun text(path: String) = projectFile(path).readText()

    private val base = "brailleback/"
    private val composeDir = base + "src/com/googlecode/eyesfree/brailleback/compose/"
    private fun compose(name: String) = text(composeDir + name)

    private fun stringValues(path: String): Map<String, String> =
        Regex("""<string name="([^"]+)">([^<]*)</string>""")
            .findAll(text(path))
            .associate { it.groupValues[1] to it.groupValues[2] }

    /** settings.xml 中盲文显示子屏的片段（回退用的旧实现）。 */
    private fun legacyScreen(): String = text(base + "res/xml/settings.xml")
        .substringAfter("""app:key="@string/pref_category_braille_key"""")
        .substringBefore("""app:key="@string/pref_category_sound_behavior_key"""")

    // -------------------------------------------------------------- 项与键

    @Test
    fun screenRendersExactlyFiveLegacyItems() {
        val screen = compose("BrailleDisplayScreen.kt")
        listOf(
            "item(key = BrailleDisplayLogic.KEY_BRAILLE_TABLE)",
            "item(key = BrailleDisplayLogic.KEY_WORD_WRAP)",
            "item(key = BrailleDisplayLogic.KEY_CURSOR_DOTS)",
            "item(key = BrailleDisplayLogic.KEY_CONTRACTED_MODE)",
            "item(key = BrailleDisplayLogic.KEY_CHINESE_SEGMENTATION)",
        ).forEach { fragment ->
            assertTrue("界面缺少设置项：$fragment", screen.contains(fragment))
        }
        val mainList = screen.substringAfter("} else {")
        assertEquals(
            "设置列表本身只能是五项（表选择器是另一段懒加载内容）",
            5,
            Regex("""\bitem\(key =""").findAll(mainList).count()
        )
        assertTrue(
            "一个分组标题（盲文类型）要保留 heading 语义",
            mainList.contains("settingsSectionHeader(labels.sectionBrailleType)")
        )
    }

    @Test
    fun logicKeysEqualLegacyPreferenceKeyResourceValues() {
        val values = stringValues(base + "res/values/donottranslate.xml")
        val logic = compose("BrailleDisplayLogic.kt")
        val pairs = listOf(
            "pref_braille_table_key" to "KEY_BRAILLE_TABLE",
            "pref_braille_word_wrap_key" to "KEY_WORD_WRAP",
            "pref_cursor_dots_key" to "KEY_CURSOR_DOTS",
            "pref_braille_contracted_mode_key" to "KEY_CONTRACTED_MODE",
            "pref_chinese_segmentation_key" to "KEY_CHINESE_SEGMENTATION",
        )
        pairs.forEach { (resource, constant) ->
            val value = values[resource] ?: error("资源缺失：$resource")
            assertTrue(
                "Logic.$constant 必须等于旧偏好键 $resource 的实际值 $value",
                logic.contains("""const val $constant = "$value"""")
            )
        }
        assertEquals("braille_table", values["pref_braille_table_key"])
        assertEquals("auto", values["table_value_default"])
    }

    @Test
    fun activityReadsAndWritesTheSameLegacyKeys() {
        val activity = compose("BrailleDisplayComposeActivity.kt")
        listOf(
            "getString(R.string.pref_braille_table_key)",
            "R.bool.pref_braille_word_wrap_default",
            "R.bool.pref_chinese_segmentation_default",
            "PreferenceManager.getDefaultSharedPreferences(this)",
        ).forEach { fragment ->
            assertTrue("Activity 必须读原偏好：$fragment", activity.contains(fragment))
        }
        assertTrue(
            "选表必须回写原 key",
            activity.contains("prefs.edit().putString(tableKey, value).apply()")
        )
        assertTrue(
            "四个开关必须回写原 key",
            activity.contains("prefs.edit().putBoolean(key, value).apply()")
        )
    }

    // -------------------------------------------------------- 动态表清单

    @Test
    fun tableListComesFromTranslatorServiceNotAStaticArray() {
        val activity = compose("BrailleDisplayComposeActivity.kt")
        listOf(
            "manager.getTranslatorClient().getTables().orEmpty()",
            "manager.findDefaultTableInfo(false)",
            "TranslatorManager.OnTablesChangedListener",
            "manager.addOnTablesChangedListener(this)",
            "override fun onTablesChanged()",
            "runOnUiThread { refreshTables() }",
            "translatorManager?.removeOnTablesChangedListener(this)",
            "translatorManager?.shutdown()",
        ).forEach { fragment ->
            assertTrue("表清单服务链路缺少：$fragment", activity.contains(fragment))
        }
        assertFalse(
            "不能把 72 张表写死在 Compose 代码里",
            activity.contains("zh-CN-cbs") || activity.contains("en-US-g1")
        )
    }

    @Test
    fun refreshBroadcastMatchesLegacyFragment() {
        val activity = compose("BrailleDisplayComposeActivity.kt")
        assertTrue(
            activity.contains("IntentFilter(SettingsActivity.INTENT_REFRESH_DISPLAY)")
        )
        assertTrue(activity.contains("ContextCompat.RECEIVER_NOT_EXPORTED"))
        assertTrue(activity.contains("unregisterReceiver(refreshReceiver)"))
        assertTrue(
            "收到广播后要重新读取偏好值（服务可能刚切过六/八点）",
            activity.contains("currentTable = BrailleDisplayLogic.initialTable(")
        )
    }

    // ---------------------------------------------------------- 国家分组

    @Test
    fun groupingUsesCountryFirstAndLanguageFallback() {
        val catalog = compose("BrailleTableCatalog.kt")
        assertTrue(
            "有国家/地区码时分组标题必须是地区名",
            catalog.contains("if (seed.countryName.isNotEmpty()) {")
        )
        assertTrue(
            "无国家码时必须回落到语言名 + 通用后缀",
            catalog.contains("seed.languageName + genericGroupSuffix")
        )
        assertTrue(
            "gradeCount 必须按 locale 统计，与旧 getRelatedTables 口径一致",
            catalog.contains(".groupingBy { it.localeKey() }") &&
                catalog.contains(".eachCount()")
        )
        assertTrue(
            "系统 locale 组必须置顶（参考谷歌 AddLanguageActivity）",
            catalog.contains("val rank = when {") && catalog.contains("-> 0")
        )
        assertTrue(catalog.contains("Collator.getInstance()"))
    }

    @Test
    fun chinaRegionsAreExplicitlyMappedInBothLanguages() {
        val catalog = compose("BrailleTableCatalog.kt")
        listOf(
            """"TW" to "中国台湾"""",
            """"HK" to "中国香港"""",
            """"MO" to "中国澳门"""",
            """"TW" to "Taiwan, China"""",
            """"HK" to "Hong Kong, China"""",
            """"MO" to "Macao, China"""",
        ).forEach { fragment ->
            assertTrue("缺少合规地区名映射：$fragment", catalog.contains(fragment))
        }
    }

    @Test
    fun activityPassesSystemLocaleAndReusesLegacyNameTemplates() {
        val activity = compose("BrailleDisplayComposeActivity.kt")
        assertTrue(activity.contains("BrailleTableCatalog.build("))
        assertTrue(activity.contains("Locale.getDefault().language"))
        assertTrue(activity.contains("Locale.getDefault().country"))
        listOf(
            "R.string.table_name_eight_dot",
            "R.string.table_name_variant",
            "R.string.table_name_grade",
            "R.string.table_name_variant_grade",
            "R.string.pref_braille_table_default_label",
            "R.string.pref_braille_table_default_none_label",
        ).forEach { fragment ->
            assertTrue("必须复用旧表名模板：$fragment", activity.contains(fragment))
        }
        assertTrue(activity.contains("BrailleTableCatalog.regionName("))
    }

    @Test
    fun pickerLoadsEveryGroupAtOnceWithHeadingSemantics() {
        val screen = compose("BrailleDisplayScreen.kt")
        listOf(
            "groups.forEach { group ->",
            "settingsSectionHeader(group.title)",
            "group.choices.forEach { choice ->",
            "item(key = \"table:\${choice.id}\")",
            "SettingsRadioRow(",
            "BackHandler(enabled = pickerOpen)",
            "onBack = { if (pickerOpen) onClosePicker() else onBack() }",
        ).forEach { fragment ->
            assertTrue("表选择器缺少：$fragment", screen.contains(fragment))
        }
        assertFalse(
            "分组列表必须一次加载，不能有「下一页」按钮",
            screen.contains("下一页") || screen.contains("next page")
        )
        assertFalse(
            "选择器不能手写整行 contentDescription（只查代码行，注释里的词不算）",
            Regex("""contentDescription\s*=""").containsMatchIn(screen)
        )
    }

    @Test
    fun screenReusesComponentBaseWithoutCustomRows() {
        val screen = compose("BrailleDisplayScreen.kt")
        listOf(
            "SettingsScreenScaffold(",
            "SettingsListRow(",
            "SettingsSwitchRow(",
            "SettingsActionRow(",
            "SettingsRadioRow(",
        ).forEach { fragment ->
            assertTrue("必须复用组件底座：$fragment", screen.contains(fragment))
        }
        listOf("Modifier.", ".clickable(", ".toggleable(", "contentDescription =")
            .forEach { fragment ->
                assertFalse(
                    "子屏不得自己写 Row/语义，否则读屏行为会与组件底座漂移：$fragment",
                    screen.contains(fragment)
                )
            }
    }

    @Test
    fun radioRowIsPartOfTheReusableComponentBase() {
        val components = compose("SettingsComponents.kt")
        assertTrue(components.contains("fun SettingsRadioRow("))
        // 单选对话框里也有 Role.RadioButton / stateDescription；必须只检查
        // SettingsRadioRow 函数体，否则把这一行降级成 Button 会被对话框满足。
        val radioBody = components.substringAfter("fun SettingsRadioRow(")
            .substringBefore("fun SettingsChoiceDialog(")
        assertTrue(
            "长列表单选行必须是 Role.RadioButton",
            radioBody.contains("role = Role.RadioButton")
        )
        assertTrue(
            "长列表单选行必须播报选中状态",
            radioBody.contains(
                "stateDescription = if (selected) selectedLabel else unselectedLabel"
            )
        )
        assertTrue(
            radioBody.contains("semantics(mergeDescendants = true)")
        )
        assertEquals(
            "整行朗读只应由 mergeDescendants + stateDescription 组合；" +
                "新增组件不能引入第二个 contentDescription",
            1,
            Regex("""contentDescription\s*=""").findAll(components).count()
        )
    }

    @Test
    fun realTableListIsGroupedByCountryWithoutDroppingTables() {
        // 用服务侧真实表清单跑一遍纯逻辑，避免「分组效果」只停留在口算。
        val xml = text("service/res/xml/tablelist.xml")
        val seeds = Regex("""<table\b([\s\S]*?)/?>""").findAll(xml).mapNotNull { match ->
            val attrs = match.groupValues[1]
            val id = Regex("""id="([^"]+)"""").find(attrs)?.groupValues?.get(1)
                ?: return@mapNotNull null
            val locale = Regex("""locale="([^"]+)"""").find(attrs)?.groupValues?.get(1)
                ?: return@mapNotNull null
            val parts = locale.split("_")
            val country = parts.getOrElse(1) { "" }
            val dots = Regex("""dots="(\d+)"""").find(attrs)?.groupValues?.get(1)
                ?.toIntOrNull() ?: 6
            val grade = Regex("""grade="(\d+)"""").find(attrs)?.groupValues?.get(1)
                ?.toIntOrNull() ?: 1
            val variant = Regex("""variant="([^"]*)"""").find(attrs)
                ?.groupValues?.get(1).orEmpty()
            BrailleTableCatalog.TableSeed(
                id = id,
                language = parts[0],
                country = country,
                localeName = locale,
                languageName = parts[0],
                countryName = country, // 用国家码当组名，不依赖 JVM 默认 locale
                isEightDot = dots == 8,
                grade = grade,
                variant = variant,
            )
        }.toList()
        assertEquals("服务侧表清单应有 72 张表", 72, seeds.size)

        val labels = BrailleTableCatalog.TableLabels(
            genericGroupSuffix = "（通用）",
            eightDot = "%1\$s（八点）",
            variant = "%1\$s（%2\$s）",
            grade = "%1\$s - %2\$d 级",
            variantGrade = "%1\$s（%2\$s）- %3\$d 级",
        )
        val groups = BrailleTableCatalog.build(seeds, "zh", "CN", labels)
        assertEquals("72 张表一张都不能丢", 72, groups.flatMap { it.choices }.size)
        assertEquals("8 个国家/地区 + 34 个无国家码语言 = 42 组", 42, groups.size)
        assertEquals("系统语言+国家（中文/中国）组必须置顶", "CN", groups.first().title)
        assertEquals("中国组的五张中文表必须在一起", 5, groups.first().choices.size)
        assertTrue("中国台湾单独成组", groups.any { it.title == "TW" })
        assertTrue("无国家码的中文表有通用组", groups.any { it.title == "zh（通用）" })
    }

    // -------------------------------------------------------------- 文案

    @Test
    fun cursorDotsSummaryOmitsStatePrefixToAvoidDoubleAnnouncement() {
        val chinese = text(base + "res/values-zh-rCN/strings.xml")
        val english = text(base + "res/values/strings.xml")
        listOf(chinese, english).forEach { locale ->
            listOf(
                "compose_settings_cursor_dots_summary_on",
                "compose_settings_cursor_dots_summary_off",
            ).forEach { name ->
                assertTrue(
                    "缺少第三批开关文案 $name",
                    Regex("""<string name="$name">""").containsMatchIn(locale)
                )
            }
        }
        assertTrue(
            "中文开启摘要必须直接以内容开头，不能带「已开启。」前缀（stateDescription 已播报）",
            chinese.contains(
                """<string name="compose_settings_cursor_dots_summary_on">光标和焦点"""
            )
        )
        assertTrue(
            "中文关闭摘要必须直接以内容开头，不能带「已关闭。」前缀",
            chinese.contains(
                """<string name="compose_settings_cursor_dots_summary_off">盲文格上"""
            )
        )
        val screen = compose("BrailleDisplayScreen.kt")
        assertTrue(screen.contains("labels.cursorDotsSummaryOn"))
        assertTrue(screen.contains("labels.cursorDotsSummaryOff"))
    }

    @Test
    fun thirdBatchStringsExistInBothLocales() {
        val english = text(base + "res/values/strings.xml")
        val chinese = text(base + "res/values-zh-rCN/strings.xml")
        listOf(
            "compose_table_picker_title",
            "compose_table_group_auto",
            "compose_table_group_generic",
            "compose_table_loading",
            "compose_table_empty",
            "compose_table_refresh_hint",
            "compose_settings_cursor_dots_summary_on",
            "compose_settings_cursor_dots_summary_off",
        ).forEach { name ->
            val pattern = """<string name="$name">""".toRegex()
            assertTrue("英文缺少 $name", pattern.containsMatchIn(english))
            assertTrue("中文缺少 $name", pattern.containsMatchIn(chinese))
        }
        // 中文 UI 硬规范：新增文案必须是中文，不能把英文直接搬过来。
        val values = stringValues(base + "res/values-zh-rCN/strings.xml")
        assertEquals("选择盲文表", values["compose_table_picker_title"])
        assertEquals("（通用）", values["compose_table_group_generic"])
    }

    // -------------------------------------------------------------- 接线边界

    @Test
    fun wiringRoutesFiveScreensToComposeAndNoOthers() {
        val settings = text(base + "src/com/googlecode/eyesfree/brailleback/SettingsActivity.kt")
        val wiring = settings.substringAfter("override fun onPreferenceStartScreen(")
            .substringBefore("val args = Bundle()")
        assertTrue(
            wiring.contains("""getString(R.string.pref_category_braille_key) -> {""")
        )
        assertTrue(
            wiring.contains("Intent(this, BrailleDisplayComposeActivity::class.java)")
        )
        assertTrue(
            wiring.contains("Intent(this, DeveloperOptionsComposeActivity::class.java)")
        )
        assertEquals(
            "第五批收官后共有五条 Compose 接线分支（本批接线仍保留）",
            5,
            Regex("""startActivity\(""").findAll(wiring).count()
        )
        assertTrue(
            "第五批收官：开发者选项子屏也必须被拦截",
            wiring.contains("category_developer")
        )
    }

    @Test
    fun manifestRegistersThirdComposeActivity() {
        val manifest = text(base + "AndroidManifest.xml")
        assertTrue(
            manifest.contains("""android:name=".compose.BrailleDisplayComposeActivity"""")
        )
        assertTrue(
            manifest.contains("""android:label="@string/pref_category_braille_title"""")
        )
        assertTrue(manifest.contains("""android:theme="@style/BraillebackSettingsTheme""""))
    }

    @Test
    fun legacyXmlScreenRemainsForRollback() {
        val xml = legacyScreen()
        listOf(
            "pref_braille_table_key",
            "pref_braille_word_wrap_key",
            "pref_cursor_dots_key",
            "pref_braille_contracted_mode_key",
            "pref_chinese_segmentation_key",
        ).forEach { key ->
            assertTrue("旧子屏必须完整保留以便回退：$key", xml.contains("""@string/$key"""))
        }
        assertEquals(
            "主屏仍应是五个平级子屏（入口层级不变）",
            5,
            Regex("""<PreferenceScreen""").findAll(text(base + "res/xml/settings.xml")).count() - 1
        )
    }
}
