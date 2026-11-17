/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 *
 * 设置页（androidx.preference）：只保留主屏导航。
 *
 * 信息架构：主屏按「开始使用 / 设置分类 / 功能与内容」分组，设置分类下是
 * 设备与连接、按键与手势、盲文显示、声音与行为、开发者选项五个平级入口。
 * 五个子屏的内容全部由 compose 包下的独立 Activity 渲染，本文件不再持有任何
 * 旧子屏的偏好绑定；旧实现归档在 docs/归档-旧设置界面/，可随时按 README 回退。
 *
 * 数据层与迁移前共用同一个 SharedPreferences（key 完全一致），值不丢失。
 * 服务读取的偏好键由 res/values/donottranslate.xml 定义，与界面项无关。
 */

package com.googlecode.eyesfree.brailleback

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.MenuItem
import android.view.accessibility.AccessibilityManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.googlecode.eyesfree.braille.display.Display
import com.googlecode.eyesfree.braille.display.DisplayClient
import com.googlecode.eyesfree.braille.service.display.ConnectionProtocolPolicy
import com.googlecode.eyesfree.braille.service.display.UsbDisplayConnection
import com.googlecode.eyesfree.brailleback.compose.BrailleDisplayComposeActivity
import com.googlecode.eyesfree.brailleback.compose.DeviceConnectionComposeActivity
import com.googlecode.eyesfree.brailleback.compose.DeveloperOptionsComposeActivity
import com.googlecode.eyesfree.brailleback.compose.KeysGesturesComposeActivity
import com.googlecode.eyesfree.brailleback.compose.SoundBehaviorComposeActivity
import com.googlecode.eyesfree.brailleback.compose.StabilityComposeActivity
import com.googlecode.eyesfree.brailleback.utils.LogFileWriter

