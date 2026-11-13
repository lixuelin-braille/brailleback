/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 *
 * 兼容入口：文件日志的实现已移到 libraries/utils，供无障碍服务、显示服务和
 * 设置页共用同一个 RollingFileLog。这里保留原有调用点所需的 API。
 *
 * 文件位置：getFilesDir()/logs/brailleback.log（256KB 滚动为 .1/.2/.3）
 */

package com.googlecode.eyesfree.brailleback.utils

import android.content.Context
import com.googlecode.eyesfree.utils.LogFileWriter as SharedLogFileWriter

object LogFileWriter {

    /** 初始化文件日志并注册为 LogUtils 的落盘目标。幂等。 */
    @JvmStatic
    fun init(context: Context) {
        SharedLogFileWriter.init(context)
    }

    /** 读取全部日志文本（历史在前，当前文件最后）。 */
    @JvmStatic
    fun readAll(context: Context): String = SharedLogFileWriter.readAll(context)

    /** 清空全部日志文件（保留写入句柄继续写入）。 */
    @JvmStatic
    fun clear(context: Context) {
        SharedLogFileWriter.clear(context)
    }
}