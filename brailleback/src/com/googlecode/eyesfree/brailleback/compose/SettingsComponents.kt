/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 *
 * Compose 设置页的组件底座。
 *
 * 无障碍是这里的第一设计约束，不是事后补的 contentDescription：
 * - 每个可交互行把标题、摘要、当前状态合并成一次朗读（mergeDescendants）；
 * - 开关用 Role.Switch + stateDescription，读屏会明确播报「已开启 / 已关闭」；
 * - 分组标题用 heading()，读屏可按标题跳转；
 * - 列表用 LazyColumn，一次性加载、没有「下一页」按钮；
 * - 所有状态都有文字说明，不靠颜色单独传达。
 *
 * 后续子屏（按键与手势 / 盲文显示 / 声音与行为 / 开发者选项）直接复用这些组件，
 * 不要各自再写一套 Row，避免读屏语义漂移。
 */

package com.googlecode.eyesfree.brailleback.compose

import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

private val MinRowHeight = 56.dp
private val ScreenPadding = 16.dp

/**
 * 设置子屏外壳：跟随系统深浅色，自带带「返回」的标题栏与可滚动列表。
 * [content] 由调用方用 LazyListScope 提供，方便复用分组与行组件。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreenScaffold(
    title: String,
    backLabel: String,
    onBack: () -> Unit,
    content: LazyListScope.() -> Unit,
) {
    SettingsScaffold(title = title, backLabel = backLabel, onBack = onBack, content = content)
}

/**
 * 主屏外壳：与子屏同一套主题与列表，但没有返回箭头——主屏就是导航根，
 * 返回键交给系统（按返回直接离开设置）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsHomeScaffold(
    title: String,
    content: LazyListScope.() -> Unit,
) {
    SettingsScaffold(title = title, backLabel = null, onBack = null, content = content)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScaffold(
    title: String,
    backLabel: String?,
    onBack: (() -> Unit)?,
    content: LazyListScope.() -> Unit,
) {
    val colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
    MaterialTheme(colorScheme = colorScheme) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            text = title,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    navigationIcon = {
                        if (onBack != null && backLabel != null) {
                            IconButton(onClick = onBack) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = backLabel,
                                )
                            }
                        }
                    },
                )
            },
        ) { padding ->
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(
                    top = padding.calculateTopPadding(),
                    bottom = padding.calculateBottomPadding(),
                ),
                content = content,
            )
        }
    }
}

/** 分组标题：heading() 让读屏可以按标题导航到这一段。 */
fun LazyListScope.settingsSectionHeader(title: String) {
    item(key = "section:$title") {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = ScreenPadding, vertical = 12.dp)
                .semantics { heading() },
        )
    }
}

/**
 * 开关设置行。整行是可聚焦的 Switch（不是「文字 + 另一个可聚焦开关」），
 * 一次朗读包含标题、摘要与当前状态。
 */
@Composable
fun SettingsSwitchRow(
    title: String,
    summary: String,
    checked: Boolean,
    onLabel: String,
    offLabel: String,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    val state = if (checked) onLabel else offLabel
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = MinRowHeight)
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            )
            // 不手写 contentDescription：mergeDescendants 会把两个 Text 合并成一次朗读，
            // 手写 contentDescription 反而会覆盖 role/stateDescription 的组合播报。
            .semantics(mergeDescendants = true) { stateDescription = state }
            .padding(horizontal = ScreenPadding, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = summary,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = null, // 由整行 toggleable 统一处理，避免双焦点
            enabled = enabled,
        )
    }
}

/**
 * 列表选择行：点击弹出单选对话框。朗读里直接带上当前值，
 * 读屏用户不用进对话框就知道现在选的是哪一项。
 */
@Composable
fun SettingsListRow(
    title: String,
    currentLabel: String,
    chooseHint: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    SettingsTextRow(
        title = title,
        summary = currentLabel,
        clickLabel = chooseHint,
        onClick = onClick,
        enabled = enabled,
    )
}

/**
 * 动作 / 信息行：点击执行动作（如刷新连接状态、打开书架）。
 */
@Composable
fun SettingsActionRow(
    title: String,
    summary: String,
    clickLabel: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    SettingsTextRow(
        title = title,
        summary = summary,
        clickLabel = clickLabel,
        onClick = onClick,
        enabled = enabled,
    )
}

