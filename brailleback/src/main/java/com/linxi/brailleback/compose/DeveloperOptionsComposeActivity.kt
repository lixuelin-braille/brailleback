/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 *
 * Compose 迁移第五批（最后一批）：「开发者选项」子屏。
 *
 * 边界：主屏入口、导航层级、其余子屏一律不动；XML 中同名子屏完整保留，
 * 作为随时可回退的旧实现。两个持久化 key（log_level / overlay_enabled）
 * 与原 Preference 完全一致，两代 UI 读写同一份 SharedPreferences。
 *
 * 四处必须照搬的旧行为：
 * - 日志级别是 String 偏好，改动必须经 PreferenceUtils.updateLogLevel；
 *   诊断包里该方法返回 false，此时不能写新值（并给一句文字提示）；
 * - 叠加层被打开时要跑 OverlayTutorialActivity.startIfFirstTime；
 * - 教程行依赖叠加层开关，关闭时不可点；
 * - 日志查看 / 分享 / 复制 / 清除全部走 DebugLogActions，与旧 Fragment 共用真源。
 */

package com.linxi.brailleback.compose

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.googlecode.eyesfree.utils.SettingsStore
import com.linxi.brailleback.OverlayTutorialActivity
import com.linxi.brailleback.R
import com.linxi.brailleback.utils.DebugLogActions
import com.linxi.brailleback.utils.LogFileWriter
import com.linxi.brailleback.utils.PreferenceUtils

/** 一次性收集好的中文文案，让 Screen 保持无 Context 依赖。 */
internal data class DeveloperOptionsLabels(
    val title: String,
    val back: String,
    val sectionLog: String,
    val sectionOverlay: String,
    val logLevelTitle: String,
    val logLevelEntries: List<String>,
    val logLevelValues: List<String>,
    val chooseHint: String,
    val viewLogTitle: String,
    val viewLogSummary: String,
    val shareLogTitle: String,
    val shareLogSummary: String,
    val copyLogTitle: String,
    val copyLogSummary: String,
    val clearLogTitle: String,
    val clearLogSummary: String,
    val actionHint: String,
    val overlayTitle: String,
    val overlaySummary: String,
    val tutorialTitle: String,
    val tutorialSummary: String,
    val tutorialDisabled: String,
    val cancel: String,
    val selected: String,
    val unselected: String,
    val on: String,
    val off: String,
    val logViewTitle: String,
    val logCopyLabel: String,
    val logShareLabel: String,
    val clearConfirmMessage: String,
    val clearConfirmLabel: String,
)

class DeveloperOptionsComposeActivity : AppCompatActivity() {

    private val prefs by lazy { SettingsStore.get(this) }

    private var logLevelValue by mutableStateOf("")
    private var overlayEnabled by mutableStateOf(DeveloperOptionsLogic.OVERLAY_DEFAULT)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 标题栏交给 Compose 的 TopAppBar，避免两个标题栏叠加、读屏重复朗读。
        supportActionBar?.hide()
        // 与旧 PreferenceFragment.onCreate 一致：无 adb 环境也能导出日志。
        LogFileWriter.init(this)

        val logLevelKey = getString(R.string.pref_log_level_key)
        val overlayKey = getString(R.string.pref_braille_overlay_key)
        val levelValues = resources.getStringArray(R.array.pref_log_level_values).toList()
        val levelEntries = resources.getStringArray(R.array.pref_log_level_entries).toList()

        logLevelValue = DeveloperOptionsLogic.initialLogLevel(
            prefs.getString(logLevelKey, null),
            getString(R.string.pref_log_level_default),
            levelValues,
        )
        overlayEnabled = prefs.getBoolean(
            overlayKey, resources.getBoolean(R.bool.pref_braille_overlay_default)
        )

        setContent {
            val labels = DeveloperOptionsLabels(
                title = getString(R.string.pref_category_developer_title),
                back = getString(R.string.compose_settings_back),
                sectionLog = getString(R.string.pref_category_log_title),
                sectionOverlay = getString(R.string.pref_category_braille_overlay_title),
                logLevelTitle = getString(R.string.pref_log_level_label),
                logLevelEntries = levelEntries,
                logLevelValues = levelValues,
                chooseHint = getString(R.string.compose_settings_choose),
                viewLogTitle = getString(R.string.pref_view_log_title),
                viewLogSummary = getString(R.string.pref_view_log_summary),
                shareLogTitle = getString(R.string.pref_share_log_title),
                shareLogSummary = getString(R.string.pref_share_log_summary),
                copyLogTitle = getString(R.string.pref_copy_log_title),
                copyLogSummary = getString(R.string.pref_copy_log_summary),
                clearLogTitle = getString(R.string.pref_clear_log_title),
                clearLogSummary = getString(R.string.pref_clear_log_summary),
                actionHint = getString(R.string.compose_settings_log_action_hint),
                overlayTitle = getString(R.string.pref_braille_overlay_title),
                overlaySummary = getString(R.string.pref_braille_overlay_summary),
                tutorialTitle = getString(R.string.pref_braille_overlay_tutorial_title),
                tutorialSummary = getString(R.string.pref_braille_overlay_tutorial_summary),
                tutorialDisabled = getString(R.string.compose_settings_overlay_tutorial_disabled),
                cancel = getString(R.string.compose_settings_cancel),
                selected = getString(R.string.compose_settings_selected),
                unselected = getString(R.string.compose_settings_unselected),
                on = getString(R.string.compose_settings_on),
                off = getString(R.string.compose_settings_off),
                logViewTitle = getString(R.string.debug_log_view_title),
                logCopyLabel = getString(R.string.debug_log_copy),
                logShareLabel = getString(R.string.debug_log_share),
                clearConfirmMessage = getString(R.string.debug_log_clear_confirm),
                clearConfirmLabel = getString(android.R.string.ok),
            )
            DeveloperOptionsScreen(
                labels = labels,
                logLevelValue = logLevelValue,
                overlayEnabled = overlayEnabled,
                onLogLevelSelected = { value ->
                    val parsed = DeveloperOptionsLogic.parseLogLevel(value)
                    val accepted = parsed?.let { PreferenceUtils.updateLogLevel(it) } ?: false
                    if (DeveloperOptionsLogic.shouldPersistLogLevel(parsed, accepted)) {
                        logLevelValue = value
                        prefs.edit().putString(logLevelKey, value).apply()
                    } else {
                        // 诊断包把级别写死：不能悄悄改偏好，也不能静默无反应。
                        Toast.makeText(
                            this,
                            R.string.compose_settings_log_level_locked,
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                },
                onViewLog = {
                    DeveloperOptionsLogic.logPreview(
                        raw = DebugLogActions.readAll(this),
                        emptyLabel = getString(R.string.debug_log_empty),
                        truncatedHint = getString(R.string.compose_settings_log_preview_hint),
                    )
                },
                onShareLog = { DebugLogActions.shareCurrentLog(this) },
                onCopyLog = { DebugLogActions.copyCurrentLog(this) },
                onClearLog = { DebugLogActions.clear(this) },
                onOverlayChange = { value ->
                    overlayEnabled = value
                    prefs.edit().putBoolean(overlayKey, value).apply()
                    if (DeveloperOptionsLogic.shouldCheckFirstTimeTutorial(value)) {
                        OverlayTutorialActivity.startIfFirstTime(this)
                    }
                },
                onOpenTutorial = {
                    startActivity(Intent(this, OverlayTutorialActivity::class.java))
                },
                onBack = { finish() },
            )
        }
    }
}
