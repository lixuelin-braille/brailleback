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

import com.googlecode.eyesfree.utils.RollingFileLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.io.File

/**
 * 文件日志的“清除”必须真的删掉内容与滚动文件。
 *
 * 现场问题：用户点了“清除调试日志”，Toast 说已清除，但 files/logs 下
 * 四个文件仍是旧时间戳/旧内容，之后导出反馈的还是几小时前的日志。
 */
class RollingFileLogTest {

    /** android.util.Log.INFO；单测里直接引用常量避免依赖 android.jar 桩。 */
    private val infoPriority = 4

    @Test
    fun clearRemovesCurrentFileAndRotations() {
        val directory = Files.createTempDirectory("rolling-file-log").toFile()
        try {
            val log = RollingFileLog(
                directory,
                RollingFileLog.MAIN_LOG_NAME,
                /* maxBytes= */ 128L,
                /* keepFiles= */ 2,
            )
            assertTrue("open() 应能创建日志文件", log.open())

            repeat(20) { index ->
                log.onLog(infoPriority, "Test", "line-$index")
            }
            assertTrue(
                "写入超过上限后应产生滚动文件",
                File(directory, "${RollingFileLog.MAIN_LOG_NAME}.1").isFile,
            )

            log.clear()

            assertEquals(
                "清除后当前日志应为空",
                0L,
                File(directory, RollingFileLog.MAIN_LOG_NAME).length(),
            )
            assertFalse(
                "清除后不应残留滚动文件",
                File(directory, "${RollingFileLog.MAIN_LOG_NAME}.1").exists(),
            )
            assertFalse(
                "清除后不应残留滚动文件",
                File(directory, "${RollingFileLog.MAIN_LOG_NAME}.2").exists(),
            )
        } finally {
            directory.deleteRecursively()
        }
    }
}
