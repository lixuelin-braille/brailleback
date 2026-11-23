/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 */

package com.linxi.brailleback

import com.linxi.brailleback.compose.BrailleCodeCatalog
import com.linxi.brailleback.compose.BrailleDisplayLogic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 谷歌式多选方案（Code）模型的静态契约。
 *
 * 断言锚定真实代码行，不用会被注释满足的弱断言；
 * 与 BrailleDisplayContractTest 的分工：那边管「两级全屏 + 五个设置项」，
 * 这边管「多选偏好 + 复选框语义 + 当前方案 + 切换按键不跨语言」。
 */
class BrailleCodeContractTest {

    private fun projectFile(path: String): File {
        val candidates = listOf(File(path), File("..", path), File("../..", path))
        return candidates.firstOrNull { it.isFile }
            ?: error("找不到 $path（cwd=${System.getProperty("user.dir")}）")
    }

    private fun text(path: String) = projectFile(path).readText()
    private val base = "brailleback/"
    private val composeDir = base + "src/main/java/com/linxi/brailleback/compose/"
    private fun compose(name: String) = text(composeDir + name)

    private fun stringValues(path: String): Map<String, String> =
        Regex("""<string name="([^"]+)">([^<]*)</string>""")
            .findAll(text(path))
            .associate { m ->
                // 部分历史资源用引号包住值（如 "%1$s（八点）"），统一剥掉。
                m.groupValues[1] to m.groupValues[2].trim().removeSurrounding("\"")
            }

