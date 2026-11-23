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
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Setup wizard that helps the user to enable the braille IME and make
 * it the default input method.
 */
class IMESetupWizardActivity : ComponentActivity() {

    private var inputMethodManager: InputMethodManager? = null

    /**
     * 系统输入法设置页返回后统一重查一次状态。
     * 这页只关心「回来之后 IME 是否已启用」，不关心具体结果码。
     */
    private val imeSettingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()) {
        checkStatus()
    }

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
        // 取一次局部再设置：可空属性不参与智能转换，也避免两次读之间被清空。
        val defaultFrag = DefaultBrailleImeFragment()
        defaultBrailleImeFrag = defaultFrag
        val imm = inputMethodManager
        if (imm != null) {
            defaultFrag.setInputMethodManager(imm)
        }
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
            // Fragment 还没挂到 Activity 时 activity 为空，此时没有可用的 Context，直接不动。
            val context = activity ?: return null
            val enableBrailleImeLayout = LinearLayout(context)
            enableBrailleImeLayout.orientation = LinearLayout.VERTICAL
            enableBrailleImeLayout.addView(
                    IMESetupWizardActivity.createMessageText(context, R.string.ime_setup_need_enable_message))

            // Create "enable braille IME" button.
            val button = Button(context)
            button.setText(R.string.ime_setup_need_enable_positive)
            button.setOnClickListener {
                // 结果回到宿主的 launcher，回来后就重查一次状态。
                (activity as? IMESetupWizardActivity)?.launchImeSettings()
            }
            enableBrailleImeLayout.addView(button)

            enableBrailleImeLayout.addView(IMESetupWizardActivity.createCancelButton(context))
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
            val context = activity ?: return null
            val defaultBrailleImeLayout = LinearLayout(context)
            defaultBrailleImeLayout.orientation = LinearLayout.VERTICAL
            defaultBrailleImeLayout.addView(
                    IMESetupWizardActivity.createMessageText(context, R.string.ime_setup_need_default_message))

            // Create "set default IME" button -- in case immediate call to showInputMethodPicker() fails,
            // or in case user exits IME-picker without selecting braille IME.
            val button = Button(context)
            button.setText(R.string.ime_setup_need_default_positive)
            button.setOnClickListener {
                inputMethodManager?.showInputMethodPicker()
            }
            defaultBrailleImeLayout.addView(button)

            defaultBrailleImeLayout.addView(IMESetupWizardActivity.createCancelButton(context))
            return defaultBrailleImeLayout
        }

        override fun onResume() {
            super.onResume()
            // Try to call showInputMethodPicker() without button click.
            // Need delay to ensure showInputMethodPicker() receives correct UID, else it fails.
            //
            // 原来这里 new 一个 Handler 只用来排一次 300ms；换成生命周期作用域的协程后，
            // 页面在 300ms 内被销毁（用户按返回、切走）会自动取消，不再有
            // 「往一个已 dead 的 Activity 弹选择器」的窗口。
            // 这个 Fragment 是嵌套类（拿不到外部 Activity 的 this），也不是 LifecycleOwner，
            // 所以取宿主 Activity 的作用域：Activity 销毁时这次延迟自动取消。
            val host = activity as? ComponentActivity ?: return
            host.lifecycleScope.launch {
                delay(INPUT_METHOD_PICKER_DELAY_MS)
                // 取一次局部，避免延迟执行时属性已被清空。
                inputMethodManager?.showInputMethodPicker()
            }
        }
    }

    /** Creates a text-view with a little bit of formatting. */
    companion object {

        /** showInputMethodPicker() 前必须等的这一小段，等系统把 UID 认对。 */
        private const val INPUT_METHOD_PICKER_DELAY_MS = 300L

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

    /** 由向导页按钮调用：打开系统输入法设置页。 */
    internal fun launchImeSettings() {
        imeSettingsLauncher.launch(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
    }

    ////////////////////////////////////////////////////////////////////////////////////////
    // Methods to handle completion of spawned IME setup sub-activities.

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

    /**
     * ContentObserver 的构造参数要求一个 Handler（回调要落到某个 Looper 上），
     * 这是 API 硬约束，不能换协程；它的唯一用途就是给下面这个观察者。
     */
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
