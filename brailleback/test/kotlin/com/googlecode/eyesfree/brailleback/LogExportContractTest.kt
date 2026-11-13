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

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 分享/复制日志必须覆盖无障碍服务与显示服务共用的一份日志。 */
class LogExportContractTest {

    private fun projectFile(path: String): File {
        val candidates = listOf(File(path), File("..", path), File("../..", path))
        return candidates.firstOrNull { it.isFile }
            ?: error("找不到 $path（cwd=${System.getProperty("user.dir")}）")
    }

    @Test
    fun oneSharedLogAndLegacyLogAreExported() {
        val legacyApi = projectFile(
            "brailleback/src/com/googlecode/eyesfree/brailleback/utils/LogFileWriter.kt",
        ).readText()
        assertTrue(legacyApi.contains("SharedLogFileWriter.init(context)"))

        val shared = projectFile(
            "libraries/utils/src/com/googlecode/eyesfree/utils/LogFileWriter.kt",
        ).readText()
        assertTrue(shared.contains("RollingFileLog.MAIN_LOG_NAME"))
        assertTrue(shared.contains("LEGACY_SERVICE_LOG_NAME"))

        val rolling = projectFile(
            "libraries/utils/src/com/googlecode/eyesfree/utils/RollingFileLog.kt",
        ).readText()
        assertTrue(rolling.contains("const val MAIN_LOG_NAME = \"brailleback.log\""))
        assertTrue(
            rolling.contains(
                "const val LEGACY_SERVICE_LOG_NAME = \"brailleback-service.log\"",
            ),
        )
    }

    @Test
    fun clearDeletesMainLogEvenWhenSinkWasNeverOpened() {
        // 现场问题：清除日志时若文件 sink 还没打开，旧实现只删了旧版连接
        // 日志，主日志原封不动，用户导出的仍是上一次进程的内容。
        val shared = projectFile(
            "libraries/utils/src/com/googlecode/eyesfree/utils/LogFileWriter.kt",
        ).readText()
        val clearBody = shared.substringAfter("fun clear(context: Context)")
        assertTrue(
            "clear() 必须在 sink 未打开时也删除主日志",
            clearBody.contains("RollingFileLog.MAIN_LOG_NAME"),
        )
    }
}
