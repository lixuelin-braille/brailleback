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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 批次 2/3 三个大类 Java→Kotlin 的源码文本契约。
 *
 * 这些类都有 Java 侧调用方（BrlttyWrapper 的 native 包装、AIDL Stub、
 * Java 单测），或 Kotlin 侧按 `类名.静态成员` 调用。编译只能保证当下通过，
 * 本测试固定住容易被后续「顺手重构」破坏的 JVM 互调契约与常量值。
 *
 * 注意：这是源码文本契约，不替代行为测试；DeviceFinder 的行为仍由
 * BondedDeviceFilterTest 等 Java 单测覆盖。
 */
class LargeClassConversionContractTest {

    private fun projectFile(path: String): File {
        val candidates = listOf(File(path), File("..", path), File("../..", path))
        return candidates.firstOrNull { it.isFile }
            ?: error("找不到 $path（cwd=${System.getProperty("user.dir")}）")
    }

    private fun text(path: String) = projectFile(path).readText()

    private val display = "service/src/com/googlecode/eyesfree/braille/service/display/"
    private val ime = "brailleback/src/com/googlecode/eyesfree/brailleback/ime/"

    private val converted = listOf(
        display + "DeviceFinder.kt",
        display + "WujieGattConnection.kt",
        ime + "BrailleImeHostBridge.kt",
    )

    @Test
    fun convertedClassesDoNotUseInternalVisibility() {
        // internal 会触发 JVM 名字改写，Java 侧按原类名/成员名就调不到。
        val declaration = Regex("""\binternal\s+(class|fun|val|var|object|interface|enum)\b""")
        for (path in converted) {
            val src = text(path)
            assertFalse(
                "$path 不得用 internal 声明（JVM 名字改写会破坏 Java 互调）",
                declaration.containsMatchIn(src)
            )
        }
    }

    @Test
    fun deviceFinderKeepsJavaStyleGettersAndStaticFactories() {
        val src = text(display + "DeviceFinder.kt")
        // BrlttyWrapper.java (保留 Java，含 11 个 native 方法) 按这些 getter 取值；
        // DisplayService.kt 也在用 getBluetoothDevice()。改成 Kotlin 属性会编译失败。
        for (getter in listOf(
            "fun getBrailleDevice(",
            "fun getBluetoothDevice(",
            "fun getDriverCode(",
            "fun getSdpUuid(",
            "fun getConnectSecurely(",
            "fun getFriendlyKeyNames(",
        )) {
            assertTrue("DeviceFinder.DeviceInfo 缺少 $getter", src.contains(getter))
        }
        // 两个 public static 工厂被 HidDisplayConnection / UsbDisplayConnection 调用，
        // 一个 package-private static 被 BondedDeviceFilterTest.java 调用。
        assertTrue(src.contains("fun forHid("))
        assertTrue(src.contains("fun forUsb("))
        assertTrue(src.contains("fun shouldAutoConnectBondedDevice("))
        val jvmStaticCount = Regex("""@JvmStatic""").findAll(src).count()
        assertTrue("DeviceFinder 需要至少 3 个 @JvmStatic，实测 $jvmStaticCount", jvmStaticCount >= 3)
    }

    @Test
    fun deviceFinderKeepsEverySupportedDeviceMatcher() {
        val src = text(display + "DeviceFinder.kt")
        // 15 个 NameRegexSupportedDevice 条目、16 个 Pattern.compile（HandyTech 有 2 个正则）。
        assertEquals(
            "设备支持表条目数漂移",
            15, Regex("""NameRegexSupportedDevice\(""").findAll(src).count()
        )
        assertEquals(
            "设备名正则数漂移",
            16, Regex("""Pattern\.compile\(""").findAll(src).count()
        )
        for (driver in listOf("\"vo\"", "\"eu\"", "\"fs\"", "\"hw\"", "\"hm\"",
                "\"bm\"", "\"pm\"", "\"al\"", "\"ht\"", "\"sk\"")) {
            assertTrue("缺少 driver code $driver", src.contains(driver))
        }
        assertTrue(src.contains("00001101-0000-1000-8000-00805F9B34FB"))
        assertTrue(src.contains("hid:name=unused"))
    }

    @Test
    fun wujieGattConnectionKeepsStaticContract() {
        val src = text(display + "WujieGattConnection.kt")
        // Kotlin 侧 DisplayServiceHandlerActions 按 ERROR_* 取提示文案。
        for (pair in listOf(
            "ERROR_CONNECT" to "-1",
            "ERROR_PERMISSION" to "-2",
            "ERROR_SERVICE" to "-3",
            "ERROR_MTU" to "-4",
            "ERROR_NO_WRITE" to "-5",
        )) {
            val (name, value) = pair
            assertTrue(
                "$name 的值必须是 $value",
                Regex("""$name\s*[:=][^=\n]*?=\s*$value\b""").containsMatchIn(src) ||
                    Regex("""$name\s*=\s*$value\b""").containsMatchIn(src)
            )
        }
        // 9 个公开 UUID + CCCD，共 10 个，字面量一个都不能漂。
        for (suffix in listOf("abcdef0", "abcdef1", "abcdef2", "abcdef3",
                "abcdef4", "abcdef5", "abcdef6", "abcdef7", "abcdefa")) {
            assertTrue(
                "缺少 UUID 12345678-1234-5678-1234-56789$suffix",
                src.contains("12345678-1234-5678-1234-56789$suffix")
            )
        }
        assertTrue(src.contains("interface Listener"))
        assertTrue(src.contains("@JvmField"))
    }

    @Test
    fun brailleImeHostBridgeKeepsTalkBackInterfaces() {
        val src = text(ime + "BrailleImeHostBridge.kt")
        assertTrue(src.contains("TalkBackForBrailleIme"))
        assertTrue(src.contains("TalkBackForBrailleCommon"))
        assertTrue(src.contains("TextToSpeech"))
        assertTrue(src.contains("enum class ReadingGranularity"))
        // 精确到常量赋值：项目里 "brailleime" 字样很多，只断言子串等于没断言。
        assertTrue(src.contains("TTS_UTTERANCE_ID = \"brailleime\""))
    }
}
