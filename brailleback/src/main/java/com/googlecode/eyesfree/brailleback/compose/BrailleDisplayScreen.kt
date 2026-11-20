/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 */

package com.googlecode.eyesfree.brailleback.compose

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable

/**
 * 「盲文显示」子屏：设置列表 + 严格复刻谷歌读屏的两级全屏方案选择。
 *
 * 三段路由各占满整个 Scaffold，标题栏与列表一起替换：
 * - Settings：五个设置项（与 settings.xml 子屏一一对应）；
 * - Languages：第一级全屏，只列「自动」和可点击的语言行，**没有分组标题**；
 * - Tables：第二级全屏，标题栏是语言名，整屏平铺该语言的全部方案，
 *   每行是 Role.Checkbox 的多选启用项，**没有分组标题**。
 *
 * 第二层复刻 `LocaleLanguageActivity` 的 CheckBoxPreference：勾选决定「启用哪些
 * 方案」；当前正在使用哪一个由第一行的「当前方案」单选对话框决定，两者分开，
 * 避免「点一下既是启用又是选当前」的语义混淆。第一行是一条可点击行，不是标题。
 *
 * 全部复用 SettingsComponents 的组件底座，不另写 Row / contentDescription。
 */
@Composable
internal fun BrailleDisplayScreen(
    labels: BrailleDisplayLabels,
    route: BrailleDisplayLogic.TableRoute,
    languageRows: List<BrailleDisplayLogic.LanguageRow>,
    codes: List<BrailleCodeCatalog.Code>,
    enabledCodes: Set<String>,
    currentCodeOptions: List<BrailleCodeCatalog.Code>,
    currentCodeLabel: String,
    showCurrentDialog: Boolean,
    title: String,
    autoLabel: String,
    currentTableLabel: String,
    currentTable: String,
    wordWrap: Boolean,
    cursorDots: Boolean,
    contractedMode: Boolean,
    chineseSegmentation: Boolean,
    onOpenLanguages: () -> Unit,
    onOpenLanguage: (String) -> Unit,
    onNavigateBack: () -> Unit,
    onRefreshTables: () -> Unit,
    onToggleCode: (String, Boolean) -> Unit,
    onOpenCurrentDialog: () -> Unit,
    onDismissCurrentDialog: () -> Unit,
    onSelectCurrent: (String) -> Unit,
    onAutoSelected: () -> Unit,
    onBooleanChanged: (String, Boolean) -> Unit,
    onBack: () -> Unit,
) {
    val inPicker = BrailleDisplayLogic.isPicker(route)
    // 系统返回键：第二级 → 第一级 → 设置列表；到设置列表后交还系统退出 Activity。
    BackHandler(enabled = inPicker) { onNavigateBack() }

    if (showCurrentDialog) {
        SettingsChoiceDialog(
            title = labels.currentCodeDialog,
            entries = currentCodeOptions.map { it.label },
            values = currentCodeOptions.map { it.id },
            currentValue = currentTable,
            cancelLabel = labels.cancel,
            selectedLabel = labels.selected,
            unselectedLabel = labels.unselected,
            onSelect = onSelectCurrent,
            onDismiss = onDismissCurrentDialog,
        )
    }

    SettingsScreenScaffold(
        title = title,
        backLabel = labels.back,
        onBack = { if (inPicker) onNavigateBack() else onBack() },
    ) {
        when (route) {
            BrailleDisplayLogic.TableRoute.Settings -> {
                settingsSectionHeader(labels.sectionBrailleType)

                item(key = BrailleDisplayLogic.KEY_BRAILLE_TABLE) {
                    SettingsListRow(
                        title = labels.tableTitle,
                        currentLabel = currentTableLabel,
                        chooseHint = labels.chooseHint,
                        onClick = onOpenLanguages,
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

            BrailleDisplayLogic.TableRoute.Languages -> {
                // 「自动」是第一级唯一的全局选项，渲染成可点击的单选行；
                // 刻意不设 settingsSectionHeader，第一级全部是等权的可聚焦项。
                item(key = "table:auto") {
                    SettingsRadioRow(
                        title = autoLabel.ifEmpty { labels.autoFallback },
                        selected = currentTable == BrailleTableCatalog.AUTO_VALUE,
                        selectedLabel = labels.selected,
                        unselectedLabel = labels.unselected,
                        onClick = onAutoSelected,
                    )
                }

                if (languageRows.isEmpty()) {
                    item(key = "table:empty") {
                        SettingsActionRow(
                            title = labels.emptyLabel,
                            summary = labels.loadingLabel,
                            clickLabel = labels.refreshHint,
                            onClick = onRefreshTables,
                        )
                    }
                }

                // 方案清单运行时才从翻译服务拿到；第一级按语言一次性列出，
                // 每行是可点击的导航按钮，点进去是另一整屏。
                languageRows.forEach { row ->
                    item(key = "language:${row.language}") {
                        SettingsActionRow(
                            title = row.title,
                            summary = row.summary,
                            clickLabel = row.openHint,
                            onClick = { onOpenLanguage(row.language) },
                        )
                    }
                }
            }

            is BrailleDisplayLogic.TableRoute.Tables -> {
                // 第一行是「当前方案」单选对话框入口：一条可点击行，不是分组标题。
                // 复选框只管「启用哪些方案」，当前用哪一个在这里单独选，语义不混淆。
                item(key = "code:current") {
                    SettingsListRow(
                        title = labels.currentCodeTitle,
                        currentLabel = currentCodeLabel,
                        chooseHint = labels.currentCodeHint,
                        onClick = onOpenCurrentDialog,
                    )
                }

                // 第二级全屏：整屏平铺该语言的全部方案，不加 heading。
                codes.forEach { code ->
                    item(key = "code:${code.id}") {
                        val checked = code.id in enabledCodes
                        val state = (if (checked) labels.enabledLabel else labels.disabledLabel) +
                            if (code.id == currentTable) labels.currentSuffix else ""
                        SettingsCheckRow(
                            title = code.label,
                            summary = code.summary,
                            checked = checked,
                            state = state,
                            onCheckedChange = { onToggleCode(code.id, it) },
                        )
                    }
                }
            }
        }
    }
}
