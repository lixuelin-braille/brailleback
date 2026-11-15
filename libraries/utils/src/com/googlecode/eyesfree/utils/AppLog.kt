/*
 * Copyright (C) 2026 The BrailleBack Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.googlecode.eyesfree.utils

import android.util.Log

/**
 * 全仓唯一的日志出口。
 *
 * 背景：此前 app 进程的代码直接用 `android.util.Log`，只有 `DisplayService`
 * 里的 `Timber` 会经过 [LogUtils] 落到可分享的文件日志。结果是用户报障时，
 * 连接、导航、输入这些 app 侧的日志只在 logcat 里，抓不到文件里。
 *
 * 这里保留 `Log.d/i/w/e/v` 的**参数顺序**（tag 在前、Throwable 在后），
 * 因为 163 个调用点都是这个形状；统一出口不付出改写调用点的代价。
 *
 * 实现只有一条路：[LogUtils.logWithTag]，它同时写 logcat 与文件日志（文件
 * 未初始化时自然退化到 logcat）。不再额外套 Timber，避免同一行日志写两遍。
 */
object AppLog {

    fun v(tag: String, message: String) = emit(Log.VERBOSE, tag, message, null)

    fun v(tag: String, message: String, throwable: Throwable) =
        emit(Log.VERBOSE, tag, message, throwable)

    fun d(tag: String, message: String) = emit(Log.DEBUG, tag, message, null)

    fun d(tag: String, message: String, throwable: Throwable) =
        emit(Log.DEBUG, tag, message, throwable)

    fun i(tag: String, message: String) = emit(Log.INFO, tag, message, null)

    fun i(tag: String, message: String, throwable: Throwable) =
        emit(Log.INFO, tag, message, throwable)

    fun w(tag: String, message: String) = emit(Log.WARN, tag, message, null)

    fun w(tag: String, message: String, throwable: Throwable) =
        emit(Log.WARN, tag, message, throwable)

    fun e(tag: String, message: String) = emit(Log.ERROR, tag, message, null)

    fun e(tag: String, message: String, throwable: Throwable) =
        emit(Log.ERROR, tag, message, throwable)

    private fun emit(priority: Int, tag: String, message: String, throwable: Throwable?) {
        LogUtils.logWithTag(priority, tag, throwable, message)
    }
}
