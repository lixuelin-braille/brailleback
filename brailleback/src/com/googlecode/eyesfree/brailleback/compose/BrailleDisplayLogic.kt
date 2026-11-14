/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 */

package com.googlecode.eyesfree.brailleback.compose

/**
 * 「盲文显示」子屏的纯逻辑：零 Android 依赖，便于 JVM 单测。
 *
 * 五个 key 与 `res/xml/settings.xml` 中同名子屏一一对应，值由
 * BrailleDisplayContractTest 与 donottranslate.xml 交叉校验，
 * 保证两代 UI 读写同一份 SharedPreferences，用户设置不丢。
 */
object BrailleDisplayLogic {

    const val KEY_BRAILLE_TABLE = "braille_table"
    const val KEY_WORD_WRAP = "braille_word_wrap"
    const val KEY_CURSOR_DOTS = "cursor_dots"
    const val KEY_CONTRACTED_MODE = "braille_contracted_mode"
    const val KEY_CHINESE_SEGMENTATION = "chinese_segmentation"

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
}
