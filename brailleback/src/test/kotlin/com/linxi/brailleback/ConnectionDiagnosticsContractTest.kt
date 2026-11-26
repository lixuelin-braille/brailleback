/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 */

package com.linxi.brailleback

import com.linxi.brailleback.compose.ConnectionDiagnosticsLogic
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 连接诊断页与 USB 权限代理的结构护栏。
 *
 * - 诊断页取值必须来自真实 API：UsbManager 枚举 / hasPermission、服务端运行时
 *   快照、AccessibilityManager 已启用列表；不许写死占位文本。
 * - 清单必须注册诊断 Activity、透明代理 Activity 与 USB 插拔唤醒接收器。
 * - 纯口径：路由归一与 VID/PID 格式化。
 */
class ConnectionDiagnosticsContractTest {

    private fun projectFile(path: String): File {
        val candidates = listOf(File(path), File("..", path), File("../..", path))
        return candidates.firstOrNull { it.isFile }
            ?: error("找不到 $path（cwd=${System.getProperty("user.dir")}）")
    }

    private fun text(path: String) = projectFile(path).readText()
    private val composeDir = "brailleback/src/main/java/com/linxi/brailleback/compose/"
    private fun compose(name: String) = text(composeDir + name)

    @Test
    fun `路由归一未知值按未连接`() {
        assertEquals(
            ConnectionDiagnosticsLogic.ROUTE_USB,
            ConnectionDiagnosticsLogic.normalizeRoute("usb"),
        )
        assertEquals(
            ConnectionDiagnosticsLogic.ROUTE_BLUETOOTH,
            ConnectionDiagnosticsLogic.normalizeRoute("bluetooth"),
        )
        assertEquals(
            ConnectionDiagnosticsLogic.ROUTE_NONE,
            ConnectionDiagnosticsLogic.normalizeRoute(null),
        )
        assertEquals(
            ConnectionDiagnosticsLogic.ROUTE_NONE,
            ConnectionDiagnosticsLogic.normalizeRoute("garbage"),
        )
    }

    @Test
    fun `VID PID 格式化为四位十六进制`() {
        assertEquals("0452", ConnectionDiagnosticsLogic.formatHex(0x0452))
        assertEquals("0100", ConnectionDiagnosticsLogic.formatHex(0x0100))
    }

    @Test
    fun `诊断页读取真实 API 取值`() {
        val activity = compose("ConnectionDiagnosticsComposeActivity.kt")
        assertTrue(activity.contains("UsbManager"))
        assertTrue(activity.contains("hasPermission"))
        assertTrue(activity.contains("ConnectionDiagnosticsState"))
        assertTrue(activity.contains("AccessibilityManager"))
        assertTrue(activity.contains("getEnabledAccessibilityServiceList"))
    }

    @Test
    fun `清单注册诊断页代理 Activity 与唤醒接收器`() {
        val manifest = text("brailleback/src/main/AndroidManifest.xml")
        assertTrue(manifest.contains(".compose.ConnectionDiagnosticsComposeActivity"))
        assertTrue(manifest.contains(".compose.UsbPermissionProxyActivity"))
        assertTrue(manifest.contains(".compose.UsbAttachWakeReceiver"))
        assertTrue(
            manifest.contains("android.hardware.usb.action.USB_DEVICE_ATTACHED"),
        )
        assertTrue(
            manifest.contains("android.hardware.usb.action.USB_DEVICE_DETACHED"),
        )
    }

    @Test
    fun `代理 Activity 注册本进程结果广播带显式导出标志`() {
        val proxy = compose("UsbPermissionProxyActivity.kt")
        assertTrue(proxy.contains("Context.RECEIVER_NOT_EXPORTED"))
        assertTrue(proxy.contains("UsbDisplayConnection.ACTION_USB_PERMISSION"))
    }

    @Test
    fun `设备连接页提供诊断入口`() {
        val screen = compose("DeviceConnectionScreen.kt")
        assertTrue(screen.contains("KEY_CONNECTION_DIAGNOSTICS"))
        val activity = compose("DeviceConnectionComposeActivity.kt")
        assertTrue(activity.contains("ConnectionDiagnosticsComposeActivity::class.java"))
    }
}
