/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 *
 * 新框架设置页（androidx.preference，迁移 step 1）。
 *
 * 覆盖「设备与连接」分类 + 顶部快捷入口（启用服务引导 / 盲文阅读器 / 书架）。
 * 其余分类（盲文 / 声音 / 高级）仍在老框架设置页 [BrailleBackPreferencesActivity]，
 * 通过「完整设置」入口进入，过渡期不丢功能。
 * 数据层与老页共用同一个 SharedPreferences（key 完全一致），值不丢失。
 */

package com.googlecode.eyesfree.brailleback

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.googlecode.eyesfree.braille.display.Display
import com.googlecode.eyesfree.braille.display.DisplayClient
import com.googlecode.eyesfree.brailleback.utils.LogFileWriter

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportFragmentManager.beginTransaction()
            .replace(android.R.id.content, SettingsFragment())
            .commit()
    }

    class SettingsFragment : PreferenceFragmentCompat(),
        Display.OnConnectionStateChangeListener,
        Display.OnConnectionChangeProgressListener {

        private var mDisplay: DisplayClient? = null
        private var mConnectionState = Display.STATE_NOT_CONNECTED
        private var mConnectionProgress: String? = null
        private var mStatusPreference: Preference? = null
        private var mSetupServicePreference: Preference? = null

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.settings, rootKey)
        }

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            // 文件日志：无 adb 环境也能导出日志反馈问题
            LogFileWriter.init(requireActivity())

            mStatusPreference =
                findPreference<Preference>(getString(R.string.pref_connection_status_key))
            mStatusPreference?.setOnPreferenceClickListener {
                mDisplay?.poll()
                true
            }

            findPreference<Preference>(getString(R.string.pref_key_bindings_key))
                ?.intent = Intent(requireActivity(), KeyBindingsActivity::class.java)

            mSetupServicePreference =
                findPreference<Preference>(getString(R.string.pref_setup_service_key))
            mSetupServicePreference?.setOnPreferenceClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                true
            }

            findPreference<Preference>(getString(R.string.pref_braille_reader_key))
                ?.setOnPreferenceClickListener {
                    startActivity(Intent(requireActivity(), BrailleReaderActivity::class.java))
                    true
                }

            findPreference<Preference>(getString(R.string.pref_bookshelf_key))
                ?.setOnPreferenceClickListener {
                    startActivity(Intent(requireActivity(), BookshelfActivity::class.java))
                    true
                }

            // 迁移过渡期：完整设置（老框架页）
            findPreference<Preference>(getString(R.string.pref_settings_full_key))
                ?.setOnPreferenceClickListener {
                    startActivity(
                        Intent(requireActivity(), BrailleBackPreferencesActivity::class.java))
                    true
                }
        }

        override fun onResume() {
            super.onResume()
            requestBluetoothPermissions()
            val display = DisplayClient(requireActivity())
            mDisplay = display
            display.setOnConnectionStateChangeListener(this)
            display.setOnConnectionChangeProgressListener(this)
            onConnectionStateChanged(Display.STATE_NOT_CONNECTED)
            updateSetupServicePreference()
        }

        override fun onPause() {
            super.onPause()
            mDisplay?.shutdown()
            mDisplay = null
        }

        // ------------------------------------------------------------------
        // 连接状态
        // ------------------------------------------------------------------

        override fun onConnectionStateChanged(state: Int) {
            mConnectionState = state
            val summary: CharSequence = when (state) {
                Display.STATE_CONNECTED -> getText(R.string.connstate_connected)
                Display.STATE_NOT_CONNECTED -> getText(R.string.connstate_not_connected)
                else -> getText(R.string.connstate_error)
            }
            if (mConnectionProgress == null) {
                mStatusPreference?.summary = summary
            }
        }

        override fun onConnectionChangeProgress(description: String?) {
            mConnectionProgress = description
            if (description == null) {
                onConnectionStateChanged(mConnectionState)
            } else {
                mStatusPreference?.summary = description
            }
        }

        // ------------------------------------------------------------------
        // 启用服务引导（受限设置引导）
        // ------------------------------------------------------------------

        private fun updateSetupServicePreference() {
            val pref = mSetupServicePreference ?: return
            pref.summary = if (isServiceEnabled()) {
                getString(R.string.pref_setup_service_enabled)
            } else {
                getString(R.string.pref_setup_service_summary)
            }
        }

        private fun isServiceEnabled(): Boolean {
            val context = requireActivity()
            val expected =
                ComponentName(context, BrailleBackService::class.java).flattenToString()
            val enabled = Settings.Secure.getString(
                context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
            return enabled.split(':').contains(expected)
        }

        // ------------------------------------------------------------------
        // 蓝牙权限（Android 12+）
        // ------------------------------------------------------------------

        private fun requestBluetoothPermissions() {
            val context = requireActivity()
            if (Build.VERSION.SDK_INT >= 31) {
                if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                    != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(
                        arrayOf(Manifest.permission.BLUETOOTH_CONNECT),
                        REQUEST_BLUETOOTH_CONNECT)
                }
            }
            if (Build.VERSION.SDK_INT >= 33 &&
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    REQUEST_POST_NOTIFICATIONS)
            }
        }

        override fun onRequestPermissionsResult(
            requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
            super.onRequestPermissionsResult(requestCode, permissions, grantResults)
            if (requestCode == REQUEST_BLUETOOTH_CONNECT) {
                val granted = grantResults.isNotEmpty() &&
                    grantResults[0] == PackageManager.PERMISSION_GRANTED
                if (granted) {
                    // Permission just granted: re-scan for connected displays.
                    mDisplay?.poll()
                }
            }
        }

        companion object {
            private const val REQUEST_BLUETOOTH_CONNECT = 1
            private const val REQUEST_POST_NOTIFICATIONS = 2
        }
    }
}
