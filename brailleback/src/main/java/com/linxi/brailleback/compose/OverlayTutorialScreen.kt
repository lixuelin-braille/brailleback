/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 *
 * 叠加层教程页 Compose 界面。
 *
 * 时序边界：两段演示协程（500ms 左右键翻转 / 1.5s 随机点亮 16 格）仍留在
 * OverlayTutorialActivity 的 lifecycleScope，本 Screen 只接收上浮的按压态与
 * 高亮格下标；不在此层起定时任务。
 */

package com.linxi.brailleback.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.linxi.brailleback.R

/** 路由键演示格数，与原 ROUTING_KEY_DEMO_CELLS 一致。 */
internal const val OVERLAY_TUTORIAL_DEMO_CELLS = 16

@Composable
internal fun OverlayTutorialScreen(
    panDemoPressed: Boolean,
    routingHighlightedCell: Int,
    onAccessibilitySettings: () -> Unit,
    onFinish: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = stringResource(R.string.overlay_tutorial_introduction),
                style = MaterialTheme.typography.bodyMedium,
            )

            Button(
                onClick = onAccessibilitySettings,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.overlay_tutorial_accessibility_settings_button))
            }

            Text(
                text = stringResource(R.string.overlay_tutorial_pan),
                modifier = Modifier.padding(top = 20.dp),
                style = MaterialTheme.typography.bodyMedium,
            )

            PanDemoRow(panDemoPressed = panDemoPressed)

            Text(
                text = stringResource(R.string.overlay_tutorial_routing_keys),
                modifier = Modifier.padding(top = 20.dp),
                style = MaterialTheme.typography.bodyMedium,
            )

            BrailleOverlayCanvas(
                numCells = OVERLAY_TUTORIAL_DEMO_CELLS,
                braille = ByteArray(OVERLAY_TUTORIAL_DEMO_CELLS),
                text = "",
                brailleToTextPositions = IntArray(OVERLAY_TUTORIAL_DEMO_CELLS),
                highlightedCell = routingHighlightedCell,
                onHighlightCleared = { },
            )

            Text(
                text = stringResource(R.string.overlay_tutorial_moving),
                modifier = Modifier.padding(top = 20.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        Button(
            onClick = onFinish,
            modifier = Modifier.fillMaxWidth().padding(10.dp),
        ) {
            Text(stringResource(R.string.overlay_tutorial_finish_button))
        }
    }
}

@Composable
private fun PanDemoRow(panDemoPressed: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        val pressedLabel = if (panDemoPressed) "pressed" else "released"
        IconButton(onClick = { }) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.overlay_pan_left_button),
                modifier = Modifier.semantics {
                    role = Role.Button
                    stateDescription = pressedLabel
                },
            )
        }
        IconButton(onClick = { }) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = stringResource(R.string.overlay_pan_right_button),
                modifier = Modifier.semantics {
                    role = Role.Button
                    stateDescription = pressedLabel
                },
            )
        }
    }
}
