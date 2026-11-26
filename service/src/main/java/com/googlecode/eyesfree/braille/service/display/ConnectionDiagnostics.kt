/*
 * Copyright (C) 2026 The BrailleBack Project
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
package com.googlecode.eyesfree.braille.service.display

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import com.googlecode.eyesfree.braille.service.BuildConfig
import com.googlecode.eyesfree.utils.LogUtils
import com.googlecode.eyesfree.utils.SettingsStore

/**
 * 连接诊断快照：在「进入连接流程前 / 连接失败超时 / USB 插拔广播」三个时机各打一次，
 * 一行一条键值，统一前缀 [SNAP_PREFIX]，便于 grep。
 *
 * 隐私红线（硬）：不写 IMEI / 序列号 / ANDROID_ID / MAC 地址；不写屏幕文本；
 * 不写用户书籍路径；USB 条目只写 VID/PID 与类码，不读序列号与字符串描述符。
 *
 * 结构：[ConnectionSnapshotInput] 是纯数据，[buildLines] 是不碰 Android 的纯函数，
 * 单测直接喂假数据断言字段齐全、缺失写 `na`、不含序列号类字段；[collect] 在宿主
 * 进程里逐项 try/catch 收集，任何一项读不到都降级为 `na` / `unknown`，不得抛异常。
 */
internal object ConnectionDiagnostics {

    /** 快照行统一前缀，落盘 / logcat 都按它检索。 */
    const val SNAP_PREFIX = "CONN|SNAP|"

    // ---- 纯函数：输入数据对象 → 快照行列表（无 Android 依赖，可单测） ----

    /** 一条 USB 设备的脱敏快照。不含序列号、设备名、厂商名、产品名、MAC。 */
    internal data class SnapshotUsbDevice(
        val vendorId: Int,
        val productId: Int,
        val deviceClass: Int,
        val deviceSubclass: Int,
        val protocol: Int,
        val hasPermission: Boolean,
    )

    /** 纯函数输入：所有系统读取由宿主收集好后填入；缺失项用 `na` / `unknown` 占位。 */
    internal data class ConnectionSnapshotInput(
        val trigger: String,
        val manufacturer: String?,
        val model: String?,
        val sdkInt: Int,
        val release: String?,
        val versionCode: Long,
        val versionName: String?,
        val flavor: String,
        val protoPref: String?,
        val resolvedRoute: String,
        val reason: String?,
        val usbManagerAvailable: Boolean,
        val usbDevices: List<SnapshotUsbDevice>,
        val btAvailable: Boolean,
        val btEnabled: Boolean,
        val btPairedCount: Int,
        val bleSupported: Boolean,
        val a11ySelfEnabled: Boolean,
        /** `true` / `false` / `unknown`（API<33 写 `na`）。 */
        val a11yRestrictedSettings: String,
        /** 权限名 → `true` / `false` / `na`（当前 SDK 不适用该权限）。 */
        val perms: Map<String, String>,
        /** `true` / `false` / `unknown`。 */
        val batteryWhitelist: String,
        val fgTypeDeclared: Boolean,
    )

    private fun naStr(value: String?): String =
        if (value.isNullOrEmpty()) "na" else value

