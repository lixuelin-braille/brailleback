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

/**
 * Single entry point for the app's persistent log.
 *
 * BrailleBackService (accessibility service) and the display service share one
 * process, so only one [RollingFileLog] may be installed as the [LogUtils] sink.
 * Whichever component starts first calls [init]; later calls are no-ops.
 */
object LogFileWriter {

    private const val LEGACY_SERVICE_LOG_NAME = RollingFileLog.LEGACY_SERVICE_LOG_NAME

    @Volatile
    private var sLog: RollingFileLog? = null

    /** Opens the log file and registers it as the [LogUtils] sink. Idempotent. */
    @JvmStatic
    fun init(context: Context) {
        if (sLog != null) return
        synchronized(this) {
            if (sLog != null) return
            val log = RollingFileLog(
                RollingFileLog.logDirectory(context),
                RollingFileLog.MAIN_LOG_NAME,
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
