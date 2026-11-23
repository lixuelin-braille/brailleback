/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 */

package com.linxi.brailleback.compose

/**
 * 「盲文显示」子屏的纯逻辑：零 Android 依赖，便于 JVM 单测。
 *
 * 五个 key 与 `res/xml/settings.xml` 中同名子屏一一对应，值由
 * BrailleDisplayContractTest 与 donottranslate.xml 交叉校验，
 * 保证两代 UI 读写同一份 SharedPreferences，用户设置不丢。
 *
 * 导航部分复刻谷歌读屏 `AddLanguageActivity` → `LocaleLanguageActivity`：
 * 设置列表 → 语言列表（全屏）→ 某一语言的表列表（全屏）。
 */
object BrailleDisplayLogic {

    const val KEY_BRAILLE_TABLE = "braille_table"
    const val KEY_WORD_WRAP = "braille_word_wrap"
    const val KEY_CURSOR_DOTS = "cursor_dots"
    const val KEY_CONTRACTED_MODE = "braille_contracted_mode"
    const val KEY_CHINESE_SEGMENTATION = "chinese_segmentation"

    /** 多选「已启用方案」列表；与 [BrailleCodeCatalog.KEY_PREFERRED_CODES] 必须一致。 */
    const val KEY_PREFERRED_CODES = "preferred_braille_codes"

    /** 与 `table_value_default` 一致；「自动」不是一张真实的表。 */
    const val DEFAULT_TABLE = BrailleTableCatalog.AUTO_VALUE

    /** 持久化项，顺序即界面顺序。 */
    val PERSISTED_KEYS = listOf(
        KEY_BRAILLE_TABLE,
        KEY_WORD_WRAP,
        KEY_CURSOR_DOTS,
        KEY_CONTRACTED_MODE,
        KEY_CHINESE_SEGMENTATION,
    )

    /** 首次读取或历史脏值时回落到「自动」，避免列表行朗读空白。 */
    fun initialTable(stored: String?): String =
        if (stored.isNullOrEmpty()) DEFAULT_TABLE else stored

    /** 四个布尔项的出厂默认值，与 settings.xml 的 app:defaultValue 对应。 */
    fun defaultBoolean(key: String, wordWrapDefault: Boolean, segmentationDefault: Boolean): Boolean =
        when (key) {
            KEY_WORD_WRAP -> wordWrapDefault
            KEY_CHINESE_SEGMENTATION -> segmentationDefault
            KEY_CURSOR_DOTS, KEY_CONTRACTED_MODE -> false
            else -> false
        }

    // ---------------------------------------------------------- 两级全屏导航

    /**
     * 三段路由。每一段都占满整个 Scaffold（标题栏 + 列表一起换），
     * 不是在同一列表里用 heading 分段。
     */
    sealed class TableRoute {
        /** 五个设置项。 */
        object Settings : TableRoute()

        /** 第一级全屏：语言列表 + 「自动」。 */
        object Languages : TableRoute()

        /** 第二级全屏：某一语言下的全部盲文表，[language] 是语言码。 */
        data class Tables(val language: String) : TableRoute()
    }

    /** 第一级一行所需的、已经本地化好的文字；Activity 层用资源算好。 */
    data class LanguageRow(
        val language: String,
        val title: String,
        val summary: String,
        val openHint: String,
        /** 该语言已启用的方案数，读屏不用进第二层就知道规模。 */
        val enabledCount: Int,
    )

    fun isPicker(route: TableRoute): Boolean = route !is TableRoute.Settings

    /** 系统返回键 / 标题栏返回：第二级回第一级，第一级回设置列表。 */
    fun back(route: TableRoute): TableRoute = when (route) {
        is TableRoute.Tables -> TableRoute.Languages
        TableRoute.Languages -> TableRoute.Settings
        TableRoute.Settings -> TableRoute.Settings
    }

    fun groupForLanguage(
        groups: List<BrailleTableCatalog.TableGroup>,
        language: String,
    ): BrailleTableCatalog.TableGroup? =
        groups.firstOrNull { it.language.equals(language, ignoreCase = true) }

    /** 当前正在使用的方案属于哪种语言，用于第一级的「当前选择在此语言」文字提示。 */
    fun languageOfTable(
        groups: List<BrailleTableCatalog.TableGroup>,
        value: String,
    ): String? =
        if (value == BrailleTableCatalog.AUTO_VALUE || value.isEmpty()) {
            null
        } else {
            groups.firstOrNull { group -> group.choices.any { it.id == value } }?.language
        }
}
