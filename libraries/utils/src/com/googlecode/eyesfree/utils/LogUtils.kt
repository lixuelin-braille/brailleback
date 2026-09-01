/*
 * Copyright (C) 2014 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.googlecode.eyesfree.utils

import android.util.Log
import java.util.IllegalFormatException

/** Handles logging formatted strings. */
object LogUtils {
    private const val TAG = "LogUtils"

    /**
     * The minimum log level that will be printed to the console. Set this to
     * [Log.ERROR] for release or [Log.VERBOSE] for debugging.
     */
    private var sLogLevel = Log.ERROR

    /**
     * Optional file-log sink. When set, every log entry (subject to [sLogLevel])
     * is additionally forwarded to this listener, e.g. to persist to a file so
     * the user can export it without a debugger attached.
     */
    private var sFileLogListener: FileLogListener? = null

    /** Receives log entries for persistence (file logging). */
    interface FileLogListener {
        /**
         * @param priority The log priority, see [Log.println].
         * @param tag The source class simple name (or [TAG] when source is null).
         * @param message The fully formatted log message (may contain a stack trace).
         */
        fun onLog(priority: Int, tag: String, message: String)
    }

    /** Registers a listener that receives all logged entries (or null to disable). */
    @JvmStatic
    fun setFileLogListener(listener: FileLogListener?) {
        sFileLogListener = listener
    }

    /**
     * Logs a formatted string to the console using the source object's name as
     * the log tag. If the source object is null, the default tag (see [TAG]) is
     * used.
     *
     * Example usage:
     * ```
     * LogUtils.log(this, Log.ERROR, "Invalid value: %d", value)
     * ```
     *
     * @param source The object that generated the log event.
     * @param priority The log entry priority, see [Log.println].
     * @param throwable A [Throwable] containing system state information to log.
     * @param format A format string, see [String.format].
     * @param args String formatter arguments.
     */
    @JvmStatic
    fun log(
        source: Any?,
        priority: Int,
        throwable: Throwable?,
        format: String,
        vararg args: Any?
    ) {
        if (priority < sLogLevel) {
            return
        }

        val sourceClass = when {
            source == null -> TAG
            source is Class<*> -> source.simpleName
            else -> source.javaClass.simpleName
        }

        try {
            var message = String.format(format, *args)
            if (throwable == null) {
                Log.println(priority, sourceClass, message)
            } else {
                message = String.format("%s\n%s", message, Log.getStackTraceString(throwable))
                Log.println(priority, sourceClass, message)
            }
            sFileLogListener?.onLog(priority, sourceClass, message)
        } catch (e: IllegalFormatException) {
            Log.e(TAG, "Bad formatting string: \"" + format + "\"", e)
        }
    }

    /**
     * Logs a formatted string to the console using the source object's name as
     * the log tag. If the source object is null, the default tag (see [TAG]) is
     * used.
     *
     * Example usage:
     * ```
     * LogUtils.log(this, Log.ERROR, "Invalid value: %d", value)
     * ```
     *
     * @param source The object that generated the log event.
     * @param priority The log entry priority, see [Log.println].
     * @param format A format string, see [String.format].
     * @param args String formatter arguments.
     */
    @JvmStatic
    fun log(source: Any?, priority: Int, format: String, vararg args: Any?) {
        log(source, priority, null, format, *args)
    }

    /**
     * Logs a formatted string to the console using the default tag (see [TAG]).
     *
     * @param priority The log entry priority, see [Log.println].
     * @param format A format string, see [String.format].
     * @param args String formatter arguments.
     */
    @JvmStatic
    fun log(priority: Int, format: String, vararg args: Any?) {
        log(null, priority, format, *args)
    }

    /** Logs at verbose level. @see #log(Object, int, String, Object...) */
    @JvmStatic
    fun v(source: Any?, format: String, vararg args: Any?) {
        log(source, Log.VERBOSE, format, *args)
    }

    /** Logs at verbose level with a throwable. @see #log(Object, int, Throwable, String, Object...) */
    @JvmStatic
    fun v(source: Any?, throwable: Throwable?, format: String, vararg args: Any?) {
        log(source, Log.VERBOSE, throwable, format, *args)
    }

    /** Logs at debug level. @see #log(Object, int, String, Object...) */
    @JvmStatic
    fun d(source: Any?, format: String, vararg args: Any?) {
        log(source, Log.DEBUG, format, *args)
    }

    /** Logs at debug level with a throwable. @see #log(Object, int, Throwable, String, Object...) */
    @JvmStatic
    fun d(source: Any?, throwable: Throwable?, format: String, vararg args: Any?) {
        log(source, Log.DEBUG, throwable, format, *args)
    }

    /** Logs at info level. @see #log(Object, int, String, Object...) */
    @JvmStatic
    fun i(source: Any?, format: String, vararg args: Any?) {
        log(source, Log.INFO, format, *args)
    }

    /** Logs at info level with a throwable. @see #log(Object, int, Throwable, String, Object...) */
    @JvmStatic
    fun i(source: Any?, throwable: Throwable?, format: String, vararg args: Any?) {
        log(source, Log.INFO, throwable, format, *args)
    }

    /** Logs at warning level. @see #log(Object, int, String, Object...) */
    @JvmStatic
    fun w(source: Any?, format: String, vararg args: Any?) {
        log(source, Log.WARN, format, *args)
    }

    /** Logs at warning level with a throwable. @see #log(Object, int, Throwable, String, Object...) */
    @JvmStatic
    fun w(source: Any?, throwable: Throwable?, format: String, vararg args: Any?) {
        log(source, Log.WARN, throwable, format, *args)
    }

    /** Logs at error level. @see #log(Object, int, String, Object...) */
    @JvmStatic
    fun e(source: Any?, format: String, vararg args: Any?) {
        log(source, Log.ERROR, format, *args)
    }

    /** Logs at error level with a throwable. @see #log(Object, int, Throwable, String, Object...) */
    @JvmStatic
    fun e(source: Any?, throwable: Throwable?, format: String, vararg args: Any?) {
        log(source, Log.ERROR, throwable, format, *args)
    }

    /** Logs at assert level. @see #log(Object, int, String, Object...) */
    @JvmStatic
    fun wtf(source: Any?, format: String, vararg args: Any?) {
        log(source, Log.ASSERT, format, *args)
    }

    /** Logs at assert level with a throwable. @see #log(Object, int, Throwable, String, Object...) */
    @JvmStatic
    fun wtf(source: Any?, throwable: Throwable?, format: String, vararg args: Any?) {
        log(source, Log.ASSERT, throwable, format, *args)
    }

    /**
     * Sets the log display level.
     *
     * @param logLevel The minimum log level that will be printed to the console.
     */
    @JvmStatic
    fun setLogLevel(logLevel: Int) {
        sLogLevel = logLevel
    }

    /**
     * Tells if the current settings are at or above the specified level of
     * verboseness. This indicates whether or not a log statement at the provided
     * level will actually result in logging.
     *
     * @param logLevel The log level to compare the current setting to.
     */
    @JvmStatic
    fun shouldLog(logLevel: Int): Boolean {
        // Higher levels of verbosity correspond with lower int values.
        // https://developer.android.com/reference/android/util/Log.html#ASSERT
        return sLogLevel <= logLevel
    }

    /** Gets the current level logging is configured for. */
    @JvmStatic
    fun getLogLevel(): Int {
        return sLogLevel
    }
}
