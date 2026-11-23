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
 * Jetpack Compose 分批迁移的静态契约。
 *
 * 第一批「设备与连接」、第二批「按键与手势」、第三批「盲文显示」、
 * 第四批「声音与行为」、第五批「开发者选项」（五屏全部收官）；
 * 护栏要同时防两件事：
 * 1) 迁移后读屏语义退化（开关没状态、分组不能按标题导航、整行被拆成多个焦点）；
 * 2) 批次越界，顺手把还没验证过的其它子屏也改掉，或改动 SharedPreferences 键。
 * 第二、三、四、五批的专项护栏见 KeysGesturesContractTest /
 * BrailleDisplayContractTest / SoundBehaviorContractTest /
 * DeveloperOptionsContractTest。
 *
 * 纯 JVM 单测读不了 Android 资源，直接对源码/构建脚本文本做断言。
 */
class ComposeMigrationContractTest {

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

    /**
     * 根与模块都叫 build.gradle，且单测 cwd 可能是模块目录；
     * 用「含 apply false 的插件声明」这一特征定位根脚本，不能只按文件名取第一个。
     */
    private fun rootGradle(): String = listOf(
        File("build.gradle"), File("..", "build.gradle"), File("../..", "build.gradle")
    ).firstOrNull { it.isFile && it.readText().contains("apply false") }?.readText()
        ?: error("找不到根 build.gradle（cwd=${System.getProperty("user.dir")}）")

    // -------------------------------------------------------------- 构建链

    @Test
    fun composeCompilerPluginMatchesBuiltInKotlin() {
        val root = rootGradle()
        // 版本搬进 gradle/libs.versions.toml 后，这里锁两件事：
        // 根脚本必须走目录里的插件别名，目录里的版本必须与 AGP 9.2 内置 Kotlin 一致。
        assertTrue(
            "根构建脚本必须通过版本目录声明 Compose 编译器插件",
            root.contains("alias(libs.plugins.kotlin.compose)")
        )
        val catalog = text("gradle/libs.versions.toml")
        assertTrue("版本目录里 AGP 必须是 9.2.0", catalog.contains("agp = \"9.2.0\""))
        assertTrue(
            "版本目录里 Compose 编译器插件必须与 AGP 9.2 内置 Kotlin（2.2.10）同版",
            catalog.contains("kotlinCompose = \"2.2.10\"")
        )
        val module = text(base + "build.gradle")
        listOf(
            // 2026-11-20 迁到 plugins {} 块（Gradle 10 兼容 + 约定插件，见 docs/47、48），
            // 断言跟着新写法走：只要仍然是被显式应用、且版本来自目录即可。
            "id 'org.jetbrains.kotlin.plugin.compose'",
            "compose = true",
            "libs.androidx.activity.compose",
            "libs.compose.ui",
            "libs.compose.foundation",
            "libs.compose.material3",
        ).forEach { fragment ->
            assertTrue("brailleback/build.gradle 缺少：$fragment", module.contains(fragment))
        }
    }

    // ------------------------------------------------------------ 无障碍语义

    @Test
    fun reusableComponentsCarryTalkBackSemantics() {
        val components = compose("SettingsComponents.kt")
        listOf(
            "semantics { heading() }",
            // 必须锚定真实代码行：只写 "Role.Switch" 会被注释里的同一句话满足，
            // 导致把开关降级成 Button 的变异溜过去（变异检验实测发现过）。
            "role = Role.Switch",
            "stateDescription = state",
            "semantics(mergeDescendants = true)",
            "role = Role.RadioButton",
            "role = Role.Button",
            "onClickLabel = clickLabel",
            "contentDescription = backLabel",
            "LazyColumn(",
            "isSystemInDarkTheme()"
        ).forEach { fragment ->
            assertTrue("组件底座缺少读屏语义：$fragment", components.contains(fragment))
        }
        assertEquals(
            "整行朗读只应由 mergeDescendants + stateDescription 组合；" +
                "手写 contentDescription 会吞掉 role / stateDescription 的组合播报",
            1, // 只允许返回按钮那一处
            Regex("""contentDescription\s*=""").findAll(components).count()
        )
    }

    @Test
    fun scaffoldUsesLazyColumnWithoutPagingButtons() {
        val components = compose("SettingsComponents.kt")
        assertTrue(components.contains("content: LazyListScope.() -> Unit"))
        assertFalse(
            "设置列表必须一次加载，不能有「下一页」按钮",
            components.contains("\"next page\"") || components.contains("\"下一页\"")
        )
    }

    // -------------------------------------------------------------- 试点屏

