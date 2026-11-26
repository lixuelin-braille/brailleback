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
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Appends [LogUtils] entries to a size-bounded rolling file.
 *
 * BrailleBack runs its accessibility service and display service in one
 * process, so both must share a single writer. The app installs exactly one
 * [RollingFileLog] as the [LogUtils] file sink.
 */
class RollingFileLog @JvmOverloads constructor(
    private val directory: File,
    private val fileName: String,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
    private val keepFiles: Int = DEFAULT_KEEP_FILES,
    private val header: String? = null,
) : LogUtils.FileLogListener {

    private val lock = Any()
    private var writer: OutputStreamWriter? = null
    private var currentFile: File? = null

    /** The file currently being appended to, or null before [open]. */
    fun getCurrentFile(): File? = currentFile

    /** Opens (or creates) the log file. Safe to call more than once. */
    fun open(): Boolean {
        synchronized(lock) {
            if (writer != null) return true
            return try {
                if (!directory.isDirectory && !directory.mkdirs()) {
                    Log.w(TAG, "Could not create log directory $directory")
                    return false
                }
                val file = File(directory, fileName)
                currentFile = file
                writer = openWriterLocked(file) ?: return false
                true
            } catch (e: Exception) {
                Log.w(TAG, "Could not open log file $fileName", e)
                false
            }
        }
    }

    override fun onLog(priority: Int, tag: String, message: String) {
        synchronized(lock) {
            try {
                if (writer == null) return
                if (!shouldPersistToFile(priority, tag, message)) return
                // The other process may have cleared the log by deleting the
                // file; reopen instead of writing into an unlinked inode.
                if (currentFile?.exists() == false) {
                    reopenLocked()
                }
                rotateIfNeeded()
                val out = writer ?: return
                val time = SimpleDateFormat(TIME_FORMAT, Locale.US).format(Date())
                out.write("$time ${levelChar(priority)}/$tag: $message\n")
                out.flush()
            } catch (e: Exception) {
                // 落盘失败静默，不干扰主流程
            }
        }
    }

    /**
     * 判定一条日志是否落盘。logcat 不受此影响，被丢弃的行仍进系统日志。
     *
     * 隐私：无障碍事件 `toString()` 与节点详情会夹带 `ContentDescription`
     * 屏幕正文（实测抓到微信聊天全文）；体积：一条事件详情几百字节，100 KB
     * 窗口很快被吃满。所以事件流与节点工具链只进 logcat，不进文件。
     * 落盘以连接链路为主：连接 / 显示生命周期相关 TAG 保留 VERBOSE，
     * 其余 TAG 的 VERBOSE 不落盘，DEBUG 及以上照常。
     */
    private fun shouldPersistToFile(priority: Int, tag: String, message: String): Boolean {
        // 节点工具链的逐节点排查行只进 logcat。
        if (tag == ACCESSIBILITY_NODE_UTILS_TAG) return false
        // 无障碍事件摘要 / 详情 / 节点快照：只进 logcat。
        if (tag == BRAILLE_BACK_SERVICE_TAG && EVENT_PREFIXES.any { message.startsWith(it) }) {
            return false
        }
        // BrailleBackService 仅保留连接生命周期行，其余行不落盘。
        if (tag == BRAILLE_BACK_SERVICE_TAG) {
            return CONNECTION_KEYWORDS.any {
                message.contains(it, ignoreCase = true)
            }
        }
        if (TestBuild.VERBOSE_BY_DEFAULT &&
            priority == Log.VERBOSE && !isVerboseAllowedTag(tag)
        ) {
            return false
        }
        return true
    }

    /**
     * Opens an append writer for [file]; a freshly created empty file gets the
     * header marker as its first line. Every new current file (open / rotate /
     * reopen after delete / clear) goes through here, so each shard is tagged.
     */
    private fun openWriterLocked(file: File): OutputStreamWriter? = try {
        val w = OutputStreamWriter(
            FileOutputStream(file, true), StandardCharsets.UTF_8)
        if (header != null && file.length() == 0L) {
            w.write("$header\n")
            w.flush()
        }
        w
    } catch (e: Exception) {
        Log.w(TAG, "Could not open log file $fileName", e)
        null
    }

    private fun reopenLocked() {
        val file = currentFile ?: return
        file.parentFile?.mkdirs()
        writer = openWriterLocked(file)
    }

    private fun isVerboseAllowedTag(tag: String): Boolean =
        VERBOSE_ALLOWED_TAGS.contains(tag)

    /** Rolls `.log` -> `.1` -> ... -> `.keepFiles`, dropping the oldest. */
    private fun rotateIfNeeded() {
        val current = currentFile ?: return
        if (current.length() < maxBytes) return
        try {
            writer?.close()
            val oldest = File(directory, "$fileName.$keepFiles")
            if (oldest.exists()) oldest.delete()
            for (i in keepFiles - 1 downTo 1) {
                val f = File(directory, "$fileName.$i")
                if (f.exists()) f.renameTo(File(directory, "$fileName.${i + 1}"))
            }
            current.renameTo(File(directory, "$fileName.1"))
            writer = openWriterLocked(current)
        } catch (e: Exception) {
            Log.w(TAG, "Could not rotate log file $fileName", e)
        }
    }

    /** Reads this file, oldest rotation first. */
    fun readAll(): String {
        synchronized(lock) {
            return readLogFiles(directory, fileName, keepFiles)
        }
    }

    /** Deletes this file and its rotations. */
    fun clear() {
        synchronized(lock) {
            try {
                writer?.close()
                writer = null
                clearLogFiles(directory, fileName, keepFiles)
                val file = File(directory, fileName)
                currentFile = file
                writer = openWriterLocked(file)
            } catch (e: Exception) {
                Log.w(TAG, "Could not clear log file $fileName", e)
            }
        }
    }

    companion object {
        /** Directory under `filesDir` holding all log files. */
        const val LOG_DIRECTORY = "logs"

        /** Log written by the main process. */
        const val MAIN_LOG_NAME = "brailleback.log"
        /** Separate connection log written by earlier builds; still exported. */
        const val LEGACY_SERVICE_LOG_NAME = "brailleback-service.log"

        const val DEFAULT_MAX_BYTES = 256L * 1024L
        const val DEFAULT_KEEP_FILES = 3

        /** 测试包单独一组：单文件 100 KB、保留 6 份（总占用约 600 KB）；正式包沿用上面的默认值。 */
        const val TEST_MAX_BYTES = 100L * 1024L
        const val TEST_KEEP_FILES = 6

        /**
         * 测试包 VERBOSE 落盘白名单：连接 / 显示生命周期相关 TAG。
         * 其余 TAG 的 VERBOSE 不落盘（DEBUG 及以上照常），避免逐节点事件详情
         * 把滚动日志刷掉。TAG 取 `LogUtils` 由类名派生的 simpleName 或显式 TAG。
         */
        private val VERBOSE_ALLOWED_TAGS = setOf(
            "UsbDisplayConnection",
            "HidDisplayConnection",
            "BluetoothConnection",
            "WujieGattConnection",
            "DeviceFinder",
            "DisplayService",
            "BrlttyWrapper",
            "BrailleBackService",
        )

        /** 节点工具链 TAG：其逐节点排查行只进 logcat，不落盘。 */
        private const val ACCESSIBILITY_NODE_UTILS_TAG = "AccessibilityNodeInfoUtils"

        /** 主服务 TAG（`LogUtils.log(this, ...)` 派生 simpleName）。 */
        private const val BRAILLE_BACK_SERVICE_TAG = "BrailleBackService"

        /** 无障碍事件行前缀：摘要 / 详情 / 节点快照，一律不落盘。 */
        private val EVENT_PREFIXES = listOf("Event:", "Event detail:", "Node detail:")

        /** BrailleBackService 允许落盘的连接生命周期关键词。 */
        private val CONNECTION_KEYWORDS = listOf(
            "Starting connection",
            "route",
            "reconnect attempt",
            "connected",
            "disconnected",
        )

        private const val TIME_FORMAT = "yyyy-MM-dd HH:mm:ss.SSS"
        private const val TAG = "RollingFileLog"

        @JvmStatic
        fun logDirectory(context: Context): File =
            File(context.filesDir, LOG_DIRECTORY)

        /** Reads one log file including its rotations, oldest first. */
        @JvmStatic
        @JvmOverloads
        fun readLogFiles(
            directory: File,
            fileName: String,
            keepFiles: Int = DEFAULT_KEEP_FILES
        ): String {
            val sb = StringBuilder()
            for (i in keepFiles downTo 1) {
                val f = File(directory, "$fileName.$i")
                if (f.isFile) {
                    try {
                        sb.append(f.readText(StandardCharsets.UTF_8))
                    } catch (e: Exception) {
                        // 单个文件损坏跳过，不影响其余
                    }
                }
            }
            val current = File(directory, fileName)
            if (current.isFile) {
                try {
                    sb.append(current.readText(StandardCharsets.UTF_8))
                } catch (e: Exception) {
                    // 单个文件损坏跳过，不影响其余
                }
            }
            return sb.toString()
        }

        /** Deletes one log file and its rotations. */
        @JvmStatic
        @JvmOverloads
        fun clearLogFiles(
            directory: File,
            fileName: String,
            keepFiles: Int = DEFAULT_KEEP_FILES
        ) {
            File(directory, fileName).delete()
            for (i in 1..keepFiles) {
                File(directory, "$fileName.$i").delete()
            }
        }

        private fun levelChar(priority: Int): Char = when (priority) {
            Log.VERBOSE -> 'V'
            Log.DEBUG -> 'D'
            Log.INFO -> 'I'
            Log.WARN -> 'W'
            Log.ERROR -> 'E'
            Log.ASSERT -> 'A'
            else -> '?'
        }
    }
}