/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.linxi.brailleback.compose

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityManager
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.googlecode.eyesfree.braille.display.Display
import com.googlecode.eyesfree.braille.display.DisplayClient
import com.googlecode.eyesfree.braille.service.display.ConnectionDiagnosticsState
import com.googlecode.eyesfree.braille.service.display.EXTRA_USB_WAKE_REASON
import com.linxi.brailleback.BrailleBackService
import com.linxi.brailleback.R

/**
 * 「连接诊断」页。取值口径：
 * <ul>
 *   <li>USB 设备列表 / VID/PID / 授权：[UsbManager] 实时枚举；</li>
 *   <li>链路路由、USB 驱动是否活着、最近失败原因：服务端进程内快照
 *       [ConnectionDiagnosticsState]；</li>
 *   <li>连接状态 / 进度：DisplayClient 回调；</li>
 *   <li>本应用无障碍服务是否启用：[AccessibilityManager] 查询已启用列表。</li>
 * </ul>
 * 页面不反向改任何连接状态；只读取与展示。
 */
class ConnectionDiagnosticsComposeActivity : AppCompatActivity(),
    Display.OnConnectionStateChangeListener,
    Display.OnConnectionChangeProgressListener {

    private var displayClient: DisplayClient? = null

    private var connectionState by mutableStateOf(Display.STATE_NOT_CONNECTED)
    private var progressText by mutableStateOf<String?>(null)

    private var devices by mutableStateOf<List<UsbDeviceDiagnostic>>(emptyList())
    private var route by mutableStateOf(ConnectionDiagnosticsLogic.ROUTE_NONE)
    private var failureReason by mutableStateOf<String?>(null)
    private var a11yEnabled by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()

        displayClient = DisplayClient(this).also { client ->
            client.setOnConnectionStateChangeListener(this)
            client.setOnConnectionChangeProgressListener(this)
        }

        setContent {
            val labels = DiagnosticsLabels(
                title = getString(R.string.conn_diag_title),
                back = getString(R.string.compose_settings_back),
                section = getString(R.string.conn_diag_section),
                stateTitle = getString(R.string.conn_diag_state_title),
                routeTitle = getString(R.string.conn_diag_route_title),
                routeUsb = getString(R.string.conn_diag_route_usb),
                routeBluetooth = getString(R.string.conn_diag_route_bluetooth),
                routeNone = getString(R.string.conn_diag_route_none),
                usbCountTitle = getString(R.string.conn_diag_usb_count_title),
                usbNoneHint = getString(R.string.conn_diag_usb_none_hint),
                deviceTitle = getString(R.string.conn_diag_device_title),
                permissionTitle = getString(R.string.conn_diag_permission_title),
                permissionGranted = getString(R.string.conn_diag_permission_granted),
                permissionDenied = getString(R.string.conn_diag_permission_denied),
                openTitle = getString(R.string.conn_diag_open_title),
                openActive = getString(R.string.conn_diag_open_active),
                openInactive = getString(R.string.conn_diag_open_inactive),
                failureTitle = getString(R.string.conn_diag_failure_title),
                failureNone = getString(R.string.conn_diag_failure_none),
                a11yTitle = getString(R.string.conn_diag_a11y_title),
                a11yEnabled = getString(R.string.conn_diag_a11y_enabled),
                a11yDisabled = getString(R.string.conn_diag_a11y_disabled),
                freezeHintTitle = getString(R.string.conn_diag_freeze_hint_title),
                freezeHint = getString(R.string.conn_diag_freeze_hint),
                refreshTitle = getString(R.string.conn_diag_refresh_title),
                refreshHint = getString(R.string.conn_diag_refresh_hint),
                retryTitle = getString(R.string.conn_diag_retry_title),
                retryHint = getString(R.string.conn_diag_retry_hint),
            )
            ConnectionDiagnosticsScreen(
                labels = labels,
                stateText = currentStateText(),
                routeLabel = routeLabel(labels),
                devices = devices,
                failureReason = failureReason,
                a11yEnabled = a11yEnabled,
                onRefresh = { refreshAndPoll() },
                onRetryConnect = { retryConnect() },
                onBack = { finish() },
            )
        }
    }

    override fun onResume() {
        super.onResume()
        refreshSnapshot()
        displayClient?.poll()
    }

    override fun onDestroy() {
        displayClient?.shutdown()
        displayClient = null
        super.onDestroy()
    }

    private fun refreshAndPoll() {
        refreshSnapshot()
        displayClient?.poll()
    }

    /**
     * 手动重试：拉起 DisplayService 并带唤醒原因。服务侧走显式连接分支，
     * 会清零重连计数、解除人工断开抑制后立刻试一次——即便周期重连已放弃，
     * 人工与插拔事件仍能重新触发连接。
     */
    private fun retryConnect() {
        val service = Intent(ACTION_DISPLAY_SERVICE)
            .setPackage(packageName)
            .putExtra(EXTRA_USB_WAKE_REASON, "manual-retry")
        try {
            startService(service)
        } catch (t: Throwable) {
            Log.w(LOG_TAG, "retryConnect cannot start DisplayService", t)
        }
        refreshAndPoll()
    }

    /** 重新采集页面上的全部取值；进入页面、回到前台与点刷新时都走这里。 */
    private fun refreshSnapshot() {
        val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager?
        devices = usbManager?.deviceList?.values?.map { device ->
            UsbDeviceDiagnostic(
                vid = device.vendorId,
                pid = device.productId,
                hasPermission = usbManager.hasPermission(device),
                active = ConnectionDiagnosticsState.usbActive,
            )
        } ?: emptyList()
        route = ConnectionDiagnosticsLogic.normalizeRoute(ConnectionDiagnosticsState.route)
        failureReason = ConnectionDiagnosticsState.lastFailureReason
        a11yEnabled = isSelfAccessibilityServiceEnabled()
    }

    private fun routeLabel(labels: DiagnosticsLabels): String = when (route) {
        ConnectionDiagnosticsLogic.ROUTE_USB -> labels.routeUsb
        ConnectionDiagnosticsLogic.ROUTE_BLUETOOTH -> labels.routeBluetooth
        else -> labels.routeNone
    }

    private fun currentStateText(): String = DeviceConnectionLogic.resolveStateText(
        state = connectionState,
        progress = progressText,
        connected = getString(R.string.connstate_connected),
        notConnected = getString(R.string.connstate_not_connected),
        unavailable = getString(R.string.connstate_error),
    )

    private fun isSelfAccessibilityServiceEnabled(): Boolean {
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
        } catch (t: Throwable) {
            false
        }
    }

    override fun onConnectionStateChanged(state: Int) {
        connectionState = state
    }

    override fun onConnectionChangeProgress(description: String?) {
        progressText = description
    }

    companion object {
        private const val LOG_TAG = "ConnectionDiagnostics"
        private const val ACTION_DISPLAY_SERVICE =
            "com.googlecode.eyesfree.braille.service.ACTION_DISPLAY_SERVICE"
    }
}