    @Test
    fun pilotScreenKeepsExactlyThreeLegacyKeys() {
        val screen = compose("DeviceConnectionScreen.kt")
        // 协议行已并入连接方式（同一件事不再存两份），但偏好键改由链路推导后
        // 回写，所以 STATUS / AUTO_CONNECT 这两个原设置项必须还在界面上。
        listOf(
            DeviceConnectionKeys.STATUS,
            DeviceConnectionKeys.AUTO_CONNECT
        ).forEach { key ->
            assertTrue("试点屏缺少原设置项 $key", screen.contains("KEY_${key.uppercase()}"))
        }
        // 集合断言：原两个设置键 + 断开 + 连接方式。任何新增键都要先来这里登记，
        // 防止顺手往设置页塞新的持久化项。
        val usedKeys = Regex("""item\(key = DeviceConnectionLogic\.(KEY_[A-Z_]+)\)""")
            .findAll(screen)
            .map { it.groupValues[1] }
            .toSet()
        assertEquals(
            "试点屏的 item key 集合必须正好是「两个原设置键 + 断开 + 连接方式」",
            setOf(
                "KEY_CONNECTION_STATUS",
                "KEY_AUTO_CONNECT",
                "KEY_DISCONNECT",
                "KEY_TRANSPORT"
            ),
            usedKeys
        )
        val activity = compose("DeviceConnectionComposeActivity.kt")
        assertTrue(
            "连接方式三项必须直接写协议键，并把历史链路值一并复位",
            activity.contains(".putString(protocolKey, next)") &&
                activity.contains("DEFAULT_TRANSPORT_AFTER_PROTOCOL_PICK")
        )
        assertTrue(
            "自动连接写入必须回写原 key",
            activity.contains("prefs.edit().putBoolean(autoConnectKey, value).apply()")
        )
    }

    @Test
    fun pilotScreenReusesServiceProtocolPolicyAndRefreshesConnection() {
        val logic = compose("DeviceConnectionLogic.kt")
        assertTrue(
            "协议归一化必须复用服务端策略，不能复制第二份",
            logic.contains("ConnectionProtocolPolicy.normalize(value)")
        )
        val activity = compose("DeviceConnectionComposeActivity.kt")
        assertTrue("刷新状态必须调用 poll()", activity.contains("displayClient?.poll()"))
        assertTrue("必须监听连接状态", activity.contains("Display.OnConnectionStateChangeListener"))
        assertTrue("退出必须释放点显器客户端", activity.contains("displayClient?.shutdown()"))
        assertTrue("必须复用旧协议值迁移", activity.contains("normalizeProtocol(rawProtocol)"))
    }

    // -------------------------------------------------------------- 接线边界

    @Test
    fun settingsActivityRoutesFiveMigratedScreensToCompose() {
        // 接线表随主屏宿主迁到 HomeContainerActivity（SettingsActivity 已是转跳别名）。
        val settings = text(base + "src/main/java/com/linxi/brailleback/HomeContainerActivity.kt")
        // 五个入口是 Preference + 点击监听（wireCategory）。不能用空 PreferenceScreen：
        // androidx 1.2.1 的 PreferenceScreen.onClick() 在无 intent/fragment/子项时直接 return，
        // 点击静默失败；这次真机上五个入口全部点了没反应就是这个原因。
        val wiring = settings.substringAfter("// 六个分类入口跳各自的 Compose 页。")
            .substringBefore("// 屏幕盲文键盘")
        listOf(
            "pref_category_device_key" to "DeviceConnectionComposeActivity",
            "pref_category_keys_key" to "KeysGesturesComposeActivity",
            "pref_category_braille_key" to "BrailleDisplayComposeActivity",
            "pref_category_sound_behavior_key" to "SoundBehaviorComposeActivity",
            "pref_category_developer_key" to "DeveloperOptionsComposeActivity",
            "pref_category_stability_key" to "StabilityComposeActivity",
        ).forEach { (keyRes, activity) ->
            assertTrue(
                "$keyRes 未接线到 $activity",
                Regex(
                    """wireCategory\(\s*R\.string\.$keyRes,\s*$activity::class\.java"""
                ).containsMatchIn(wiring)
            )
        }
        assertEquals(
            "六个已迁移的子屏应各有一条 wireCategory 接线",
            6,
            Regex("""wireCategory\(""").findAll(wiring).count()
        )
        assertFalse(
            "不应再保留 onPreferenceStartScreen：空 PreferenceScreen 走不到它",
            settings.contains("OnPreferenceStartScreenCallback")
        )
        assertTrue(
            "入口点击必须真的 startActivity",
            settings.contains("startActivity(Intent(this, it))")
        )
    }