@Composable
private fun SettingsTextRow(
    title: String,
    summary: String,
    clickLabel: String,
    onClick: () -> Unit,
    enabled: Boolean,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = MinRowHeight)
            .clickable(
                enabled = enabled,
                role = Role.Button,
                onClickLabel = clickLabel,
                onClick = onClick,
            )
            .semantics(mergeDescendants = true) {}
            .padding(horizontal = ScreenPadding, vertical = 12.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(text = title, style = MaterialTheme.typography.bodyLarge)
        Text(
            text = summary,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 多选方案行：整行是 Role.Checkbox，对应谷歌 `LocaleLanguageActivity` 的
 * `CheckBoxPreference`。[state] 由调用方拼好完整状态文字（含「当前正在使用」），
 * 读屏一次朗读就能知道启用状态与是否为当前方案；不手写 contentDescription。
 */
@Composable
fun SettingsCheckRow(
    title: String,
    summary: String,
    checked: Boolean,
    state: String,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = MinRowHeight)
            .toggleable(
                value = checked,
                role = Role.Checkbox,
                onValueChange = onCheckedChange,
            )
            .semantics(mergeDescendants = true) { stateDescription = state }
            .padding(horizontal = ScreenPadding, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = summary,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 长列表分组选择器中的一行（如按国家分组的盲文表）。
 *
 * 与 [SettingsChoiceDialog] 的选项语义完全一致：整行是可聚焦的 RadioButton，
 * 由 stateDescription 播报「已选中 / 未选中」；刻意不手写 contentDescription，
 * 否则会吞掉 role / stateDescription 的组合播报。
 */
@Composable
fun SettingsRadioRow(
    title: String,
    selected: Boolean,
    selectedLabel: String,
    unselectedLabel: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = MinRowHeight)
            .selectable(
                selected = selected,
                role = Role.RadioButton,
                onClick = onClick,
            )
            .semantics(mergeDescendants = true) {
                stateDescription = if (selected) selectedLabel else unselectedLabel
            }
            .padding(horizontal = ScreenPadding, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(modifier = Modifier.width(12.dp))
        Text(text = title, style = MaterialTheme.typography.bodyLarge)
    }
}

/**
 * 单选对话框，替代 ListPreference。
 * 每个选项用 Role.RadioButton，读屏会播报「已选中 / 未选中」。
 */
@Composable
fun SettingsChoiceDialog(
    title: String,
    entries: List<String>,
    values: List<String>,
    currentValue: String,
    cancelLabel: String,
    selectedLabel: String,
    unselectedLabel: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                entries.forEachIndexed { index, label ->
                    val value = values.getOrElse(index) { "" }
                    val selected = value == currentValue
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = MinRowHeight)
                            .selectable(
                                selected = selected,
                                role = Role.RadioButton,
                                onClick = { onSelect(value) },
                            )
                            .semantics(mergeDescendants = true) {
                                stateDescription = if (selected) selectedLabel else unselectedLabel
                            }
                            .padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = selected, onClick = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = label, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(cancelLabel) }
        },
    )
}

/**
 * 数值调节对话框，替代旧设置页里直接用 View 塞进 AlertDialog 的 SeekBar。
 *
 * 无障碍要点：滑杆本身对中文读屏不一定好用（上下滑动手势可能被读屏拦截），
 * 所以额外提供「减 1 / 加 1」两个纯点击按钮作为等价路径；当前值始终以文字显示，
 * 且写进滑杆的 stateDescription，不靠滑块的视觉位置传达状态。
 * 组件本身不认识任何业务偏好键，区间与文案都由调用方给。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsVolumeDialog(
    title: String,
    currentValue: Int,
    min: Int,
    max: Int,
    valueText: (Int) -> String,
    decreaseLabel: String,
    increaseLabel: String,
    confirmLabel: String,
    cancelLabel: String,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var value by remember(currentValue) { mutableStateOf(currentValue.coerceIn(min, max)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = valueText(value),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Slider(
                    value = value.toFloat(),
                    onValueChange = { value = it.toInt() },
                    valueRange = min.toFloat()..max.toFloat(),
                    steps = (max - min - 1).coerceAtLeast(0),
                    modifier = Modifier
                        .fillMaxWidth()
                        // 不手写 contentDescription：会吞掉滑杆自带的进度语义。
                        .semantics { stateDescription = valueText(value) },
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    TextButton(onClick = { value = (value - 1).coerceIn(min, max) }) {
                        Text(decreaseLabel)
                    }
                    TextButton(onClick = { value = (value + 1).coerceIn(min, max) }) {
                        Text(increaseLabel)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(value) }) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(cancelLabel) }
        },
    )
}

/**
 * 通用二次确认对话框（如「确定清除全部调试日志？」）。
 * 两个动作都是纯点击的 TextButton，读屏会自带「按钮」角色；
 * 不手写 contentDescription，避免吞掉按钮语义。
 */
@Composable
fun SettingsConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    cancelLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(cancelLabel) }
        },
    )
}

/**
 * 调试日志查看对话框，替代旧设置页里直接塞进 AlertDialog 的 ScrollView + TextView。
 *
 * 无障碍要点：日志最多 256KB×4，把全文交给读屏线性朗读既卡顿又找不到重点，
 * 因此这里只显示调用方已经裁好的预览正文；完整日志一律通过「复制 / 分享」
 * 两个纯点击按钮导出。正文仍包在 SelectionContainer 里，保留长按选择能力。
 */
@Composable
fun SettingsLogViewDialog(
    title: String,
    body: String,
    copyLabel: String,
    shareLabel: String,
    closeLabel: String,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 320.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                SelectionContainer {
                    Text(text = body, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            Row {
                TextButton(onClick = onCopy) { Text(copyLabel) }
                TextButton(onClick = onShare) { Text(shareLabel) }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(closeLabel) }
        },
    )
}
