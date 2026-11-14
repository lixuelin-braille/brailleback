/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 */

package com.googlecode.eyesfree.brailleback.compose

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable

/**
 * 「盲文显示」子屏：五个设置项 + 一个分组标题，与 settings.xml 同名子屏一一对应。
 *
 * 盲文表选择器与设置列表共用同一个 Scaffold，用状态切换而不是再开一层导航：
 * 用户感知仍是「点开一个选择列表」，主屏到子屏的层级不变。选择列表按
 * 国家/地区分组，一次性全部加载，分组标题带 heading() 语义，可按标题跳转。
 *
 * 全部复用 SettingsComponents 的组件底座，不在这里另写 Row / contentDescription，
 * 保证读屏语义与前两批不漂移。
 */
@Composable
internal fun BrailleDisplayScreen(
    labels: BrailleDisplayLabels,
    groups: List<BrailleTableCatalog.TableGroup>,
    autoLabel: String,
    currentTableLabel: String,
    currentTable: String,
    wordWrap: Boolean,
    cursorDots: Boolean,
    contractedMode: Boolean,
    chineseSegmentation: Boolean,
    pickerOpen: Boolean,
    onOpenPicker: () -> Unit,
    onClosePicker: () -> Unit,
    onRefreshTables: () -> Unit,
    onTableSelected: (String) -> Unit,
    onBooleanChanged: (String, Boolean) -> Unit,
    onBack: () -> Unit,
) {
    // 系统返回键先关闭表选择器，再退出整个子屏。
    BackHandler(enabled = pickerOpen) { onClosePicker() }

    SettingsScreenScaffold(
        title = if (pickerOpen) labels.pickerTitle else labels.title,
        backLabel = labels.back,
        onBack = { if (pickerOpen) onClosePicker() else onBack() },
    ) {
        if (pickerOpen) {
            // 「自动」永远排在最前，与旧 ListPreference 的第一项一致。
            settingsSectionHeader(labels.autoGroupTitle)
            item(key = "table:auto") {
                SettingsRadioRow(
                    title = autoLabel.ifEmpty { labels.loadingLabel },
                    selected = currentTable == BrailleTableCatalog.AUTO_VALUE,
                    selectedLabel = labels.selected,
                    unselectedLabel = labels.unselected,
                    onClick = { onTableSelected(BrailleTableCatalog.AUTO_VALUE) },
                )
            }

            if (groups.isEmpty()) {
                item(key = "table:empty") {
                    SettingsActionRow(
                        title = labels.emptyLabel,
                        summary = labels.loadingLabel,
                        clickLabel = labels.refreshHint,
                        onClick = onRefreshTables,
                    )
                }
            }

            // 72 张表运行时才从翻译服务拿到，这里按分组动态展开、一次性加载。
            groups.forEach { group ->
                settingsSectionHeader(group.title)
                group.choices.forEach { choice ->
                    item(key = "table:${choice.id}") {
                        SettingsRadioRow(
                            title = choice.label,
                            selected = currentTable == choice.id,
                            selectedLabel = labels.selected,
                            unselectedLabel = labels.unselected,
                            onClick = { onTableSelected(choice.id) },
                        )
                    }
                }
            }
        } else {
            settingsSectionHeader(labels.sectionBrailleType)

            item(key = BrailleDisplayLogic.KEY_BRAILLE_TABLE) {
                SettingsListRow(
                    title = labels.tableTitle,
                    currentLabel = currentTableLabel,
                    chooseHint = labels.chooseHint,
                    onClick = onOpenPicker,
                )
            }

            item(key = BrailleDisplayLogic.KEY_WORD_WRAP) {
                SettingsSwitchRow(
                    title = labels.wordWrapTitle,
                    summary = if (wordWrap) {
                        labels.wordWrapSummaryOn
                    } else {
                        labels.wordWrapSummaryOff
                    },
                    checked = wordWrap,
                    onLabel = labels.on,
                    offLabel = labels.off,
                    onCheckedChange = {
                        onBooleanChanged(BrailleDisplayLogic.KEY_WORD_WRAP, it)
                    },
                )
            }

            item(key = BrailleDisplayLogic.KEY_CURSOR_DOTS) {
                SettingsSwitchRow(
                    title = labels.cursorDotsTitle,
                    // 状态由 stateDescription 播报，摘要刻意不带「已开启。/已关闭。」前缀。
                    summary = if (cursorDots) {
                        labels.cursorDotsSummaryOn
                    } else {
                        labels.cursorDotsSummaryOff
                    },
                    checked = cursorDots,
                    onLabel = labels.on,
                    offLabel = labels.off,
                    onCheckedChange = {
                        onBooleanChanged(BrailleDisplayLogic.KEY_CURSOR_DOTS, it)
                    },
                )
            }

            item(key = BrailleDisplayLogic.KEY_CONTRACTED_MODE) {
                SettingsSwitchRow(
                    title = labels.contractedTitle,
                    summary = if (contractedMode) {
                        labels.contractedSummaryOn
                    } else {
                        labels.contractedSummaryOff
                    },
                    checked = contractedMode,
                    onLabel = labels.on,
                    offLabel = labels.off,
                    onCheckedChange = {
                        onBooleanChanged(BrailleDisplayLogic.KEY_CONTRACTED_MODE, it)
                    },
                )
            }

            item(key = BrailleDisplayLogic.KEY_CHINESE_SEGMENTATION) {
                SettingsSwitchRow(
                    title = labels.segmentationTitle,
                    summary = labels.segmentationSummary,
                    checked = chineseSegmentation,
                    onLabel = labels.on,
                    offLabel = labels.off,
                    onCheckedChange = {
                        onBooleanChanged(BrailleDisplayLogic.KEY_CHINESE_SEGMENTATION, it)
                    },
                )
            }
        }
    }
}
