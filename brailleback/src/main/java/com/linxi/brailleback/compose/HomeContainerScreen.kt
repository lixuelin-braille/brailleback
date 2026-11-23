/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 *
 * 主屏容器：底部导航 + 两个内嵌 Tab。
 *
 * 纯状态切换，不引 NavHost（本仓库 idiom 是「子屏各自独立 Activity、主屏无
 * back stack」）。选中 Tab 用 rememberSaveable 持有，从二级页返回后回到离开时
 * 的 Tab；切 Tab 由宿主播报目标 Tab 名。
 *
 * 无障碍：
 * - 每个 Tab 项是一个合并焦点，选中态用 stateDescription 播报，不只靠颜色/指示器；
 * - 点击区沿用 NavigationBarItem 默认高度（≥56dp）；
 * - 阅读 Tab 内嵌 [BookshelfScreen]，设置 Tab 内嵌 [SettingsHomeScreen]，
 *   两者原有分组/行语义不变。
 */

package com.linxi.brailleback.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription

@Composable
internal fun HomeContainerScreen(
    initialTab: HomeTab,
    readingLabels: BookshelfLabels,
    readingEntries: List<BookshelfEntry>,
    readingDialog: BookshelfDialog?,
    readingActions: BookshelfActions,
    settingsTitle: String,
    settingsSections: List<HomeSectionUi>,
    settingsOpenHint: String,
    tabLabels: List<String>,
    selectedLabel: String,
    unselectedLabel: String,
    onSettingsOpen: (String) -> Unit,
    onTabSelected: (HomeTab) -> Unit,
) {
    var selectedTab by rememberSaveable { mutableStateOf(initialTab) }

    Scaffold(
        bottomBar = {
            NavigationBar {
                HomeTab.entries.forEach { tab ->
                    val isSelected = tab == selectedTab
                    NavigationBarItem(
                        selected = isSelected,
                        onClick = {
                            if (!isSelected) {
                                selectedTab = tab
                                onTabSelected(tab)
                            }
                        },
                        modifier = Modifier.semantics {
                            stateDescription = if (isSelected) selectedLabel else unselectedLabel
                        },
                        icon = {},
                        label = { Text(tabLabels[tab.ordinal]) },
                    )
                }
            }
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when (selectedTab) {
                HomeTab.READING -> BookshelfScreen(
                    labels = readingLabels,
                    entries = readingEntries,
                    dialog = readingDialog,
                    actions = readingActions,
                )

                HomeTab.SETTINGS -> SettingsHomeScreen(
                    title = settingsTitle,
                    sections = settingsSections,
                    openHint = settingsOpenHint,
                    onOpen = onSettingsOpen,
                )
            }
        }
    }
}
