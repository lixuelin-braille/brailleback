/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 */

package com.googlecode.eyesfree.brailleback

import android.os.Bundle
import android.util.Log
import android.view.MenuItem
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceScreen
import com.googlecode.eyesfree.braille.display.BrailleInputEvent
import com.googlecode.eyesfree.braille.display.Display
import com.googlecode.eyesfree.braille.display.DisplayClient
import com.googlecode.eyesfree.brailleback.keymap.BrailleKeyMapPolicy
import com.googlecode.eyesfree.brailleback.keymap.CustomKeyMapStore
import com.googlecode.eyesfree.brailleback.utils.BrailleKeyBindingUtils

/**
 * 盲文显示器按键自定义设置页。
 *
 * 交互对照 Google TalkBack 的 `BrailleImeGestureCommandActivity` / `KeyBindingsCommandActivity`：
 * 按类别列出可编辑项、每项显示当前与默认动作、支持单条改回默认与一键全部复位。
 * 这里不采用「弹出层录制按键」的方式：盲人用户用手机读屏逐个选择动作更可靠，
 * 且能直接读出「当前 / 默认 / 实际按键名」三段信息；若点显器已连接，会从
 * `BrailleDisplayProperties.keyBindings` 补充该设备上真实的组合键名。
 *
 * 所有列表项一次性加载、无「下一页」按钮，标题与摘要均为中文文本，TalkBack 可完整朗读。
 */
class CustomKeyMapActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setTitle(R.string.custom_key_map_title)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        if (supportFragmentManager.findFragmentById(android.R.id.content) == null) {
            supportFragmentManager.beginTransaction()
                .replace(android.R.id.content, CustomKeyMapFragment())
                .commit()
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    class CustomKeyMapFragment : PreferenceFragmentCompat(),
        Display.OnConnectionStateChangeListener {

        private lateinit var screen: PreferenceScreen
        private lateinit var sourceValues: Array<String>
        private lateinit var actionValues: Array<String>
        private lateinit var actionLabels: Array<String>
        private lateinit var entries: Array<CharSequence>
        private lateinit var entryValues: Array<CharSequence>
        private lateinit var conflictHint: Preference

        /** 命令名 → 当前连接设备上的真实组合键名；拿不到时为空，界面回退静态中文名。 */
        private val deviceKeyNames = HashMap<String, String>()
        private var displayClient: DisplayClient? = null

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            val context = requireContext()
            sourceValues = resources.getStringArray(R.array.key_map_source_values)
            actionValues = resources.getStringArray(R.array.key_map_action_values)
            actionLabels = resources.getStringArray(R.array.key_map_action_labels)
            entries = (listOf<CharSequence>(
                getString(R.string.custom_key_map_default_action)
            ) + actionLabels.toList()).toTypedArray()
            entryValues = (listOf<CharSequence>(
                BrailleKeyMapPolicy.DEFAULT_ACTION
            ) + actionValues.toList()).toTypedArray()

            screen = preferenceManager.createPreferenceScreen(context)
            preferenceScreen = screen

            val instructions = Preference(context).apply {
                key = "custom_key_map_instructions"
                setTitle(R.string.custom_key_map_instructions_title)
                setSummary(R.string.custom_key_map_instructions)
                isSelectable = false
                isPersistent = false
            }
            screen.addPreference(instructions)

            val category = PreferenceCategory(context).apply {
                key = "custom_key_map_category"
                setTitle(R.string.custom_key_map_category_title)
            }
            screen.addPreference(category)
            for (source in sourceValues) {
                category.addPreference(createKeyPreference(context, source))
            }

            conflictHint = Preference(context).apply {
                key = "custom_key_map_conflict_hint"
                isSelectable = false
                isPersistent = false
                isVisible = false
            }
            screen.addPreference(conflictHint)

            val reset = Preference(context).apply {
                key = "custom_key_map_reset"
                setTitle(R.string.custom_key_map_reset_title)
                setSummary(R.string.custom_key_map_reset_summary)
                isPersistent = false
                onPreferenceClickListener = Preference.OnPreferenceClickListener {
                    confirmResetAll()
                    true
                }
            }
            screen.addPreference(reset)

            refreshConflictHint()
        }

        private fun createKeyPreference(
            context: android.content.Context,
            source: String
        ): ListPreference {
            val label = labelOf(source)
            val preference = ListPreference(context).apply {
                key = "custom_key_$source"
                title = label
                dialogTitle = getString(R.string.custom_key_map_dialog_title, label)
                entries = this@CustomKeyMapFragment.entries
                entryValues = this@CustomKeyMapFragment.entryValues
                // 整张表由 CustomKeyMapStore 统一落盘；禁止 ListPreference 自己按 key 写偏好。
                isPersistent = false
                value = CustomKeyMapStore.load(context)[source]
                    ?: BrailleKeyMapPolicy.DEFAULT_ACTION
                onPreferenceChangeListener = Preference.OnPreferenceChangeListener { _, newValue ->
                    val target = newValue as? String
                        ?: return@OnPreferenceChangeListener false
                    CustomKeyMapStore.setMapping(context, source, target)
                    updateSummary(this, source)
                    refreshConflictHint()
                    val actionLabel =
                        if (target == BrailleKeyMapPolicy.DEFAULT_ACTION) {
                            getString(R.string.custom_key_map_default_action)
                        } else {
                            labelOf(target)
                        }
                    Toast.makeText(
                        context,
                        getString(R.string.custom_key_map_saved, label, actionLabel),
                        Toast.LENGTH_SHORT
                    ).show()
                    true
                }
            }
            updateSummary(preference, source)
            return preference
        }

