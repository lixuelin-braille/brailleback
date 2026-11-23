/*
 * Copyright (C) 2013 Google Inc.
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

import android.app.AlertDialog
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.os.Bundle
import com.googlecode.eyesfree.utils.SettingsStore
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import com.googlecode.eyesfree.braille.display.BrailleDisplayProperties
import com.googlecode.eyesfree.braille.display.BrailleKeyBinding
import com.linxi.brailleback.compose.OverlayTutorialScreen
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Random

/**
 * Activity which guides new users through the use of the on-screen overlay.
 *
 * 两段演示协程仍挂在本 Activity 的 lifecycleScope：500ms 翻转左右按压态、
 * 1.5s 随机点亮一格；onPause 取消、onResume 重启。界面按压态 / 高亮格下标
 * 上浮给 [OverlayTutorialScreen]，本类不持有旧 View。
 */
class OverlayTutorialActivity : ComponentActivity() {

    private val mRandom = Random()

    // 上浮给 Compose 界面的演示状态；仪器测试通过 onActivity 读取这两个字段观测。
    internal var panDemoPressed by mutableStateOf(false)
        private set
    internal var routingHighlightedCell by mutableIntStateOf(-1)
        private set

    private var mPanDemoJob: Job? = null
    private var mRoutingKeyDemoJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            OverlayTutorialScreen(
                panDemoPressed = panDemoPressed,
                routingHighlightedCell = routingHighlightedCell,
                onAccessibilitySettings = { onClickAccessibilitySettingsButton() },
                onFinish = { onClickFinishButton() },
            )
        }
    }

    override fun onResume() {
        super.onResume()

        // 左右按钮的「按下去 / 抬起来」演示，每 500ms 翻一次。
        mPanDemoJob = lifecycleScope.launch {
            while (isActive) {
                panDemoPressed = !panDemoPressed
                delay(PAN_DEMO_INTERVAL)
            }
        }
        // 路由键演示：每 1.5s 随机点亮一格；300ms 高亮清除由画布协程完成。
        mRoutingKeyDemoJob = lifecycleScope.launch {
            while (isActive) {
                routingHighlightedCell = mRandom.nextInt(ROUTING_KEY_DEMO_CELLS)
                delay(ROUTING_KEY_DEMO_INTERVAL)
            }
        }
    }

    override fun onPause() {
        super.onPause()

        mPanDemoJob?.cancel()
        mPanDemoJob = null
        mRoutingKeyDemoJob?.cancel()
        mRoutingKeyDemoJob = null
    }

    fun onClickFinishButton() {
        finish()
    }

    fun onClickAccessibilitySettingsButton() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    companion object {
        private const val PREF_HAS_RUN = "overlay_tutorial_run"
        private const val PAN_DEMO_INTERVAL = 500L
        private const val ROUTING_KEY_DEMO_CELLS = 16
        private val ROUTING_KEY_DEMO_DISPLAY_PROPERTIES = BrailleDisplayProperties(
                ROUTING_KEY_DEMO_CELLS, 0,
                emptyArray<BrailleKeyBinding>(),
                emptyMap<String, String>())
        private const val ROUTING_KEY_DEMO_INTERVAL = 1500L

        internal fun startIfFirstTime(context: Context) {
            val prefs = SettingsStore.get(context)
            if (prefs.getBoolean(PREF_HAS_RUN, false)) {
                return
            }
            prefs.edit().putBoolean(PREF_HAS_RUN, true).apply()

            val listener = DialogInterface.OnClickListener { dialog, which ->
                when (which) {
                    DialogInterface.BUTTON_POSITIVE -> context.startActivity(
                            Intent(context, OverlayTutorialActivity::class.java))
                    DialogInterface.BUTTON_NEGATIVE -> {
                        // Do nothing.
                    }
                }
            }
            val dialog = AlertDialog.Builder(context)
                    .setTitle(R.string.overlay_tutorial_dialog_title)
                    .setMessage(R.string.overlay_tutorial_dialog_message)
                    .setPositiveButton(android.R.string.ok, listener)
                    .setNegativeButton(android.R.string.cancel, listener)
                    .create()
            dialog.show()
        }
    }
}
