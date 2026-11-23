/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 *
 * 盲文显示器按键自定义设置页（Compose）。
 *
 * 交互对照 Google TalkBack 的 `BrailleImeGestureCommandActivity` / `KeyBindingsCommandActivity`：
 * 按类别列出可编辑项、每项显示当前与默认动作、支持单条改回默认与一键全部复位。
 * 这里不采用「弹出层录制按键」的方式：盲人用户用手机读屏逐个选择动作更可靠，
 * 且能直接读出「当前 / 默认 / 实际按键名」三段信息；若点显器已连接，会从
 * `BrailleDisplayProperties.keyBindings` 补充该设备上真实的组合键名。
 *
 * 2026-11-23：界面从旧设置框架搬到 Compose（CustomKeyMapScreen）。
 * 类名、Manifest 声明、KeysGesturesComposeActivity 里的启动入口一字未改；
 * 落盘仍走 CustomKeyMapStore，Screen 不直接写偏好存储。
 */

package com.linxi.brailleback

import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.googlecode.eyesfree.braille.display.BrailleInputEvent
import com.googlecode.eyesfree.braille.display.Display
import com.googlecode.eyesfree.braille.display.DisplayClient
import com.linxi.brailleback.compose.CustomKeyMapLabels
import com.linxi.brailleback.compose.CustomKeyMapScreen
import com.linxi.brailleback.compose.KeyMapRow
import com.linxi.brailleback.keymap.BrailleKeyMapPolicy
import com.linxi.brailleback.keymap.CustomKeyMapStore
import com.linxi.brailleback.utils.BrailleKeyBindingUtils

