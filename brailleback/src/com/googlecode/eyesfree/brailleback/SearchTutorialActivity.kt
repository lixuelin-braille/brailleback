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
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.preference.PreferenceManager
import android.view.View
import android.widget.CheckBox
import android.widget.TextView
import com.googlecode.eyesfree.braille.display.BrailleDisplayProperties
import com.googlecode.eyesfree.braille.display.BrailleInputEvent
import com.googlecode.eyesfree.braille.display.BrailleKeyBinding
import com.googlecode.eyesfree.braille.display.Display
import com.googlecode.eyesfree.braille.display.DisplayClient
import com.googlecode.eyesfree.brailleback.utils.BrailleKeyBindingUtils
import java.util.ArrayList
import java.util.Map

/**
 * Activity explaining how to use incremental search mode.
 */
class SearchTutorialActivity : Activity(),
        Display.OnConnectionStateChangeListener {
    private var mDisplay: DisplayClient? = null

    // Whether the activity was closed through the close button.
    private var mShouldActivateSearch = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.search_tutorial_activity)

        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val doNotShowCheckBox = findViewById(R.id.doNotShowCheckBox) as CheckBox
        doNotShowCheckBox.isChecked = prefs.getBoolean(PREF_DO_NOT_SHOW, false)
    }

    override fun onStart() {
        super.onStart()
        mDisplay = DisplayClient(this)
        mDisplay!!.setOnConnectionStateChangeListener(this)
    }

    override fun onStop() {
        if (mDisplay != null) {
            mDisplay!!.shutdown()
            mDisplay = null
        }
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
            val instructionsTextView = findViewById(R.id.instructionsSpecific) as TextView
            instructionsTextView.visibility = View.INVISIBLE
        }
    }

    /**
     * Fills in the tutorial text view. Adds in key bindings as arguments
     * if possible.
     */
    private fun populateTutorialText() {
        // Find friendly names for key commands.
        val props = mDisplay!!.displayProperties ?: return

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

        // Set text in text view.
        val instructionsTextView = findViewById(R.id.instructionsSpecific) as TextView

        instructionsTextView.text = getString(
                R.string.search_tutorial_instructions_specific,
                nextItemName,
                previousItemName,
                enterName,
                toggleSearchName)
        instructionsTextView.visibility = View.VISIBLE
    }

    companion object {
        private const val PREF_DO_NOT_SHOW = "search_tutorial_no_show"

        @JvmStatic
        fun tryStartActivity(context: Context): Boolean {
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
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
    fun onClickCloseButton(v: View) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val doNotShowCheckBox = findViewById(R.id.doNotShowCheckBox) as CheckBox
        prefs.edit()
                .putBoolean(PREF_DO_NOT_SHOW, doNotShowCheckBox.isChecked)
                .apply()
        mShouldActivateSearch = true
        finish()
    }
}