    /** 拼快照行；字段有就写，没有写 `na`，纯函数不抛异常。 */
    fun buildLines(input: ConnectionSnapshotInput): List<String> {
        val lines = mutableListOf<String>()
        fun add(key: String, value: String) {
            lines.add("${SNAP_PREFIX}$key=$value")
        }
        add("trigger", naStr(input.trigger))
        add(
            "build",
            "${naStr(input.manufacturer)}/${naStr(input.model)}/" +
                "sdk=${input.sdkInt}/${naStr(input.release)}",
        )
        add("app", "${input.versionCode}/${naStr(input.versionName)}/${input.flavor}")
        // 持久化偏好原值：null 也要如实写 `null`，不做默认值归一。
        add("proto", input.protoPref ?: "null")
        add("resolvedRoute", naStr(input.resolvedRoute))
        add("reason", naStr(input.reason))
        add("usbHost", input.usbManagerAvailable.toString())
        add("usbCount", input.usbDevices.size.toString())
        input.usbDevices.forEachIndexed { index, device ->
            val identity = "%04x:%04x".format(device.vendorId, device.productId)
            add(
                "usb[$index]",
                "$identity class=${device.deviceClass}/" +
                    "${device.deviceSubclass}/${device.protocol} " +
                    "hasPermission=${device.hasPermission}",
            )
        }
        add("bt", "${input.btAvailable}/${input.btEnabled}")
        add("btPaired", input.btPairedCount.toString())
        add("bleSupported", input.bleSupported.toString())
        add("a11ySelf", input.a11ySelfEnabled.toString())
        add("a11yRestrictedSettings", naStr(input.a11yRestrictedSettings))
        add(
            "perms",
            input.perms.entries.joinToString(",") { "${it.key}=${it.value}" },
        )
        add("batt", naStr(input.batteryWhitelist))
        add("fgType", input.fgTypeDeclared.toString())
        return lines
    }

    // ---- Android 绑定收集：逐项 try/catch，降级为占位值，不拖垮连接链路 ----

    /**
     * 在 [context] 进程内收集快照并逐行交给 [LogUtils]。
     *
     * @param trigger `before-connect` / `fail` / `usb-attached`（插拔广播到达）。
     * @param resolvedRoute AUTO 解析结果；读不到写 `na`。
     * @param reason 解析理由或失败原因；读不到写 `na`。
     */
    fun emit(
        source: Any?,
        context: Context,
        trigger: String,
        resolvedRoute: String,
        reason: String?,
    ) {
        val lines = try {
            buildLines(collect(context, trigger, resolvedRoute, reason))
        } catch (e: Exception) {
            // 快照本身绝不能反过来打断连接流程；异常留一条 WARN 进 logcat 供排查。
            LogUtils.w(source, e, "CONN|SNAP collect failed")
            emptyList()
        }
        lines.forEach { LogUtils.i(source, it) }
    }

    private fun collect(
        context: Context,
        trigger: String,
        resolvedRoute: String,
        reason: String?,
    ): ConnectionSnapshotInput {
        val packageManager = context.packageManager

        // ---- 设备 / 应用版本 ----
        var versionCode = -1L
        var versionName: String? = null
        try {
            val info = packageManager.getPackageInfo(context.packageName, 0)
            versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                info.versionCode.toLong()
            }
            versionName = info.versionName
        } catch (e: Exception) {
            versionCode = -1L
        }
        val flavor = if (BuildConfig.DEBUG) "debug" else "release"

        // ---- 持久化偏好原值（null 如实保留） ----
        var protoPref: String? = null
        try {
            protoPref = SettingsStore.get(context)
                .getString(ConnectionProtocolPolicy.PREFERENCE_KEY, null)
        } catch (e: Exception) {
            protoPref = null
        }

        // ---- USB ----
        var usbManagerAvailable = false
        val usbDevices = mutableListOf<SnapshotUsbDevice>()
        try {
            val usb = context.getSystemService(Context.USB_SERVICE) as? UsbManager
            usbManagerAvailable = usb != null
            usb?.deviceList?.values?.forEach { device ->
                // 只取类码与授权态；不读 getSerialNumber / 设备名 / 字符串描述符。
                usbDevices.add(
                    SnapshotUsbDevice(
                        vendorId = device.vendorId,
                        productId = device.productId,
                        deviceClass = device.deviceClass,
                        deviceSubclass = device.deviceSubclass,
                        protocol = device.deviceProtocol,
                        hasPermission = usb.hasPermission(device),
                    ),
                )
            }
        } catch (e: Exception) {
            usbDevices.clear()
        }

