/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 *
 * 设置主屏的目录数据：三个语义分组、九行入口。
 *
 * 为什么单独抽成数据而不是写在界面里：
 * - 读屏用户是线性浏览的，分组层级与一屏节点数直接决定能不能找到设置，
 *   这两点必须能被 JVM 单测（SettingsStructureContractTest）当数据核对，
 *   而不是靠人看截图；
 * - key 就是旧 settings.xml 里 app:key 的取值，与 SharedPreferences 的键同一套，
 *   迁移到 Compose 不改键名，老用户的设置不会丢。
 */

package com.linxi.brailleback.compose

import android.app.Activity
import androidx.annotation.StringRes
import com.linxi.brailleback.R

/** 主屏的三个语义分组。key 与旧 settings.xml 的 app:key 完全一致。 */
internal enum class HomeSection(val key: String, @StringRes val titleRes: Int) {
    START("category_start", R.string.pref_category_start_title),
    SETTINGS("category_settings", R.string.pref_category_settings_title),
    FEATURES("category_features", R.string.pref_category_features_title),
}

/** 点一下这一行会发生什么。 */
internal sealed interface HomeAction {
    /**
     * 打开某个设置子屏。目标 Activity 由宿主按 [keyRes] 查表决定，
     * 这样「哪一行跳哪一屏」集中在宿主一处接线，不会散落在界面代码里。
     */
    data class Screen(@StringRes val keyRes: Int) : HomeAction
    data object Bookshelf : HomeAction
    data object AccessibilitySettings : HomeAction
    /** 屏幕盲文键盘：服务没起来时只能提示，不能跳转（目标页会空指针）。 */
    data object BrailleKeyboard : HomeAction
    data object Licenses : HomeAction
}

/**
 * 一行入口。
 *
 * [dynamicSummary] 表示摘要来自实时状态（如「启用服务」要显示已启用 / 未启用），
 * 不是资源里的静态文案。
 */
internal data class HomeEntry(
    /** 与旧 app:key 同值，稳定不迁移。 */
    val key: String,
    @StringRes val titleRes: Int,
    @StringRes val summaryRes: Int,
    val section: HomeSection,
    val action: HomeAction,
    val dynamicSummary: Boolean = false,
)

/**
 * 主屏全部入口，列表顺序就是读屏的浏览顺序：先「怎么开始用」，再「设置分类」，
 * 最后「功能与内容」。三组共九行，加上三个分组标题，一屏 12 个可聚焦节点。
 */
internal object SettingsHomeCatalog {

    val entries: List<HomeEntry> = listOf(
        HomeEntry(
            key = "setup_service",
            titleRes = R.string.pref_setup_service_title,
            summaryRes = R.string.pref_setup_service_summary,
            section = HomeSection.START,
            action = HomeAction.AccessibilitySettings,
            dynamicSummary = true,
        ),
        HomeEntry(
            key = "category_device",
            titleRes = R.string.pref_category_device_title,
            summaryRes = R.string.pref_category_device_summary,
            section = HomeSection.SETTINGS,
            action = HomeAction.Screen(R.string.pref_category_device_key),
        ),
        HomeEntry(
            key = "category_keys",
            titleRes = R.string.pref_category_keys_title,
            summaryRes = R.string.pref_category_keys_summary,
            section = HomeSection.SETTINGS,
            action = HomeAction.Screen(R.string.pref_category_keys_key),
        ),
        HomeEntry(
            key = "category_braille",
            titleRes = R.string.pref_category_braille_title,
            summaryRes = R.string.pref_category_braille_summary,
            section = HomeSection.SETTINGS,
            action = HomeAction.Screen(R.string.pref_category_braille_key),
        ),
        HomeEntry(
            key = "category_sound_behavior",
            titleRes = R.string.pref_category_sound_behavior_title,
            summaryRes = R.string.pref_category_sound_behavior_summary,
            section = HomeSection.SETTINGS,
            action = HomeAction.Screen(R.string.pref_category_sound_behavior_key),
        ),
        HomeEntry(
            key = "category_developer",
            titleRes = R.string.pref_category_developer_title,
            summaryRes = R.string.pref_category_developer_summary,
            section = HomeSection.SETTINGS,
            action = HomeAction.Screen(R.string.pref_category_developer_key),
        ),
        // 稳定性与省电：无障碍服务被系统或厂商省电策略杀掉时点显器是静默断开的，
        // 这一屏给出实测状态和直达各设置页的入口，和按键设置一样属于必看项。
        HomeEntry(
            key = "category_stability",
            titleRes = R.string.pref_category_stability_title,
            summaryRes = R.string.pref_category_stability_summary,
            section = HomeSection.SETTINGS,
            action = HomeAction.Screen(R.string.pref_category_stability_key),
        ),
        HomeEntry(
            key = "pref_braille_keyboard_key",
            titleRes = R.string.pref_braille_keyboard_title,
            summaryRes = R.string.pref_braille_keyboard_summary,
            section = HomeSection.FEATURES,
            action = HomeAction.BrailleKeyboard,
        ),
        HomeEntry(
            key = "opensource_license_notice",
            titleRes = R.string.pref_os_license_title,
            summaryRes = R.string.pref_os_license_summary,
            section = HomeSection.FEATURES,
            action = HomeAction.Licenses,
        ),
    )

    fun entriesOf(section: HomeSection): List<HomeEntry> = entries.filter { it.section == section }

    fun entryOf(key: String): HomeEntry? = entries.firstOrNull { it.key == key }
}