class SettingsActivity : AppCompatActivity(),
    Display.OnConnectionStateChangeListener,
    Display.OnConnectionChangeProgressListener {

    /** Message from BrailleBackService, indicating that grade pref changed. */
    companion object {
        const val INTENT_REFRESH_DISPLAY =
            "com.googlecode.eyesfree.brailleback.REFRESH_DISPLAY"
    }

    /** 点显器客户端：Activity 级持有，连接状态在这里播报，不随分类切换抖动。 */
    private var mDisplay: DisplayClient? = null
    private var mConnectionState = Display.STATE_NOT_CONNECTED
    private var mConnectionProgress: String? = null
    /** 首次连接状态回调只更新状态、不播报——避免每次打开设置都重复播报"已连接"。 */
    private var mFirstConnectionState = true

    /** 上一次主动播报过的子屏标题，避免同一屏重复朗读。 */
    private var mLastAnnouncedScreenTitle: CharSequence? = null

    /** 当前点显器客户端（已迁移的 Compose 子屏通过它 poll 等）。 */
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

    /** 点击标题栏返回箭头时回到上一级子屏；主屏无返回箭头，交给系统。 */
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home &&
            supportFragmentManager.backStackEntryCount > 0
        ) {
            supportFragmentManager.popBackStack()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    /** 进入子屏时主动朗读子屏标题，让读屏用户知道当前所在分类。 */
    fun announceSubScreenTitle(title: CharSequence) {
        if (title.isEmpty() || title == mLastAnnouncedScreenTitle) {
            return
        }
        mLastAnnouncedScreenTitle = title
        window.decorView.announceForAccessibility(title)
    }

    override fun onDestroy() {
        mDisplay?.shutdown()
        mDisplay = null
        super.onDestroy()
    }

    // ------------------------------------------------------------------
    // 点显器连接状态（Activity 级播报）
    // ------------------------------------------------------------------

    override fun onConnectionStateChanged(state: Int) {
        mConnectionState = state
        if (mConnectionProgress == null) {
            val summary = connectionStateSummary(state)
            if (mFirstConnectionState) {
                // 首次（打开设置时收到真实状态）只记状态，不播报
                mFirstConnectionState = false
            } else {
                announceConnectionState(summary)
            }
        }
    }

    override fun onConnectionChangeProgress(description: String?) {
        mConnectionProgress = description
        if (description == null) {
            // 进度结束：回到真实连接状态
            onConnectionStateChanged(mConnectionState)
        } else {
            // The description is localized by the server.
            announceConnectionState(description)
        }
    }

    private fun connectionStateSummary(state: Int): CharSequence = when (state) {
        Display.STATE_CONNECTED -> getText(R.string.connstate_connected)
        Display.STATE_NOT_CONNECTED -> getText(R.string.connstate_not_connected)
        else -> getText(R.string.connstate_error)
    }

    private fun announceConnectionState(state: CharSequence) {
        window.decorView.announceForAccessibility(state)
    }

    class SettingsFragment : PreferenceFragmentCompat(),
        Preference.OnPreferenceClickListener {

        /** 蓝牙权限结果：CONNECT 到手就重扫一次已连接点显器。 */
        private val bluetoothPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()) {
            activity?.let { host ->
                if (host.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                    == PackageManager.PERMISSION_GRANTED) {
                    (host as? SettingsActivity)?.displayClient?.poll()
                }
            }
        }

        /** 通知权限只影响提醒，不改变主屏任何状态。 */
        private val notificationPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestPermission()) { }

        private var mLicensesPreference: Preference? = null
        private var mSetupServicePreference: Preference? = null

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            migrateConnectionProtocolPreference()
            setPreferencesFromResource(R.xml.settings, rootKey)
        }

        /** 旧版六种协议值合并为“蓝牙”和“USB”两项。 */
        private fun migrateConnectionProtocolPreference() {
            val prefs = androidx.preference.PreferenceManager
                .getDefaultSharedPreferences(requireContext())
            val key = getString(R.string.pref_connection_protocol_key)
            val value = prefs.getString(key, null)
            val normalized = ConnectionProtocolPolicy.normalize(value)
            if (value != normalized) {
                prefs.edit().putString(key, normalized).apply()
            }
        }

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            // 文件日志：无 adb 环境也能导出日志反馈问题
            LogFileWriter.init(requireActivity())

            mLicensesPreference =
                findPreference<Preference>(getString(R.string.pref_os_license_key))
            mLicensesPreference?.setOnPreferenceClickListener(this)

            mSetupServicePreference =
                findPreference<Preference>(getString(R.string.pref_setup_service_key))
            mSetupServicePreference?.setOnPreferenceClickListener(this)

            // 六个分类入口跳各自的 Compose 页。
            // 这里用 Preference + 点击监听器，而不是空 PreferenceScreen：androidx 的
            // PreferenceScreen.onClick() 在「无 intent、无 fragment、无子项」时直接 return，
            // 点击会静默失败；而且它覆写了 onClick，setOnPreferenceClickListener 对它无效。
            wireCategory(
                R.string.pref_category_device_key,
                DeviceConnectionComposeActivity::class.java
            )
            wireCategory(
                R.string.pref_category_keys_key,
                KeysGesturesComposeActivity::class.java
            )
            wireCategory(
                R.string.pref_category_braille_key,
                BrailleDisplayComposeActivity::class.java
            )
            wireCategory(
                R.string.pref_category_sound_behavior_key,
                SoundBehaviorComposeActivity::class.java
            )
            wireCategory(
                R.string.pref_category_developer_key,
                DeveloperOptionsComposeActivity::class.java
            )
            // 稳定性与省电：无障碍服务被系统/厂商省电策略杀掉时点显器是静默断开的，
            // 这一屏给出四项实测状态和直达各设置页的入口。
            wireCategory(
                R.string.pref_category_stability_key,
                StabilityComposeActivity::class.java
            )

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
        }

        override fun onResume() {
            super.onResume()
            updateScreenTitle()
            // 用户回到本界面说明本人在场，允许下一次连接再弹一次 USB 授权框。
            UsbDisplayConnection.allowPermissionPromptAgain()
            requestBluetoothPermissions()
            updateSetupServicePreference()
        }

        /**
         * 子屏导航的读屏定位：进入分类子屏时把 ActionBar 标题改成该分类名，
         * 并显示返回箭头；回到主屏恢复应用标题、隐藏箭头。
         *
         * setPreferencesFromResource(R.xml.settings, rootKey) 会把 rootKey 对应的
         * PreferenceScreen 设为当前 preferenceScreen，因此它的 title 就是子屏标题；
         * 主屏根节点没有 title，据此区分两级，不需要额外维护标题栈。
         */
        private fun updateScreenTitle() {
            val host = requireActivity() as SettingsActivity
            val subScreenTitle = preferenceScreen?.title
            val isSubScreen = parentFragmentManager.backStackEntryCount > 0
            host.title = if (subScreenTitle.isNullOrEmpty()) {
                getString(R.string.brailleback_setup_title)
            } else {
                subScreenTitle
            }
            host.supportActionBar?.setDisplayHomeAsUpEnabled(isSubScreen)
            if (isSubScreen && !subScreenTitle.isNullOrEmpty()) {
                host.announceSubScreenTitle(subScreenTitle)
            }
        }

        /** 分类入口统一接线：点一下进入对应的 Compose 子屏 Activity。 */
        private fun wireCategory(keyRes: Int, target: Class<*>) {
            findPreference<Preference>(getString(keyRes))
                ?.setOnPreferenceClickListener {
                    startActivity(Intent(requireActivity(), target))
                    true
                }
        }

        override fun onPreferenceClick(preference: Preference): Boolean {
            when (preference) {
                mLicensesPreference -> {
                    val intent = WebViewDialog.getIntent(
                        requireActivity(), R.string.pref_os_license_title,
                        "file:///android_asset/licenses.html")
                    startActivity(intent)
                    return true
                }
                mSetupServicePreference -> {
                    openAccessibilitySettings()
                    return true
                }
            }
            return false
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
                    bluetoothPermissionLauncher.launch(missing.toTypedArray())
                }
            }
            if (Build.VERSION.SDK_INT >= 33 &&
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch(
                    Manifest.permission.POST_NOTIFICATIONS
                )
            }
        }
    }
}
