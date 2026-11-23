/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 *
 * 自定义键位页 Compose 化的静态契约：
 * - 类名 / Manifest / 启动入口一字不改；
 * - Activity 不再依赖 androidx.preference，落盘只走 CustomKeyMapStore；
 * - 24 行动态行与「默认 + 30 动作」单选项与策略白名单对齐；
 * - 无障碍：整行合并语义、单选 RadioButton、冲突提示是可读文本节点。
 */

package com.linxi.brailleback

import com.linxi.brailleback.keymap.BrailleKeyMapPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CustomKeyMapComposeContractTest {

    private fun projectFile(path: String): File {
        val candidates = listOf(File(path), File("..", path), File("../..", path))
        return candidates.firstOrNull { it.isFile }
            ?: error("找不到 $path（cwd=${System.getProperty("user.dir")}）")
    }

    private fun text(path: String) = projectFile(path).readText()

    private val base = "brailleback/"

    private val activity =
        text(base + "src/main/java/com/linxi/brailleback/CustomKeyMapActivity.kt")
    private val screen =
        text(base + "src/main/java/com/linxi/brailleback/compose/CustomKeyMapScreen.kt")

    // ------------------------------------------------------------ 入口不变

    @Test
    fun classNameAndManifestEntryUnchanged() {
        assertTrue(
            "类名必须保持 CustomKeyMapActivity",
            activity.contains("class CustomKeyMapActivity : AppCompatActivity()"),
        )
        val manifest = text(base + "src/main/AndroidManifest.xml")
        assertTrue(
            "Manifest 声明必须保留 .CustomKeyMapActivity",
            manifest.contains("android:name=\".CustomKeyMapActivity\""),
        )
        val keysGestures = text(
            base + "src/main/java/com/linxi/brailleback/compose/KeysGesturesComposeActivity.kt"
        )
        assertTrue(
            "启动入口 Intent 必须保持字面量不变",
            keysGestures.contains(
                "Intent(this@KeysGesturesComposeActivity, CustomKeyMapActivity::class.java)"
            ),
        )
    }

    // ------------------------------------------------------------ 去 preference

    @Test
    fun activityHasNoAndroidxPreferenceDependency() {
        listOf(
            "androidx.preference",
            "PreferenceFragmentCompat",
            "ListPreference",
            "PreferenceCategory",
            "PreferenceScreen",
        ).forEach { banned ->
            assertFalse(
                "Activity 不得再引用 $banned",
                activity.contains(banned),
            )
        }
    }

    @Test
    fun persistenceGoesThroughStoreNotDirectPrefs() {
        assertTrue("单条改写必须走 CustomKeyMapStore.setMapping",
            activity.contains("CustomKeyMapStore.setMapping"))
        assertTrue("全量复位必须走 CustomKeyMapStore.resetAll",
            activity.contains("CustomKeyMapStore.resetAll"))
        listOf("getSharedPreferences(", "SharedPreferences", ".edit()").forEach { banned ->
            assertFalse("Activity 不得直接写偏好：$banned", activity.contains(banned))
        }
    }

    // ------------------------------------------------------------ 行数 / 选项数

    @Test
    fun rowsMatchRemappableCommandsAndOptionsMatchAssignableActions() {
        // 行清单来自资源数组，既有 KeyMapResourceContractTest 已锁它 == REMAPPABLE_COMMANDS；
        // 这里再锁 Activity 确实读的是该数组，且选项是「默认 + 动作清单」。
        assertTrue("动态行必须读 key_map_source_values 数组",
            activity.contains("R.array.key_map_source_values"))
        assertTrue("单选项首项为 DEFAULT_ACTION",
            activity.contains("BrailleKeyMapPolicy.DEFAULT_ACTION) +"))
        assertTrue("单选项余下列表来自 actionValues",
            activity.contains("actionValues.toList()"))
        assertEquals("可自定义按键为 24 条", 24, BrailleKeyMapPolicy.REMAPPABLE_COMMANDS.size)
        assertEquals("可选动作含 30 项（含虚拟 NONE）",
            30, BrailleKeyMapPolicy.ASSIGNABLE_ACTIONS.size)
    }

    // ------------------------------------------------------------ 无障碍 / 外壳

    @Test
    fun screenReusesSharedScaffoldAndSemantics() {
        assertTrue("复用 SettingsScreenScaffold 外壳",
            screen.contains("SettingsScreenScaffold("))
        assertTrue("单选用共享 SettingsChoiceDialog（RadioButton + stateDescription）",
            screen.contains("SettingsChoiceDialog("))
        assertTrue("复位移位用共享 SettingsConfirmDialog",
            screen.contains("SettingsConfirmDialog("))
        assertTrue("每行整行合并语义",
            screen.contains("semantics(mergeDescendants = true)"))
        assertTrue("冲突提示作为文本节点渲染（不只视觉置灰）",
            screen.contains("conflictText") && screen.contains("Text("))
    }

    @Test
    fun noBareHandlerDelayedWork() {
        assertFalse("Activity 不得出现 postDelayed(", activity.contains("postDelayed("))
        assertFalse("Activity 不得出现 object : Runnable", activity.contains("object : Runnable"))
    }
}
