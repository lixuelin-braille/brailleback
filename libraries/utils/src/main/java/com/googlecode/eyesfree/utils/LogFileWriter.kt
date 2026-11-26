/*
 * Copyright (C) 2026 The BrailleBack Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.googlecode.eyesfree.utils

import android.content.Context
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * Single entry point for the app's persistent log.
 *
 * BrailleBackService (accessibility service) and the display service share one
 * process, so only one [RollingFileLog] may be installed as the [LogUtils] sink.
 * Whichever component starts first calls [init]; later calls are no-ops.
 */
object LogFileWriter {

    private const val LEGACY_SERVICE_LOG_NAME = RollingFileLog.LEGACY_SERVICE_LOG_NAME

    /** 旧版（非本测试包）日志挪到的归档名，便于排查时对照。 */
    private const val PREV_TESTBUILD_LOG_NAME =
        "${RollingFileLog.MAIN_LOG_NAME}.prev-testbuild"

    @Volatile
    private var sLog: RollingFileLog? = null

    /** Opens the log file and registers it as the [LogUtils] sink. Idempotent. */
    @JvmStatic
    fun init(context: Context) {
        if (sLog != null) return
        synchronized(this) {
            if (sLog != null) return
            val dir = RollingFileLog.logDirectory(context)
            archivePreviousBuildLogIfNeeded(dir)
            // 测试包单独一组轮转参数（4 MB / 6 份）；正式包沿用默认 256 KB / 3 份。
            val maxBytes = if (TestBuild.VERBOSE_BY_DEFAULT) {
                RollingFileLog.TEST_MAX_BYTES
            } else {
                RollingFileLog.DEFAULT_MAX_BYTES
            }
            val keepFiles = if (TestBuild.VERBOSE_BY_DEFAULT) {
                RollingFileLog.TEST_KEEP_FILES
            } else {
                RollingFileLog.DEFAULT_KEEP_FILES
            }
            val log = RollingFileLog(
                dir,
                RollingFileLog.MAIN_LOG_NAME,
                maxBytes = maxBytes,
                keepFiles = keepFiles,
                header = if (TestBuild.VERBOSE_BY_DEFAULT) TestBuild.MARKER else null,
            )
            if (log.open()) {
                sLog = log
                LogUtils.setFileLogListener(log)
                LogUtils.w(this, "文件日志已启用: %s", log.getCurrentFile())
            } else {
                LogUtils.e(this, "初始化文件日志失败: %s", log.getCurrentFile())
            }
        }
    }

    /**
     * 测试包：覆盖安装后若主日志仍是旧版（首行没有 [TestBuild.MARKER]），
     * 先挪到 [PREV_TESTBUILD_LOG_NAME]，让新日志文件以标记首行起头。
     * 只动日志文件，不清设置、不清阅读进度与书签。
     */
    private fun archivePreviousBuildLogIfNeeded(dir: File) {
        if (!TestBuild.VERBOSE_BY_DEFAULT) return
        val main = File(dir, RollingFileLog.MAIN_LOG_NAME)
        if (!main.isFile || main.length() == 0L) return
        if (firstLineContains(main, TestBuild.MARKER)) return
        val prev = File(dir, PREV_TESTBUILD_LOG_NAME)
        if (prev.exists()) prev.delete()
        main.renameTo(prev)
    }

    private fun firstLineContains(file: File, marker: String): Boolean = try {
        file.bufferedReader(StandardCharsets.UTF_8).use {
            it.readLine()?.contains(marker) == true
        }
    } catch (e: Exception) {
        false
    }

    /** Reads the current log plus the legacy connection log, oldest first. */
    @JvmStatic
    fun readAll(context: Context): String {
        val dir = RollingFileLog.logDirectory(context)
        val main = RollingFileLog.readLogFiles(dir, RollingFileLog.MAIN_LOG_NAME)
        // Builds before 2026-11-12 split connection logs into a second file.
        // Keep exporting it so an update does not hide earlier USB failures.
        val legacy = RollingFileLog.readLogFiles(dir, LEGACY_SERVICE_LOG_NAME)
        if (legacy.isEmpty()) return main
        if (main.isEmpty()) return legacy
        return "$main\n===== 兼容旧版连接日志 =====\n$legacy"
    }

    /** Clears the current log and any legacy connection log. */
    @JvmStatic
    fun clear(context: Context) {
        synchronized(this) {
            // 有些进程只起过设置页/调试页，init 可能没跑到或曾经失败。此前
            // 这种情况下只删了旧版连接日志，主日志原封不动，用户却看到
            // “日志已清除”，之后导出的仍是旧内容。这里先补一次 init，
            // 无论成败都保证把磁盘上的主日志删掉。
            if (sLog == null) {
                init(context)
                if (sLog == null) {
                    RollingFileLog.clearLogFiles(
                        RollingFileLog.logDirectory(context),
                        RollingFileLog.MAIN_LOG_NAME,
                    )
                }
            }
            sLog?.clear()
            RollingFileLog.clearLogFiles(
                RollingFileLog.logDirectory(context),
                LEGACY_SERVICE_LOG_NAME,
            )
            LogUtils.w(this, "日志已清除")
        }
    }
}
