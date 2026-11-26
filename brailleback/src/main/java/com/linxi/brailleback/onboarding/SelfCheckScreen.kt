/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 *
 * 「设备与权限自检」子屏：纯展示，系统查询与 Intent 构建都在 Activity 侧。
 * 复用 compose 包的设置屏外壳与动作行，避免读屏语义漂移。
 */

package com.linxi.brailleback.onboarding

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.linxi.brailleback.compose.SettingsActionRow
import com.linxi.brailleback.compose.SettingsScreenScaffold
import com.linxi.brailleback.compose.settingsSectionHeader

/** 一次性收好的文案，Screen 不碰 Context。 */
internal data class SelfCheckLabels(
    val title: String,
    val back: String,
    val vendorName: String,
    val summaryTitle: String,
    val allOk: String,
    val statusOk: String,
    val statusNeedAction: String,
    val statusUnknown: String,
    val summaryPending: String,
    val openPage: String,
    /** target → 一行标题；target → 一句话指引（点哪里 / 开哪个开关 / 做完回来重新检查）。 */
    val titles: Map<SelfCheckTarget, String>,
    val bodies: Map<SelfCheckTarget, String>,
)

/**
 * 自检结果列表。
 *
 * [results] 顺序已由引擎按优先级排好；[onOpenTarget] 只回传 target，
 * 候选尝试与回落都在 Activity 里做。
 */
@Composable
internal fun SelfCheckScreen(
    labels: SelfCheckLabels,
    results: List<SelfCheckResult>,
    onOpenTarget: (SelfCheckTarget) -> Unit,
    onBack: () -> Unit,
) {
    SettingsScreenScaffold(
        title = labels.title,
        backLabel = labels.back,
        onBack = onBack,
    ) {
        settingsSectionHeader(labels.vendorName)

        item(key = "selfcheck:summary") {
            val allOk = results.all { it.status == SelfCheckStatus.OK }
            val pending = results.count { it.status == SelfCheckStatus.NEED_ACTION }
            SelfCheckTextRow(
                title = labels.summaryTitle,
                body = if (allOk) labels.allOk else labels.summaryPending.replace("%1\$d", pending.toString()),
            )
        }

        results.forEach { result ->
            val statusLabel = when (result.status) {
                SelfCheckStatus.OK -> labels.statusOk
                SelfCheckStatus.NEED_ACTION -> labels.statusNeedAction
                SelfCheckStatus.UNKNOWN -> labels.statusUnknown
            }
            val body = labels.bodies[result.target].orEmpty()
            item(key = "selfcheck:${result.target.name}") {
                // 加锁 / OTG 这类系统不开放 Intent 的步骤，只给文字指引，行不可点。
                val hasJump = result.candidates.isNotEmpty() || result.fallback != null
                SettingsActionRow(
                    title = labels.titles[result.target] ?: result.target.name,
                    summary = if (body.isEmpty()) statusLabel else "$body　$statusLabel",
                    clickLabel = labels.openPage,
                    onClick = { onOpenTarget(result.target) },
                    enabled = hasJump && result.status == SelfCheckStatus.NEED_ACTION,
                )
            }
        }
    }
}

/** 只读信息行：没有点击动作，不给 Role.Button，避免读屏报「可点击」却点不动。 */
@Composable
private fun SelfCheckTextRow(title: String, body: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(text = title, style = MaterialTheme.typography.bodyLarge)
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