        // ---- 蓝牙 / BLE ----
        var btAvailable = false
        var btEnabled = false
        var btPairedCount = 0
        var bleSupported = false
        try {
            val adapter = BluetoothAdapter.getDefaultAdapter()
            btAvailable = adapter != null
            if (adapter != null) {
                btEnabled = try {
                    adapter.isEnabled
                } catch (e: SecurityException) {
                    false
                }
                btPairedCount = try {
                    adapter.bondedDevices?.size ?: 0
                } catch (e: SecurityException) {
                    0
                }
            }
            bleSupported = packageManager.hasSystemFeature(
                PackageManager.FEATURE_BLUETOOTH_LE,
            )
        } catch (e: Exception) {
            // 蓝牙不可用时按关闭 / 未配对处理。
        }

        // ---- 无障碍：本包是否在已启用列表 ----
        var a11ySelfEnabled = false
        try {
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            )
            a11ySelfEnabled = enabled?.split(':')
                ?.any { it.startsWith(context.packageName) } == true
        } catch (e: Exception) {
            a11ySelfEnabled = false
        }
        // API≥33 才尝试读受限设置；系统未公开该状态的查询接口，读不到写 `unknown`。
        val a11yRestrictedSettings = if (Build.VERSION.SDK_INT >= 33) {
            try {
                Settings.Secure.getString(
                    context.contentResolver,
                    "accessibility_restricted_settings",
                ) ?: "unknown"
            } catch (e: Exception) {
                "unknown"
            }
        } else {
            "na"
        }

        // ---- 关键权限：当前 SDK 不适用的写 `na` ----
        val perms = linkedMapOf<String, String>()
        perms[Manifest.permission.BLUETOOTH_CONNECT] =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                boolPerm(context, Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                "na"
            }
        perms[Manifest.permission.BLUETOOTH_SCAN] =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                boolPerm(context, Manifest.permission.BLUETOOTH_SCAN)
            } else {
                "na"
            }
        perms[Manifest.permission.POST_NOTIFICATIONS] =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                boolPerm(context, Manifest.permission.POST_NOTIFICATIONS)
            } else {
                "na"
            }
        perms[Manifest.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE] =
            boolPerm(context, Manifest.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE)

        // ---- 省电白名单 / Doze 豁免（编译桩不可见时走反射，读不到 unknown） ----
        val batteryWhitelist = try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            when {
                pm == null -> "unknown"
                else -> runCatching {
                    PowerManager::class.java
                        .getMethod("isIgnoringBatterySaver")
                        .invoke(pm) as? Boolean
                }.getOrNull()?.toString() ?: "unknown"
            }
        } catch (e: Exception) {
            "unknown"
        }

        // ---- 前台服务类型是否声明 connectedDevice ----
        val fgTypeDeclared = try {
            val info = packageManager.getPackageInfo(
                context.packageName,
                PackageManager.GET_SERVICES,
            )
            info.services?.any { service ->
                service.foregroundServiceType and
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE != 0
            } == true
        } catch (e: Exception) {
            false
        }

        return ConnectionSnapshotInput(
            trigger = trigger,
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
            sdkInt = Build.VERSION.SDK_INT,
            release = Build.VERSION.RELEASE,
            versionCode = versionCode,
            versionName = versionName,
            flavor = flavor,
            protoPref = protoPref,
            resolvedRoute = resolvedRoute,
            reason = reason,
            usbManagerAvailable = usbManagerAvailable,
            usbDevices = usbDevices,
            btAvailable = btAvailable,
            btEnabled = btEnabled,
            btPairedCount = btPairedCount,
            bleSupported = bleSupported,
            a11ySelfEnabled = a11ySelfEnabled,
            a11yRestrictedSettings = a11yRestrictedSettings,
            perms = perms,
            batteryWhitelist = batteryWhitelist,
            fgTypeDeclared = fgTypeDeclared,
        )
    }

    private fun boolPerm(context: Context, permission: String): String =
        try {
            (context.checkSelfPermission(permission) ==
                PackageManager.PERMISSION_GRANTED).toString()
        } catch (e: Exception) {
            "false"
        }
}
