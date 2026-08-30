/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 *
 * 设置主页：多层级分类设置。
 * 使用 android.preference 老 API（PreferenceScreen 嵌套天然支持子页面导航），
 * 仅保留连接/盲文/声音/高级四类入口，移除音效自定义（方案编辑器）模块。
 */

package com.googlecode.eyesfree.brailleback

import android.Manifest
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.ComponentName
import android.content.DialogInterface
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.preference.ListPreference
import android.provider.Settings
import android.preference.Preference
import android.preference.PreferenceActivity
import android.preference.PreferenceManager
import android.text.TextUtils
import android.util.Log
import android.widget.SeekBar
import com.googlecode.eyesfree.braille.display.Display
import com.googlecode.eyesfree.braille.display.DisplayClient
import com.googlecode.eyesfree.braille.translate.TableInfo
import com.googlecode.eyesfree.brailleback.sound.SoundSchemeManager
import com.googlecode.eyesfree.brailleback.utils.PreferenceUtils
import com.googlecode.eyesfree.utils.LogUtils
import java.text.CollationKey
import java.text.Collator
import java.util.ArrayList
import java.util.Collections
import java.util.Comparator
import java.util.HashMap

/**
 * Activity used to set the braille service preferences.
 *
 * The layout is a multi-level, category-based tree:
 *   Device / Braille / Sound / Advanced
 * nested `PreferenceScreen`s provide sub-page navigation automatically.
 */