    @Test
    fun preferredCodesKeyIsSharedByLogicCatalogAndResource() {
        val values = stringValues(base + "src/main/res/values/donottranslate.xml")
        assertEquals("preferred_braille_codes", values["pref_preferred_codes_key"])
        assertEquals(values["pref_preferred_codes_key"], BrailleCodeCatalog.KEY_PREFERRED_CODES)
        assertEquals(values["pref_preferred_codes_key"], BrailleDisplayLogic.KEY_PREFERRED_CODES)
        assertTrue(
            "Logic 常量必须写成字面量，方便静态审查",
            compose("BrailleDisplayLogic.kt")
                .contains("""const val KEY_PREFERRED_CODES = "preferred_braille_codes"""")
        )
        assertEquals(
            "braille_table 仍保存当前方案，key 零迁移",
            "braille_table", values["pref_braille_table_key"]
        )
        assertEquals(BrailleDisplayLogic.KEY_BRAILLE_TABLE, values["pref_braille_table_key"])
    }

    @Test
    fun checkboxRowHasTalkBackSemanticsAndNoHandwrittenDescription() {
        val components = compose("SettingsComponents.kt")
        val body = components.substringAfter("fun SettingsCheckRow(")
            .substringBefore("fun SettingsRadioRow(")
        listOf(
            "role = Role.Checkbox",
            "stateDescription = state",
            "semantics(mergeDescendants = true)",
            "heightIn(min = MinRowHeight)",
            "Checkbox(checked = checked, onCheckedChange = null)",
        ).forEach { fragment ->
            assertTrue("多选行缺少无障碍语义：$fragment", body.contains(fragment))
        }
        assertFalse(
            "多选行不能手写 contentDescription，会吞掉 role + stateDescription",
            Regex("""contentDescription\s*=""").containsMatchIn(body)
        )
        assertEquals(
            "组件底座整文件只允许返回按钮那一处 contentDescription",
            1,
            Regex("""contentDescription\s*=""").findAll(components).count()
        )
    }

    @Test
    fun secondLevelIsCheckboxListWithACurrentCodeRowNotAHeading() {
        val screen = compose("BrailleDisplayScreen.kt")
        val second = screen.substringAfter("is BrailleDisplayLogic.TableRoute.Tables -> {")
        listOf(
            "item(key = \"code:current\")",
            "SettingsListRow(",
            "codes.forEach { code ->",
            "SettingsCheckRow(",
            "val checked = code.id in enabledCodes",
            "state = state,",
            "onCheckedChange = { onToggleCode(code.id, it) },",
        ).forEach { fragment ->
            assertTrue("第二级缺少：$fragment", second.contains(fragment))
        }
        assertFalse("第二级不能是单选表行", second.contains("SettingsRadioRow("))
        assertFalse("第二级不能有分组标题", second.contains("settingsSectionHeader("))
        assertTrue(
            "当前方案必须走可复用的单选对话框",
            screen.contains("onSelect = onSelectCurrent")
        )
    }

    @Test
    fun screenShowsEnabledCountOnFirstLevel() {
        val activity = compose("BrailleDisplayComposeActivity.kt")
        listOf(
            "R.string.compose_code_language_summary",
            "BrailleCodeCatalog.enabledCount(codeGroups, normalized, group.language)",
            "enabledCount = BrailleCodeCatalog.enabledCount(",
        ).forEach { fragment ->
            assertTrue("第一级缺少已启用数：$fragment", activity.contains(fragment))
        }
    }

    @Test
    fun activityWiresEverythingToViewModelAndNeverTouchesPreferences() {
        val activity = compose("BrailleDisplayComposeActivity.kt")
        listOf(
            "val settings by viewModel.state.collectAsState()",
            "viewModel.toggleCode(id, checked, codeGroups, systemLanguage)",
            "viewModel.chooseCurrent(value)",
            "viewModel.chooseCurrent(BrailleTableCatalog.AUTO_VALUE)",
            "viewModel.setBoolean(key, value)",
            "viewModel.openCurrentDialog()",
            "viewModel.dismissCurrentDialog()",
            "viewModel.reconcileCodes(codeGroups, systemLanguage)",
            "viewModel.refreshFromStore()",
            "getString(R.string.pref_preferred_codes_key)",
            "getString(R.string.pref_braille_table_key)",
        ).forEach { fragment ->
            assertTrue("Activity 的 ViewModel 接线缺少：$fragment", activity.contains(fragment))
        }
        // 单一读口径、单一写入口：Activity 自己再碰一次偏好，就会出现「界面按 A 显示、
        // 服务按 B 干活」的分叉。这一条是本次重构的核心不变量。
        assertFalse(
            "Activity 不得再直接写偏好（必须走 ViewModel）",
            activity.contains("prefs.edit()")
        )
        assertFalse(
            "Activity 不得再直接读偏好集合（必须走 ViewModel）",
            activity.contains("prefs.getStringSet")
        )
        assertFalse(
            "单选表回调必须移除，避免两套选择语义并存",
            activity.contains("onTableSelected")
        )
        assertEquals(
            "显示用的归一化只剩 setContent 一处；写入前的归一化在对账/勾选里，归 ViewModel",
            1,
            Regex("""BrailleCodeCatalog\.normalize\(""").findAll(activity).count()
        )
    }

    @Test
    fun viewModelOwnsCodeSemanticsAndPersistence() {
        val vm = compose("BrailleDisplayViewModel.kt")
        listOf(
            "BrailleCodeCatalog.canDisable(normalized, id)",
            "BrailleCodeCatalog.resolveCurrent(",
            "store.putStringSet(keys.preferredCodes, next)",
            "store.putString(keys.table, corrected)",
            "val firstRun = !store.contains(keys.preferredCodes)",
            "pool = pool + s.currentTable",
            "store.putStringSet(keys.preferredCodes, pool)",
            "store.putString(keys.table, tableId)",
        ).forEach { fragment ->
            assertTrue("ViewModel 缺少方案语义/落盘：$fragment", vm.contains(fragment))
        }
        assertFalse(
            "ViewModel 不得直接依赖 SharedPreferences，否则无法纯 JVM 测试",
            vm.contains("android.content.SharedPreferences") &&
                !vm.contains("interface BrailleSettingsStore")
        )
    }

    @Test
    fun normalizeNeverPutsTheCurrentCodeBackIntoThePool() {
        val catalog = compose("BrailleCodeCatalog.kt")
        val body = catalog.substringAfter("fun normalize(")
            .substringBefore("/** 切换单个方案的启用状态")
        assertFalse(
            "谷歌原版是池管当前码（readCurrentActiveInputCodeAndCorrect）；" +
                "把当前码补进池会让用户没勾过的方案显示成「已启用」",
            Regex("""cleaned\.add\(currentId\)""").containsMatchIn(body)
        )
        assertFalse("normalize 签名里不应再有 currentId", body.contains("currentId"))
        assertTrue(
            "纠正仍由 resolveCurrent 承担",
            catalog.contains("fun resolveCurrent(")
        )
    }

    @Test
    fun toggleBrailleDotsNeverSearchesOutsideTheCurrentLanguage() {
        val manager = text(base + "src/main/java/com/linxi/brailleback/TranslatorManager.kt")
        val body = manager.substringAfter("fun toggleBrailleDots(): Boolean {")
            .substringBefore("private fun migrateLegacyTablePreference")
        listOf(
            "mSharedPreferences.getStringSet(mPrefPreferredCodesKey, null)",
            "info.locale.language.equals(language, ignoreCase = true)",
            "info.isEightDot != wasEightDot",
            "id in enabled",
        ).forEach { fragment ->
            assertTrue("切换按键函数体缺少：$fragment", body.contains(fragment))
        }
        assertTrue(
            "偏好 key 必须从资源读取，不能硬编码",
            manager.contains("getString(R.string.pref_preferred_codes_key)")
        )
        assertFalse(
            "旧实现按 matchRank 全局挑表，中文环境会跳到英文八点表；" +
                "锚定真实赋值行，注释里出现同样的词不算",
            Regex("""val target = findDefaultTableInfo\(!eightDot\)""")
                .containsMatchIn(body)
        )
    }

    @Test
    fun eightDotNameIsCompleteInBothLocalesAndLegacyTemplateStaysForFallback() {
        val zh = stringValues(base + "src/main/res/values-zh-rCN/strings.xml")
        val en = stringValues(base + "src/main/res/values/strings.xml")
        assertEquals("%1\$s八点计算机盲文", zh["compose_code_eight_dot"])
        assertEquals("%1\$s 8-dot Computer Braille", en["compose_code_eight_dot"])
        assertFalse(requireNotNull(zh["compose_code_eight_dot"]).contains("（八点）"))
        assertEquals("八点计算机盲文", zh["compose_code_summary_eight"])
        assertEquals("8-dot computer braille", en["compose_code_summary_eight"])
        // 旧 XML 回退屏仍用旧模板，不能删。
        assertEquals("%1\$s（八点）", zh["table_name_eight_dot"])
        assertEquals("%1\$s (8-dot)", en["table_name_eight_dot"])
    }

    @Test
    // 因 zhcn-cbs-full 在本版 liblouis 加载失败已从清单移除，中文组 6 张（4 六点 + 2 八点）
    fun realTableListStillHasFourChineseSixDotAndTwoEightDotTables() {
        val xml = text("service/src/main/res/xml/tablelist.xml")
            .replace(Regex("""<!--[\s\S]*?-->"""), "")
        val rows = Regex("""<table\b([^>]*?)/>""").findAll(xml).toList()
        fun attr(s: String, name: String) =
            Regex("""$name="([^"]+)"""").find(s)?.groupValues?.get(1)
        val chinese = rows.map { it.groupValues[1] }.filter {
            attr(it, "locale")?.startsWith("zh") == true
        }
        assertEquals("中文表总数不能变", 6, chinese.size)
        assertEquals("中文六点方案不能丢", 4, chinese.count { attr(it, "dots") == "6" })
        assertEquals("中文八点方案不能丢", 2, chinese.count { attr(it, "dots") == "8" })
        assertEquals(
            "中文四张六点表靠 variant 区分，不能退化成一个布尔",
            4,
            chinese.filter { attr(it, "dots") == "6" }
                .mapNotNull { attr(it, "variant") }.toSet().size
        )
    }

    @Test
    fun newEnglishStringsContainNoChinese() {
        val en = stringValues(base + "src/main/res/values/strings.xml")
        listOf(
            "compose_code_language_summary",
            "compose_code_eight_dot",
            "compose_code_summary_six",
            "compose_code_summary_eight",
            "compose_code_enabled",
            "compose_code_disabled",
            "compose_code_current_suffix",
            "compose_code_current_title",
            "compose_code_current_hint",
            "compose_code_current_dialog",
            "compose_table_picker_title",
            "compose_table_language_current",
            "compose_table_language_open_hint",
        ).forEach { name ->
            val value = en[name] ?: error("默认英文资源缺少 $name")
            assertFalse(
                "默认英文资源不能混入中文：$name = $value",
                Regex("""[\u4e00-\u9fff]""").containsMatchIn(value)
            )
        }
    }
}