    @Test
    fun manifestRegistersComposeActivities() {
        val manifest = text(base + "src/main/AndroidManifest.xml")
        assertTrue(
            manifest.contains("""android:name=".compose.DeveloperOptionsComposeActivity"""")
        )
        assertTrue(
            manifest.contains("android:name=\".compose.DeviceConnectionComposeActivity\"")
        )
        assertTrue(
            manifest.contains("android:name=\".compose.KeysGesturesComposeActivity\"")
        )
        assertTrue(
            manifest.contains("android:name=\".compose.BrailleDisplayComposeActivity\"")
        )
        assertTrue(
            manifest.contains("android:name=\".compose.SoundBehaviorComposeActivity\"")
        )
        assertTrue(manifest.contains("android:theme=\"@style/BraillebackSettingsTheme\""))
    }

    @Test
    fun legacySubScreensAreArchivedAndMainScreenKeepsOnlyEntryRows() {
        // 2026-11-18：主屏从 settings.xml 改成 Compose 目录数据（SettingsHomeCatalog）。
        val xml =
            text(base + "src/main/java/com/linxi/brailleback/compose/SettingsHomeCatalog.kt")
        val archived = text(ARCHIVE_PATH)
        // 主屏只剩入口行：迁移的 5 个子屏内容移出编译路径，主屏层级不变；
        // 稳定性与省电是新增屏（没有旧 XML 可归档），单独断言它必须在主屏上。
        listOf(
            "pref_category_device_key",
            "pref_category_keys_key",
            "pref_category_braille_key",
            "pref_category_sound_behavior_key",
            "pref_category_developer_key"
        ).forEach { key ->
            assertTrue("主屏入口行不得丢失：$key", xml.contains("R.string.$key"))
            assertTrue("旧子屏原文必须进归档：$key", archived.contains("@string/$key"))
        }
        // 稳定性与省电是新增入口，没有旧 XML 可归档，所以只要求主屏有它。
        assertTrue(
            "新增的稳定性与省电入口必须出现在主屏",
            xml.contains("R.string.pref_category_stability_key"),
        )
        // 内容项与全部偏好键只保留在归档里：两代 UI 共用同一份偏好数据。
        listOf(
            "pref_connection_status_key",
            "pref_connection_protocol_key",
            "pref_auto_connect_key",
            "pref_key_bindings_key",
            "pref_custom_key_map_key",
            "pref_volume_gestures_enabled_key",
            "pref_braille_table_key",
            "pref_braille_word_wrap_key",
            "pref_cursor_dots_key",
            "pref_braille_contracted_mode_key",
            "pref_chinese_segmentation_key",
            "pref_sound_enabled_key",
            "pref_sound_volume_key",
            "pref_pause_behavior_key",
            "pref_idle_clear_key",
            "pref_motion_mode_key",
            "pref_quiet_navigation_key",
            "pref_log_level_key",
            "pref_view_log_key",
            "pref_share_log_key",
            "pref_copy_log_key",
            "pref_clear_log_key",
            "pref_braille_overlay_key",
            "pref_braille_overlay_tutorial_key"
        ).forEach { key ->
            assertTrue("旧界面的偏好 key 必须留在归档里（可回退）：$key", archived.contains("@string/$key"))
            assertFalse("旧界面的内容项不得再留在主屏目录：$key", xml.contains("R.string.$key"))
        }
        assertFalse(
            "主屏已改为 Compose 目录数据（settings.xml 已删除），不应再引用旧 XML 资源",
            text(base + "src/main/java/com/linxi/brailleback/HomeContainerActivity.kt")
                .contains("R.xml.settings"),
        )
        assertEquals(
            "归档必须仍是完整的五个子屏原文",
            5,
            Regex("""<PreferenceScreen""").findAll(archived).count() - 1
        )
    }

    // --------------------------------------------------------------- 文案

    @Test
    fun composeStringsAreLocalizedInBothLocales() {
        val english = text(base + "src/main/res/values/strings.xml")
        val chinese = text(base + "src/main/res/values-zh-rCN/strings.xml")
        listOf(
            "compose_settings_back", "compose_settings_section_connection",
            "compose_settings_refresh", "compose_settings_choose",
            "compose_settings_protocol_dialog_title", "compose_settings_cancel",
            "compose_settings_on", "compose_settings_off",
            "compose_settings_selected", "compose_settings_unselected",
            "compose_table_picker_title", "compose_table_group_auto",
            "compose_table_group_generic", "compose_table_loading",
            "compose_table_empty", "compose_table_refresh_hint",
            "compose_settings_cursor_dots_summary_on",
            "compose_settings_cursor_dots_summary_off",
            "compose_settings_sound_summary_on",
            "compose_settings_sound_summary_off",
            "compose_settings_adjust_hint",
            "compose_settings_volume_decrease",
            "compose_settings_volume_increase",
            "compose_settings_confirm",
            "compose_settings_log_action_hint",
            "compose_settings_log_level_locked",
            "compose_settings_log_preview_hint",
            "compose_settings_overlay_tutorial_disabled",
            "home_tab_reading_title", "home_tab_settings_title",
            "home_tab_switched_announce",
            "braille_reader_export", "braille_reader_export_title",
            "braille_reader_export_fmt_brf", "braille_reader_export_fmt_brl",
            "braille_reader_export_fmt_utf", "braille_reader_export_preparing",
            "braille_reader_export_done", "braille_reader_export_fail",
            "braille_reader_export_cancelled", "braille_reader_export_empty",
            "braille_reader_export_warn_unmappable",
            "braille_reader_export_warn_dropped"
        ).forEach { name ->
            val pattern = """<string name="$name">""".toRegex()
            assertTrue("英文缺少 $name", pattern.containsMatchIn(english))
            assertTrue("中文缺少 $name", pattern.containsMatchIn(chinese))
        }
    }

    private object DeviceConnectionKeys {
        const val STATUS = "connection_status"
        const val PROTOCOL = "connection_protocol"
        const val AUTO_CONNECT = "auto_connect"
    }

    // ------------------------------------------------ 第六批：键盘帮助屏（只读帮助页）

    private val keyBindingsActivityPath =
        base + "src/main/java/com/linxi/brailleback/KeyBindingsActivity.kt"

    @Test
    fun keyBindingsScreenIsComposeAndXmlLayoutIsGone() {
        val activity = text(keyBindingsActivityPath)
        assertTrue("这屏必须用 setContent 承载界面", activity.contains("setContent {"))
        assertFalse("不该再 setContentView", activity.contains("setContentView"))
        assertFalse("不该再持有 ListView", activity.contains("import android.widget.ListView"))
        assertTrue("列表交给 KeyBindingsScreen", activity.contains("KeyBindingsScreen("))

        // 布局删掉以后，「界面还在」这件事只剩 Compose 一个来源，不是双份维护。
        val layout = listOf(
            File(base + "src/main/res/layout/key_bindings.xml"),
            File("..", base + "src/main/res/layout/key_bindings.xml"),
            File("../..", base + "src/main/res/layout/key_bindings.xml"),
        )
        assertFalse("key_bindings.xml 应已删除", layout.any { it.exists() })
    }

    @Test
    fun keyBindingsKeepsBindingAssemblyLogic() {
        // 界面可以换，取键位的逻辑不能丢：命令表、描述表、排序、友好键名、菜单命令门槛。
        val activity = text(keyBindingsActivityPath)
        listOf(
            "help_supportedCommands",
            "help_commandDescriptions",
            "getSortedBindingsForDisplay",
            "getFriendlyKeyNamesForCommand",
            "CMD_TOGGLE_BRAILLE_MENU",
            "LABELING_ENABLED",
        ).forEach { fragment ->
            assertTrue("取键位逻辑缺：$fragment", activity.contains(fragment))
        }
    }

    @Test
    fun keyBindingsStaysReadOnly() {
        // 帮助页只读：一旦它开始写偏好，就是在没人验证的路径上改用户设置。
        val activity = text(keyBindingsActivityPath)
        assertFalse("帮助页不应写偏好", activity.contains("prefs.edit()"))
        assertFalse("帮助页不应写偏好", activity.contains(".putBoolean("))
    }

    // ------------------------------------------------ 第七批：盲文书架

    private val bookshelfActivityPath =
        base + "src/main/java/com/linxi/brailleback/BookshelfActivity.kt"

    /** 2026-11-23：书架状态/动作/存储抽到 BookshelfController，Activity 是薄壳。 */
    private val bookshelfControllerPath =
        base + "src/main/java/com/linxi/brailleback/compose/BookshelfController.kt"

    @Test
    fun bookshelfIsComposeAndXmlLayoutIsGone() {
        val activity = text(bookshelfActivityPath)
        assertTrue("书架必须用 setContent 承载界面", activity.contains("setContent {"))
        assertTrue("列表交给 BookshelfScreen", activity.contains("BookshelfScreen("))
        assertFalse("不该再 setContentView", activity.contains("setContentView"))
        assertFalse("不该再 import ListView", activity.contains("import android.widget.ListView"))
        assertFalse("不该再用 ArrayAdapter", activity.contains("import android.widget.ArrayAdapter"))

        val layout = listOf(
            File(base + "src/main/res/layout/bookshelf.xml"),
            File("..", base + "src/main/res/layout/bookshelf.xml"),
            File("../..", base + "src/main/res/layout/bookshelf.xml"),
        )
        assertFalse("bookshelf.xml 应已删除", layout.any { it.exists() })
    }

    @Test
    fun bookshelfKeepsStorageFormat() {
        // 存储格式是用户已有书籍的 ABI：换了编码，用户的 200 本书就全部读不出来。
        // 存储实现随控制器迁移；文件名/键名/分隔符/上限/两段兼容分支逐条不变。
        val controller = text(bookshelfControllerPath)
        listOf(
            "const val SEP",
            "split(SEP)",
            "PREFS_NAME = \"bookshelf\"",
            "KEY_BOOKS = \"books\"",
            "BOOK_LIMIT = 200",
            "p.size >= 4",         // 四段格式
            "p.size == 2",         // 旧两段格式的兼容分支
        ).forEach { fragment ->
            assertTrue("书架存储格式被改动：$fragment", controller.contains(fragment))
        }
        // 阅读器依赖这个静态入口把打开过的书送回书架，签名不能变（仍在 Activity 上）。
        val activity = text(bookshelfActivityPath)
        assertTrue(
            "recordRecent 签名必须保持",
            activity.contains("fun recordRecent(context: Activity, uri: String, name: String)"),
        )
    }

    @Test
    fun bookshelfKeepsLongPressMenuAndConfirmations() {
        // 长按菜单与两次确认是防误删的最后一道闸：长按菜单丢了，用户就只能在列表上乱点。
        val controller = text(bookshelfControllerPath)
        assertTrue("长按菜单丢了", controller.contains("BookshelfDialog.Menu("))
        assertTrue("删除确认丢了", controller.contains("BookshelfDialog.DeleteConfirm("))
        assertTrue("清空确认丢了", controller.contains("BookshelfDialog.ClearConfirm("))
        assertTrue("重命名入口丢了", controller.contains("BookshelfDialog.Rename("))
        assertTrue(
            "删除确认文案必须带上书名",
            controller.contains("R.string.bookshelf_delete_confirm, entry.name"),
        )
        assertTrue(
            "清空确认必须带上本数",
            controller.contains("R.string.bookshelf_clear_confirm, mBooks.size"),
        )
    }

    @Test
    fun bookshelfRowsReadNameTypeAndTime() {
        // 读屏念的是「书名 + 格式 + 上次打开时间」，不是只有书名。
        val screen = compose("BookshelfScreen.kt")
        assertTrue("条目必须用 description 覆盖朗读内容", screen.contains("contentDescription = entry.description"))
        val controller = text(bookshelfControllerPath)
        listOf(
            "R.string.bookshelf_item_desc,",
            "R.string.bookshelf_item_desc_no_time,",
            "DateUtils.getRelativeTimeSpanString(",
        ).forEach { fragment ->
            assertTrue("朗读文案装配缺：$fragment", controller.contains(fragment))
        }
    }

    // ------------------------------------------------ 阅读器 Compose 化 + 导出

    private val readerActivityPath =
        base + "src/main/java/com/linxi/brailleback/BrailleReaderActivity.kt"

    @Test
    fun readerIsComposeAndXmlLayoutIsGone() {
        val activity = text(readerActivityPath)
        assertTrue("阅读器必须用 setContent 承载界面", activity.contains("setContent {"))
        assertTrue("界面交给 BrailleReaderScreen", activity.contains("BrailleReaderScreen("))
        assertFalse("不该再 setContentView", activity.contains("setContentView"))
        assertFalse("不该再 findViewById", activity.contains("findViewById"))

        val layout = listOf(
            File(base + "src/main/res/layout/braille_reader.xml"),
            File("..", base + "src/main/res/layout/braille_reader.xml"),
            File("../..", base + "src/main/res/layout/braille_reader.xml"),
        )
        assertFalse("braille_reader.xml 应已删除", layout.any { it.exists() })
    }

    @Test
    fun readerKeepsStorageKeysAndOpenUriContract() {
        val activity = text(readerActivityPath)
        listOf(
            "const val EXTRA_URI = \"com.linxi.brailleback.extra.OPEN_URI\"",
            "PREFS_NAME = \"braille_reader\"",
            "KEY_URI = \"last_uri\"",
            "KEY_OFFSET = \"last_offset\"",
        ).forEach { fragment ->
            assertTrue("阅读器存储契约被改动：$fragment", activity.contains(fragment))
        }
    }
}
