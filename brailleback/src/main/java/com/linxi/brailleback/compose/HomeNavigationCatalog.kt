/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 *
 * 主屏底部导航的 Tab 数据表。
 *
 * 目前固定两个 Tab：
 * - [READING] 书籍阅读管理：内嵌原有书架（BookshelfScreen + BookshelfController）；
 * - [SETTINGS] 设置：复用原设置主屏的十行三组（SettingsHomeCatalog / SettingsHomeScreen）。
 *
 * 顺序就是读屏的线性浏览顺序；新增 Tab 必须先在这里登记，单测按此表核对。
 */

package com.linxi.brailleback.compose

import androidx.annotation.StringRes
import com.linxi.brailleback.R

internal enum class HomeTab(@StringRes val titleRes: Int) {
    /** 第 1 个 Tab：书架与书籍阅读管理。 */
    READING(R.string.home_tab_reading_title),

    /** 第 2 个 Tab：设置。 */
    SETTINGS(R.string.home_tab_settings_title);

    companion object {
        /** 初始选中项：进入主屏默认落到设置档（用户 2026-11-26 要求）。 */
        val DEFAULT = SETTINGS

        /** 按序号还原 Tab；越界回落到默认 Tab（进程恢复等场景兜底）。 */
        fun fromOrdinal(ordinal: Int): HomeTab =
            entries.getOrNull(ordinal) ?: DEFAULT
    }
}
