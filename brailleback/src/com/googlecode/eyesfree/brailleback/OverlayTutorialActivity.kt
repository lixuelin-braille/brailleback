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

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.os.Bundle
import android.preference.PreferenceManager
import android.provider.Settings
import android.view.View
import com.googlecode.eyesfree.braille.display.BrailleDisplayProperties
import com.googlecode.eyesfree.braille.display.BrailleKeyBinding
import java.util.Random

/**
 * Activity which guides new users through the use of the on-screen overlay.
 */
class OverlayTutorialActivity : Activity() {

    private val mRandom = Random()
    private var mPanDemo: View? = null
    private var mPanDemoLeftButton: View? = null
    private var mPanDemoRightButton: View? = null
    private var mPanDemoState: Boolean = false
    private val mPanDemoRunnable = object : Runnable {
        override fun run() {
            mPanDemoState = !mPanDemoState
            mPanDemoLeftButton!!.setPressed(mPanDemoState)
            mPanDemoRightButton!!.setPressed(mPanDemoState)
            mPanDemo!!.postDelayed(this, PAN_DEMO_INTERVAL)
        }
    }

    private var mRoutingKeyDemo: BrailleView? = null
    private val mRoutingKeyDemoRunnable = object : Runnable {
        override fun run() {
            mRoutingKeyDemo!!.cancelPendingTouches()
            mRoutingKeyDemo!!.highlightCell(mRandom.nextInt(ROUTING_KEY_DEMO_CELLS))
            mRoutingKeyDemo!!.postDelayed(this, ROUTING_KEY_DEMO_INTERVAL)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.overlay_tutorial_activity)
        mPanDemo = findViewById(R.id.pan_demo)
        mPanDemoLeftButton = mPanDemo!!.findViewById(R.id.pan_left_button)
        mPanDemoRightButton = mPanDemo!!.findViewById(R.id.pan_right_button)
        val routingKeyDemoView = findViewById<View>(R.id.routing_key_demo)
        mRoutingKeyDemo = routingKeyDemoView.findViewById<BrailleView>(R.id.braille_view)
        mRoutingKeyDemo!!.setDisplayProperties(ROUTING_KEY_DEMO_DISPLAY_PROPERTIES)
        mRoutingKeyDemo!!.displayDots(byteArrayOf(), "", intArrayOf())
    }

    override fun onResume() {
        super.onResume()

        mPanDemo!!.post(mPanDemoRunnable)
        mRoutingKeyDemo!!.post(mRoutingKeyDemoRunnable)
    }

    override fun onPause() {
        super.onPause()

        mPanDemo!!.removeCallbacks(mPanDemoRunnable)
        mRoutingKeyDemo!!.removeCallbacks(mRoutingKeyDemoRunnable)
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
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
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
