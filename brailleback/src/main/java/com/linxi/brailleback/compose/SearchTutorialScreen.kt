/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 *
 * 增量搜索教程页 Compose 界面。
 *
 * 连接态相关的四条命令键名由 Activity 经 DisplayClient 取出后上浮为
 * [specificInstructions]；未连接时为 null，不渲染该段、也不进入读屏焦点树。
 * 本页不起协程定时任务。
 */

package com.linxi.brailleback.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.linxi.brailleback.R

@Composable
internal fun SearchTutorialScreen(
    specificInstructions: String?,
    doNotShowChecked: Boolean,
    onDoNotShowChange: (Boolean) -> Unit,
    onClose: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = stringResource(R.string.search_tutorial_title),
            style = MaterialTheme.typography.headlineSmall,
        )

        Text(
            text = stringResource(R.string.search_tutorial_instructions_general),
            style = MaterialTheme.typography.bodyMedium,
        )

        // 未连接点显器时该段不可见，且不进焦点树（null 即不渲染）。
        if (specificInstructions != null) {
            Text(
                text = specificInstructions,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
        }

        androidx.compose.foundation.layout.Row(
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            Checkbox(
                checked = doNotShowChecked,
                onCheckedChange = onDoNotShowChange,
                modifier = Modifier.semantics { role = Role.Checkbox },
            )
            Text(
                text = stringResource(R.string.search_tutorial_checkbox),
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        Button(
            onClick = onClose,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.search_tutorial_close_button))
        }
    }
}
