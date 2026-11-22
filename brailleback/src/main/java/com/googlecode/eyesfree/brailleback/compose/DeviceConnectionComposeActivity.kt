/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 *
 * Compose 迁移试点屏：「设备与连接」子屏。
 *
 * 边界：只迁移这一个子屏。其余四个子屏仍由 SettingsActivity 的
 * PreferenceFragmentCompat 渲染；主屏入口与导航层级完全不变。
 * 三个 SharedPreferences 键与原 XML 设置页一致，读写同一个文件，
 * 因此两代 UI 可以并存、数据不丢。
 */

package com.googlecode.eyesfree.brailleback.compose

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.googlecode.eyesfree.utils.SettingsStore
import com.googlecode.eyesfree.braille.display.DeviceListEntry
import com.googlecode.eyesfree.braille.display.Display
import com.googlecode.eyesfree.braille.display.DisplayClient
import com.googlecode.eyesfree.braille.service.display.ConnectionProtocolPolicy
import com.googlecode.eyesfree.brailleback.R

/** 一次性收集好的中文文案，让 Screen 保持无 Context 依赖。 */
internal data class DeviceConnectionLabels(
    val title: String,
    val back: String,
    val section: String,
    val statusTitle: String,
    val refreshHint: String,
    val protocolTitle: String,
    val chooseHint: String,
    val dialogTitle: String,
    val cancel: String,
    val autoConnectTitle: String,
    val autoConnectSummary: String,
    val on: String,
    val off: String,
    val selected: String,
    val unselected: String,
    val protocolEntries: List<String>,
    val protocolValues: List<String>,
    val discoverTitle: String,
    val discoverHint: String,
    val discoverSearching: String,
    val discoverDenied: String,
    val bondedLabel: String,
    val unpairedLabel: String,
    val bondingLabel: String,
    val failedLabel: String,
    val timeoutLabel: String,
    val unknownName: String,
    val connectHint: String,
    val pairLabel: String,
    val retryLabel: String,
)

