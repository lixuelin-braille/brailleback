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

    /** 导出硬上限 256 KB：无论分片多大，分享 / 复制出去的总量都不超过这个值，超出取尾部。 */
    const val EXPORT_MAX_BYTES = 256 * 1024

    /** 自愈归档（`.legacy-<时间戳>`）总量上限 2 MB，超出丢弃最旧。 */
    private const val LEGACY_ARCHIVE_MAX_BYTES = 2L * 1024 * 1024

    /** 自愈归档文件名前缀（主日志名 + 该后缀 + 时间戳）。 */
    private const val LEGACY_ARCHIVE_SUFFIX = ".legacy-"

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
            // 测试包：旧规格（4 MB 分片）残留先归档再清掉，不让超规格分片再被 readAll 合并导出。
            if (TestBuild.VERBOSE_BY_DEFAULT) {
                healOversizedRotations(
                    dir,
                    RollingFileLog.MAIN_LOG_NAME,
                    RollingFileLog.TEST_MAX_BYTES.toLong(),
                    LEGACY_ARCHIVE_MAX_BYTES,
                )
            }
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

    /**
     * Reads the current log plus the legacy connection log, oldest first,
     * then truncates to the last [maxBytes] UTF-8 bytes (line-aligned where possible).
     */
    @JvmStatic
    @JvmOverloads
    fun readAll(context: Context, maxBytes: Int = EXPORT_MAX_BYTES): String {
        val dir = RollingFileLog.logDirectory(context)
        // 已安装的实例带自己的 keepFiles（测试包 6 份）；未初始化时回落默认份数。
        // 不能只看主文件，也不能按默认 3 份漏读 .4–.6。
        val main = sLog?.readAll()
            ?: RollingFileLog.readLogFiles(dir, RollingFileLog.MAIN_LOG_NAME)
        // Builds before 2026-11-12 split connection logs into a second file.
        // Keep exporting it so an update does not hide earlier USB failures.
        val legacy = RollingFileLog.readLogFiles(dir, LEGACY_SERVICE_LOG_NAME)
        val full = when {
            legacy.isEmpty() -> main
            main.isEmpty() -> legacy
            else -> "$main\n===== 兼容旧版连接日志 =====\n$legacy"
        }
        return tailByBytes(full, maxBytes)
    }

    /**
     * 纯函数：按 UTF-8 字节数保留尾部区间，尽量按行对齐；单行就超限时按字节硬截尾。
     * 输入为空或未超限原样返回。
     */
    @JvmStatic
    fun tailByBytes(text: String, maxBytes: Int): String {
        if (text.isEmpty()) return text
        val raw = text.toByteArray(StandardCharsets.UTF_8)
        if (raw.size <= maxBytes) return text
        val lines = text.lines()
        val kept = ArrayList<String>()
        var used = 0
        for (i in lines.lastIndex downTo 0) {
            val line = lines[i]
            val lineBytes = (line + "\n").toByteArray(StandardCharsets.UTF_8).size
            if (used > 0 && used + lineBytes > maxBytes) break
            used += lineBytes
            kept.add(0, line)
        }
        val joined = kept.joinToString("\n")
        val bytes = joined.toByteArray(StandardCharsets.UTF_8)
        if (bytes.size <= maxBytes) return joined
        // 单行就超上限：按字节取尾部，避免整行被丢掉。
        return String(
            bytes.copyOfRange(bytes.size - maxBytes, bytes.size),
            StandardCharsets.UTF_8,
        )
    }

    /**
     * 启动自愈：把超过 [maxBytes] 的轮转分片（`<主日志>.<数字>`）归档为
     * `<主日志>.legacy-<时间戳>`，再删除原分片；归档总量超过 [legacyCapBytes] 时
     * 丢弃最旧。只动 logs 目录下本主日志的分片与归档，不碰偏好、书籍或其它文件。
     *
     * @return 本次被归档的文件名列表（便于排查 / 单测断言）。
     */
    @JvmStatic
    fun healOversizedRotations(
        dir: File,
        mainName: String,
        maxBytes: Long,
        legacyCapBytes: Long,
        now: Long = System.currentTimeMillis(),
    ): List<String> {
        if (!dir.isDirectory) return emptyList()
        val rotationRegex = Regex("^${Regex.escape(mainName)}\\.(\\d+)$")
        val moved = mutableListOf<String>()
        dir.listFiles()?.forEach { file ->
            if (!file.isFile || !rotationRegex.matches(file.name)) return@forEach
            if (file.length() <= maxBytes) return@forEach
            var target = File(dir, "$mainName$LEGACY_ARCHIVE_SUFFIX$now")
            var index = 1
            while (target.exists()) {
                target = File(dir, "$mainName$LEGACY_ARCHIVE_SUFFIX${now}-${index++}")
            }
            if (file.renameTo(target)) moved.add(target.name)
        }
        pruneLegacyArchives(dir, mainName, legacyCapBytes)
        return moved
    }

    /** 归档总量超限时按修改时间从旧到新丢弃；归档名带时间戳，同名同戳再按文件名排序兜底。 */
    private fun pruneLegacyArchives(dir: File, mainName: String, capBytes: Long) {
        val archives = dir.listFiles()
            ?.filter { it.isFile && it.name.startsWith("$mainName$LEGACY_ARCHIVE_SUFFIX") }
            ?.sortedWith(compareBy({ it.lastModified() }, { it.name }))
            ?: return
        var total = archives.sumOf { it.length() }
        for (archive in archives) {
            if (total <= capBytes) break
            total -= archive.length()
            archive.delete()
        }
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
