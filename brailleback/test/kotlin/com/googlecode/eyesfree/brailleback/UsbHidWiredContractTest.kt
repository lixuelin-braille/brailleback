/*
 * Copyright (C) 2026 The BrailleBack Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.googlecode.eyesfree.brailleback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Locks down the two-choice connection contract and the wired USB HID path
 * ported from Google TalkBack 16.2.
 *
 * The settings expose exactly "bluetooth" and "usb". The Bluetooth choice
 * covers Wujie BLE, Braille HID and classic RFCOMM, while the USB choice
 * covers the brltty serial backend with the system BrailleDisplayController
 * USB HID overload as its fallback. The tests catch a regression that
 * reintroduces separate USB HID / RFCOMM / Wujie options, drops the protocol
 * from the settings, or leaves wired retry dependent on Bluetooth.
 */
class UsbHidWiredContractTest {

    /** Gradle 单测的 cwd 可能是仓库根，也可能是 brailleback/ 模块目录。 */
    private fun projectFile(repoRelativePath: String): File {
        val candidates = listOf(
            File(repoRelativePath),
            File("..", repoRelativePath),
            File("../..", repoRelativePath),
        )
        return candidates.firstOrNull { it.isFile }
            ?: error("找不到 $repoRelativePath（cwd=${System.getProperty("user.dir")}）")
    }

    private fun text(path: String) = projectFile(path).readText()

