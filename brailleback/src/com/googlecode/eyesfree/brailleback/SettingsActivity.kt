/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 *
 * 设置主屏（Compose 宿主）。
 *
 * 这是设置界面里最后一处旧 preference 界面：此前主屏靠 settings.xml +
 * Preference 点击监听跳子屏，fragment 只用来承载一屏导航，代价是导航层级、 * 标题栏与 back stack 三处状态彼此耦合（子屏标题要靠 fragment backStack 反推）。
 * 改成 Compose 后主屏就是一个列表 + 一张「key → 目标 Activity」接线表：
 * 没有 fragment、没有 back stack，每个子屏都是独立 Activity，标题由各自的
 * TopAppBar 播报。
 *
 * 保持不变的三件事：
 * 1. 点显器连接状态在 Activity 级监听与播报（切子屏不重建客户端，连接不抖）；
 * 2. 蓝牙 / 通知权限申请时机仍在 onResume；
 * 3. 偏好键与旧 settings.xml 的 app:key 完全一致（见 SettingsHomeCatalog），
 *    老用户设置不丢，服务端读键没有任何变化。
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
import android.view.accessibility.AccessibilityManager
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.preference.PreferenceManager
import com.googlecode.eyesfree.braille.display.Display
import com.googlecode.eyesfree.braille.display.DisplayClient
import com.googlecode.eyesfree.braille.service.display.ConnectionProtocolPolicy
import com.googlecode.eyesfree.braille.service.display.UsbDisplayConnection
import com.googlecode.eyesfree.brailleback.compose.BrailleDisplayComposeActivity
import com.googlecode.eyesfree.brailleback.compose.DeviceConnectionComposeActivity
import com.googlecode.eyesfree.brailleback.compose.DeveloperOptionsComposeActivity
import com.googlecode.eyesfree.brailleback.compose.HomeAction
import com.googlecode.eyesfree.brailleback.compose.HomeEntry
import com.googlecode.eyesfree.brailleback.compose.HomeRowUi
import com.googlecode.eyesfree.brailleback.compose.HomeSection
import com.googlecode.eyesfree.brailleback.compose.HomeSectionUi
import com.googlecode.eyesfree.brailleback.compose.KeysGesturesComposeActivity
import com.googlecode.eyesfree.brailleback.compose.SettingsHomeCatalog
import com.googlecode.eyesfree.brailleback.compose.SettingsHomeScreen
import com.googlecode.eyesfree.brailleback.compose.SoundBehaviorComposeActivity
import com.googlecode.eyesfree.brailleback.compose.StabilityComposeActivity
import com.googlecode.eyesfree.brailleback.utils.LogFileWriter
import com.googlecode.eyesfree.utils.LogUtils