        private fun updateSummary(preference: ListPreference, source: String) {
            val context = requireContext()
            val mapping = CustomKeyMapStore.load(context)
            val target = BrailleKeyMapPolicy.resolvedTarget(mapping, source)
            var summary = getString(
                R.string.custom_key_map_current_summary,
                labelOf(target),
                labelOf(source)
            )
            val deviceKey = deviceKeyNames[source]
            if (!deviceKey.isNullOrBlank()) {
                summary += "\n" + getString(R.string.custom_key_map_device_key, deviceKey)
            } else {
                summary += "\n" + getString(R.string.custom_key_map_no_device)
            }
            preference.summary = summary
        }

        private fun labelOf(commandName: String): String {
            val index = actionValues.indexOf(commandName)
            return if (index >= 0 && index < actionLabels.size) actionLabels[index] else commandName
        }

        private fun refreshConflictHint() {
            val duplicates =
                BrailleKeyMapPolicy.duplicateTargets(CustomKeyMapStore.load(requireContext()))
            conflictHint.isVisible = duplicates.isNotEmpty()
            if (duplicates.isNotEmpty()) {
                conflictHint.summary =
                    getString(R.string.custom_key_map_conflict, duplicates.size)
            }
        }

        private fun confirmResetAll() {
            val context = requireContext()
            AlertDialog.Builder(context)
                .setTitle(R.string.custom_key_map_reset_confirm_title)
                .setMessage(R.string.custom_key_map_reset_confirm_message)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    CustomKeyMapStore.resetAll(context)
                    for (i in 0 until screen.preferenceCount) {
                        val preference = screen.getPreference(i)
                        if (preference is PreferenceCategory) {
                            for (j in 0 until preference.preferenceCount) {
                                val child = preference.getPreference(j)
                                if (child is ListPreference) {
                                    child.value = BrailleKeyMapPolicy.DEFAULT_ACTION
                                }
                            }
                        }
                    }
                    refreshSummaries()
                    refreshConflictHint()
                    Toast.makeText(
                        context, R.string.custom_key_map_reset_done, Toast.LENGTH_SHORT
                    ).show()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        private fun refreshSummaries() {
            for (i in 0 until screen.preferenceCount) {
                val preference = screen.getPreference(i)
                if (preference is ListPreference) {
                    val source = preference.key.removePrefix("custom_key_")
                    updateSummary(preference, source)
                } else if (preference is PreferenceCategory) {
                    for (j in 0 until preference.preferenceCount) {
                        val child = preference.getPreference(j)
                        if (child is ListPreference) {
                            val source = child.key.removePrefix("custom_key_")
                            updateSummary(child, source)
                        }
                    }
                }
            }
        }

        // ------------------------------------------------------------------
        // 设备真实键名（渐进增强）：服务未启用或驱动不提供绑定时，界面仍完整可用。
        // ------------------------------------------------------------------

        override fun onStart() {
            super.onStart()
            try {
                displayClient = DisplayClient(requireActivity()).also {
                    it.setOnConnectionStateChangeListener(this)
                }
            } catch (e: Exception) {
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
            if (state != Display.STATE_CONNECTED) {
                return
            }
            val activity = activity ?: return
            activity.runOnUiThread { loadDeviceKeyNames() }
        }

        private fun loadDeviceKeyNames() {
            try {
                val props = displayClient?.displayProperties ?: return
                val sorted = BrailleKeyBindingUtils.getSortedBindingsForDisplay(props)
                val friendlyNames = props.friendlyKeyNames
                for (source in sourceValues) {
                    val command = BrailleInputEvent.stringToCommand(source)
                    if (command == BrailleInputEvent.CMD_NONE) {
                        continue
                    }
                    val binding = BrailleKeyBindingUtils
                        .getBrailleKeyBindingForCommand(command, sorted) ?: continue
                    val names = BrailleKeyBindingUtils.getFriendlyKeyNamesForCommand(
                        binding, friendlyNames, requireContext()
                    )
                    if (!names.isNullOrBlank()) {
                        deviceKeyNames[source] = names
                    }
                }
                refreshSummaries()
            } catch (e: Exception) {
                Log.w(TAG, "读取点显器按键绑定失败，保持静态标签", e)
            }
        }

        private companion object {
            const val TAG = "CustomKeyMapActivity"
        }
    }
}
