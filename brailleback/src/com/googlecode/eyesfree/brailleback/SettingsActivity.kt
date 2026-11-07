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
import android.accessibilityservice.AccessibilityServiceInfo
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
import android.view.accessibility.AccessibilityManager
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
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
    PreferenceFragmentCompat.OnPreferenceStartScreenCallback,
    Display.OnConnectionStateChangeListener,
    Display.OnConnectionChangeProgressListener {

    /** Message from BrailleBackService, indicating that grade pref changed. */
    companion object {
        const val INTENT_REFRESH_DISPLAY =
            "com.googlecode.eyesfree.brailleback.REFRESH_DISPLAY"
    }

    /**
     * 当前可见设置 fragment 的接口：Activity 把点显器连接状态推给当前
     * fragment 渲染 UI（分类切换会重建 fragment，但连接状态只保存在 Activity）。
     */
    interface ConnectionStatusUi {
        fun onConnectionStatusChanged(state: Int, progress: String?)
    }

    /** 点显器客户端：Activity 级持有。分类切换/返回只重建 fragment，
     *  不再 bind/unbind 点显器服务，避免连接抖动与断开感。 */
    private var mDisplay: DisplayClient? = null
    private var mConnectionState = Display.STATE_NOT_CONNECTED
    private var mConnectionProgress: String? = null
    /** 首次连接状态回调只更新 UI、不播报——避免每次打开设置都重复播报"已连接"。
     *  真实变化才播报（Activity 级客户端在分类切换时不会产生状态回调）。 */
    private var mFirstConnectionState = true

    /** 当前可见 fragment（用于推送连接状态渲染 UI）。 */
    var mConnectionStatusUi: ConnectionStatusUi? = null

    /** 当前点显器客户端（fragment 通过它 poll 等）。 */
    val displayClient: DisplayClient?
        get() = mDisplay

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 点显器客户端在 Activity 级创建：分类切换/返回只重建 fragment，
        // 不会再触发 bind/unbind 与服务端的连接状态抖动。
        val display = DisplayClient(this)
        mDisplay = display
        display.setOnConnectionStateChangeListener(this)
        display.setOnConnectionChangeProgressListener(this)
        supportFragmentManager.beginTransaction()
            .replace(android.R.id.content, SettingsFragment())
            .commit()
    }

    override fun onDestroy() {
        mDisplay?.shutdown()
        mDisplay = null
        mConnectionStatusUi = null
        super.onDestroy()
    }

    // ------------------------------------------------------------------
    // 点显器连接状态（Activity 级）
    // ------------------------------------------------------------------

    override fun onConnectionStateChanged(state: Int) {
        mConnectionState = state
        mConnectionStatusUi?.onConnectionStatusChanged(state, mConnectionProgress)
        if (mConnectionProgress == null) {
            val summary = connectionStateSummary(state)
            if (mFirstConnectionState) {
                // 首次（打开设置时收到真实状态）只更新 UI，不播报
                mFirstConnectionState = false
            } else {
                announceConnectionState(summary)
            }
        }
    }

    override fun onConnectionChangeProgress(description: String?) {
        mConnectionProgress = description
        mConnectionStatusUi?.onConnectionStatusChanged(mConnectionState, description)
        if (description == null) {
            // 进度结束：回到真实连接状态渲染
            onConnectionStateChanged(mConnectionState)
        } else {
            // The description is localized by the server.
            announceConnectionState(description)
        }
    }

    /** 把当前连接状态推给当前可见 fragment（fragment onResume 时调用）。 */
    fun refreshConnectionStatusUi() {
        mConnectionStatusUi?.onConnectionStatusChanged(mConnectionState, mConnectionProgress)
    }

    private fun connectionStateSummary(state: Int): CharSequence = when (state) {
        Display.STATE_CONNECTED -> getText(R.string.connstate_connected)
        Display.STATE_NOT_CONNECTED -> getText(R.string.connstate_not_connected)
        else -> getText(R.string.connstate_error)
    }

    private fun announceConnectionState(state: CharSequence) {
        window.decorView.announceForAccessibility(state)
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
        ConnectionStatusUi,
        TranslatorManager.OnTablesChangedListener,
        Preference.OnPreferenceChangeListener,
        Preference.OnPreferenceClickListener {

        /** Message from BrailleBackService, indicating that grade pref changed. */
        companion object {
            private const val REQUEST_BLUETOOTH_PERMISSIONS = 1
            private const val REQUEST_POST_NOTIFICATIONS = 2
        }

        private val mTableInfoComparator = TableInfoComparator()

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

            // 屏幕盲文键盘（移植自 TalkBack brailleime）。
            // 键盘设置页依赖 TalkBackForBrailleIme，后者在 BrailleBackService 连接时注入，
            // 因此服务未启用时先提示、不跳转，避免进入后空指针。
            findPreference<Preference>("pref_braille_keyboard_key")
                ?.setOnPreferenceClickListener {
                    if (BrailleBackService.getActiveInstance() == null) {
                        Toast.makeText(
                            requireContext(),
                            R.string.pref_braille_keyboard_need_service,
                            Toast.LENGTH_LONG,
                        ).show()
                    } else {
                        startActivity(
                            Intent(
                                requireActivity(),
                                com.google.android.accessibility.brailleime.settings
                                    .BrailleImePreferencesActivity::class.java,
                            )
                        )
                    }
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

            // 点显器客户端在 Activity 级持有（onCreate 创建、onDestroy 关闭）。
            // 这里只把自己注册为当前 UI 渲染目标，并渲染一次当前状态。
            val activity = requireActivity() as SettingsActivity
            activity.mConnectionStatusUi = this
            activity.refreshConnectionStatusUi()

            val translatorManager = TranslatorManager(requireActivity())
            mTranslatorManager = translatorManager
            translatorManager.addOnTablesChangedListener(this)

            refreshBrailleTypeSummary()
            ContextCompat.registerReceiver(
                requireContext(),
                mRefreshReceiver,
                IntentFilter(SettingsActivity.INTENT_REFRESH_DISPLAY),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
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
            val activity = requireActivity() as SettingsActivity
            if (activity.mConnectionStatusUi === this) {
                activity.mConnectionStatusUi = null
            }
            mTranslatorManager?.removeOnTablesChangedListener(this)
            mTranslatorManager?.shutdown()
            mTranslatorManager = null
        }

        private val mRefreshReceiver: BroadcastReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent) {
                if (TextUtils.equals(intent.action, SettingsActivity.INTENT_REFRESH_DISPLAY)) {
                    refreshBrailleTypeSummary()
                }
            }
        }

        // ------------------------------------------------------------------
        // 连接状态（UI 渲染；真实状态与播报由 Activity 级 DisplayClient 提供）
        // ------------------------------------------------------------------

        override fun onConnectionStatusChanged(state: Int, progress: String?) {
            val summary: CharSequence
            if (progress != null) {
                // The description is localized by the server.
                summary = progress
            } else {
                summary = when (state) {
                    Display.STATE_CONNECTED -> getText(R.string.connstate_connected)
                    Display.STATE_NOT_CONNECTED -> getText(R.string.connstate_not_connected)
                    else -> getText(R.string.connstate_error)
                }
            }
            mStatusPreference?.summary = summary
            findPreference<Preference>(getString(R.string.pref_key_bindings_key))
                ?.isEnabled = state == Display.STATE_CONNECTED
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
                    (requireActivity() as SettingsActivity).displayClient?.poll()
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
            val expected = ComponentName(context, BrailleBackService::class.java)
            val manager = context.getSystemService(Context.ACCESSIBILITY_SERVICE)
                as? AccessibilityManager ?: return false
            return try {
                manager.getEnabledAccessibilityServiceList(
                    AccessibilityServiceInfo.FEEDBACK_ALL_MASK
                ).any { info ->
                    val serviceInfo = info.resolveInfo?.serviceInfo
                    serviceInfo != null &&
                        ComponentName(serviceInfo.packageName, serviceInfo.name) == expected
                }
            } catch (e: SecurityException) {
                false
            }
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
                val missing = ArrayList<String>(2)
                if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                    != PackageManager.PERMISSION_GRANTED) {
                    missing.add(Manifest.permission.BLUETOOTH_CONNECT)
                }
                if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)
                    != PackageManager.PERMISSION_GRANTED) {
                    missing.add(Manifest.permission.BLUETOOTH_SCAN)
                }
                if (missing.isNotEmpty()) {
                    requestPermissions(
                        missing.toTypedArray(),
                        REQUEST_BLUETOOTH_PERMISSIONS)
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
            if (requestCode == REQUEST_BLUETOOTH_PERMISSIONS) {
                val context = requireActivity()
                if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                    == PackageManager.PERMISSION_GRANTED) {
                    // CONNECT just granted: re-scan for connected displays.
                    (context as SettingsActivity).displayClient?.poll()
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
