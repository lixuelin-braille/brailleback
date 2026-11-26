/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 */

package com.googlecode.eyesfree.braille.service.display

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 连接诊断快照纯函数断言：
 * - 字段齐全（每个规定字段都出现在输出里）；
 * - 缺失项写 `na`，持久化偏好原值为 null 时如实写 `null`；
 * - 输出不含序列号类字段（无 `serial`、无 MAC 形态、无长十六进制串）。
 */
class ConnectionDiagnosticsTest {

    private fun fullInput(
        protoPref: String? = "AUTO",
        manufacturer: String? = "Xiaomi",
        model: String? = "24117RK2CC",
        release: String? = "15",
        versionName: String? = "1.0",
        reason: String? = "usb-display-found",
        a11yRestricted: String = "unknown",
        batt: String = "false",
        usbDevices: List<ConnectionDiagnostics.SnapshotUsbDevice> = listOf(
            ConnectionDiagnostics.SnapshotUsbDevice(
                vendorId = 0x0452,
                productId = 0x0100,
                deviceClass = 3,
                deviceSubclass = 0,
                protocol = 0,
                hasPermission = true,
            ),
        ),
    ): ConnectionDiagnostics.ConnectionSnapshotInput =
        ConnectionDiagnostics.ConnectionSnapshotInput(
            trigger = "before-connect",
            manufacturer = manufacturer,
            model = model,
            sdkInt = 35,
            release = release,
            versionCode = 34,
            versionName = versionName,
            flavor = "debug",
            protoPref = protoPref,
            resolvedRoute = "BLUETOOTH",
            reason = reason,
            usbManagerAvailable = true,
            usbDevices = usbDevices,
            btAvailable = true,
            btEnabled = true,
            btPairedCount = 1,
            bleSupported = true,
            a11ySelfEnabled = true,
            a11yRestrictedSettings = a11yRestricted,
            perms = mapOf(
                "BLUETOOTH_CONNECT" to "true",
                "BLUETOOTH_SCAN" to "true",
                "POST_NOTIFICATIONS" to "true",
                "FOREGROUND_SERVICE_CONNECTED_DEVICE" to "true",
            ),
            batteryWhitelist = batt,
            fgTypeDeclared = true,
        )

    @Test
    fun allRequiredFieldsAppear() {
        val lines = ConnectionDiagnostics.buildLines(fullInput())
        val joined = lines.joinToString("\n")

        listOf(
            "trigger=",
            "build=",
            "app=",
            "proto=",
            "resolvedRoute=",
            "reason=",
            "usbHost=",
            "usbCount=",
            "usb[0]=",
            "bt=",
            "btPaired=",
            "bleSupported=",
            "a11ySelf=",
            "a11yRestrictedSettings=",
            "perms=",
            "batt=",
            "fgType=",
        ).forEach { key ->
            assertTrue("缺少字段 $key", joined.contains("${ConnectionDiagnostics.SNAP_PREFIX}$key"))
        }

        // 每个规定权限逐个出现。
        listOf(
            "BLUETOOTH_CONNECT",
            "BLUETOOTH_SCAN",
            "POST_NOTIFICATIONS",
            "FOREGROUND_SERVICE_CONNECTED_DEVICE",
        ).forEach { perm ->
            assertTrue("缺少权限 $perm", joined.contains(perm))
        }

        // USB 条目只写 VID/PID 与类码。
        assertTrue(joined.contains("usb[0]=0452:0100 class=3/0/0 hasPermission=true"))
    }

    @Test
    fun missingValuesAreWrittenAsNaAndNullStaysNull() {
        val lines = ConnectionDiagnostics.buildLines(
            fullInput(
                protoPref = null,
                manufacturer = null,
                model = null,
                release = null,
                versionName = null,
                reason = null,
                a11yRestricted = "na",
                batt = "unknown",
                usbDevices = emptyList(),
            ),
        )
        val joined = lines.joinToString("\n")

        // 持久化偏好原值 null 必须如实写 null，不能被归一化成 AUTO。
        assertTrue(joined.contains("proto=null"))
        assertTrue(joined.contains("build=na/na/sdk=35/na"))
        assertTrue(joined.contains("app=34/na/debug"))
        assertTrue(joined.contains("reason=na"))
        assertTrue(joined.contains("usbHost=true"))
        assertTrue(joined.contains("usbCount=0"))
        assertTrue(joined.contains("a11yRestrictedSettings=na"))
        assertTrue(joined.contains("batt=unknown"))
        assertFalse(joined.contains("usb["))
    }

    @Test
    fun outputContainsNoSerialOrMacOrLongHex() {
        val lines = ConnectionDiagnostics.buildLines(fullInput())
        val joined = lines.joinToString("\n").lowercase()

        // 不出现序列号字样或序列号字段键。
        assertFalse(joined.contains("serial"))
        assertFalse(joined.contains("serialnumber"))
        assertFalse(joined.contains("serial="))

        // 不出现 MAC 形态（六段冒号十六进制）。
        val macRegex = Regex("[0-9a-f]{2}(:[0-9a-f]{2}){5}")
        assertFalse("出现 MAC 形态: ${macRegex.find(joined)?.value}", macRegex.containsMatchIn(joined))

        // 不出现 16 位及以上连续十六进制串（IMEI / ANDROID_ID / 序列号常见形态）。
        val longHexRegex = Regex("[0-9a-f]{16,}")
        assertFalse("出现长十六进制: ${longHexRegex.find(joined)?.value}", longHexRegex.containsMatchIn(joined))
    }

    @Test
    fun snapshotPrefixIsUniform() {
        val lines = ConnectionDiagnostics.buildLines(fullInput())
        assertTrue(lines.isNotEmpty())
        lines.forEach { line ->
            assertTrue(line.startsWith(ConnectionDiagnostics.SNAP_PREFIX))
        }
        assertEquals(17, lines.size)
    }
}