    /** 抽出单个 Java 方法体，避免蓝牙路径里的同名调用造成假通过。 */
    private fun methodBody(source: String, signature: String): String {
        val start = source.indexOf(signature)
        check(start >= 0) { "找不到方法 $signature" }
        val open = source.indexOf('{', start)
        var depth = 0
        for (i in open until source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(start, i + 1)
                }
            }
        }
        error("方法 $signature 括号不匹配")
    }

    /** 抽出从 marker 开始到配对右花括号的代码块，用于精确断言单个分支。 */
    private fun block(source: String, marker: String): String {
        val start = source.indexOf(marker)
        check(start >= 0) { "找不到代码块 $marker" }
        val open = source.indexOf('{', start)
        var depth = 0
        for (i in open until source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(start, i + 1)
                }
            }
        }
        error("代码块 $marker 括号不匹配")
    }

    /** 去掉 // 行注释，避免注释里解释死循环的文字干扰代码断言。 */
    private fun withoutLineComments(source: String) = source
        .lineSequence()
        .map { line ->
            val idx = line.indexOf("//")
            if (idx >= 0) line.substring(0, idx) else line
        }
        .joinToString("\n")

    private fun displayServiceSource() = text(
        "service/src/com/googlecode/eyesfree/braille/service/display/DisplayService.java",
    )

    private fun arrayBlock(name: String): String {
        val xml = text("brailleback/res/values/donottranslate.xml")
        return Regex(
            "<string-array name=\"$name\">([\\s\\S]*?)</string-array>",
        ).find(xml)?.groupValues?.get(1)
            ?: error("找不到数组 $name")
    }

    private fun protocolValues(): String = arrayBlock("pref_connection_protocol_values")

    private fun protocolEntries(): String = arrayBlock("pref_connection_protocol_entries")

    private fun items(block: String): List<String> =
        Regex("<item>([^<]*)</item>").findAll(block)
            .map { it.groupValues[1] }
            .toList()

    @Test
    fun settingsExposeOnlyBluetoothAndUsb() {
        assertEquals(listOf("bluetooth", "usb"), items(protocolValues()))
        assertFalse(protocolValues().contains("usbhid"))
        assertFalse(protocolValues().contains("rfcomm"))
        assertFalse(protocolValues().contains("wujie"))

        val entries = items(protocolEntries())
        assertEquals(2, entries.size)
        assertTrue(entries[0].contains("pref_connection_protocol_bluetooth_label"))
        assertTrue(entries[1].contains("pref_connection_protocol_usb_label"))
        assertFalse(protocolEntries().contains("usbhid"))
    }

    @Test
    fun displayServiceRoutesUsbThroughUnifiedEntryPoint() {
        val source = displayServiceSource()
        val connectBraille = methodBody(source, "private void connectBraille()")
        assertTrue(connectBraille.contains("if (isUsbProtocol()) {"))
        assertTrue(connectBraille.contains("connectUsbUnified();"))
        assertTrue(connectBraille.contains("connectBluetooth();"))
        assertTrue(source.contains("private void connectUsbUnified()"))
    }

    @Test
    fun usbFallsBackToHidWhenSerialBackendFindsNoDevice() {
        val source = displayServiceSource()
        val fallback = methodBody(
            source,
            "public void onUsbConnectFailed(UsbDisplayConnection source,",
        )
        assertTrue(fallback.contains("isUsbProtocol() && !mUsbHidTried && canUseUsbHid()"))
        assertTrue(fallback.contains("connectUsbHid();"))
        assertTrue(source.contains("private void connectUsbHid()"))
    }

    @Test
    fun bluetoothEntryPointCoversWujieHidAndRfcomm() {
        val bluetooth = methodBody(displayServiceSource(), "private void connectBluetooth()")
        assertTrue(bluetooth.contains("findNamedWujieAddress()"))
        assertTrue(bluetooth.contains("connectWujie(wujieAddress);"))
        assertTrue(bluetooth.contains("tryConnectHid()"))
        assertTrue(bluetooth.contains("new ReadThread("))
    }

    @Test
    fun usbHidUsesFrameworkUsbDeviceOverload() {
        val source = text(
            "service/src/com/googlecode/eyesfree/braille/service/display/" +
                "HidDisplayConnection.java",
        )
        val connectUsb = methodBody(source, "public void connectUsb(")
        val finishUsbConnect = methodBody(source, "private void finishUsbConnect(")
        val requestUsbPermission = methodBody(source, "private void requestUsbPermission(")
        assertTrue(connectUsb.contains("UsbHidDevicePolicy.firstCompatible"))
        assertTrue(connectUsb.contains("requestUsbPermission("))
        assertTrue(requestUsbPermission.contains("requestPermission("))
        assertTrue(
            finishUsbConnect.contains("controller.connect(device, mExecutor, mCallback)"),
        )
    }

    @Test
    fun usbHidReusesNativeHidDriverInsteadOfSerialBackend() {
        val source = text(
            "service/src/com/googlecode/eyesfree/braille/service/display/" +
                "HidDisplayConnection.java",
        )
        assertTrue(source.contains("DeviceFinder.DeviceInfo.forUsb(\"hid\", \"hid:name=unused\")"))
        // The serial usb: backend belongs to UsbDisplayConnection only.
        assertFalse(source.contains("\"usb:"))
    }

    @Test
    fun wiredProtocolTriggersOnUsbAttachAndSkipsBluetoothGate() {
        val source = displayServiceSource()
        assertTrue(source.contains("private boolean isWiredProtocol()"))
        assertTrue(source.contains("return isUsbProtocol();"))
        assertTrue(source.contains("if (isWiredProtocol() && !isConnectionActive())"))
        assertTrue(source.contains("if (isWiredProtocol()) {"))

        val detached = block(source, "UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)")
        assertTrue(detached.contains("isUsbProtocol()"))
        assertTrue(detached.contains("mHidConnection != null"))
    }

    /**
     * Regression: a wired USB HID attempt with no device attached fails
     * synchronously. The old failure handler called connectBraille() directly,
     * which reposted the same failure on the main looper and spun at ~2800
     * attempts/second (47,400 in 17 s, ~106% CPU) while the UI stayed stuck on
     * "Connecting via wired USB HID…". The wired branch must report once and
     * wait for a device / backoff instead.
     */
    @Test
    fun usbHidFailureReportsOnceInsteadOfSpinning() {
        val handler = methodBody(displayServiceSource(), "private void handleOnHidConnectFailed(")
        val branch = block(
            handler,
            "if (service.mHidConnectionIsUsb && service.isUsbProtocol()) {",
        )
        val code = withoutLineComments(branch)
        assertTrue(code.contains("getUsbHidFailureMessage(event.error)"))
        assertTrue(code.contains("setConnectionProgress("))
        assertTrue(code.contains("scheduleAutoReconnectIfNeeded()"))
        assertTrue(code.contains("return;"))
        assertFalse(
            "USB HID 失败分支不得立即重连，否则形成主线程死循环",
            code.contains("connectBraille()"),
        )
        assertTrue(displayServiceSource().contains("private String getUsbHidFailureMessage(int error)"))
    }

    @Test
    fun usbHidFailureMessageDistinguishesNoDeviceAndPermission() {
        val body = methodBody(
            displayServiceSource(),
            "private String getUsbHidFailureMessage(",
        )
        assertTrue(body.contains("error == -7"))
        assertTrue(body.contains("error == -8"))
        assertTrue(body.contains("connprog_usb_hid_not_found"))
        assertTrue(body.contains("connprog_usb_hid_permission_denied"))
        assertTrue(body.contains("connprog_usb_hid_failed"))
    }

    @Test
    fun usbHidRetriesWhenAnInputDeviceAppears() {
        val body = methodBody(
            displayServiceSource(),
            "private void retryHidWhenInputDeviceAppears()",
        )
        assertTrue(body.contains("boolean wiredUsbHid = isUsbProtocol();"))
        assertTrue(body.contains("prepareAutomaticRetry("))
    }

    @Test
    fun bothLocalesDefineUsbHidStrings() {
        val required = listOf(
            "connprog_trying_usb_hid",
            "connprog_usb_hid_unavailable",
            "connprog_usb_hid_not_found",
            "connprog_usb_hid_permission_denied",
            "connprog_usb_hid_failed",
        )
        for (path in listOf(
            "service/res/values/strings.xml",
            "service/res/values-zh-rCN/strings.xml",
        )) {
            val xml = text(path)
            for (name in required) {
                assertTrue("$path 缺少 $name", xml.contains("name=\"$name\""))
            }
        }
    }
}