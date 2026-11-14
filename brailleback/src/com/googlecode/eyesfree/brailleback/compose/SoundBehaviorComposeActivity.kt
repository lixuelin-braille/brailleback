/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 *
 * Compose 迁移第四批：「声音与行为」子屏。
 *
 * 边界：主屏入口、导航层级、其余子屏一律不动；XML 中同名子屏完整保留，
 * 作为随时可回退的旧实现。六个持久化 key 与原 Preference 完全一致，
 * 两代 UI 读写同一份 SharedPreferences，用户设置不丢。
 *
 * 两处必须照搬的旧行为：
 * - 音效总开关与音量走 SoundSchemeManager（同一个默认 SharedPreferences 文件），
 *   音量是 Int 且夹在 0..100，设置页不能直接拼 key 写 String；
 * - 运动模式被用户手动拨过时，要同时写 motion_mode_explicit，
 *   否则 BrailleBackService 会在下次连接时按 USB / 蓝牙把值覆盖回去。
 */

package com.googlecode.eyesfree.brailleback.compose

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.preference.PreferenceManager
import com.googlecode.eyesfree.brailleback.R
import com.googlecode.eyesfree.brailleback.sound.SoundSchemeManager

/** 一次性收集好的中文文案，让 Screen 保持无 Context 依赖。 */
internal data class SoundBehaviorLabels(
    val title: String,
    val back: String,
    val sectionSound: String,
    val sectionBehavior: String,
    val soundTitle: String,
    val soundSummaryOn: String,
    val soundSummaryOff: String,
    val volumeTitle: String,
    val volumeTemplate: String,
    val adjustHint: String,
    val pauseTitle: String,
    val pauseEntries: List<String>,
    val pauseValues: List<String>,
    val idleTitle: String,
    val idleEntries: List<String>,
    val idleValues: List<String>,
    val motionTitle: String,
    val motionSummaryOn: String,
    val motionSummaryOff: String,
    val quietTitle: String,
    val quietSummaryOn: String,
    val quietSummaryOff: String,
    val chooseHint: String,
    val decrease: String,
    val increase: String,
    val confirm: String,
    val cancel: String,
    val on: String,
    val off: String,
    val selected: String,
    val unselected: String,
)

class SoundBehaviorComposeActivity : AppCompatActivity() {

    private val prefs by lazy { PreferenceManager.getDefaultSharedPreferences(this) }
    private val soundManager by lazy { SoundSchemeManager.getInstance(this) }

    private var soundEnabled by mutableStateOf(true)
    private var volume by mutableStateOf(SoundBehaviorLogic.VOLUME_DEFAULT)
    private var pauseValue by mutableStateOf("")
    private var idleValue by mutableStateOf("")
    private var motionMode by mutableStateOf(SoundBehaviorLogic.MOTION_MODE_DEFAULT)
    private var quietNavigation by mutableStateOf(true)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 标题栏交给 Compose 的 TopAppBar，避免两个标题栏叠加、读屏重复朗读。
        supportActionBar?.hide()

        val pauseKey = getString(R.string.pref_pause_behavior_key)
        val idleKey = getString(R.string.pref_idle_clear_key)
        val motionKey = getString(R.string.pref_motion_mode_key)
        val quietKey = getString(R.string.pref_quiet_navigation_key)

        // 与旧 PreferenceFragment 的默认值保持完全一致：
        // 音效由 SoundSchemeManager 提供默认，运动模式没有 XML 默认值（false），
        // 两个 ListPreference 读 XML 的 app:defaultValue。
        soundEnabled = soundManager.isEnabled()
        volume = soundManager.getVolume()
        pauseValue = SoundBehaviorLogic.initialValue(
            prefs.getString(pauseKey, null),
            getString(R.string.pref_pause_behavior_clear_value),
        )
        idleValue = SoundBehaviorLogic.initialValue(
            prefs.getString(idleKey, null),
            getString(R.string.pref_idle_clear_3min_value),
        )
        motionMode = prefs.getBoolean(motionKey, SoundBehaviorLogic.MOTION_MODE_DEFAULT)
        quietNavigation = prefs.getBoolean(
            quietKey, resources.getBoolean(R.bool.pref_quiet_navigation_default)
        )

