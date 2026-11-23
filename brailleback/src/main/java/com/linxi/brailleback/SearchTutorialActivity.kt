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

import android.content.Context
import android.content.Intent
import android.os.Bundle
import com.googlecode.eyesfree.utils.SettingsStore
import com.googlecode.eyesfree.braille.display.BrailleInputEvent
import com.googlecode.eyesfree.braille.display.Display
import com.googlecode.eyesfree.braille.display.DisplayClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.linxi.brailleback.compose.SearchTutorialScreen
import com.linxi.brailleback.utils.BrailleKeyBindingUtils

/**
 * Activity explaining how to use incremental search mode.
 *
 * 界面交 [SearchTutorialScreen]；DisplayClient 连接监听、偏好写入、关闭后进入
 * 搜索模式的链路保持不变。本页不使用协程定时。
 */
class SearchTutorialActivity : ComponentActivity(),
        Display.OnConnectionStateChangeListener {
    private var mDisplay: DisplayClient? = null

    // Whether the activity was closed through the close button.
    private var mShouldActivateSearch = false

    private var specificInstructions by mutableStateOf<String?>(null)
    private var doNotShowChecked by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = SettingsStore.get(this)
        doNotShowChecked = prefs.getBoolean(PREF_DO_NOT_SHOW, false)

        setContent {
            SearchTutorialScreen(
                specificInstructions = specificInstructions,
                doNotShowChecked = doNotShowChecked,
                onDoNotShowChange = { doNotShowChecked = it },
                onClose = { onClickCloseButton() },
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
        if (mShouldActivateSearch) {
            BrailleBackService.getActiveInstance()?.startSearchMode()
            mShouldActivateSearch = false
        }
        super.onStop()
    }

    override fun onConnectionStateChanged(state: Int) {
        if (state == Display.STATE_CONNECTED) {
            populateTutorialText()
        } else {
            // Hide specific instructions if no display connected.
            specificInstructions = null
        }
    }

    /**
     * Fills in the tutorial text. Adds in key bindings as arguments
     * if possible.
     */
    private fun populateTutorialText() {
        // Find friendly names for key commands.
        val props = mDisplay?.displayProperties ?: return

        val sortedBindings =
                BrailleKeyBindingUtils.getSortedBindingsForDisplay(props)
        val friendlyKeyNames = props.friendlyKeyNames

        // Find bindings for the four commands we need.
        val nextItemBinding =
                BrailleKeyBindingUtils.getBrailleKeyBindingForCommand(
                        BrailleInputEvent.CMD_NAV_ITEM_NEXT,
                        sortedBindings)
        val previousItemBinding =
                BrailleKeyBindingUtils.getBrailleKeyBindingForCommand(
                        BrailleInputEvent.CMD_NAV_ITEM_PREVIOUS,
                        sortedBindings)
        val enterBinding =
                BrailleKeyBindingUtils.getBrailleKeyBindingForCommand(
                        BrailleInputEvent.CMD_KEY_ENTER,
                        sortedBindings)
        val toggleSearchBinding =
                BrailleKeyBindingUtils.getBrailleKeyBindingForCommand(
                        BrailleInputEvent.CMD_TOGGLE_INCREMENTAL_SEARCH,
                        sortedBindings)

        if (nextItemBinding == null || previousItemBinding == null ||
                enterBinding == null || toggleSearchBinding == null) {
            // Stop here if any of the bindings aren't set.
            return
        }

        val nextItemName =
                BrailleKeyBindingUtils.getFriendlyKeyNamesForCommand(
                        nextItemBinding, friendlyKeyNames, this)
        val previousItemName =
                BrailleKeyBindingUtils.getFriendlyKeyNamesForCommand(
                        previousItemBinding, friendlyKeyNames, this)
        val enterName =
                BrailleKeyBindingUtils.getFriendlyKeyNamesForCommand(
                        enterBinding, friendlyKeyNames, this)
        val toggleSearchName =
                BrailleKeyBindingUtils.getFriendlyKeyNamesForCommand(
                        toggleSearchBinding, friendlyKeyNames, this)

        specificInstructions = getString(
                R.string.search_tutorial_instructions_specific,
                nextItemName,
                previousItemName,
                enterName,
                toggleSearchName)
    }

    companion object {
        private const val PREF_DO_NOT_SHOW = "search_tutorial_no_show"

        @JvmStatic
        fun tryStartActivity(context: Context): Boolean {
            val prefs = SettingsStore.get(context)
            if (prefs.getBoolean(PREF_DO_NOT_SHOW, false)) {
                return false
            }

            val intent = Intent(context, SearchTutorialActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            context.startActivity(intent)
            return true
        }
    }

    /**
     * Closes the activity and saves a preference about whether or not
     * to display the tutorial in the future.
     */
    fun onClickCloseButton() {
        val prefs = SettingsStore.get(this)
        prefs.edit()
                .putBoolean(PREF_DO_NOT_SHOW, doNotShowChecked)
                .apply()
        mShouldActivateSearch = true
        finish()
    }
}
