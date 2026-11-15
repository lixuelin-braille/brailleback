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
 * 「盲文显示」子屏静态契约。
 *
 * 第三批做「按国家/地区分组」时，42 个组名是列表里的 heading 标题；
 * 本批改为严格复刻谷歌读屏 `AddLanguageActivity` → `LocaleLanguageActivity`：
 * 第一级全屏只列语言（可点击行），第二级全屏平铺该语言的所有表，
 * 两段都不允许出现分组标题。
 *
 * 护栏要防五件事：
 * 1) 五个设置项在改造中丢项，或读写漂到另一套 SharedPreferences key；
 * 2) 动态表清单被写死、服务回调与偏好监听被漏掉（这会让换表不生效）；
 * 3) 退回「同一列表 + heading 标题」的老形式，或加「下一页」按钮；
 * 4) 语言列表行不可点击 / 第二级不是整屏替换 / 系统返回键少一段；
 * 5) 中国台湾/中国香港/中国澳门被系统 Locale 渲染成不合规表述，
 *    或默认英文资源里混入中文。
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

    /** 旧五子屏冻结原文（归档，不参与编译）。 */
    private val ARCHIVE_PATH = "docs/归档-旧设置界面/settings-legacy.xml"
    private val composeDir = base + "src/com/googlecode/eyesfree/brailleback/compose/"
    private fun compose(name: String) = text(composeDir + name)

    private fun stringValues(path: String): Map<String, String> =
        Regex("""<string name="([^"]+)">([^<]*)</string>""")
            .findAll(text(path))
            .associate { it.groupValues[1] to it.groupValues[2] }

    /** settings.xml 中盲文显示子屏的片段（回退用的旧实现）。 */
    private fun legacyScreen(): String = text(ARCHIVE_PATH)
        .substringAfter("""app:key="@string/pref_category_braille_key"""")
        .substringBefore("""app:key="@string/pref_category_sound_behavior_key"""")

    // -------------------------------------------------------------- 项与键

    @Test
    fun screenRendersExactlyFiveLegacyItems() {
        val screen = compose("BrailleDisplayScreen.kt")
        // 只取 Settings 路由那一段，避免把两段全屏选择器的 item 算进来。
        val settingsList = screen.substringAfter("TableRoute.Settings -> {")
            .substringBefore("TableRoute.Languages -> {")
        listOf(
            "item(key = BrailleDisplayLogic.KEY_BRAILLE_TABLE)",
            "item(key = BrailleDisplayLogic.KEY_WORD_WRAP)",
            "item(key = BrailleDisplayLogic.KEY_CURSOR_DOTS)",
            "item(key = BrailleDisplayLogic.KEY_CONTRACTED_MODE)",
            "item(key = BrailleDisplayLogic.KEY_CHINESE_SEGMENTATION)",
        ).forEach { fragment ->
            assertTrue("界面缺少设置项：$fragment", settingsList.contains(fragment))
        }
        assertEquals(
            "设置列表本身只能是五项（两级选择器是另外两段内容）",
            5,
            Regex("""\bitem\(key =""").findAll(settingsList).count()
        )
        assertTrue(
            "设置列表原有的一个分组标题（盲文类型）要保留 heading 语义",
            settingsList.contains("settingsSectionHeader(labels.sectionBrailleType)")
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
    fun activityAndViewModelReadAndWriteTheSameLegacyKeys() {
        val activity = compose("BrailleDisplayComposeActivity.kt")
        val vm = compose("BrailleDisplayViewModel.kt")
        // 键名仍然只从资源解析：key 是 SharedPreferences 的 ABI，不能出现字面量。
        listOf(
            "getString(R.string.pref_braille_table_key)",
            "getString(R.string.pref_preferred_codes_key)",
            "R.bool.pref_braille_word_wrap_default",
            "R.bool.pref_chinese_segmentation_default",
            "PreferenceManager.getDefaultSharedPreferences(this)",
            "BrailleSettingsKeys(",
        ).forEach { fragment ->
            assertTrue("Activity 必须解析原偏好键：$fragment", activity.contains(fragment))
        }
        // 写入口收敛到 ViewModel 一处，且一律经由 keys（不是复制的字面量）。
        listOf(
            "store.putString(keys.table,",
            "store.putStringSet(keys.preferredCodes,",
            "store.putBoolean(key, value)",
        ).forEach { fragment ->
            assertTrue("ViewModel 回写原 key 缺少：$fragment", vm.contains(fragment))
        }
        assertFalse(
            "Activity 不能再自己写偏好：两处写入口就是两套口径",
            activity.contains("prefs.edit()")
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
            activity.contains("viewModel.refreshFromStore()")
        )
    }

    // ---------------------------------------------------------- 语言分组

    @Test
    fun groupingUsesLanguageCodeAsFirstLevelKey() {
        val catalog = compose("BrailleTableCatalog.kt")
        listOf(
            "grouped.getOrPut(seed.language.lowercase()) { ArrayList() }.add(seed)",
            "val rank = if (language.equals(systemLanguage, ignoreCase = true)) 0 else 1",
            "title = languageTitle(sorted.first()),",
            "language = language,",
            "fun languageTitle(seed: TableSeed): String = seed.languageName",
            "Collator.getInstance()",
            ".groupingBy { it.localeKey() }",
            ".eachCount()",
        ).forEach { fragment ->
            assertTrue("语言分组真源缺少：$fragment", catalog.contains(fragment))
        }
        assertTrue(
            "系统语言组必须置顶、其余按语言显示名 Collator 排序（复刻 AddLanguageActivity）",
            catalog.contains(
                ".sortedWith(compareBy({ it.rank }, { collator.getCollationKey(it.group.title) }))"
            )
        )
        assertFalse(
            "不能退回按国家名当一级标题的旧实现",
            catalog.contains("fun groupTitle(") ||
                catalog.contains("grouped.getOrPut(title)")
        )
    }

    @Test
    fun secondLevelSortsByCountryButNeverCreatesAHeading() {
        val catalog = compose("BrailleTableCatalog.kt")
        assertTrue(
            "第二级组内先按国家/地区名聚拢，国家层级仍体现在条目里",
            catalog.contains("{ collator.getCollationKey(it.countryName) },")
        )
        assertTrue(
            "第二级仍是平铺的 choices 列表，不产生 TableGroup 之外的分组",
            catalog.contains("choices = sorted.map {")
        )
        val screen = compose("BrailleDisplayScreen.kt")
        assertFalse(
            "选择器两段都不能再出现分组标题",
            screen.contains("settingsSectionHeader(group.title)")
        )
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
        assertTrue(
            "条目名必须用合规地区名拼装，不能直接用系统显示名",
            catalog.contains("fun compliantLocaleName(seed: TableSeed, labels: TableLabels): String =") &&
                catalog.contains("labels.localePattern.format(seed.languageName, seed.countryName)")
        )
    }

    @Test
    fun activityBuildsCompliantLocaleNamesAndPassesOnlySystemLanguage() {
        val activity = compose("BrailleDisplayComposeActivity.kt")
        listOf(
            "BrailleTableCatalog.build(",
            "Locale.getDefault().language",
            "R.string.compose_table_locale_pattern",
            "BrailleTableCatalog.compliantLocaleName(seed, tableLabels)",
            "BrailleTableCatalog.regionName(",
        ).forEach { fragment ->
            assertTrue("Activity 缺少：$fragment", activity.contains(fragment))
        }
        assertFalse(
            "按语言分组后不能再把系统国家码传进 build（谷歌 AddLanguageActivity 只看 language）",
            activity.contains("Locale.getDefault().country")
        )
        assertFalse(
            "不能让系统本地化显示名绕过中国台湾/中国香港/中国澳门的合规映射",
            activity.contains("localeName = locale.getDisplayName()")
        )
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
    }

    // ------------------------------------------------------ 两级全屏导航

    @Test
    fun routeLogicDefinesThreeFullScreenStatesAndBackOrder() {
        val logic = compose("BrailleDisplayLogic.kt")
        listOf(
            "object Settings : TableRoute()",
            "object Languages : TableRoute()",
            "data class Tables(val language: String) : TableRoute()",
            "fun isPicker(route: TableRoute): Boolean = route !is TableRoute.Settings",
            "is TableRoute.Tables -> TableRoute.Languages",
            "TableRoute.Languages -> TableRoute.Settings",
            "TableRoute.Settings -> TableRoute.Settings",
            "fun groupForLanguage(",
            "fun languageOfTable(",
        ).forEach { fragment ->
            assertTrue("路由真源缺少：$fragment", logic.contains(fragment))
        }
        // back() 函数体必须按「第二级 → 第一级 → 设置列表」顺序，且只有三段。
        val backBody = logic.substringAfter("fun back(route: TableRoute): TableRoute = when (route) {")
            .substringBefore("}")
        assertEquals(3, Regex("""-> TableRoute\.""").findAll(backBody).count())
    }

    @Test
    fun screenRendersThreeRoutesAndHandlesBackForBothPickers() {
        val screen = compose("BrailleDisplayScreen.kt")
        listOf(
            "val inPicker = BrailleDisplayLogic.isPicker(route)",
            "BackHandler(enabled = inPicker) { onNavigateBack() }",
            "onBack = { if (inPicker) onNavigateBack() else onBack() },",
            "when (route) {",
            "BrailleDisplayLogic.TableRoute.Settings -> {",
            "BrailleDisplayLogic.TableRoute.Languages -> {",
            "is BrailleDisplayLogic.TableRoute.Tables -> {",
            "title = title,",
        ).forEach { fragment ->
            assertTrue("Screen 缺少：$fragment", screen.contains(fragment))
        }
    }

    @Test
    fun firstLevelIsClickableLanguageRowsWithoutHeadings() {
        val screen = compose("BrailleDisplayScreen.kt")
        val languages = screen.substringAfter("TableRoute.Languages -> {")
            .substringBefore("is BrailleDisplayLogic.TableRoute.Tables -> {")
        listOf(
            "item(key = \"table:auto\")",
            "languageRows.forEach { row ->",
            "item(key = \"language:\${row.language}\")",
            "SettingsActionRow(",
            "onClick = { onOpenLanguage(row.language) },",
        ).forEach { fragment ->
            assertTrue("第一级语言列表缺少：$fragment", languages.contains(fragment))
        }
        assertFalse(
            "第一级不能有任何 heading 分组标题（用户明确要求不要标题形式）",
            languages.contains("settingsSectionHeader(")
        )
        assertTrue(
            "「自动」必须是可点击的单选行，不是标题",
            languages.contains("SettingsRadioRow(")
        )
    }

    @Test
    fun secondLevelIsFullScreenFlatTableListWithoutHeadings() {
        val screen = compose("BrailleDisplayScreen.kt")
        val tables = screen.substringAfter("is BrailleDisplayLogic.TableRoute.Tables -> {")
        listOf(
            "codes.forEach { code ->",
            "item(key = \"code:\${code.id}\")",
            "item(key = \"code:current\")",
            "SettingsCheckRow(",
            "SettingsListRow(",
            "onCheckedChange = { onToggleCode(code.id, it) },",
        ).forEach { fragment ->
            assertTrue("第二级方案列表缺少：$fragment", tables.contains(fragment))
        }
        assertFalse(
            "多选方案模型不能退回单选表行",
            tables.contains("SettingsRadioRow(")
        )
        assertFalse(
            "第二级整屏平铺，不能有分组标题",
            tables.contains("settingsSectionHeader(")
        )
        assertFalse(
            "不能退回 groups.forEach 的单列表平铺",
            screen.contains("groups.forEach { group ->")
        )
        assertFalse(
            "两级列表都一次性加载，不能有「下一页」按钮",
            screen.contains("下一页") || screen.contains("next page")
        )
        assertFalse(
            "选择器不能手写整行 contentDescription（只查代码行，注释里的词不算）",
            Regex("""contentDescription\s*=""").containsMatchIn(screen)
        )
    }

    @Test
    fun activityWiresRouteChangesAndSecondLevelTitle() {
        val activity = compose("BrailleDisplayComposeActivity.kt")
        listOf(
            "private var route by mutableStateOf<BrailleDisplayLogic.TableRoute>(",
            "val currentRoute = route",
            "val languageRows = codeGroups.map { group ->",
            "R.string.compose_code_language_summary",
            "R.string.compose_table_language_current",
            "R.string.compose_table_language_open_hint",
            "val visibleCodes = (currentRoute as? BrailleDisplayLogic.TableRoute.Tables)",
            "val normalized = BrailleCodeCatalog.normalize(",
            "val screenTitle = when (currentRoute) {",
            "BrailleDisplayLogic.TableRoute.Languages -> labels.pickerTitle",
            "is BrailleDisplayLogic.TableRoute.Tables ->",
            "BrailleCodeCatalog.groupForLanguage(codeGroups, currentRoute.language)?.title",
            "route = BrailleDisplayLogic.TableRoute.Languages",
            "route = BrailleDisplayLogic.TableRoute.Tables(language)",
            "onNavigateBack = { route = BrailleDisplayLogic.back(route) },",
            "route = BrailleDisplayLogic.TableRoute.Settings",
        ).forEach { fragment ->
            assertTrue("Activity 接线缺少：$fragment", activity.contains(fragment))
        }
        assertFalse(
            "旧的 pickerOpen 布尔开关必须彻底移除，否则两段返回会打架",
            activity.contains("pickerOpen")
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
            "SettingsCheckRow(",
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
        assertTrue(radioBody.contains("semantics(mergeDescendants = true)"))
        assertEquals(
            "整行朗读只应由 mergeDescendants + stateDescription 组合；" +
                "新增组件不能引入第二个 contentDescription",
            1,
            Regex("""contentDescription\s*=""").findAll(components).count()
        )
    }

    @Test
    fun realTableListIsGroupedByLanguageWithoutDroppingTables() {
        // 用服务侧真实表清单跑一遍纯逻辑，避免「分组效果」只停留在口算。
        val xml = text("service/res/xml/tablelist.xml")
            .replace(Regex("""<!--[\s\S]*?-->"""), "")
        val labels = BrailleTableCatalog.TableLabels(
            genericGroupSuffix = "（通用）",
            localePattern = "%1\$s（%2\$s）",
            eightDot = "%1\$s（八点）",
            variant = "%1\$s（%2\$s）",
            grade = "%1\$s - %2\$d 级",
            variantGrade = "%1\$s（%2\$s）- %3\$d 级",
        )
        val seeds = Regex("""<table\b([^>]*?)/>""").findAll(xml).mapNotNull { match ->
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
            // 用显式国家名表模拟 Activity 传入的 Locale.getDisplayCountry()，
            // 不依赖 JVM 默认 locale；TW/HK/MO 仍由 regionName 的合规映射接管。
            val displayCountry = mapOf(
                "CA" to "加拿大", "CH" to "瑞士", "CN" to "中国", "DE" to "德国",
                "FR" to "法国", "GB" to "英国", "US" to "美国",
            )[country] ?: country
            val seed = BrailleTableCatalog.TableSeed(
                id = id,
                language = parts[0],
                country = country,
                localeName = "",
                languageName = parts[0],
                countryName = BrailleTableCatalog.regionName(country, displayCountry, true),
                isEightDot = dots == 8,
                grade = grade,
                variant = variant,
            )
            seed.copy(localeName = BrailleTableCatalog.compliantLocaleName(seed, labels))
        }.toList()
        assertEquals("服务侧表清单应有 72 张表", 72, seeds.size)

        val groups = BrailleTableCatalog.build(seeds, "zh", labels)
        assertEquals("72 张表一张都不能丢", 72, groups.flatMap { it.choices }.size)
        assertEquals("34 种语言 = 34 个第一级条目", 34, groups.size)
        assertEquals("系统语言（中文）组必须置顶", "zh", groups.first().language)
        assertEquals("中文组实测 7 张表（通用 + 中国 + 中国台湾）", 7, groups.first().choices.size)
        val chineseLabels = groups.first().choices.map { it.label }
        assertTrue(
            "中文组必须同时含中国与中国台湾的表：$chineseLabels",
            chineseLabels.any { it.contains("中国台湾") } &&
                chineseLabels.any { it.contains("中国）") }
        )
        assertTrue(
            "无国家码的中文表要标「通用」，与地区表可区分：$chineseLabels",
            chineseLabels.any { it.contains("（通用）") }
        )
        assertEquals("英语组实测 8 张表", 8, groups.single { it.language == "en" }.choices.size)
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
    fun tableStringsExistInBothLocales() {
        val english = text(base + "res/values/strings.xml")
        val chinese = text(base + "res/values-zh-rCN/strings.xml")
        listOf(
            "compose_table_picker_title",
            "compose_table_group_auto",
            "compose_table_group_generic",
            "compose_table_locale_pattern",
            "compose_code_language_summary",
            "compose_table_language_current",
            "compose_table_language_open_hint",
            "compose_table_loading",
            "compose_table_empty",
            "compose_table_refresh_hint",
            "compose_code_eight_dot",
            "compose_code_summary_six",
            "compose_code_summary_eight",
            "compose_code_enabled",
            "compose_code_disabled",
            "compose_code_current_suffix",
            "compose_code_current_title",
            "compose_code_current_hint",
            "compose_code_current_dialog",
            "compose_settings_cursor_dots_summary_on",
            "compose_settings_cursor_dots_summary_off",
        ).forEach { name ->
            val pattern = """<string name="$name">""".toRegex()
            assertTrue("英文缺少 $name", pattern.containsMatchIn(english))
            assertTrue("中文缺少 $name", pattern.containsMatchIn(chinese))
        }
        val zh = stringValues(base + "res/values-zh-rCN/strings.xml")
        val en = stringValues(base + "res/values/strings.xml")
        assertEquals("按语言管理盲文方案", zh["compose_table_picker_title"])
        assertEquals("（通用）", zh["compose_table_group_generic"])
        assertEquals("双击打开 %1\$s 的盲文方案", zh["compose_table_language_open_hint"])
        assertEquals("共 %1\$d 个方案，已启用 %2\$d 个%3\$s", zh["compose_code_language_summary"])
        assertEquals("%1\$d codes, %2\$d enabled%3\$s", en["compose_code_language_summary"])
        assertEquals("Double-tap to open %1\$s braille codes", en["compose_table_language_open_hint"])
        assertEquals("%1\$s八点计算机盲文", zh["compose_code_eight_dot"])
        assertEquals("%1\$s 8-dot Computer Braille", en["compose_code_eight_dot"])
    }

    @Test
    fun defaultEnglishTableStringsContainNoChinese() {
        // 之前踩过坑：英文界面混入「启用音效」等中文文案。
        val values = stringValues(base + "res/values/strings.xml")
        listOf(
            "compose_table_picker_title",
            "compose_table_group_auto",
            "compose_table_locale_pattern",
            "compose_code_language_summary",
            "compose_table_language_current",
            "compose_table_language_open_hint",
            "compose_code_eight_dot",
            "compose_code_summary_six",
            "compose_code_summary_eight",
            "compose_code_enabled",
            "compose_code_disabled",
            "compose_code_current_suffix",
            "compose_code_current_title",
            "compose_code_current_hint",
            "compose_code_current_dialog",
        ).forEach { name ->
            val value = values[name] ?: error("英文缺少 $name")
            assertFalse(
                "默认英文资源不能混入中文：$name = $value",
                Regex("""[\u4e00-\u9fff]""").containsMatchIn(value)
            )
        }
        assertEquals("Manage braille codes by language", values["compose_table_picker_title"])
    }

    // -------------------------------------------------------------- 接线边界

    @Test
    fun wiringRoutesFiveScreensToComposeAndNoOthers() {
        val settings = text(base + "src/com/googlecode/eyesfree/brailleback/SettingsActivity.kt")
        val wiring = settings.substringAfter("// 五个分类入口跳各自的 Compose 页。")
            .substringBefore("// 屏幕盲文键盘")
        assertTrue(
            "盲文显示入口必须接线到自己的 Compose 页",
            Regex(
                """wireCategory\(\s*R\.string\.pref_category_braille_key,\s*"""
                    + """BrailleDisplayComposeActivity::class\.java"""
            ).containsMatchIn(wiring)
        )
        assertTrue(
            wiring.contains("DeveloperOptionsComposeActivity::class.java")
        )
        assertEquals(
            "五个分类入口各一条 wireCategory 接线",
            5,
            Regex("""wireCategory\(""").findAll(wiring).count()
        )
        assertTrue(
            "开发者选项子屏也必须被拦截",
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
            "主屏不应再有子屏定义：五个子屏内容已归档，入口改为 Preference + 点击监听",
            0,
            Regex("""<PreferenceScreen""").findAll(text(base + "res/xml/settings.xml")).count() - 1
        )
    }
}