class CustomKeyMapActivity : AppCompatActivity(),
    Display.OnConnectionStateChangeListener {

    /** 当前自定义映射：源命令名 → 目标动作；无自定义时为空表。 */
    private var mappings by mutableStateOf<Map<String, String>>(emptyMap())

    /** 命令名 → 已连接设备上的真实组合键名；拿不到时为空，界面回退静态中文名。 */
    private var deviceKeyNames by mutableStateOf<Map<String, String>>(emptyMap())

    /** 「多个键指向同一动作」的分组数；0 表示不显示冲突提示。 */
    private var conflictCount by mutableStateOf(0)

    private var displayClient: DisplayClient? = null

    private lateinit var sourceValues: Array<String>
    private lateinit var actionValues: Array<String>
    private lateinit var actionLabels: Array<String>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setTitle(R.string.custom_key_map_title)

        sourceValues = resources.getStringArray(R.array.key_map_source_values)
        actionValues = resources.getStringArray(R.array.key_map_action_values)
        actionLabels = resources.getStringArray(R.array.key_map_action_labels)

        mappings = CustomKeyMapStore.load(this)
        conflictCount = BrailleKeyMapPolicy.duplicateTargets(mappings).size

        // 标题栏交给 Compose 的 TopAppBar，避免两个标题栏叠加、读屏重复朗读。
        supportActionBar?.hide()

        setContent {
            val defaultAction = getString(R.string.custom_key_map_default_action)
            val optionValues = listOf(BrailleKeyMapPolicy.DEFAULT_ACTION) +
                actionValues.toList()
            val optionLabels = listOf(defaultAction) + actionLabels.toList()

            val labels = CustomKeyMapLabels(
                title = getString(R.string.custom_key_map_title),
                instructionsTitle = getString(R.string.custom_key_map_instructions_title),
                instructions = getString(R.string.custom_key_map_instructions),
                sectionTitle = getString(R.string.custom_key_map_category_title),
                chooseHint = getString(R.string.compose_settings_choose),
                back = getString(R.string.compose_settings_back),
                resetTitle = getString(R.string.custom_key_map_reset_title),
                resetSummary = getString(R.string.custom_key_map_reset_summary),
                resetConfirmTitle = getString(R.string.custom_key_map_reset_confirm_title),
                resetConfirmMessage = getString(R.string.custom_key_map_reset_confirm_message),
                selected = getString(R.string.compose_settings_selected),
                unselected = getString(R.string.compose_settings_unselected),
                ok = getString(android.R.string.ok),
                cancel = getString(android.R.string.cancel),
            )

            val rows = sourceValues.map { source -> rowFor(source) }
            val conflictText = if (conflictCount > 0) {
                getString(R.string.custom_key_map_conflict, conflictCount)
            } else {
                null
            }

            CustomKeyMapScreen(
                labels = labels,
                rows = rows,
                optionLabels = optionLabels,
                optionValues = optionValues,
                currentValueOf = { source -> mappings[source] ?: BrailleKeyMapPolicy.DEFAULT_ACTION },
                conflictText = conflictText,
                onPickAction = ::pickAction,
                onResetAll = ::resetAll,
                onBack = { finish() },
            )
        }
    }

    override fun onStart() {
        super.onStart()
        try {
            displayClient = DisplayClient(this).also {
                it.setOnConnectionStateChangeListener(this)
            }
        } catch (e: Exception) {
            // 服务未启用或驱动不提供绑定时，界面仍完整可用，键名回落静态标签。
            Log.w(TAG, "无法连接点显器服务，按键名回退为静态标签", e)
            displayClient = null
        }
    }

    override fun onStop() {
        displayClient?.shutdown()
        displayClient = null
        super.onStop()
    }

    override fun onConnectionStateChanged(state: Int) {
        if (state != Display.STATE_CONNECTED) return
        runOnUiThread { loadDeviceKeyNames() }
    }

    // ------------------------------------------------------------------
    // 行数据装配（纯展示，落盘走 Store）
    // ------------------------------------------------------------------

    private fun rowFor(source: String): KeyMapRow {
        val target = BrailleKeyMapPolicy.resolvedTarget(mappings, source)
        val summary = getString(
            R.string.custom_key_map_current_summary,
            labelOf(target),
            labelOf(source),
        )
        val deviceKey = deviceKeyNames[source]
        val deviceKeyLine = if (!deviceKey.isNullOrBlank()) {
            getString(R.string.custom_key_map_device_key, deviceKey)
        } else {
            getString(R.string.custom_key_map_no_device)
        }
        return KeyMapRow(
            source = source,
            title = labelOf(source),
            summary = summary,
            deviceKeyLine = deviceKeyLine,
            dialogTitle = getString(R.string.custom_key_map_dialog_title, labelOf(source)),
        )
    }

    private fun labelOf(commandName: String): String {
        val index = actionValues.indexOf(commandName)
        return if (index >= 0 && index < actionLabels.size) {
            actionLabels[index]
        } else {
            commandName
        }
    }

    // ------------------------------------------------------------------
    // 动作改写 / 全量复位（单层映射，不递归；落盘统一走 CustomKeyMapStore）
    // ------------------------------------------------------------------

    private fun pickAction(source: String, target: String) {
        CustomKeyMapStore.setMapping(this, source, target)
        mappings = CustomKeyMapStore.load(this)
        conflictCount = BrailleKeyMapPolicy.duplicateTargets(mappings).size

        val actionLabel = if (target == BrailleKeyMapPolicy.DEFAULT_ACTION) {
            getString(R.string.custom_key_map_default_action)
        } else {
            labelOf(target)
        }
        Toast.makeText(
            this,
            getString(R.string.custom_key_map_saved, labelOf(source), actionLabel),
            Toast.LENGTH_SHORT,
        ).show()
    }

    private fun resetAll() {
        CustomKeyMapStore.resetAll(this)
        mappings = emptyMap()
        conflictCount = 0
        Toast.makeText(
            this, R.string.custom_key_map_reset_done, Toast.LENGTH_SHORT,
        ).show()
    }

    // ------------------------------------------------------------------
    // 设备真实键名（渐进增强）：读取失败只记日志，保持静态标签。
    // ------------------------------------------------------------------

    private fun loadDeviceKeyNames() {
        try {
            val props = displayClient?.displayProperties ?: return
            val sorted = BrailleKeyBindingUtils.getSortedBindingsForDisplay(props)
            val friendlyNames = props.friendlyKeyNames
            val names = HashMap<String, String>()
            for (source in sourceValues) {
                val command = BrailleInputEvent.stringToCommand(source)
                if (command == BrailleInputEvent.CMD_NONE) continue
                val binding = BrailleKeyBindingUtils
                    .getBrailleKeyBindingForCommand(command, sorted) ?: continue
                val friendly = BrailleKeyBindingUtils.getFriendlyKeyNamesForCommand(
                    binding, friendlyNames, this,
                )
                if (!friendly.isNullOrBlank()) {
                    names[source] = friendly
                }
            }
            deviceKeyNames = names
        } catch (e: Exception) {
            Log.w(TAG, "读取点显器按键绑定失败，保持静态标签", e)
        }
    }

    private companion object {
        const val TAG = "CustomKeyMapActivity"
    }
}
