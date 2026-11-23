/*
 * Copyright (C) 2012 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.linxi.brailleback

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.googlecode.eyesfree.braille.display.BrailleDisplayProperties
import com.googlecode.eyesfree.braille.display.BrailleInputEvent
import com.googlecode.eyesfree.braille.display.Display
import com.googlecode.eyesfree.braille.display.DisplayClient
import com.linxi.brailleback.compose.DisplayBinding
import com.linxi.brailleback.compose.KeyBindingsScreen
import com.linxi.brailleback.utils.BrailleKeyBindingUtils

/**
 * Shows key bindings for the currently connected Braille display.
 *
 * 2026-11-18：界面从 `TextView + ListView` 换成 Compose（`key_bindings.xml` 已删除），
 * 取键位的逻辑一行没动 —— 它依赖 `BrailleDisplayProperties`，是这屏唯一的真逻辑。
 */
class KeyBindingsActivity : ComponentActivity(), Display.OnConnectionStateChangeListener {
    private var mDisplay: DisplayClient? = null

    // 键位表由服务端回调填充，所以状态必须能触发重组。
    private var bindings by mutableStateOf<List<DisplayBinding>>(emptyList())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 这屏用 BraillebackTheme（Theme.DeviceDefault），不是 AppCompat 主题，
        // 所以基类只能是 ComponentActivity；标题交给 Compose 的 TopAppBar。

        setContent {
            KeyBindingsScreen(
                title = getString(R.string.key_bindings_title),
                backLabel = getString(R.string.compose_settings_back),
                instructions = getString(R.string.key_bindings_instructions),
                bindings = bindings,
                // 与原实现一致：返回箭头回设置主屏（CLEAR_TOP，不叠栈）。
                onBack = {
                    startActivity(
                        Intent(this@KeyBindingsActivity, SettingsActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    )
                },
            )
        }
    }

    override fun onStart() {
        super.onStart()
        val display = DisplayClient(this)
        mDisplay = display
        display.setOnConnectionStateChangeListener(this)
    }

    override fun onStop() {
        mDisplay?.shutdown()
        mDisplay = null
        super.onStop()
    }

    override fun onConnectionStateChanged(state: Int) {
        if (state == Display.STATE_CONNECTED) {
            bindings = loadBindings()
        }
    }

    private fun loadBindings(): List<DisplayBinding> {
        val props: BrailleDisplayProperties = mDisplay?.displayProperties ?: return emptyList()
        val sortedBindings = BrailleKeyBindingUtils.getSortedBindingsForDisplay(props)

        val result = ArrayList<DisplayBinding>()

        val supportedCommands = resources.getStringArray(R.array.help_supportedCommands)
        val descriptions = resources.getStringArray(R.array.help_commandDescriptions)
        val friendlyKeyNames = props.friendlyKeyNames
        for (i in supportedCommands.indices) {
            val name = supportedCommands[i]
            val command = BrailleInputEvent.stringToCommand(name)

            // 标签菜单命令只在 LABELING_ENABLED 时列出（版本门槛已随 minSdk 29 消失）。
            val labelingSupported = BrailleBackService.LABELING_ENABLED
            if (command == BrailleInputEvent.CMD_TOGGLE_BRAILLE_MENU && !labelingSupported) {
                continue
            }

            val binding = BrailleKeyBindingUtils.getBrailleKeyBindingForCommand(
                    command, sortedBindings) ?: continue

            val keys = BrailleKeyBindingUtils.getFriendlyKeyNamesForCommand(
                    binding, friendlyKeyNames, this)
            result.add(DisplayBinding(descriptions[i], keys))
        }
        return result
    }
}