class BrailleBackPreferencesActivity : PreferenceActivity(),
    Display.OnConnectionStateChangeListener,
    Display.OnConnectionChangeProgressListener,
    TranslatorManager.OnTablesChangedListener,
    Preference.OnPreferenceChangeListener,
    Preference.OnPreferenceClickListener {

    /** Message from BrailleBackService, indicating that grade pref changed, so update display. */
    companion object {
        const val INTENT_REFRESH_DISPLAY =
            "com.googlecode.eyesfree.brailleback.REFRESH_DISPLAY"
        private const val REQUEST_BLUETOOTH_CONNECT = 1
        private const val REQUEST_POST_NOTIFICATIONS = 2
        private const val LOG_TAG = "BrailleBackPrefs"
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
    private var mBrailleReaderPreference: Preference? = null
    private var mConnectionState = Display.STATE_NOT_CONNECTED
    private var mConnectionProgress: String? = null
    private var mTables: List<TableInfo> = emptyList()

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestBluetoothPermissions()

        addPreferencesFromResource(R.xml.preferences)

        mStatusPreference = findPreferenceByResId(R.string.pref_connection_status_key)
        mStatusPreference?.setOnPreferenceClickListener(this)
        assignKeyBindingsIntent()

        mBrailleTypePreference =
            findPreferenceByResId(R.string.pref_braille_type_key) as ListPreference?
        mBrailleTypePreference?.setOnPreferenceChangeListener(this)
        mSixDotTablePreference =
            findPreferenceByResId(R.string.pref_six_dot_braille_table_key) as ListPreference?
        mSixDotTablePreference?.setOnPreferenceChangeListener(this)
        mEightDotTablePreference =
            findPreferenceByResId(R.string.pref_eight_dot_braille_table_key) as ListPreference?
        mEightDotTablePreference?.setOnPreferenceChangeListener(this)

        mOverlayPreference = findPreferenceByResId(R.string.pref_braille_overlay_key)
        mOverlayPreference?.setOnPreferenceChangeListener(this)

        mOverlayTutorialPreference =
            findPreferenceByResId(R.string.pref_braille_overlay_tutorial_key)
        mOverlayTutorialPreference?.setOnPreferenceClickListener(this)

        mLicensesPreference = findPreferenceByResId(R.string.pref_os_license_key)
        mLicensesPreference?.setOnPreferenceClickListener(this)

        mLogLevelPreference =
            findPreferenceByResId(R.string.pref_log_level_key) as ListPreference?
        mLogLevelPreference?.setOnPreferenceChangeListener(this)
        if (BuildConfig.DEBUG) {
            val logLevel = PreferenceUtils.getLogLevel(this)
            updateListPreferenceSummary(mLogLevelPreference, Integer.toString(logLevel))
            mLogLevelPreference?.isEnabled = false
        }

        mSoundVolumePreference = findPreferenceByResId(R.string.pref_sound_volume_key)
        mSoundVolumePreference?.setOnPreferenceClickListener(this)

        mSetupServicePreference = findPreferenceByResId(R.string.pref_setup_service_key)
        mSetupServicePreference?.setOnPreferenceClickListener(this)

        mBrailleReaderPreference = findPreferenceByResId(R.string.pref_braille_reader_key)
        mBrailleReaderPreference?.setOnPreferenceClickListener(this)
    }

    override fun onResume() {
        super.onResume()
        val display = DisplayClient(this)
        mDisplay = display
        display.setOnConnectionStateChangeListener(this)
        display.setOnConnectionChangeProgressListener(this)
        val translatorManager = TranslatorManager(this)
        mTranslatorManager = translatorManager
        translatorManager.addOnTablesChangedListener(this)
        onConnectionStateChanged(Display.STATE_NOT_CONNECTED)
        refreshPreferences()
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(
                mRefreshReceiver,
                IntentFilter(INTENT_REFRESH_DISPLAY),
                Context.RECEIVER_NOT_EXPORTED
            )
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(mRefreshReceiver, IntentFilter(INTENT_REFRESH_DISPLAY))
        }
        refreshSoundPreferences()
        updateSetupServicePreference()
    }

    private val mRefreshReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent) {
            if (TextUtils.equals(intent.action, INTENT_REFRESH_DISPLAY)) {
                refreshPreferences()
            }
        }
    }

    /** Refresh displayed preference value for braille type. */
    private fun refreshPreferences() {
        val sharedPrefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
        val prefKey = getString(R.string.pref_braille_type_key)
        val prefDefault = getString(R.string.pref_braille_type_six_dot_value)
        val prefValue = sharedPrefs.getString(prefKey, prefDefault)
        updateListPreferenceSummary(mBrailleTypePreference, prefValue)
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(mRefreshReceiver)
        mTranslatorManager?.removeOnTablesChangedListener(this)
        mTranslatorManager?.shutdown()
        mTranslatorManager = null
        mDisplay?.shutdown()
        mDisplay = null
    }

    override fun onConnectionStateChanged(state: Int) {
        mConnectionState = state
        val summary: CharSequence
        var enableBindings = false
        when (state) {
            Display.STATE_CONNECTED -> {
                summary = getText(R.string.connstate_connected)
                enableBindings = true
            }
            Display.STATE_NOT_CONNECTED -> summary = getText(R.string.connstate_not_connected)
            else -> summary = getText(R.string.connstate_error)
        }
        val bindingsPref = findPreferenceByResId(R.string.pref_key_bindings_key)
        bindingsPref?.isEnabled = enableBindings
        if (mConnectionProgress == null) {
            mStatusPreference?.summary = summary
            announceConnectionState(summary)
        }
    }

    override fun onConnectionChangeProgress(description: String?) {
        mConnectionProgress = description
        if (description == null) {
            onConnectionStateChanged(mConnectionState)
            return
        }
        // The description is localized by the server.
        mStatusPreference?.summary = description
        announceConnectionState(description)
    }

    private fun announceConnectionState(state: CharSequence) {
        window.decorView.announceForAccessibility(state)
    }

    override fun onTablesChanged() {
        mTables = mTranslatorManager?.getTranslatorClient()?.getTables() ?: emptyList()
        addTableList(mSixDotTablePreference, false)
        addTableList(mEightDotTablePreference, true)
    }

    /** Assigns the appropriate intent to the key bindings preference. */
    private fun assignKeyBindingsIntent() {
        val pref = findPreferenceByResId(R.string.pref_key_bindings_key)
        val intent = Intent(this, KeyBindingsActivity::class.java)
        pref?.intent = intent
    }

    @Suppress("DEPRECATION")
    private fun findPreferenceByResId(resId: Int): Preference? {
        return findPreference(getString(resId))
    }

    private fun addTableList(pref: ListPreference?, eightDot: Boolean) {
        if (pref == null || mTranslatorManager == null) {
            return
        }
        val tables = ArrayList<TableInfo>()
        for (info in mTables) {
            if (eightDot == info.isEightDot()) {
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
            entryValues[index] = info.getId()
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
        val localeDisplayName = tableInfo.getLocale().getDisplayName()
        if (tableInfo.isEightDot()) {
            // Computer braille is obvious from context.
            return localeDisplayName
        }
        // Chinese braille is referred to by its system name (e.g. 国家通用盲文方案).
        if ("zh".equals(tableInfo.getLocale().getLanguage(), ignoreCase = true)) {
            if (TextUtils.isEmpty(tableInfo.getVariant())) {
                return localeDisplayName
            }
            return getString(
                R.string.table_name_variant, localeDisplayName, tableInfo.getVariant())
        }
        val related = mTranslatorManager!!.getRelatedTables(tableInfo)
        var gradeCount = 0
        for (relatedInfo in related) {
            if (relatedInfo.isEightDot()) {
                continue
            }
            ++gradeCount
        }
        return if (gradeCount <= 1) {
            if (TextUtils.isEmpty(tableInfo.getVariant())) {
                localeDisplayName
            } else {
                getString(R.string.table_name_variant,
                    localeDisplayName, tableInfo.getVariant())
            }
        } else {
            if (TextUtils.isEmpty(tableInfo.getVariant())) {
                getString(R.string.table_name_grade,
                    localeDisplayName, tableInfo.getGrade())
            } else {
                getString(R.string.table_name_variant_grade,
                    localeDisplayName, tableInfo.getVariant(), tableInfo.getGrade())
            }
        }
    }

    override fun onPreferenceChange(preference: Preference?, newValue: Any?): Boolean {
        if (preference == null) {
            return false
        }
        // Always update the summary based on the list preference value.
        if (preference is ListPreference) {
            val updated = updateListPreferenceSummary(
                preference as ListPreference?, newValue as? String)
            if (!updated) {
                return false
            }
        }

        // If the overlay was turned on for the first time, launch the tutorial.
        if (preference === mOverlayPreference && newValue == true) {
            OverlayTutorialActivity.startIfFirstTime(this)
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

    override fun onPreferenceClick(pref: Preference?): Boolean {
        when (pref) {
            mStatusPreference -> {
                mDisplay?.poll()
                return true
            }
            mOverlayTutorialPreference -> {
                startActivity(Intent(this, OverlayTutorialActivity::class.java))
                return true
            }
            mLicensesPreference -> {
                val intent = WebViewDialog.getIntent(
                    this, R.string.pref_os_license_title,
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
            mBrailleReaderPreference -> {
                startActivity(Intent(this, BrailleReaderActivity::class.java))
                return true
            }
        }
        return false
    }

    // ------------------------------------------------------------------
    // Sound preferences (master switch + volume only)
    // ------------------------------------------------------------------

    @Suppress("DEPRECATION")
    private fun refreshSoundPreferences() {
        val manager = SoundSchemeManager.getInstance(this)
        val volumePreference = mSoundVolumePreference ?: return
        volumePreference.summary = getString(R.string.sound_editor_volume, manager.getVolume())
    }

    /**
     * Shows or hides the service-setup guide at the top of the settings screen.
     * The guide is only visible while the braille accessibility service is off,
     * and reminds the user what to do if Android blocks enabling it ("Restricted
     * settings" on Android 13+).
     */
    private fun updateSetupServicePreference() {
        val pref = mSetupServicePreference ?: return
        pref.summary = if (isServiceEnabled()) {
            getString(R.string.pref_setup_service_enabled)
        } else {
            getString(R.string.pref_setup_service_summary)
        }
    }

    /** Whether the BrailleBack accessibility service is currently enabled. */
    private fun isServiceEnabled(): Boolean {
        val expected = ComponentName(this, BrailleBackService::class.java).flattenToString()
        val enabled = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
        return enabled.split(':').contains(expected)
    }

    /** Open system Accessibility settings so the user can enable the service. */
    private fun openAccessibilitySettings() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    @Suppress("DEPRECATION")
    private fun showVolumeDialog() {
        val manager = SoundSchemeManager.getInstance(this)
        val seekBar = SeekBar(this)
        seekBar.max = 100
        seekBar.progress = manager.getVolume()
        AlertDialog.Builder(this)
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
            if (ret == 0 && first.isEightDot() != second.isEightDot()) {
                ret = if (first.isEightDot()) 1 else -1
            }
            if (ret == 0) {
                ret = first.getGrade() - second.getGrade()
            }
            return ret
        }

        private fun getCollationKey(tableInfo: TableInfo): CollationKey {
            return mCollationKeyMap.getOrPut(tableInfo) {
                mCollator.getCollationKey(tableInfo.getLocale().getDisplayName())
            }
        }

        companion object {
            private const val KEY_MAP_INITIAL_CAPACITY = 50
        }
    }

    /** Requests Bluetooth runtime permissions required on Android 12+ (API 31+). */
    private fun requestBluetoothPermissions() {
        if (Build.VERSION.SDK_INT >= 31) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(
                    arrayOf(Manifest.permission.BLUETOOTH_CONNECT),
                    REQUEST_BLUETOOTH_CONNECT)
            }
        }
        // The connection stays alive as a foreground service whose
        // notification is only visible with this grant (API 33+).
        // Declined is fine: the FGS keeps running, the notification is
        // just hidden behind the Task Manager entry.
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
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
            if (!granted) {
                AlertDialog.Builder(this)
                    .setTitle(R.string.permission_bluetooth_title)
                    .setMessage(R.string.permission_bluetooth_message)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            } else {
                // Permission just granted: re-scan for connected displays.
                mDisplay?.poll()
            }
        }
    }

    override fun isValidFragment(fragmentName: String?): Boolean {
        return false
    }
}
