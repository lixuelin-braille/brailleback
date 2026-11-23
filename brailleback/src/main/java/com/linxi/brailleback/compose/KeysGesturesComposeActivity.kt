/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 *
 * Compose 迁移第二批：「按键与手势」子屏。
 *
 * 边界：主屏入口、导航层级、其余子屏一律不动；XML 中同名子屏完整保留，
 * 作为随时可回退的旧实现。四个持久化 key 与原 Preference 完全一致，
 * 两代 UI 读写同一份 SharedPreferences，用户设置不丢。
 */

package com.linxi.brailleback.compose

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.googlecode.eyesfree.utils.SettingsStore
import com.googlecode.eyesfree.braille.display.Display
import com.googlecode.eyesfree.braille.display.DisplayClient
import com.linxi.brailleback.CustomKeyMapActivity
import com.linxi.brailleback.KeyBindingsActivity
import com.linxi.brailleback.R

/** 一个音量键动作槽位：偏好 key + 朗读标题。 */
internal data class VolumeSlot(val key: String, val title: String)

/** 一次性收集好的中文文案，让 Screen 保持无 Context 依赖。 */
internal data class KeysGesturesLabels(
    val title: String,
    val back: String,
    val sectionDisplayKeys: String,
    val sectionVolume: String,
    val keyBindingsTitle: String,
    val keyBindingsSummary: String,
    val customKeyMapTitle: String,
    val customKeyMapSummary: String,
    val openHint: String,
    val gesturesTitle: String,
    val gesturesSummaryOn: String,
    val gesturesSummaryOff: String,
    val volumeSlots: List<VolumeSlot>,
    val chooseHint: String,
    val cancel: String,
    val on: String,
    val off: String,
    val selected: String,
    val unselected: String,
    val actionEntries: List<String>,
    val actionValues: List<String>,
)

class KeysGesturesComposeActivity : AppCompatActivity(),
    Display.OnConnectionStateChangeListener {

    private var displayClient: DisplayClient? = null

    // 状态放 Activity 层：连接回调来自无障碍服务，需要直接驱动重组。
    private var connectionState by mutableStateOf(Display.STATE_NOT_CONNECTED)
    private var gesturesEnabled by mutableStateOf(true)
    private var volumeValues by mutableStateOf<Map<String, String>>(emptyMap())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 标题栏交给 Compose 的 TopAppBar，避免两个标题栏叠加、读屏重复朗读。
        supportActionBar?.hide()

        val prefs = SettingsStore.get(this)
        val gesturesKey = getString(R.string.pref_volume_gestures_enabled_key)
        gesturesEnabled = prefs.getBoolean(
            gesturesKey, resources.getBoolean(R.bool.pref_volume_gestures_enabled_default)
        )

        val none = getString(R.string.pref_volume_action_none_value)
        val pause = getString(R.string.pref_volume_action_pause_value)
        val grade = getString(R.string.pref_volume_action_grade_value)
        val slots = listOf(
            VolumeSlot(
                getString(R.string.pref_volume_long_press_up_key),
                getString(R.string.pref_volume_long_press_up_title),
            ),
            VolumeSlot(
                getString(R.string.pref_volume_long_press_down_key),
                getString(R.string.pref_volume_long_press_down_title),
            ),
            VolumeSlot(
                getString(R.string.pref_volume_double_press_key),
                getString(R.string.pref_volume_double_press_title),
            ),
        )
        volumeValues = slots.associate { slot ->
            val default = KeysGesturesLogic.defaultValueFor(slot.key, none, pause, grade)
            slot.key to KeysGesturesLogic.initialValue(prefs.getString(slot.key, null), default)
        }

        // 只读连接状态，用来决定「键盘帮助」能否进入；不 poll、不申请蓝牙权限，
        // 与 KeyBindingsActivity 自身的行为保持一致。
        displayClient = DisplayClient(this).also { client ->
            client.setOnConnectionStateChangeListener(this)
        }

        setContent {
            val labels = KeysGesturesLabels(
                title = getString(R.string.pref_category_keys_title),
                back = getString(R.string.compose_settings_back),
                sectionDisplayKeys = getString(R.string.pref_category_display_keys_title),
                sectionVolume = getString(R.string.pref_category_volume_title),
                keyBindingsTitle = getString(R.string.pref_key_bindings_title),
                keyBindingsSummary = getString(R.string.pref_key_bindings_summary),
                customKeyMapTitle = getString(R.string.pref_custom_key_map_title),
                customKeyMapSummary = getString(R.string.pref_custom_key_map_summary),
                openHint = getString(R.string.compose_settings_open_hint),
                gesturesTitle = getString(R.string.pref_volume_gestures_enabled_title),
                gesturesSummaryOn = getString(R.string.compose_settings_gestures_summary_on),
                gesturesSummaryOff = getString(R.string.compose_settings_gestures_summary_off),
                volumeSlots = slots,
                chooseHint = getString(R.string.compose_settings_choose),
                cancel = getString(R.string.compose_settings_cancel),
                on = getString(R.string.compose_settings_on),
                off = getString(R.string.compose_settings_off),
                selected = getString(R.string.compose_settings_selected),
                unselected = getString(R.string.compose_settings_unselected),
                actionEntries = resources.getStringArray(
                    R.array.pref_volume_action_entries
                ).toList(),
                actionValues = resources.getStringArray(
                    R.array.pref_volume_action_values
                ).toList(),
            )
            KeysGesturesScreen(
                labels = labels,
                helpAvailable = KeysGesturesLogic.isHelpAvailable(connectionState),
                gesturesEnabled = gesturesEnabled,
                volumeValues = volumeValues,
                onOpenKeyBindings = {
                    startActivity(
                        Intent(this@KeysGesturesComposeActivity, KeyBindingsActivity::class.java)
                    )
                },
                onOpenCustomKeyMap = {
                    startActivity(
                        Intent(this@KeysGesturesComposeActivity, CustomKeyMapActivity::class.java)
                    )
                },
                onGesturesEnabledChange = { value ->
                    gesturesEnabled = value
                    prefs.edit().putBoolean(gesturesKey, value).apply()
                },
                onVolumeActionSelected = { key, value ->
                    volumeValues = volumeValues + (key to value)
                    prefs.edit().putString(key, value).apply()
                },
                onBack = { finish() },
            )
        }
    }

    override fun onDestroy() {
        displayClient?.shutdown()
        displayClient = null
        super.onDestroy()
    }

    override fun onConnectionStateChanged(state: Int) {
        connectionState = state
    }
}