class SettingsActivity : AppCompatActivity(),
    Display.OnConnectionStateChangeListener,
    Display.OnConnectionChangeProgressListener {

    /** Message from BrailleBackService, indicating that grade pref changed. */
    companion object {
        const val INTENT_REFRESH_DISPLAY =
            "com.googlecode.eyesfree.brailleback.REFRESH_DISPLAY"
    }

    /** 点显器客户端：Activity 级持有，连接状态在这里播报。 */
    private var mDisplay: DisplayClient? = null
    private var mConnectionState = Display.STATE_NOT_CONNECTED
    private var mConnectionProgress: String? = null
    /** 首次连接状态回调只更新状态、不播报——避免每次打开设置都重复播报"已连接"。 */
    private var mFirstConnectionState = true

    /** 无障碍服务是否已启用：主屏第一行的摘要跟着它变，回到前台要重算。 */
    private var mServiceEnabled by mutableStateOf(false)

    private val bluetoothPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()) {
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
            == PackageManager.PERMISSION_GRANTED) {
            mDisplay?.poll()
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()) { }

    /** 分类入口的接线表：偏好键的实际取值 → 目标 Activity。 */
    private val categoryTargets: Map<String, Class<*>> by lazy {
        listOf(
            // 六个分类入口跳各自的 Compose 页。
            wireCategory(R.string.pref_category_device_key, DeviceConnectionComposeActivity::class.java),
            wireCategory(R.string.pref_category_keys_key, KeysGesturesComposeActivity::class.java),
            wireCategory(R.string.pref_category_braille_key, BrailleDisplayComposeActivity::class.java),
            wireCategory(R.string.pref_category_sound_behavior_key, SoundBehaviorComposeActivity::class.java),
            wireCategory(R.string.pref_category_developer_key, DeveloperOptionsComposeActivity::class.java),
            wireCategory(R.string.pref_category_stability_key, StabilityComposeActivity::class.java),
            // 屏幕盲文键盘（移植自 TalkBack brailleime）不走这张表：它需要先判断服务是否
            // 已启用，见 openBrailleKeyboardSettings()。
        ).toMap()
    }

    private fun wireCategory(keyRes: Int, target: Class<*>): Pair<String, Class<*>> =
        getString(keyRes) to target

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 标题交给 Compose 的 TopAppBar，避免两个标题栏叠加、读屏重复朗读。
        supportActionBar?.hide()

        // 文件日志：无 adb 环境也能导出日志反馈问题。
        LogFileWriter.init(this)
        migrateConnectionProtocolPreference()

        val display = DisplayClient(this)
        mDisplay = display
        display.setOnConnectionStateChangeListener(this)
        display.setOnConnectionChangeProgressListener(this)

        mServiceEnabled = isServiceEnabled()

        setContent {
            SettingsHomeScreen(
                title = getString(R.string.brailleback_setup_title),
                sections = homeSections(),
                openHint = getString(R.string.compose_settings_open_hint),
                onOpen = ::onEntryClicked,
            )
        }
    }

    override fun onResume() {
        super.onResume()
        // 用户回到本界面说明本人在场，允许下一次连接再弹一次 USB 授权框。
        UsbDisplayConnection.allowPermissionPromptAgain()
        requestBluetoothPermissions()
        // 用户可能刚从系统无障碍设置里启用/停用服务，摘要必须跟着变。
        mServiceEnabled = isServiceEnabled()
    }

    override fun onDestroy() {
        mDisplay?.shutdown()
        mDisplay = null
        super.onDestroy()
    }

    // ------------------------------------------------------------------
    // 主屏数据与点击
    // ------------------------------------------------------------------

    private fun homeSections(): List<HomeSectionUi> = HomeSection.entries.map { section ->
        HomeSectionUi(
            title = getString(section.titleRes),
            rows = SettingsHomeCatalog.entriesOf(section).map { entry ->
                HomeRowUi(
                    key = entry.key,
                    title = getString(entry.titleRes),
                    summary = summaryOf(entry),
                )
            },
        )
    }

    private fun summaryOf(entry: HomeEntry): String =
        if (entry.dynamicSummary && mServiceEnabled) {
            getString(R.string.pref_setup_service_enabled)
        } else {
            getString(entry.summaryRes)
        }

    private fun onEntryClicked(key: String) {
        val entry = SettingsHomeCatalog.entryOf(key) ?: return
        when (val action = entry.action) {
            is HomeAction.Screen -> {
                categoryTargets[getString(action.keyRes)]
                    ?.let { startActivity(Intent(this, it)) }
            }
            HomeAction.Bookshelf ->
                startActivity(Intent(this, BookshelfActivity::class.java))
            // 启用服务引导：直接进系统无障碍设置，用户自己找到本应用。
            HomeAction.AccessibilitySettings ->
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            // 屏幕盲文键盘（移植自 TalkBack brailleime）。
            // 键盘设置页依赖 TalkBackForBrailleIme，后者在 BrailleBackService 连接时注入，
            // 因此服务未启用时先提示、不跳转，避免进入后空指针。
            HomeAction.BrailleKeyboard -> openBrailleKeyboardSettings()
            HomeAction.Licenses -> startActivity(
                WebViewDialog.getIntent(
                    this, R.string.pref_os_license_title, "file:///android_asset/licenses.html"
                )
            )
        }
    }

    private fun openBrailleKeyboardSettings() {
        if (BrailleBackService.getActiveInstance() == null) {
            Toast.makeText(
                this, R.string.pref_braille_keyboard_need_service, Toast.LENGTH_LONG
            ).show()
        } else {
            startActivity(
                Intent(
                    this,
                    com.google.android.accessibility.brailleime.settings
                        .BrailleImePreferencesActivity::class.java,
                )
            )
        }
    }

    // ------------------------------------------------------------------
    // 旧协议值归一（与 DeviceConnectionComposeActivity 同一策略）
    // ------------------------------------------------------------------

    /** 旧版六种协议值归一到「蓝牙 / USB」，避免两处读到不同值。 */
    private fun migrateConnectionProtocolPreference() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val key = getString(R.string.pref_connection_protocol_key)
        val value = prefs.getString(key, null)
        val normalized = ConnectionProtocolPolicy.normalize(value)
        if (value != normalized) {
            prefs.edit().putString(key, normalized).apply()
        }
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
                window.decorView.announceForAccessibility(summary)
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
            window.decorView.announceForAccessibility(description)
        }
    }

    private fun connectionStateSummary(state: Int): CharSequence = when (state) {
        Display.STATE_CONNECTED -> getText(R.string.connstate_connected)
        Display.STATE_NOT_CONNECTED -> getText(R.string.connstate_not_connected)
        else -> getText(R.string.connstate_error)
    }

    // ------------------------------------------------------------------
    // 启用服务状态
    // ------------------------------------------------------------------

    private fun isServiceEnabled(): Boolean {
        val expected = ComponentName(this, BrailleBackService::class.java)
        val manager = getSystemService(Context.ACCESSIBILITY_SERVICE)
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
            // 厂商限制查询时按「未启用」处理，但记一笔：否则用户只会看到摘要一直是
            // 「未启用」，日志里却什么都没有，排查时无从下手。
            LogUtils.logWithTag(android.util.Log.WARN, "SettingsActivity", "%s", e.toString())
            false
        }
    }

    // ------------------------------------------------------------------
    // 蓝牙权限（Android 12+）
    // ------------------------------------------------------------------

    private fun requestBluetoothPermissions() {
        if (Build.VERSION.SDK_INT >= 31) {
            val missing = ArrayList<String>(2)
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
                missing.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)
                != PackageManager.PERMISSION_GRANTED) {
                missing.add(Manifest.permission.BLUETOOTH_SCAN)
            }
            if (missing.isNotEmpty()) {
                bluetoothPermissionLauncher.launch(missing.toTypedArray())
            }
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(
                Manifest.permission.POST_NOTIFICATIONS
            )
        }
    }
}
