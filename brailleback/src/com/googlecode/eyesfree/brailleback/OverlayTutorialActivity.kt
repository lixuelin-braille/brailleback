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

package com.googlecode.eyesfree.brailleback

import android.app.AlertDialog
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.os.Bundle
import com.googlecode.eyesfree.utils.SettingsStore
import android.provider.Settings
import android.view.View
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.googlecode.eyesfree.braille.display.BrailleDisplayProperties
import com.googlecode.eyesfree.braille.display.BrailleKeyBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Random

/**
 * Activity which guides new users through the use of the on-screen overlay.
 *
 * 两个演示动画原来用自调度 Runnable（每拍在 run() 末尾再 postDelayed 自己）；
 * 现在改成两个生命周期作用域里的协程循环，onPause 取消——少了一类
 * 「宿主已销毁但定时任务还在排队」的窗口。
 */
class OverlayTutorialActivity : ComponentActivity() {

    private val mRandom = Random()
    private var mPanDemoLeftButton: View? = null
    private var mPanDemoRightButton: View? = null
    private var mPanDemoState: Boolean = false
    private var mPanDemoJob: Job? = null

    private var mRoutingKeyDemo: BrailleView? = null
    private var mRoutingKeyDemoJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.overlay_tutorial_activity)
        val panDemo: View = findViewById(R.id.pan_demo)
        mPanDemoLeftButton = panDemo.findViewById(R.id.pan_left_button)
        mPanDemoRightButton = panDemo.findViewById(R.id.pan_right_button)
        val routingKeyDemoView = findViewById<View>(R.id.routing_key_demo)
        val routingKeyDemo = routingKeyDemoView.findViewById<BrailleView>(R.id.braille_view)
        mRoutingKeyDemo = routingKeyDemo
        routingKeyDemo?.setDisplayProperties(ROUTING_KEY_DEMO_DISPLAY_PROPERTIES)
        routingKeyDemo?.displayDots(byteArrayOf(), "", intArrayOf())
    }

    override fun onResume() {
        super.onResume()

        // 左右按钮的「按下去 / 抬起来」演示，每 500ms 翻一次。
        mPanDemoJob = lifecycleScope.launch {
            while (isActive) {
                mPanDemoState = !mPanDemoState
                // 视图还没创建好时安静跳过这一拍，原来会直接崩在定时线程里。
                mPanDemoLeftButton?.setPressed(mPanDemoState)
                mPanDemoRightButton?.setPressed(mPanDemoState)
                delay(PAN_DEMO_INTERVAL)
            }
        }
        // 路由键演示：每 1.5s 随机点亮一格。
        mRoutingKeyDemoJob = lifecycleScope.launch {
            while (isActive) {
                val routingKeyDemo = mRoutingKeyDemo
                if (routingKeyDemo != null) {
                    routingKeyDemo.cancelPendingTouches()
                    routingKeyDemo.highlightCell(mRandom.nextInt(ROUTING_KEY_DEMO_CELLS))
                }
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

    fun onClickFinishButton(v: View) {
        finish()
    }

    fun onClickAccessibilitySettingsButton(v: View) {
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
