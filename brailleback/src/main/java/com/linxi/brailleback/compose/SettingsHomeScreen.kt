/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 *
 * 设置主屏（Compose）。替代最后一处 PreferenceFragmentCompat：
 * 分组标题 + 入口行，没有子屏、没有 back stack——每个子屏都是独立 Activity。
 *
 * 无障碍沿用 SettingsComponents 的既有语义：分组标题 heading()、入口行整行可点
 * 且 mergeDescendants，读屏一次朗读「标题 + 用途」，不靠颜色或位置传达信息。
 */

package com.linxi.brailleback.compose

import androidx.compose.runtime.Composable

/** 供界面渲染的一行：标题与摘要都已解析成字符串，Screen 不碰 Context。 */
internal data class HomeRowUi(
    val key: String,
    val title: String,
    val summary: String,
)

/** 一个分组：分组标题 + 若干行。 */
internal data class HomeSectionUi(
    val title: String,
    val rows: List<HomeRowUi>,
)

@Composable
internal fun SettingsHomeScreen(
    title: String,
    sections: List<HomeSectionUi>,
    openHint: String,
    onOpen: (String) -> Unit,
) {
    SettingsHomeScaffold(title = title) {
        sections.forEach { section ->
            settingsSectionHeader(section.title)
            section.rows.forEach { row ->
                item(key = "row:${row.key}") {
                    SettingsActionRow(
                        title = row.title,
                        summary = row.summary,
                        clickLabel = openHint,
                        onClick = { onOpen(row.key) },
                    )
                }
            }
        }
    }
}
