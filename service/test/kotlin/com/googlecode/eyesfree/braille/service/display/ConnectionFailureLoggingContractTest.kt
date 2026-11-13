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

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 保证连接失败（尤其 USB）会进入用户可分享/复制的文件日志。 */
class ConnectionFailureLoggingContractTest {

    private fun projectFile(path: String): File {
        val candidates = listOf(File(path), File("..", path), File("../..", path))
        return candidates.firstOrNull { it.isFile }
            ?: error("找不到 $path（cwd=${System.getProperty("user.dir")}）")
    }

    private fun text(path: String) = projectFile(path).readText()

    /**
     * DisplayService 已按职责拆成多个文件；日志安装契约跨文件成立，
     * 因此用同一个源码视图覆盖全部拆分文件，断言强度不降低。
     */
    private val displayServiceSources = listOf(
        "service/src/com/googlecode/eyesfree/braille/service/display/DisplayService.kt",
        "service/src/com/googlecode/eyesfree/braille/service/display/DisplayServiceHandler.kt",
        "service/src/com/googlecode/eyesfree/braille/service/display/DisplayServiceHandlerActions.kt",
        "service/src/com/googlecode/eyesfree/braille/service/display/DisplayServiceListeners.kt",
        "service/src/com/googlecode/eyesfree/braille/service/display/DataFileExtractor.kt",
    )

    private fun displayServiceSource(): String =
        displayServiceSources.joinToString("\n\n") { text(it) }

    @Test
    fun timberIsForwardedToTheFileLogInReleaseToo() {
        val display = displayServiceSource()
        assertTrue(display.contains("Timber.plant(LogUtilsTimberTree())"))
        assertFalse(display.contains("BuildConfig.DEBUG && Timber.treeCount() == 0"))

        val tree = text(
            "service/src/com/googlecode/eyesfree/braille/service/display/LogUtilsTimberTree.kt",
        )
        assertTrue(tree.contains("LogUtils.logWithTag("))

        val utils = text("libraries/utils/src/com/googlecode/eyesfree/utils/LogUtils.kt")
        assertTrue(utils.contains("fun logWithTag("))
        assertTrue(utils.contains("sFileLogListener?.onLog(priority, tag, message)"))
    }

    @Test
    fun displayServiceInstallsTheSharedFileLog() {
        // The display service may start before the accessibility service, so it
        // must install the same single file sink instead of only logging to
        // logcat. Both run in one process, hence init must stay idempotent.
        val display = displayServiceSource()
        assertTrue(display.contains("configureProcessLogging()"))
        assertTrue(display.contains("LogFileWriter.init(this)"))
        assertTrue(display.contains("LogUtils.setLogLevel("))
    }

    @Test
    fun usbPermissionPendingIntentsAreMutable() {
        // The USB dialog's result is delivered as a fill-in Intent. An
        // immutable PendingIntent loses EXTRA_PERMISSION_GRANTED, which looks
        // exactly like the user pressing "deny" after allowing the device.
        val serial = text(
            "service/src/com/googlecode/eyesfree/braille/service/display/UsbDisplayConnection.kt",
        )
        val hid = text(
            "service/src/com/googlecode/eyesfree/braille/service/display/HidDisplayConnection.kt",
        )
        assertTrue(serial.contains("PendingIntent.FLAG_MUTABLE"))
        assertTrue(hid.contains("PendingIntent.FLAG_MUTABLE"))
        assertFalse(serial.contains("PendingIntent.FLAG_IMMUTABLE"))
        assertFalse(hid.contains("PendingIntent.FLAG_IMMUTABLE"))
    }

    @Test
    fun usbSerialFailureIsWrittenToTheFileLog() {
        val usb = text(
            "service/src/com/googlecode/eyesfree/braille/service/display/UsbDisplayConnection.kt",
        )
        assertTrue(usb.contains("LogUtils.w(this, \"USB connection failed: \" + reason)"))
        assertTrue(text("service/build.gradle").contains("project(':libraries:utils')"))
    }
}
