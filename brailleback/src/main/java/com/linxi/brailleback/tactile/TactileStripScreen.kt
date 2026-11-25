/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 *
 * 界面（纯展示：状态与回调由 Activity 持有，这里只排版与语义）。纯重构拆分自
 * TactileStripActivity.kt，布局、语义节点（顺序 / content-desc / stateDescription /
 * liveRegion / mergeDescendants）逐字未改。
 */

package com.linxi.brailleback.tactile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp

/** 点显屏行数取值范围：单行设备到多行列屏。 */
internal const val MIN_LINES = 1
internal const val MAX_LINES = 8

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun StripScreen(
    labels: StripLabels,
    sourceSummary: String?,
    cellsPerLine: Int,
    cellsFollowDisplay: Boolean,
    linesPerScreen: Int,
    dotsPerCell: Int,
    normalizerMode: StockNormalizerMode,
    g1SplitCover: Boolean,
    converting: Boolean,
    status: String?,
    currentKind: StripPageKind?,
    canPage: Boolean,
    canPrev: Boolean,
    canNext: Boolean,
    viewportActive: Boolean,
    detailMode: Boolean,
    displayConnected: Boolean,
    vpIsOverview: Boolean,
    vpIsLegend: Boolean,
    vpLegendMultiPage: Boolean,
    onFirstShown: () -> Unit,
    onPickImage: () -> Unit,
    onCellsChange: (Int) -> Unit,
    onCellsFollowChange: () -> Unit,
    onLinesDecrease: () -> Unit,
    onLinesIncrease: () -> Unit,
    onDotsChange: (Int) -> Unit,
    onNormalizerChange: (StockNormalizerMode) -> Unit,
    onG1Change: (Boolean) -> Unit,
    onFirst: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onLast: () -> Unit,
    onExit: () -> Unit,
    onRepick: () -> Unit,
    onBack: () -> Unit,
    onPanLeft: () -> Unit,
    onPanRight: () -> Unit,
    onPanUp: () -> Unit,
    onPanDown: () -> Unit,
    onEnterDetail: () -> Unit,
    onBackOverview: () -> Unit,
    onOpenLegend: () -> Unit,
    onCloseLegend: () -> Unit,
    onLegendPrev: () -> Unit,
    onLegendNext: () -> Unit,
) {
    // 首屏只播报一次功能说明；LaunchedEffect(Unit) 在重组 / 状态变化时不会重跑。
    LaunchedEffect(Unit) { onFirstShown() }

    // 高级项默认收起，减少首屏可扫读的内容；展开状态只活在本次界面里。
    var advancedOpen by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(labels.title, maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = labels.back,
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // 细节态收成单屏：选图、方数、行数、点数、高级项全部隐去。手机上的文字节点越少，
            // 读屏越不会把大段设置文案翻到点显器上盖住点位；挪视口靠点显器按键与音量键。
            if (!detailMode) {
            Button(
                onClick = onPickImage,
                enabled = !converting,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(labels.pickImage)
            }

            Text(
                sourceSummary ?: labels.noImage,
                style = MaterialTheme.typography.bodyMedium,
            )

            Text(
                // 方数默认跟随点显器：连上就按它的方数换行，用户不必先猜自己的机器有几方。
                labels.cellsFollow.format(cellsPerLine),
                style = MaterialTheme.typography.bodyMedium,
            )

            // 行数：滑杆对读屏不一定好用，给「减 1 / 加 1」纯点击按钮作等价路径。
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(labels.sectionLines, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        labels.linesValue.format(linesPerScreen),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(
                    onClick = onLinesDecrease,
                    enabled = !converting && linesPerScreen > MIN_LINES,
                ) { Text(labels.decrease) }
                TextButton(
                    onClick = onLinesIncrease,
                    enabled = !converting && linesPerScreen < MAX_LINES,
                ) { Text(labels.increase) }
            }

            Text(
                labels.sectionDots,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            StripRadioRow(
                title = labels.dots6,
                description = "",
                selected = dotsPerCell == 6,
                selectedLabel = labels.selected,
                unselectedLabel = labels.unselected,
                onClick = { onDotsChange(6) },
            )
            StripRadioRow(
                title = labels.dots8,
                description = "",
                selected = dotsPerCell == 8,
                selectedLabel = labels.selected,
                unselectedLabel = labels.unselected,
                onClick = { onDotsChange(8) },
            )

            // 高级项默认收起：方数手动档、线条归一、G1 封面分层。平时不用碰，
            // 展开后仍是原来的单选行，读屏可逐项读。
            TextButton(onClick = { advancedOpen = !advancedOpen }) {
                Text(labels.advanced)
            }
            if (advancedOpen) {
                Text(
                    labels.advancedHint,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Text(
                    labels.sectionCells,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                StripRadioRow(
                    title = labels.cellsFollow.format(cellsPerLine),
                    description = labels.cellsDesc,
                    selected = cellsFollowDisplay,
                    selectedLabel = labels.selected,
                    unselectedLabel = labels.unselected,
                    onClick = onCellsFollowChange,
                )
                CELL_CHOICES.forEachIndexed { index, choice ->
                    StripRadioRow(
                        title = labels.cellsChoice[index],
                        description = "",
                        selected = !cellsFollowDisplay && cellsPerLine == choice,
                        selectedLabel = labels.selected,
                        unselectedLabel = labels.unselected,
                        onClick = { onCellsChange(choice) },
                    )
                }

                // 线条归一三态：自动（默认）/ 关 / 强制（调试）。
                Text(
                    labels.normSection,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                StripRadioRow(
                    title = labels.normAuto,
                    description = labels.normDesc,
                    selected = normalizerMode == StockNormalizerMode.AUTO,
                    selectedLabel = labels.selected,
                    unselectedLabel = labels.unselected,
                    onClick = { onNormalizerChange(StockNormalizerMode.AUTO) },
                )
                StripRadioRow(
                    title = labels.normOff,
                    description = "",
                    selected = normalizerMode == StockNormalizerMode.OFF,
                    selectedLabel = labels.selected,
                    unselectedLabel = labels.unselected,
                    onClick = { onNormalizerChange(StockNormalizerMode.OFF) },
                )
                StripRadioRow(
                    title = labels.normForce,
                    description = "",
                    selected = normalizerMode == StockNormalizerMode.FORCE,
                    selectedLabel = labels.selected,
                    unselectedLabel = labels.unselected,
                    onClick = { onNormalizerChange(StockNormalizerMode.FORCE) },
                )

                // G1 封面分层：仅多行设备可用；单行设备置灰不可点。
                StripRadioRow(
                    title = labels.g1Split,
                    description = labels.g1Desc,
                    selected = g1SplitCover,
                    selectedLabel = labels.selected,
                    unselectedLabel = labels.unselected,
                    enabled = linesPerScreen >= 2,
                    onClick = { onG1Change(!g1SplitCover) },
                )
            }
            }

            // 状态区放在翻页按钮上方：连接 / 解码 / 页签结果直接进首屏无障碍焦点序列，
            // live region 保证状态变化时读屏也会主动朗读。
            status?.let { message ->
                Text(
                    message,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { liveRegion = LiveRegionMode.Polite },
                )
            }

            // 视口模式：平移、进细节 / 回总览、独立图例屏；按钮均可聚焦点击，不靠视觉。
            if (viewportActive) {
                // 连上点显器时先说明硬件按键：视口优先用设备自己的键，屏幕按钮退成备用。
                if (displayConnected) {
                    Text(
                        labels.vpKeysHint,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (vpIsLegend) {
                    // 图例超一屏时给「上一页 / 下一页」，手机也能翻，不只靠点显器按键。
                    if (vpLegendMultiPage) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TextButton(onClick = onLegendPrev, modifier = Modifier.weight(1f)) {
                                Text(labels.vpLegendPrev)
                            }
                            TextButton(onClick = onLegendNext, modifier = Modifier.weight(1f)) {
                                Text(labels.vpLegendNext)
                            }
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(onClick = onCloseLegend, modifier = Modifier.weight(1f)) {
                            Text(labels.vpLegendBack)
                        }
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (vpIsOverview) {
                            TextButton(onClick = onEnterDetail, modifier = Modifier.weight(1f)) {
                                Text(labels.vpEnterDetail)
                            }
                        } else {
                            TextButton(onClick = onBackOverview, modifier = Modifier.weight(1f)) {
                                Text(labels.vpBackOverview)
                            }
                        }
                        TextButton(onClick = onOpenLegend, modifier = Modifier.weight(1f)) {
                            Text(labels.vpLegend)
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(onClick = onPanUp, modifier = Modifier.weight(1f)) {
                            Text(labels.vpPanUp)
                        }
                        TextButton(onClick = onPanDown, modifier = Modifier.weight(1f)) {
                            Text(labels.vpPanDown)
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(onClick = onPanLeft, modifier = Modifier.weight(1f)) {
                            Text(labels.vpPanLeft)
                        }
                        TextButton(onClick = onPanRight, modifier = Modifier.weight(1f)) {
                            Text(labels.vpPanRight)
                        }
                    }
                }
            } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = onFirst,
                    enabled = canPage && canPrev,
                    modifier = Modifier.weight(1f),
                ) { Text(labels.first) }
                TextButton(
                    onClick = onPrev,
                    enabled = canPage && canPrev,
                    modifier = Modifier.weight(1f),
                ) { Text(labels.prev) }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = onNext,
                    enabled = canPage && canNext,
                    modifier = Modifier.weight(1f),
                ) { Text(labels.next) }
                TextButton(
                    onClick = onLast,
                    enabled = canPage && canNext,
                    modifier = Modifier.weight(1f),
                ) { Text(labels.last) }
            }

            // 封底屏专属动作：返回上一层 / 换一张图。其余屏不显示，避免图形页混进文字动作。
            if (currentKind == StripPageKind.BACK) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        onClick = onExit,
                        modifier = Modifier.weight(1f),
                    ) { Text(labels.back) }
                    TextButton(
                        onClick = onRepick,
                        modifier = Modifier.weight(1f),
                    ) { Text(labels.actionRepick) }
                }
            }
            }
        }
    }
}

/** 单选行：整行是 RadioButton，选中态用 stateDescription 播报。 */
@Composable
private fun StripRadioRow(
    title: String,
    description: String,
    selected: Boolean,
    selectedLabel: String,
    unselectedLabel: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .selectable(
                selected = selected,
                role = Role.RadioButton,
                enabled = enabled,
                onClick = onClick,
            )
            .semantics(mergeDescendants = true) {
                stateDescription = if (selected) selectedLabel else unselectedLabel
            }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (description.isNotEmpty()) {
                Text(
                    description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