class DeviceConnectionComposeActivity : AppCompatActivity(),
    Display.OnConnectionStateChangeListener,
    Display.OnConnectionChangeProgressListener {

    private var displayClient: DisplayClient? = null

    /**
     * 蓝牙权限结果。判定沿用旧逻辑：只要 CONNECT 到手就重扫一次，
     * 不看「本次是不是刚授出」（原先也只查最终权限状态）。
     */
    private val bluetoothPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()) {
        refreshPermissionState()
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
            == PackageManager.PERMISSION_GRANTED) {
            displayClient?.poll()
        }
    }

    /** 通知权限只影响能否发提醒，不改变本页任何状态。 */
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()) { }
    /** 首次状态只更新 UI、不播报，避免每次进页面都重复念「已连接」。 */
    private var firstConnectionState = true

    // Compose 状态放在 Activity 层：连接回调来自服务，需要直接驱动重组。
    private var connectionState by mutableStateOf(Display.STATE_NOT_CONNECTED)
    private var progressText by mutableStateOf<String?>(null)
    private var protocolValue by mutableStateOf(ConnectionProtocolPolicy.BLUETOOTH)
    private var autoConnect by mutableStateOf(true)

    /** 服务端上报的候选设备：已配对 + 本轮发现到的未配对。 */
    private var candidates by mutableStateOf<List<DeviceListEntry>>(emptyList())

    /** 用户主动查找是否正在进行（本地一次性窗口的界面态）。 */
    private var searching by mutableStateOf(false)

    /** BLUETOOTH_SCAN 运行时权限是否到手；缺失时查找入口降级为系统设置提示。 */
    private var scanPermissionGranted by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 主题沿用 BraillebackSettingsTheme（AppCompat DayNight），但标题栏交给
        // Compose 的 TopAppBar，避免两个标题栏叠加、读屏重复朗读。
        supportActionBar?.hide()

        val prefs = SettingsStore.get(this)
        val protocolKey = getString(R.string.pref_connection_protocol_key)
        val autoConnectKey = getString(R.string.pref_auto_connect_key)

        // 与 SettingsFragment.migrateConnectionProtocolPreference 完全同一策略：
        // 旧版六种协议值归一到「蓝牙 / USB」，并回写，避免两代 UI 读到不同值。
        val rawProtocol = prefs.getString(protocolKey, null)
        val normalized = DeviceConnectionLogic.normalizeProtocol(rawProtocol)
        if (rawProtocol != normalized) {
            prefs.edit().putString(protocolKey, normalized).apply()
        }
        protocolValue = normalized
        autoConnect = prefs.getBoolean(
            autoConnectKey, resources.getBoolean(R.bool.pref_auto_connect_default)
        )

        displayClient = DisplayClient(this).also { client ->
            client.setOnConnectionStateChangeListener(this)
            client.setOnConnectionChangeProgressListener(this)
            client.setOnDeviceListListener { entries ->
                candidates = entries
                // 列表回来了即视为本轮查找结束；超时/失败收尾也由服务端再推一次。
                searching = false
            }
        }

        setContent {
            val labels = DeviceConnectionLabels(
                title = getString(R.string.pref_category_device_title),
                back = getString(R.string.compose_settings_back),
                section = getString(R.string.compose_settings_section_connection),
                statusTitle = getString(R.string.pref_connection_status_title),
                refreshHint = getString(R.string.compose_settings_refresh),
                protocolTitle = getString(R.string.pref_connection_protocol_title),
                chooseHint = getString(R.string.compose_settings_choose),
                dialogTitle = getString(R.string.compose_settings_protocol_dialog_title),
                cancel = getString(R.string.compose_settings_cancel),
                autoConnectTitle = getString(R.string.pref_auto_connect_title),
                autoConnectSummary = getString(R.string.pref_auto_connect_summary),
                on = getString(R.string.compose_settings_on),
                off = getString(R.string.compose_settings_off),
                selected = getString(R.string.compose_settings_selected),
                unselected = getString(R.string.compose_settings_unselected),
                protocolEntries = resources.getStringArray(
                    R.array.pref_connection_protocol_entries
                ).toList(),
                protocolValues = resources.getStringArray(
                    R.array.pref_connection_protocol_values
                ).toList(),
                discoverTitle = getString(R.string.compose_settings_discover_title),
                discoverHint = getString(R.string.compose_settings_discover_hint),
                discoverSearching = getString(R.string.compose_settings_discover_searching),
                discoverDenied = getString(R.string.compose_settings_discover_denied),
                bondedLabel = getString(R.string.compose_settings_device_bonded),
                unpairedLabel = getString(R.string.compose_settings_device_unpaired),
                bondingLabel = getString(R.string.compose_settings_device_bonding),
                failedLabel = getString(R.string.compose_settings_device_failed),
                timeoutLabel = getString(R.string.compose_settings_device_timeout),
                unknownName = getString(R.string.compose_settings_device_unknown),
                connectHint = getString(R.string.compose_settings_device_connect),
                pairLabel = getString(R.string.compose_settings_device_pair),
                retryLabel = getString(R.string.compose_settings_device_retry),
            )
            DeviceConnectionScreen(
                labels = labels,
                stateText = currentStateText(),
                protocolValue = protocolValue,
                autoConnect = autoConnect,
                candidates = candidates,
                searching = searching,
                discoveryEnabled = DeviceConnectionLogic.canStartDiscovery(scanPermissionGranted),
                onRefresh = { displayClient?.poll() },
                onProtocolSelected = { value ->
                    protocolValue = value
                    prefs.edit().putString(protocolKey, value).apply()
                },
                onAutoConnectChange = { value ->
                    autoConnect = value
                    prefs.edit().putBoolean(autoConnectKey, value).apply()
                },
                onStartDiscovery = { startUserDiscovery() },
                onCandidateClicked = { entry -> onCandidateClicked(entry) },
                onBack = { finish() },
            )
        }
    }

    override fun onResume() {
        super.onResume()
        refreshPermissionState()
        requestBluetoothPermissions()
    }

    /**
     * 用户点「查找点显器」。扫描权限到手才下发服务端扫描；权限缺失时重新走
     * 运行时申请，被用户拒绝后界面降级为提示去系统蓝牙设置配对。
     */
    private fun startUserDiscovery() {
        if (!DeviceConnectionLogic.canStartDiscovery(scanPermissionGranted)) {
            requestBluetoothPermissions()
            return
        }
        searching = true
        displayClient?.startUserDiscovery()
    }

    private fun onCandidateClicked(entry: DeviceListEntry) {
        when (entry.state) {
            DeviceListEntry.STATE_BONDED -> displayClient?.poll()
            // 未配对 / 失败 / 超时都走配对入口；配对中由服务端忽略重复点击。
            else -> displayClient?.pairDevice(entry.address)
        }
    }

    private fun refreshPermissionState() {
        scanPermissionGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) ==
                PackageManager.PERMISSION_GRANTED
    }

    override fun onDestroy() {
        displayClient?.shutdown()
        displayClient = null
        super.onDestroy()
    }

    // -------------------------------------------------------------- 连接状态

    private fun currentStateText(): String = DeviceConnectionLogic.resolveStateText(
        state = connectionState,
        progress = progressText,
        connected = getString(R.string.connstate_connected),
        notConnected = getString(R.string.connstate_not_connected),
        unavailable = getString(R.string.connstate_error),
    )

    override fun onConnectionStateChanged(state: Int) {
        connectionState = state
        if (progressText != null) return
        val text = currentStateText()
        if (firstConnectionState) {
            firstConnectionState = false
        } else {
            window.decorView.announceForAccessibility(text)
        }
    }

    override fun onConnectionChangeProgress(description: String?) {
        progressText = description
        if (description == null) {
            onConnectionStateChanged(connectionState)
        } else {
            window.decorView.announceForAccessibility(description)
        }
    }

    // -------------------------------------------------------------- 蓝牙权限

    private fun requestBluetoothPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val missing = buildList {
                if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                    != PackageManager.PERMISSION_GRANTED) {
                    add(Manifest.permission.BLUETOOTH_CONNECT)
                }
                if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)
                    != PackageManager.PERMISSION_GRANTED) {
                    add(Manifest.permission.BLUETOOTH_SCAN)
                }
            }
            if (missing.isNotEmpty()) {
                bluetoothPermissionLauncher.launch(missing.toTypedArray())
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(
                Manifest.permission.POST_NOTIFICATIONS
            )
        }
    }
}
