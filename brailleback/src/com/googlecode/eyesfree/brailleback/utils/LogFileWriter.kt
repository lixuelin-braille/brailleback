/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 *
 * 文件日志：把 LogUtils 的日志同时写入应用私有目录，支持循环滚动、
 * 一键查看/导出。目的是在无 adb 调试环境（如用户真机）下也能拿到
 * 运行日志定位问题。
 *
 * 文件位置：getFilesDir()/logs/brailleback.log（超过 256KB 滚动为
 * .1.log/.2.log/.3.log，保留最近 3 份）。所有组件在同一进程，单例写入安全。
 */

package com.googlecode.eyesfree.brailleback.utils

import android.content.Context
import android.util.Log
import com.googlecode.eyesfree.utils.LogUtils
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 文件日志写入器，实现 [LogUtils.FileLogListener]。
 *
 * 初始化后所有经 LogUtils 输出且达到当前日志级别的条目都会同步落盘。
 */
object LogFileWriter : LogUtils.FileLogListener {

    private const val LOG_DIR = "logs"
    private const val LOG_NAME = "brailleback.log"
    private const val MAX_BYTES = 256 * 1024
    private const val KEEP_FILES = 3

    @Volatile
    private var sInitialized = false
    private var sWriter: OutputStreamWriter? = null
    private var sCurrent: File? = null

    /** 初始化文件日志并注册为 LogUtils 的落盘目标。幂等，可多次调用。 */
    @JvmStatic
    fun init(context: Context) {
        if (sInitialized) return
        synchronized(this) {
            if (sInitialized) return
            try {
                val dir = File(context.filesDir, LOG_DIR)
                if (!dir.exists()) dir.mkdirs()
                sCurrent = File(dir, LOG_NAME)
                sWriter = OutputStreamWriter(
                    FileOutputStream(sCurrent, true), StandardCharsets.UTF_8)
                sInitialized = true
                LogUtils.setFileLogListener(this)
                LogUtils.w(this, "文件日志已启用: %s", sCurrent)
            } catch (e: Exception) {
                LogUtils.e(this, "初始化文件日志失败: %s", e.message)
            }
        }
    }

    override fun onLog(priority: Int, tag: String, message: String) {
        val writer = sWriter ?: return
        synchronized(this) {
            try {
                rotateIfNeeded()
                val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
                    .format(Date())
                writer.write("$time ${levelChar(priority)}/$tag: $message\n")
                writer.flush()
            } catch (e: Exception) {
                // 落盘失败静默，不干扰主流程
            }
        }
    }

    /** 当前文件超限则滚动：.log -> .1.log -> .2.log -> .3.log，删最旧。 */
    private fun rotateIfNeeded() {
        val current = sCurrent ?: return
        if (current.length() < MAX_BYTES) return
        try {
            sWriter?.close()
            val dir = current.parentFile ?: return
            val oldest = File(dir, "$LOG_NAME.$KEEP_FILES")
            if (oldest.exists()) oldest.delete()
            for (i in KEEP_FILES - 1 downTo 1) {
                val f = File(dir, "$LOG_NAME.$i")
                if (f.exists()) f.renameTo(File(dir, "$LOG_NAME.${i + 1}"))
            }
            current.renameTo(File(dir, "$LOG_NAME.1"))
            sWriter = OutputStreamWriter(
                FileOutputStream(current, true), StandardCharsets.UTF_8)
        } catch (e: Exception) {
            LogUtils.e(this, "日志滚动失败: %s", e.message)
        }
    }

    /** 读取全部日志文本（按时间先后：历史在前，当前文件最后）。 */
    @JvmStatic
    fun readAll(context: Context): String {
        val dir = File(context.filesDir, LOG_DIR)
        if (!dir.exists()) return ""
        val sb = StringBuilder()
        val files = ArrayList<File>()
        for (i in KEEP_FILES downTo 1) {
            val f = File(dir, "$LOG_NAME.$i")
            if (f.exists()) files.add(f)
        }
        val cur = File(dir, LOG_NAME)
        if (cur.exists()) files.add(cur)
        for (f in files) {
            try {
                sb.append(f.readText(StandardCharsets.UTF_8))
            } catch (e: Exception) {
                // 单个文件损坏跳过，不影响其余
            }
        }
        return sb.toString()
    }

    /** 清空全部日志文件（保留文件句柄继续写入）。 */
    @JvmStatic
    fun clear(context: Context) {
        synchronized(this) {
            try {
                sWriter?.close()
                sWriter = null
                val dir = File(context.filesDir, LOG_DIR)
                dir.listFiles()?.forEach { it.delete() }
                val current = File(dir, LOG_NAME)
                sWriter = OutputStreamWriter(
                    FileOutputStream(current, true), StandardCharsets.UTF_8)
                LogUtils.w(this, "日志已清除")
            } catch (e: Exception) {
                LogUtils.e(this, "清除日志失败: %s", e.message)
            }
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
