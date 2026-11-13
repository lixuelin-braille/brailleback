/*
 * Copyright (C) 2026 The BrailleBack Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package com.googlecode.eyesfree.braille.service.display

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 无线自动重连的传输顺序契约。
 *
 * 真机缺陷背景：Orbit wujie 30 断连后，扫描到设备会把地址写进
 * `mPendingBluetoothAddress`，而 `connectBluetooth()` 当时的 Wujie 前置条件
 * 是 `mPendingBluetoothAddress == null`，于是整个 Wujie BLE 分支被跳过，
 * 落到标准 Braille HID 路径（该显示器的厂商 BLE 模式不响应 HID），
 * 必然失败，只能靠重新配对恢复。
 *
 * 这里固定住「目标地址本身决定走哪条传输」这一契约，避免再退回
 * 「只看有没有 pending 地址」的写法。源码文本契约，不替代行为测试。
 */
class WujieAutoConnectContractTest {

    private fun projectFile(path: String): File {
        val candidates = listOf(File(path), File("..", path), File("../..", path))
        return candidates.firstOrNull { it.isFile }
            ?: error("找不到 $path（cwd=${System.getProperty("user.dir")}）")
    }

    private val service = "service/src/com/googlecode/eyesfree/braille/service/display/"

    private val source: String by lazy {
        projectFile(service + "DisplayService.kt").readText()
    }

    /** 取一个成员函数的函数体文本（到下一个 4 空格缩进的成员定义为止）。 */
    private fun bodyOf(src: String, signature: String): String {
        val start = src.indexOf(signature)
        if (start < 0) {
            error("找不到签名：$signature")
        }
        val bodyStart = src.indexOf('\n', start) + 1
        val rest = src.substring(bodyStart)
        val next = Regex("""\n    (?:private |public |protected |internal |fun |@|/\*\*|val |var |companion |override )""")
            .find(rest)
        return if (next == null) rest else rest.substring(0, next.range.first)
    }

    @Test
    fun wujieFallbackIsNotGatedOnAbsentPendingAddress() {
        val body = bodyOf(source, "private fun connectBluetooth()")
        assertFalse(
            "Wujie 分支不得再以 pending 地址为空作为前置条件：扫描到设备会写入 " +
                "pending 地址，那样会跳过 BLE 直接落到 HID",
            body.contains("mPendingBluetoothAddress == null")
        )
        assertTrue(
            "connectBluetooth() 必须通过 isWujieTarget() 判断目标是不是 Wujie 设备",
            body.contains("isWujieTarget()")
        )
    }

    @Test
    fun wujieIsTriedBeforeHid() {
        val body = bodyOf(source, "private fun connectBluetooth()")
        val wujie = body.indexOf("connectWujie(")
        val hid = body.indexOf("tryConnectHid(")
        assertTrue("connectBluetooth() 必须仍调用 connectWujie()", wujie >= 0)
        assertTrue("connectBluetooth() 必须仍调用 tryConnectHid()", hid >= 0)
        assertTrue(
            "Wujie BLE 必须先于 HID 尝试（否则厂商 BLE 模式永远连不上）",
            wujie < hid
        )
    }

    @Test
    fun wujieTargetInspectsPendingAddressNotJustItsAbsence() {
        val body = bodyOf(source, "private fun isWujieTarget()")
        assertTrue(
            "必须读取 mPendingBluetoothAddress 并按其判定",
            body.contains("val pending = mPendingBluetoothAddress")
        )
        assertTrue(
            "必须按 pending 地址的远端名判定 Wujie",
            body.contains("readRemoteDeviceName(pending)")
        )
        assertTrue(
            "读不到名字时必须回退到已配对扫描并按地址比对",
            body.contains("findNamedWujieAddress()")
        )
        assertTrue(
            "pending 为空时按已配对扫描判定",
            body.contains("return findNamedWujieAddress() != null")
        )
    }

    @Test
    fun wujieTargetDoesNotTrustLastConnectedPreference() {
        // lastBluetoothDevice 记录的是「任何协议」上次连上的设备；把它当作
        // Wujie 证据会让普通 RFCOMM 点显器每轮先白试一次 BLE。
        val body = bodyOf(source, "private fun isWujieTarget()")
        assertFalse(
            "isWujieTarget() 不得使用 lastBluetoothDevice 作为 Wujie 证据",
            body.contains("lastBluetoothDevice")
        )
    }

    @Test
    fun wujieNameMatchCoversAllVendorSpellings() {
        val body = bodyOf(source, "private fun isWujieName(")
        assertTrue("必须匹配 orbit", body.contains("\"orbit\""))
        assertTrue("必须匹配 wujie", body.contains("\"wujie\""))
        assertTrue("必须匹配中文「无界」", body.contains("无界"))
    }

    @Test
    fun redundantAddressLookupStaysConsolidated() {
        // 名字匹配只有一处实现；bonded 扫描必须复用它，否则两处规则会漂移。
        val bonded = bodyOf(source, "private fun findNamedWujieAddress()")
        assertTrue(
            "findNamedWujieAddress() 必须复用 isWujieName()",
            bonded.contains("isWujieName(name)")
        )
        assertFalse(
            "findNamedWujieAddress() 不得再内联一套名字匹配",
            bonded.contains("contains(\"orbit\")")
        )
    }

    @Test
    fun discoveryStillResetsAttemptFlagsBeforeConnecting() {
        // 修复依赖「发现设备 -> 重置标志 -> 重新走 connectBluetooth」这条链。
        val body = bodyOf(source, "private fun handleDiscoveredDevice(")
        assertTrue("必须先写 pending 地址", body.contains("mPendingBluetoothAddress = address"))
        assertTrue("必须重置 mWujieTried", body.contains("mWujieTried = false"))
        assertTrue("必须重置 mHidTried", body.contains("mHidTried = false"))
        assertTrue("必须重新发起连接", body.contains("autoConnectBraille()"))
    }

    @Test
    fun connectWujiePrefersPendingAddressOverBondedScan() {
        val body = bodyOf(source, "private fun connectWujie()")
        val pendingAt = body.indexOf("var address: String? = mPendingBluetoothAddress")
        val fallbackAt = body.indexOf("findWujieAddress()")
        assertTrue("connectWujie() 必须优先取 pending 地址", pendingAt >= 0)
        assertTrue("connectWujie() 必须保留 findWujieAddress() 回退", fallbackAt >= 0)
        assertTrue("pending 地址优先于配对扫描", pendingAt < fallbackAt)
    }
}
