/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 */

package com.googlecode.eyesfree.braille.service.display

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * USB 权限请求路径护栏：
 * - 主路径由可见代理 Activity 承载请求，服务端不再后台弹窗；
 * - 后台直请只作为代理起不来时的兜底；
 * - 运行时快照在断开时复位，避免诊断页读到上一轮会话的陈旧路由。
 */
class UsbPermissionProxyContractTest {

    private fun projectFile(path: String): File {
        val candidates = listOf(File(path), File("..", path), File("../..", path))
        return candidates.firstOrNull { it.isFile }
                ?: error("找不到 $path（cwd=${System.getProperty("user.dir")}）")
    }

    private val dir = "service/src/main/java/com/googlecode/eyesfree/braille/service/display/"
    private fun src(name: String) = projectFile(dir + name).readText()

    @Test
    fun `权限请求主路径拉起代理 Activity`() {
        val source = src("UsbDisplayConnection.kt")
        assertTrue(
            "必须通过 ACTION_USB_PERMISSION_PROXY 拉起代理",
            source.contains("ACTION_USB_PERMISSION_PROXY"),
        )
        assertTrue(
            "代理启动失败才回退后台直请",
            source.contains("launchPermissionProxy"),
        )
        // 轮询兜底保留：广播被 OEM 吞掉时仍能 settled。
        assertTrue(
            "必须保留权限轮询兜底",
            source.contains("PERMISSION_POLL_MILLIS"),
        )
    }

    @Test
    fun `代理动作常量对外暴露供 brailleback 模块使用`() {
        assertEquals(
            "com.linxi.brailleback.action.USB_PERMISSION_PROXY",
            UsbDisplayConnection.ACTION_USB_PERMISSION_PROXY,
        )
        assertEquals(
            "com.linxi.brailleback.USB_PERMISSION",
            UsbDisplayConnection.ACTION_USB_PERMISSION,
        )
    }

    @Test
    fun `断开时复位路由与 USB 活态但保留失败原因`() {
        ConnectionDiagnosticsState.route = ConnectionDiagnosticsState.ROUTE_USB
        ConnectionDiagnosticsState.usbActive = true
        ConnectionDiagnosticsState.lastFailureReason = "boom"

        ConnectionDiagnosticsState.resetOnDisconnect()

        assertEquals(ConnectionDiagnosticsState.ROUTE_NONE, ConnectionDiagnosticsState.route)
        assertFalse(ConnectionDiagnosticsState.usbActive)
        // 失败原因不属于连接活态，保留到下一次失败覆盖。
        assertEquals("boom", ConnectionDiagnosticsState.lastFailureReason)
    }

    @Test
    fun `运行期接收器注册标志保持原样未被改动`() {
        val service = src("DisplayService.kt")
        // 既有运行期注册保持原样；本次改动不为收 USB 广播去翻导出标志。
        assertTrue(service.contains("Context.RECEIVER_EXPORTED"))
        assertFalse(
            "清单唤醒接收器不该出现在服务端运行期注册代码里",
            service.contains("UsbAttachWakeReceiver"),
        )
    }
}
