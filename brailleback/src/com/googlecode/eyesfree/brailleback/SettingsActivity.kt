/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 *
 * 设置页（androidx.preference 全量迁移）。
 *
 * 覆盖全部四个分类：设备与连接 / 盲文显示 / 声音 / 高级。
 * 老框架设置页（BrailleBackPreferencesActivity）已退役。
 * 数据层与迁移前共用同一个 SharedPreferences（key 完全一致），值不丢失。
 */

package com.googlecode.eyesfree.brailleback

import android.Manifest
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceScreen
import com.googlecode.eyesfree.braille.display.Display
import com.googlecode.eyesfree.braille.display.DisplayClient
import com.googlecode.eyesfree.braille.translate.TableInfo
import com.googlecode.eyesfree.brailleback.sound.SoundSchemeManager
import com.googlecode.eyesfree.brailleback.utils.LogFileWriter
import com.googlecode.eyesfree.brailleback.utils.PreferenceUtils
import com.googlecode.eyesfree.utils.LogUtils
import java.text.CollationKey
import java.text.Collator
import java.util.ArrayList
import java.util.Collections
import java.util.Comparator
import java.util.HashMap

class SettingsActivity : AppCompatActivity(),
    PreferenceFragmentCompat.OnPreferenceStartScreenCallback {

    /** Message from BrailleBackService, indicating that grade pref changed. */
    companion object {
        const val INTENT_REFRESH_DISPLAY =
            "com.googlecode.eyesfree.brailleback.REFRESH_DISPLAY"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportFragmentManager.beginTransaction()
            .replace(android.R.id.content, SettingsFragment())
            .commit()
    }

    /**
     * 嵌套分类（PreferenceScreen）导航：androidx.preference 的
     * PreferenceFragmentCompat 默认不会自行推入子屏，必须由宿主 Activity
     * 实现 OnPreferenceStartScreenCallback 并在返回时 push 一个以该分类为
     * rootKey 的新 fragment（加进返回栈，返回键可回上一级）。
     */
    override fun onPreferenceStartScreen(
        caller: PreferenceFragmentCompat,
        preferenceScreen: PreferenceScreen
    ): Boolean {
        val args = Bundle().apply {
            putString(
                PreferenceFragmentCompat.ARG_PREFERENCE_ROOT,
                preferenceScreen.key
            )
        }
        val fragment = SettingsFragment().apply { arguments = args }
        caller.parentFragmentManager.beginTransaction()
            .replace(android.R.id.content, fragment)
            .addToBackStack(null)
            .commit()
        return true
    }

    class SettingsFragment : PreferenceFragmentCompat(),
        Display.OnConnectionStateChangeListener,
        Display.OnConnectionChangeProgressListener,
        TranslatorManager.OnTablesChangedListener,
        Preference.OnPreferenceChangeListener,
        Preference.OnPreferenceClickListener {

        /** Message from BrailleBackService, indicating that grade pref changed. */
        companion object {
            private const val REQUEST_BLUETOOTH_CONNECT = 1
            private const val REQUEST_POST_NOTIFICATIONS = 2
        }

        private val mTableInfoComparator = TableInfoComparator()

        private var mDisplay: DisplayClient? = null
        private var mTranslatorManager: TranslatorManager? = null
        private var mStatusPreference: Preference? = null
        private var mBrailleTypePreference: ListPreference? = null
        private var mSixDotTablePreference: ListPreference? = null
        private var mEightDotTablePreference: ListPreference? = null
        private var mOverlayPreference: Preference? = null
        private var mOverlayTutorialPreference: Preference? = null
        private var mLicensesPreference: Preference? = null
        private var mLogLevelPreference: ListPreference? = null
        private var mSoundVolumePreference: Preference? = null
        private var mSetupServicePreference: Preference? = null
        private var mViewLogPreference: Preference? = null
        private var mClearLogPreference: Preference? = null
        private var mConnectionState = Display.STATE_NOT_CONNECTED
        private var mConnectionProgress: String? = null
        private var mTables: List<TableInfo> = emptyList()

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.settings, rootKey)
        }

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            // 文件日志：无 adb 环境也能导出日志反馈问题
            LogFileWriter.init(requireActivity())

            mStatusPreference =
                findPreference<Preference>(getString(R.string.pref_connection_status_key))
            mStatusPreference?.setOnPreferenceClickListener(this)
            assignKeyBindingsIntent()

            mBrailleTypePreference =
                findPreference<ListPreference>(getString(R.string.pref_braille_type_key))
            mBrailleTypePreference?.setOnPreferenceChangeListener(this)
            mSixDotTablePreference =
                findPreference<ListPreference>(getString(R.string.pref_six_dot_braille_table_key))
            mSixDotTablePreference?.setOnPreferenceChangeListener(this)
            mEightDotTablePreference =
                findPreference<ListPreference>(getString(R.string.pref_eight_dot_braille_table_key))
            mEightDotTablePreference?.setOnPreferenceChangeListener(this)

            mOverlayPreference =
                findPreference<Preference>(getString(R.string.pref_braille_overlay_key))
            mOverlayPreference?.setOnPreferenceChangeListener(this)

            mOverlayTutorialPreference =
                findPreference<Preference>(getString(R.string.pref_braille_overlay_tutorial_key))
            mOverlayTutorialPreference?.setOnPreferenceClickListener(this)

            mLicensesPreference =
                findPreference<Preference>(getString(R.string.pref_os_license_key))
            mLicensesPreference?.setOnPreferenceClickListener(this)

            mLogLevelPreference =
                findPreference<ListPreference>(getString(R.string.pref_log_level_key))
            mLogLevelPreference?.setOnPreferenceChangeListener(this)
            if (BuildConfig.DEBUG) {
                val logLevel = PreferenceUtils.getLogLevel(requireActivity())
                updateListPreferenceSummary(mLogLevelPreference, Integer.toString(logLevel))
                mLogLevelPreference?.isEnabled = false
            }

            mSoundVolumePreference =
                findPreference<Preference>(getString(R.string.pref_sound_volume_key))
            mSoundVolumePreference?.setOnPreferenceClickListener(this)

            mSetupServicePreference =
                findPreference<Preference>(getString(R.string.pref_setup_service_key))
            mSetupServicePreference?.setOnPreferenceClickListener(this)

            findPreference<Preference>(getString(R.string.pref_bookshelf_key))
                ?.setOnPreferenceClickListener {
                    startActivity(Intent(requireActivity(), BookshelfActivity::class.java))
                    true
                }

            mViewLogPreference =
                findPreference<Preference>(getString(R.string.pref_view_log_key))
            mViewLogPreference?.setOnPreferenceClickListener(this)
            mClearLogPreference =
                findPreference<Preference>(getString(R.string.pref_clear_log_key))
            mClearLogPreference?.setOnPreferenceClickListener(this)
        }

        override fun onResume() {
            super.onResume()
            requestBluetoothPermissions()

            val display = DisplayClient(requireActivity())
            mDisplay = display
            display.setOnConnectionStateChangeListener(this)
            display.setOnConnectionChangeProgressListener(this)

            val translatorManager = TranslatorManager(requireActivity())
            mTranslatorManager = translatorManager
            translatorManager.addOnTablesChangedListener(this)

            onConnectionStateChanged(Display.STATE_NOT_CONNECTED)
            refreshBrailleTypeSummary()
            if (Build.VERSION.SDK_INT >= 33) {
                requireContext().registerReceiver(
                    mRefreshReceiver,
                    IntentFilter(SettingsActivity.INTENT_REFRESH_DISPLAY),
                    Context.RECEIVER_NOT_EXPORTED
                )
            } else {
                @Suppress("DEPRECATION")
                requireContext().registerReceiver(
                    mRefreshReceiver, IntentFilter(SettingsActivity.INTENT_REFRESH_DISPLAY))
            }
            refreshSoundPreferences()
            updateSetupServicePreference()
        }

        override fun onPause() {
            super.onPause()
            try {
                requireContext().unregisterReceiver(mRefreshReceiver)
            } catch (e: IllegalArgumentException) {
                // Receiver was not registered (e.g. onPause without onResume).
            }
            mTranslatorManager?.removeOnTablesChangedListener(this)
            mTranslatorManager?.shutdown()
            mTranslatorManager = null
            mDisplay?.shutdown()
            mDisplay = null
        }

        private val mRefreshReceiver: BroadcastReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent) {
                if (TextUtils.equals(intent.action, SettingsActivity.INTENT_REFRESH_DISPLAY)) {
                    refreshBrailleTypeSummary()
                }
            }
        }

        // ------------------------------------------------------------------
        // 连接状态
        // ------------------------------------------------------------------

        override fun onConnectionStateChanged(state: Int) {
            mConnectionState = state
            val summary: CharSequence
            var enableBindings = false
            when (state) {
                Display.STATE_CONNECTED -> {
                    summary = getText(R.string.connstate_connected)
                    enableBindings = true
                }
                Display.STATE_NOT_CONNECTED ->
                    summary = getText(R.string.connstate_not_connected)
                else -> summary = getText(R.string.connstate_error)
            }
            findPreference<Preference>(getString(R.string.pref_key_bindings_key))
                ?.isEnabled = enableBindings
            if (mConnectionProgress == null) {
                mStatusPreference?.summary = summary
                announceConnectionState(summary)
            }
        }

        override fun onConnectionChangeProgress(description: String?) {
            mConnectionProgress = description
            if (description == null) {
                onConnectionStateChanged(mConnectionState)
            } else {
                // The description is localized by the server.
                mStatusPreference?.summary = description
                announceConnectionState(description)
            }
        }

        private fun announceConnectionState(state: CharSequence) {
            activity?.window?.decorView?.announceForAccessibility(state)
        }

        // ------------------------------------------------------------------
        // 盲文表格（由翻译服务动态填充）
        // ------------------------------------------------------------------

        override fun onTablesChanged() {
            mTables = mTranslatorManager?.getTranslatorClient()?.getTables() ?: emptyList()
            addTableList(mSixDotTablePreference, false)
            addTableList(mEightDotTablePreference, true)
        }

        /** Assigns the appropriate intent to the key bindings preference. */
        private fun assignKeyBindingsIntent() {
            val pref = findPreference<Preference>(getString(R.string.pref_key_bindings_key))
            val intent = Intent(requireActivity(), KeyBindingsActivity::class.java)
            pref?.intent = intent
        }

        private fun addTableList(pref: ListPreference?, eightDot: Boolean) {
            if (pref == null || mTranslatorManager == null) {
                return
            }
            val tables = ArrayList<TableInfo>()
            for (info in mTables) {
                if (eightDot == info.isEightDot) {
                    tables.add(info)
                }
            }
            Collections.sort(tables, mTableInfoComparator)
            val entryValues = arrayOfNulls<CharSequence>(tables.size + 1)
            val entries = arrayOfNulls<CharSequence>(tables.size + 1)
            var index = 0
            val defaultInfo = mTranslatorManager!!.findDefaultTableInfo(eightDot)
            if (defaultInfo != null) {
                entries[index] = getString(
                    R.string.pref_braille_table_default_label,
                    createTableDisplayName(defaultInfo)
                )
            } else {
                entries[index] = getText(R.string.pref_braille_table_default_none_label)
            }
            entryValues[index] = getString(R.string.table_value_default)
            ++index
            for (info in tables) {
                entries[index] = createTableDisplayName(info)
                entryValues[index] = info.id
                ++index
            }
            pref.entries = entries
            pref.entryValues = entryValues

            index = pref.findIndexOfValue(pref.value)
            if (index < 0 || index >= entries.size) {
                LogUtils.log(this, Log.ERROR,
                    "Unknown preference value for %s: %s", pref.key, pref.value)
            } else {
                pref.summary = entries[index]
            }
        }

        private fun createTableDisplayName(tableInfo: TableInfo): String {
            val localeDisplayName = tableInfo.locale.getDisplayName()
            if (tableInfo.isEightDot) {
                // Computer braille is obvious from context.
                return localeDisplayName
            }
            // Chinese braille is referred to by its system name (e.g. 国家通用盲文方案).
            if ("zh".equals(tableInfo.locale.getLanguage(), ignoreCase = true)) {
                if (TextUtils.isEmpty(tableInfo.variant)) {
                    return localeDisplayName
                }
                return getString(
                    R.string.table_name_variant, localeDisplayName, tableInfo.variant)
            }
            val related = mTranslatorManager!!.getRelatedTables(tableInfo)
            var gradeCount = 0
            for (relatedInfo in related) {
                if (relatedInfo.isEightDot) {
                    continue
                }
                ++gradeCount
            }
            return if (gradeCount <= 1) {
                if (TextUtils.isEmpty(tableInfo.variant)) {
                    localeDisplayName
                } else {
                    getString(R.string.table_name_variant,
                        localeDisplayName, tableInfo.variant)
                }
            } else {
                if (TextUtils.isEmpty(tableInfo.variant)) {
                    getString(R.string.table_name_grade,
                        localeDisplayName, tableInfo.grade)
                } else {
                    getString(R.string.table_name_variant_grade,
                        localeDisplayName, tableInfo.variant, tableInfo.grade)
                }
            }
        }

        // ------------------------------------------------------------------
        // 偏好变更 / 点击
        // ------------------------------------------------------------------

        override fun onPreferenceChange(preference: Preference, newValue: Any?): Boolean {
            // Always update the summary based on the list preference value.
            if (preference is ListPreference) {
                val updated = updateListPreferenceSummary(
                    preference, newValue as? String)
                if (!updated) {
                    return false
                }
            }

            // If the overlay was turned on for the first time, launch the tutorial.
            if (preference === mOverlayPreference && newValue == true) {
                OverlayTutorialActivity.startIfFirstTime(requireActivity())
            }

            // If the log level was changed, update it in LogUtils.
            if (preference === mLogLevelPreference) {
                return try {
                    val logLevel = Integer.parseInt(newValue as? String ?: "")
                    PreferenceUtils.updateLogLevel(logLevel)
                } catch (e: NumberFormatException) {
                    LogUtils.log(this, Log.ERROR, "illegal log level: %s", newValue)
                    false
                }
            }

            return true
        }

        override fun onPreferenceClick(preference: Preference): Boolean {
            when (preference) {
                mStatusPreference -> {
                    mDisplay?.poll()
                    return true
                }
                mOverlayTutorialPreference -> {
                    startActivity(
                        Intent(requireActivity(), OverlayTutorialActivity::class.java))
                    return true
                }
                mLicensesPreference -> {
                    val intent = WebViewDialog.getIntent(
                        requireActivity(), R.string.pref_os_license_title,
                        "file:///android_asset/licenses.html")
                    startActivity(intent)
                    return true
                }
                mSoundVolumePreference -> {
                    showVolumeDialog()
                    return true
                }
                mSetupServicePreference -> {
                    openAccessibilitySettings()
                    return true
                }
                mViewLogPreference -> {
                    showDebugLogDialog()
                    return true
                }
                mClearLogPreference -> {
                    AlertDialog.Builder(requireActivity())
                        .setMessage(R.string.debug_log_clear_confirm)
                        .setPositiveButton(android.R.string.ok) { _, _ ->
                            LogFileWriter.clear(requireActivity())
                            Toast.makeText(requireActivity(), R.string.debug_log_cleared,
                                Toast.LENGTH_SHORT).show()
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                    return true
                }
            }
            return false
        }

        // ------------------------------------------------------------------
        // Debug log view / export
        // ------------------------------------------------------------------

        /** 弹出调试日志对话框：ScrollView 展示 + 分享按钮。 */
        private fun showDebugLogDialog() {
            val context = requireActivity()
            val log = LogFileWriter.readAll(context)
            val body = if (log.isBlank()) getString(R.string.debug_log_empty) else log
            val textView = TextView(context).apply {
                text = body
                textSize = 11f
                typeface = android.graphics.Typeface.MONOSPACE
                setTextIsSelectable(true)
                setPadding(dp(8), dp(8), dp(8), dp(8))
            }
            val scroll = ScrollView(context).apply {
                addView(textView)
            }
            AlertDialog.Builder(context)
                .setTitle(R.string.debug_log_view_title)
                .setView(scroll)
                .setPositiveButton(R.string.debug_log_share) { _, _ ->
                    shareLog(body)
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        private fun shareLog(text: String) {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
            }
            try {
                startActivity(Intent.createChooser(intent,
                    getString(R.string.debug_log_share)))
            } catch (e: Exception) {
                Toast.makeText(requireActivity(), R.string.debug_log_share_fail,
                    Toast.LENGTH_SHORT).show()
            }
        }

        private fun dp(v: Int): Int =
            (v * resources.displayMetrics.density).toInt()

        // ------------------------------------------------------------------
        // Sound preferences (master switch + volume only)
        // ------------------------------------------------------------------

        private fun refreshSoundPreferences() {
            val manager = SoundSchemeManager.getInstance(requireActivity())
            val volumePreference = mSoundVolumePreference ?: return
            volumePreference.summary =
                getString(R.string.sound_editor_volume, manager.getVolume())
        }

        /** 弹出音量调整对话框（SeekBar）。 */
        private fun showVolumeDialog() {
            val context = requireActivity()
            val manager = SoundSchemeManager.getInstance(context)
            val seekBar = SeekBar(context)
            seekBar.max = 100
            seekBar.progress = manager.getVolume()
            AlertDialog.Builder(context)
                .setTitle(R.string.pref_sound_volume_title)
                .setView(seekBar)
                .setPositiveButton(android.R.string.ok,
                    DialogInterface.OnClickListener { _, _ ->
                        val volume = seekBar.progress
                        manager.setVolume(volume)
                        mSoundVolumePreference?.summary =
                            getString(R.string.sound_editor_volume, volume)
                    })
                .setNegativeButton(android.R.string.cancel, null)
                .show()
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

        private fun openAccessibilitySettings() {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
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

        // ------------------------------------------------------------------
        // 辅助方法
        // ------------------------------------------------------------------

        private fun refreshBrailleTypeSummary() {
            val sharedPrefs: SharedPreferences =
                androidx.preference.PreferenceManager.getDefaultSharedPreferences(requireActivity())
            val prefKey = getString(R.string.pref_braille_type_key)
            val prefDefault = getString(R.string.pref_braille_type_six_dot_value)
            val prefValue = sharedPrefs.getString(prefKey, prefDefault)
            updateListPreferenceSummary(mBrailleTypePreference, prefValue)
        }

        private fun updateListPreferenceSummary(
            listPreference: ListPreference?, newValue: String?): Boolean {
            if (listPreference == null) {
                return false
            }
            val index = listPreference.findIndexOfValue(newValue)
            val entries = listPreference.entries
            if (index < 0 || index >= entries.size) {
                LogUtils.log(this, Log.ERROR,
                    "Unknown preference value for %s: %s", listPreference.key, newValue)
                return false
            }
            listPreference.summary = entries[index]
            return true
        }

        private class TableInfoComparator : Comparator<TableInfo> {
            private val mCollator: Collator = Collator.getInstance()
            private val mCollationKeyMap: MutableMap<TableInfo, CollationKey> =
                HashMap(KEY_MAP_INITIAL_CAPACITY)

            override fun compare(first: TableInfo, second: TableInfo): Int {
                if (first == second) {
                    return 0
                }
                var ret = getCollationKey(first).compareTo(getCollationKey(second))
                if (ret == 0 && first.isEightDot != second.isEightDot) {
                    ret = if (first.isEightDot) 1 else -1
                }
                if (ret == 0) {
                    ret = first.grade - second.grade
                }
                return ret
            }

            private fun getCollationKey(tableInfo: TableInfo): CollationKey {
                return mCollationKeyMap.getOrPut(tableInfo) {
                    mCollator.getCollationKey(tableInfo.locale.getDisplayName())
                }
            }

            companion object {
                private const val KEY_MAP_INITIAL_CAPACITY = 50
            }
        }
    }
}
