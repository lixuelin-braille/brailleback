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
import android.app.Fragment
import android.app.FragmentManager
import android.app.FragmentTransaction
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.os.Bundle
import android.os.Handler
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Setup wizard that helps the user to enable the braille IME and make
 * it the default input method.
 */
class IMESetupWizardActivity : Activity() {

    private var inputMethodManager: InputMethodManager? = null

    /**
     * Screens to prompt for enabling and activating braille IME. Use screen directly in this
     * activity, not using dialog, because Android O does not allow dialog to call
     * showInputMethodPicker().
     */
    private var enableBrailleImeFrag: EnableBrailleImeFragment? = null

    private var defaultBrailleImeFrag: DefaultBrailleImeFragment? = null
    private val FRAGMENTS_LAYOUT_ID = View.generateViewId()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        inputMethodManager = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager

        contentResolver
                .registerContentObserver(
                        Settings.Secure.getUriFor(Settings.Secure.DEFAULT_INPUT_METHOD),
                        false,
                        contentObserver)

        // Create view-group containing fragments.
        val layout = LinearLayout(this)
        layout.id = FRAGMENTS_LAYOUT_ID
        enableBrailleImeFrag = EnableBrailleImeFragment()
        defaultBrailleImeFrag = DefaultBrailleImeFragment()
        defaultBrailleImeFrag!!.setInputMethodManager(inputMethodManager!!)
        setContentView(layout)

        checkStatus()
    }

    ////////////////////////////////////////////////////////////////////////////////////////
    // Screens

    /** Screen fragment that shows button to launch IME-enabling activity. */
    class EnableBrailleImeFragment : Fragment() {

        override fun onCreateView(
                inflater: LayoutInflater?, container: ViewGroup?, savedInstanceState: Bundle?): View? {

            // Create "enable braille IME" screen.
            val context = activity
            val enableBrailleImeLayout = LinearLayout(context)
            enableBrailleImeLayout.orientation = LinearLayout.VERTICAL
            enableBrailleImeLayout.addView(
                    IMESetupWizardActivity.createMessageText(context!!, R.string.ime_setup_need_enable_message))

            // Create "enable braille IME" button.
            val button = Button(context)
            button.setText(R.string.ime_setup_need_enable_positive)
            button.setOnClickListener {
                // Start IME-enable activity, and have the wizard activity handle the result.
                val intent = Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)
                context!!.startActivityForResult(intent, IMESetupWizardActivity.REQUEST_ENABLE_IME)
            }
            enableBrailleImeLayout.addView(button)

            enableBrailleImeLayout.addView(IMESetupWizardActivity.createCancelButton(context!!))
            return enableBrailleImeLayout
        }
    }

    /** Screen fragment that shows button to launch IME-choosing activity. */
    class DefaultBrailleImeFragment : Fragment() {

        private var inputMethodManager: InputMethodManager? = null

        fun setInputMethodManager(inputMethodManagerArg: InputMethodManager) {
            inputMethodManager = inputMethodManagerArg
        }

        override fun onCreateView(
                inflater: LayoutInflater?, container: ViewGroup?, savedInstanceState: Bundle?): View? {

            // Create "set default IME" screen.
            val context = activity
            val defaultBrailleImeLayout = LinearLayout(context)
            defaultBrailleImeLayout.orientation = LinearLayout.VERTICAL
            defaultBrailleImeLayout.addView(
                    IMESetupWizardActivity.createMessageText(context!!, R.string.ime_setup_need_default_message))

            // Create "set default IME" button -- in case immediate call to showInputMethodPicker() fails,
            // or in case user exits IME-picker without selecting braille IME.
            val button = Button(context)
            button.setText(R.string.ime_setup_need_default_positive)
            button.setOnClickListener {
                inputMethodManager!!.showInputMethodPicker()
            }
            defaultBrailleImeLayout.addView(button)

            defaultBrailleImeLayout.addView(IMESetupWizardActivity.createCancelButton(context!!))
            return defaultBrailleImeLayout
        }

        override fun onResume() {
            super.onResume()
            // Try to call showInputMethodPicker() without button click.
            // Need delay to ensure showInputMethodPicker() receives correct UID, else it fails.
            val handler = Handler()
            handler.postDelayed({ inputMethodManager!!.showInputMethodPicker() }, 300L)
        }
    }

    /** Creates a text-view with a little bit of formatting. */
    companion object {
        internal const val REQUEST_ENABLE_IME = 1

        internal fun createMessageText(context: Context, textResourceId: Int): TextView {
            val message = TextView(context)
            message.setText(textResourceId)
            val params = LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
            val margin = 20
            params.setMargins(margin, margin, margin, margin)
            message.layoutParams = params
            message.setLineSpacing(0.0f, 1.25f)
            return message
        }

        /** Creates a cancel button that ends enclosing activity. */
        internal fun createCancelButton(activity: Activity): Button {
            val button = Button(activity)
            button.setText(android.R.string.cancel)
            button.setOnClickListener { activity.finish() }
            return button
        }
    }

    ////////////////////////////////////////////////////////////////////////////////////////
    // Methods to handle completion of spawned IME setup sub-activities.

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        checkStatus()
    }

    private fun checkStatus() {
        if (!IMEHelper.isInputMethodEnabled(this, BrailleIME::class.java)) {
            showFragment(enableBrailleImeFrag)
        } else if (!IMEHelper.isInputMethodDefault(this, BrailleIME::class.java)) {
            showFragment(defaultBrailleImeFrag)
        } else {
            finish()
        }
    }

    private fun showFragment(newFragment: Fragment?) {
        val fragmentManager = fragmentManager
        val fragmentTransaction = fragmentManager.beginTransaction()
        fragmentTransaction.replace(FRAGMENTS_LAYOUT_ID, newFragment)
        fragmentTransaction.commit()
    }

    private val handler = Handler()

    private val contentObserver = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) {
            if (IMEHelper.isInputMethodDefault(this@IMESetupWizardActivity, BrailleIME::class.java)) {
                finish()
            }
        }
    }

    override fun finish() {
        contentResolver.unregisterContentObserver(contentObserver)
        super.finish()
    }
}
