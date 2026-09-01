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

package com.googlecode.eyesfree.brailleback

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.TextView
import com.googlecode.eyesfree.braille.display.BrailleDisplayProperties
import com.googlecode.eyesfree.braille.display.BrailleInputEvent
import com.googlecode.eyesfree.braille.display.BrailleKeyBinding
import com.googlecode.eyesfree.braille.display.Display
import com.googlecode.eyesfree.braille.display.DisplayClient
import com.googlecode.eyesfree.brailleback.utils.BrailleKeyBindingUtils
import com.googlecode.eyesfree.labeling.CustomLabelManager
import java.util.ArrayList

/**
 * Shows key bindings for the currently connected Braille display.
 */
class KeyBindingsActivity : Activity(), Display.OnConnectionStateChangeListener {
    private var mDisplay: DisplayClient? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setTitle(R.string.key_bindings_title)
        setContentView(R.layout.key_bindings)
        actionBar!!.setHomeButtonEnabled(true)
        actionBar!!.setDisplayHomeAsUpEnabled(true)
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
        super.onStop()
    }

    override fun onConnectionStateChanged(state: Int) {
        if (state == Display.STATE_CONNECTED) {
            populateListView()
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> {
                val intent = Intent(this, SettingsActivity::class.java)
                intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                startActivity(intent)
                return true
            }
            else -> return super.onOptionsItemSelected(item)
        }
    }

    private fun populateListView() {
        val props = mDisplay!!.displayProperties ?: return
        val sortedBindings = BrailleKeyBindingUtils.getSortedBindingsForDisplay(props)

        val result = ArrayList<KeyBinding>()

        val supportedCommands = resources.getStringArray(R.array.help_supportedCommands)
        val descriptions = resources.getStringArray(R.array.help_commandDescriptions)
        val friendlyKeyNames = props.friendlyKeyNames
        for (i in supportedCommands.indices) {
            val name = supportedCommands[i]
            val command = BrailleInputEvent.stringToCommand(name)

            // Labeling menu command not supported in all versions.
            val labelingSupported =
                    BrailleBackService.LABELING_ENABLED &&
                            Build.VERSION.SDK_INT >= CustomLabelManager.MIN_API_LEVEL
            if (command == BrailleInputEvent.CMD_TOGGLE_BRAILLE_MENU && !labelingSupported) {
                continue
            }

            val binding = BrailleKeyBindingUtils.getBrailleKeyBindingForCommand(
                    command, sortedBindings) ?: continue
            addBindingForCommand(binding, descriptions[i], friendlyKeyNames, result)
        }

        val adapter = KeyBindingsAdapter(
                this, android.R.layout.simple_list_item_2, android.R.id.text1)
        adapter.addAll(result)

        val list = findViewById(R.id.list) as ListView
        list.adapter = adapter
    }

    private fun addBindingForCommand(
            binding: BrailleKeyBinding,
            commandDescription: String,
            friendlyKeyNames: Map<String, String>,
            result: ArrayList<KeyBinding>) {
        val keys = BrailleKeyBindingUtils.getFriendlyKeyNamesForCommand(
                binding, friendlyKeyNames, this)
        result.add(KeyBinding(commandDescription, keys))
    }

    private class KeyBindingsAdapter(
            context: Context,
            layout: Int,
            textViewResourceId: Int) : ArrayAdapter<KeyBinding>(context, layout, textViewResourceId) {

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = super.getView(position, convertView, parent)
            val item = getItem(position)
            (view.findViewById(android.R.id.text2) as TextView).text = item!!.binding
            return view
        }
    }

    private class KeyBinding(val label: String, val binding: String) {
        override fun toString(): String = label
    }
}
