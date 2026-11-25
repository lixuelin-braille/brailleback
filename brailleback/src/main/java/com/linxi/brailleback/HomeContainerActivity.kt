/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 *
 * 应用主屏容器：底部导航承载「书籍阅读管理」与「设置」两个 Tab。
 *
 * 设置 Tab 复用原设置主屏的全部接线（点显器状态监听、蓝牙/通知权限申请时机、
 * 分类入口接线表、偏好键归一），行为与原 SettingsActivity 一致；阅读 Tab 内嵌
 * 原书架。子屏仍是各自独立 Activity，主屏没有 back stack。
 */

package com.linxi.brailleback

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
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
import com.googlecode.eyesfree.utils.SettingsStore
import com.googlecode.eyesfree.braille.display.Display
import com.googlecode.eyesfree.braille.display.DisplayClient
import com.googlecode.eyesfree.braille.service.display.ConnectionProtocolPolicy
import com.googlecode.eyesfree.braille.service.display.UsbDisplayConnection
import com.linxi.brailleback.compose.BookshelfController
import com.linxi.brailleback.compose.BrailleDisplayComposeActivity
import com.linxi.brailleback.compose.DeviceConnectionComposeActivity
import com.linxi.brailleback.compose.DeveloperOptionsComposeActivity
import com.linxi.brailleback.compose.HomeAction
import com.linxi.brailleback.compose.HomeContainerScreen
import com.linxi.brailleback.compose.HomeEntry
import com.linxi.brailleback.compose.HomeRowUi
import com.linxi.brailleback.compose.HomeSection
import com.linxi.brailleback.compose.HomeSectionUi
import com.linxi.brailleback.compose.HomeTab
import com.linxi.brailleback.compose.KeysGesturesComposeActivity
import com.linxi.brailleback.compose.SettingsHomeCatalog
import com.linxi.brailleback.compose.SoundBehaviorComposeActivity
import com.linxi.brailleback.compose.StabilityComposeActivity
import com.linxi.brailleback.utils.LogFileWriter
import com.googlecode.eyesfree.utils.LogUtils

class HomeContainerActivity : AppCompatActivity(),
    Display.OnConnectionStateChangeListener,
    Display.OnConnectionChangeProgressListener {

    companion object {
        /** 从兼容别名 SettingsActivity 转回时指定落到设置 Tab。 */
        const val EXTRA_TAB = "com.linxi.brailleback.extra.HOME_TAB"
        const val TAB_SETTINGS = "settings"
    }

    /** 点显器客户端：Activity 级持有。主设置页只被动显示状态，不主动播报。 */
    private var mDisplay: DisplayClient? = null
    private var mConnectionState = Display.STATE_NOT_CONNECTED

    /** 无障碍服务是否已启用：主屏第一行的摘要跟着它变，回到前台要重算。 */
    private var mServiceEnabled by mutableStateOf(false)

    /** 书架控制器：阅读 Tab 内嵌原书架。 */
    private lateinit var bookshelfController: BookshelfController

    /** 当前 Tab；从二级页返回后回到离开时的 Tab。 */
    private var selectedTab by mutableStateOf(HomeTab.DEFAULT)

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

        bookshelfController = BookshelfController(this)
        bookshelfController.refreshList()

        selectedTab = if (intent.getStringExtra(EXTRA_TAB) == TAB_SETTINGS) {
            HomeTab.SETTINGS
        } else {
            HomeTab.DEFAULT
        }

        setContent {
            HomeContainerScreen(
                initialTab = selectedTab,
                readingLabels = bookshelfController.labels(),
                readingEntries = bookshelfController.entries,
                readingDialog = bookshelfController.dialog,
                readingActions = bookshelfController.actions(),
                settingsTitle = getString(R.string.brailleback_setup_title),
                settingsSections = homeSections(),
                settingsOpenHint = getString(R.string.compose_settings_open_hint),
                tabLabels = HomeTab.entries.map { getString(it.titleRes) },
                selectedLabel = getString(R.string.compose_settings_selected),
                unselectedLabel = getString(R.string.compose_settings_unselected),
                onSettingsOpen = ::onEntryClicked,
                onTabSelected = ::announceTab,
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
        bookshelfController.refreshList()
        // 从阅读器回到书架（本界面的阅读 Tab）：把点显器清空。
        // 阅读器 onPause 交还了点显器，屏上还留着上一本书的点位，
        // 书架这边不该继续摸到那本书的正文。
        BrailleBackService.clearDisplayContent()
        Log.d("BrailleShelf", "home clear display")
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
            // 书架入口：直接切到阅读 Tab，不再单独跳 BookshelfActivity。
            HomeAction.Bookshelf -> selectedTab = HomeTab.READING
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

    private fun announceTab(tab: HomeTab) {
        window.decorView.announceForAccessibility(
            getString(R.string.home_tab_switched_announce, getString(tab.titleRes)))
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
        val prefs = SettingsStore.get(this)
        val key = getString(R.string.pref_connection_protocol_key)
        val value = prefs.getString(key, null)
        val normalized = ConnectionProtocolPolicy.normalize(value)
        if (value != normalized) {
            prefs.edit().putString(key, normalized).apply()
        }
    }

    // ------------------------------------------------------------------
    // 点显器连接状态（只更新界面，不播报）
    // ------------------------------------------------------------------

    override fun onConnectionStateChanged(state: Int) {
        // 主设置页只念"连上了"这一句；失败、扫描失败、驱动表不匹配之类只更新状态行，
        // 不再一条条念（后台自动连接会反复跳状态，逐条念就是"一打开就一串提示"）。
        val wasConnected = mConnectionState == Display.STATE_CONNECTED
        mConnectionState = state
        if (!wasConnected && state == Display.STATE_CONNECTED) {
            window.decorView.announceForAccessibility(getText(R.string.connstate_connected))
        }
    }

    override fun onConnectionChangeProgress(description: String?) {
        // 进度只影响状态行文案，不朗读。主设置页没有「连接」按钮，
        // 打开就自动连接的后台进度一旦逐条播报，用户一进设置就被灌一串提示。
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
            LogUtils.logWithTag(android.util.Log.WARN, "HomeContainerActivity", "%s", e.toString())
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
