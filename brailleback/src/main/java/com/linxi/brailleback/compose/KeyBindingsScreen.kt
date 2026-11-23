/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 *
 * 「键盘帮助」屏的 Compose 实现。
 *
 * 这屏只读：列出当前点显器每个键默认对应的动作，用来回答「这个键是干什么的」。
 * 数据由 Activity 里的 DisplayClient 回调喂进来，屏幕本身不碰系统资源。
 */

package com.linxi.brailleback.compose

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** 一行「动作 → 键位」。label 是动作（例如「下一行」），binding 是键位（例如「space + 1」）。 */
internal data class DisplayBinding(val label: String, val binding: String)

@Composable
internal fun KeyBindingsScreen(
    title: String,
    backLabel: String,
    instructions: String,
    bindings: List<DisplayBinding>,
    onBack: () -> Unit,
) {
    SettingsScreenScaffold(title = title, backLabel = backLabel, onBack = onBack) {
        keyBindingInstructions(instructions)
        // key 里带下标：同一个动作名可能出现在多行（不同分组各有一条），
        // 只用 label 作 key 会撞车，Compose 对重复 key 直接抛异常崩屏。
        itemsIndexed(bindings, key = { index, row -> "binding:$index:${row.label}" }) { _, row ->
            KeyBindingRow(row)
        }
    }
}

private fun LazyListScope.keyBindingInstructions(text: String) {
    item(key = "instructions") {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
        )
    }
}

/**
 * 两行文字当成一条朗读：读屏一次念「动作，键位」，不会拆成两个可聚焦节点。
 */
@Composable
private fun KeyBindingRow(row: DisplayBinding) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .semantics(mergeDescendants = true) {},
    ) {
        Text(text = row.label, style = MaterialTheme.typography.bodyLarge)
        Text(
            text = row.binding,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