        setContent {
            val labels = SoundBehaviorLabels(
                title = getString(R.string.pref_category_sound_behavior_title),
                back = getString(R.string.compose_settings_back),
                sectionSound = getString(R.string.pref_sound_category_title),
                sectionBehavior = getString(R.string.pref_category_behavior_title),
                soundTitle = getString(R.string.pref_sound_enabled_title),
                soundSummaryOn = getString(R.string.compose_settings_sound_summary_on),
                soundSummaryOff = getString(R.string.compose_settings_sound_summary_off),
                volumeTitle = getString(R.string.pref_sound_volume_title),
                volumeTemplate = getString(R.string.sound_editor_volume),
                adjustHint = getString(R.string.compose_settings_adjust_hint),
                pauseTitle = getString(R.string.pref_pause_behavior_title),
                pauseEntries = resources.getStringArray(
                    R.array.pref_pause_behavior_entries
                ).toList(),
                pauseValues = resources.getStringArray(
                    R.array.pref_pause_behavior_values
                ).toList(),
                idleTitle = getString(R.string.pref_idle_clear_title),
                idleEntries = resources.getStringArray(
                    R.array.pref_idle_clear_entries
                ).toList(),
                idleValues = resources.getStringArray(
                    R.array.pref_idle_clear_values
                ).toList(),
                motionTitle = getString(R.string.pref_motion_mode_title),
                motionSummaryOn = getString(R.string.pref_motion_mode_summary_on),
                motionSummaryOff = getString(R.string.pref_motion_mode_summary_off),
                quietTitle = getString(R.string.pref_quiet_navigation_title),
                quietSummaryOn = getString(R.string.pref_quiet_navigation_summary_on),
                quietSummaryOff = getString(R.string.pref_quiet_navigation_summary_off),
                chooseHint = getString(R.string.compose_settings_choose),
                decrease = getString(R.string.compose_settings_volume_decrease),
                increase = getString(R.string.compose_settings_volume_increase),
                confirm = getString(R.string.compose_settings_confirm),
                cancel = getString(R.string.compose_settings_cancel),
                on = getString(R.string.compose_settings_on),
                off = getString(R.string.compose_settings_off),
                selected = getString(R.string.compose_settings_selected),
                unselected = getString(R.string.compose_settings_unselected),
            )
            SoundBehaviorScreen(
                labels = labels,
                soundEnabled = soundEnabled,
                volume = volume,
                pauseValue = pauseValue,
                idleValue = idleValue,
                motionMode = motionMode,
                quietNavigation = quietNavigation,
                onSoundEnabledChange = { value ->
                    soundEnabled = value
                    soundManager.setEnabled(value)
                },
                onVolumeConfirmed = { value ->
                    val coerced = SoundBehaviorLogic.coerceVolume(value)
                    volume = coerced
                    soundManager.setVolume(coerced)
                },
                onPauseSelected = { value ->
                    pauseValue = value
                    prefs.edit().putString(pauseKey, value).apply()
                },
                onIdleSelected = { value ->
                    idleValue = value
                    prefs.edit().putString(idleKey, value).apply()
                },
                onMotionModeChange = { value ->
                    motionMode = value
                    val editor = prefs.edit()
                    SoundBehaviorLogic.motionModeWrite(value).forEach { (key, flag) ->
                        editor.putBoolean(key, flag)
                    }
                    editor.apply()
                },
                onQuietNavigationChange = { value ->
                    quietNavigation = value
                    prefs.edit().putBoolean(quietKey, value).apply()
                },
                onBack = { finish() },
            )
        }
    }
}
