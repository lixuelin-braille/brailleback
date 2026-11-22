/*
 * Copyright (C) 2011 Google Inc.
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

package com.google.android.libraries.accessibility.utils.log

import android.util.Log
import com.google.common.base.Strings
import java.util.IllegalFormatException

/** Handles logging formatted strings. */
class LogUtils private constructor() {

  /** Plug-in for custom printing complex objects, especially accessibility event & node. */
  interface ParameterCustomizer {
    /** Converts an object for display. */
    fun customize(`object`: Any?): Any?
  }

  companion object {
    /** Customizer for log parameters. By default, changes nothing. */
    private var parameterCustomizer: ParameterCustomizer? = null

    private const val TAG = "LogUtils"

    /**
     * The minimum log level that will be printed to the console. Set this to [Log.ERROR] for
     * release or [Log.VERBOSE] for debugging.
     */
    private var minLogLevel: Int = Log.ERROR

    private var logTagPrefix: String = ""

    /**
     * Set the prefix that will be prepended to all logging tags. This is useful for filtering logs
     * specific to a particular application.
     */
    @JvmStatic
    fun setTagPrefix(prefix: String) {
      logTagPrefix = prefix
    }

    /**
     * Logs a formatted string to the console.
     *
     * @param tag The tag that should be associated with the event
     * @param priority The log entry priority, see [Log.println(int, String, String)]
     * @param index The index of the log entry in the current log sequence
     * @param limit The maximum number of log entries allowed in the current sequence
     * @param format A format string, see [String.format(String, Object...)]
     * @param args String formatter arguments
     */
    @JvmStatic
    fun logWithLimit(
      tag: String,
      priority: Int,
      index: Int,
      limit: Int,
      format: String,
      vararg args: Any?,
    ) {
      val formatWithIndex: String =
        if (index > limit) {
          return
        } else if (index == limit) {
          String.format("%s (%d); further messages suppressed", format, index)
        } else {
          String.format("%s (%d)", format, index)
        }

      log(tag, priority, formatWithIndex, *args)
    }

    /**
     * Logs a string to the console at the VERBOSE log level.
     *
     * @param tag The tag that should be associated with the event
     * @param format A format string, see [String.format(String, Object...)]
     * @param args String formatter arguments
     */
    @JvmStatic
    fun v(tag: String, format: String?, vararg args: Any?) {
      log(tag, Log.VERBOSE, format, *args)
    }

    /**
     * Logs a string to the console.
     *
     * @param tag The tag that should be associated with the event
     * @param throwable A [Throwable] containing system state information to log
     * @param format A format string, see [String.format(String, Object...)]
     * @param args String formatter arguments
     */
    @JvmStatic
    fun v(tag: String, throwable: Throwable?, format: String?, vararg args: Any?) {
      log(tag, Log.VERBOSE, throwable, format, *args)
    }

    /**
     * Logs a string to the console at the DEBUG log level.
     *
     * @param tag The tag that should be associated with the event
     * @param format A format string, see [String.format(String, Object...)]
     * @param args String formatter arguments
     */
    @JvmStatic
    fun d(tag: String, format: String, vararg args: Any?) {
      log(tag, Log.DEBUG, format, *args)
    }

    /**
     * Logs a string to the console at the DEBUG log level.
     *
     * @param tag The tag that should be associated with the event
     * @param throwable A [Throwable] containing system state information to log
     * @param format A format string, see [String.format(String, Object...)]
     * @param args String formatter arguments
     */
    @JvmStatic
    fun d(tag: String, throwable: Throwable?, format: String, vararg args: Any?) {
      log(tag, Log.DEBUG, throwable, format, *args)
    }

    /**
     * Logs a string to the console at the INFO log level.
     *
     * @param tag The tag that should be associated with the event
     * @param format A format string, see [String.format(String, Object...)]
     * @param args String formatter arguments
     */
    @JvmStatic
    fun i(tag: String, format: String, vararg args: Any?) {
      log(tag, Log.INFO, format, *args)
    }

    /**
     * Logs a string to the console at the INFO log level.
     *
     * @param tag The tag that should be associated with the event
     * @param throwable A [Throwable] containing system state information to log
     * @param format A format string, see [String.format(String, Object...)]
     * @param args String formatter arguments
     */
    @JvmStatic
    fun i(tag: String, throwable: Throwable?, format: String, vararg args: Any?) {
      log(tag, Log.INFO, throwable, format, *args)
    }

    /**
     * Logs a string to the console at the WARN log level.
     *
     * @param tag The tag that should be associated with the event
     * @param format A format string, see [String.format(String, Object...)]
     * @param args String formatter arguments
     */
    @JvmStatic
    fun w(tag: String, format: String, vararg args: Any?) {
      log(tag, Log.WARN, format, *args)
    }

    /**
     * Logs a string to the console at the WARN log level.
     *
     * @param tag The tag that should be associated with the event
     * @param throwable A [Throwable] containing system state information to log
     * @param format A format string, see [String.format(String, Object...)]
     * @param args String formatter arguments
     */
    @JvmStatic
    fun w(tag: String, throwable: Throwable?, format: String, vararg args: Any?) {
      log(tag, Log.WARN, throwable, format, *args)
    }

    /**
     * Logs a string to the console at the ERROR log level.
     *
     * @param tag The tag that should be associated with the event
     * @param format A format string, see [String.format(String, Object...)]
     * @param args String formatter arguments
     */
    @JvmStatic
    fun e(tag: String, format: String?, vararg args: Any?) {
      log(tag, Log.ERROR, format, *args)
    }

    /**
     * Logs a string to the console at the ERROR log level.
     *
     * @param tag The tag that should be associated with the event
     * @param throwable A [Throwable] containing system state information to log
     * @param format A format string, see [String.format(String, Object...)]
     * @param args String formatter arguments
     */
    @JvmStatic
    fun e(tag: String, throwable: Throwable?, format: String, vararg args: Any?) {
      log(tag, Log.ERROR, throwable, format, *args)
    }

    /**
     * Logs a string to the console at the ASSERT log level.
     *
     * @param tag The tag that should be associated with the event
     * @param format A format string, see [String.format(String, Object...)]
     * @param args String formatter arguments
     */
    @JvmStatic
    fun wtf(tag: String, format: String, vararg args: Any?) {
      log(tag, Log.ASSERT, format, *args)
    }

    /**
     * Logs a string to the console at the ASSERT log level.
     *
     * @param tag The tag that should be associated with the event
     * @param throwable A [Throwable] containing system state information to log
     * @param format A format string, see [String.format(String, Object...)]
     * @param args String formatter arguments
     */
    @JvmStatic
    fun wtf(tag: String, throwable: Throwable?, format: String, vararg args: Any?) {
      log(tag, Log.ASSERT, throwable, format, *args)
    }

    /**
     * Logs a formatted string to the console.
     *
     * <p>Example usage: <br>
     * <code>
     * LogUtils.log("LogUtils", Log.ERROR, myException, "Invalid value: %d", value);
     * </code>
     *
     * @param tag The tag that should be associated with the event
     * @param priority The log entry priority, see [Log.println(int, String, String)]
     * @param throwable A [Throwable] containing system state information to log
     * @param format A format string, see [String.format(String, Object...)]
     * @param args String formatter arguments
     */
    @JvmStatic
    fun log(
      tag: String,
      priority: Int,
      throwable: Throwable?,
      format: String?,
      vararg args: Any?,
    ) {
      logInternal(tag, priority, throwable, format, args)
    }

    /**
     * Logs a formatted string to the console.
     *
     * <p>Example usage: <br>
     * <code>
     * LogUtils.log("LogUtils", Log.ERROR, "Invalid value: %d", value);
     * </code>
     *
     * @param tag The tag that should be associated with the event
     * @param priority The log entry priority, see [Log.println(int, String, String)]
     * @param format A format string, see [String.format(String, Object...)]
     * @param args String formatter arguments
     */
    @JvmStatic
    fun log(tag: String, priority: Int, format: String?, vararg args: Any?) {
      logInternal(tag, priority, null, format, args)
    }

    private fun logInternal(
      tag: String,
      priority: Int,
      throwable: Throwable?,
      format: String?,
      args: Array<out Any?>,
    ) {
      if (priority < minLogLevel) {
        return
      }

      val prefixedTag = logTagPrefix + tag

      // For each argument... replace with custom text.
      val formattedArgs: Array<Any?> = arrayOfNulls<Any?>(args.size)
      for (i in args.indices) {
        formattedArgs[i] = args[i]
      }
      val customizer = parameterCustomizer
      if (customizer != null) {
        for (a in formattedArgs.indices) {
          formattedArgs[a] = customizer.customize(formattedArgs[a])
        }
      }

      try {
        val message = String.format(Strings.nullToEmpty(format), *formattedArgs)
        if (throwable == null) {
          Log.println(priority, prefixedTag, message)
        } else {
          Log.println(
            priority,
            prefixedTag,
            String.format("%s\n%s", message, Log.getStackTraceString(throwable)),
          )
        }
      } catch (e: IllegalFormatException) {
        Log.e(TAG, "Bad formatting string: \"" + format + "\"", e)
      }
    }

    /** Sets customizer for log parameters. */
    @JvmStatic
    fun setParameterCustomizer(parameterCustomizerArg: ParameterCustomizer?) {
      parameterCustomizer = parameterCustomizerArg
    }

    /**
     * Sets the log display level.
     *
     * @param logLevel The minimum log level that will be printed to the console.
     */
    @JvmStatic
    fun setLogLevel(logLevel: Int) {
      minLogLevel = logLevel
    }

    /** Gets the log display level. */
    @JvmStatic
    fun getLogLevel(): Int = minLogLevel

    /** Returns the name of the current min log level. */
    @JvmStatic
    fun getLogLevelName(): String =
      when (minLogLevel) {
        Log.VERBOSE -> "VERBOSE"
        Log.DEBUG -> "DEBUG"
        Log.INFO -> "INFO"
        Log.WARN -> "WARN"
        Log.ERROR -> "ERROR"
        Log.ASSERT -> "ASSERT"
        else -> "UNKNOWN"
      }

    /**
     * Tells if the current settings are at or above the specified level of verboseness. This
     * indicates whether or not a log statement at the provided level will actually result in logging.
     *
     * @param logLevel The log level to compare the current setting to.
     */
    @JvmStatic
    fun shouldLog(logLevel: Int): Boolean {
      // Higher levels of verbosity correspond with lower int values.
      // https://developer.android.com/reference/android/util/Log.html#ASSERT
      return minLogLevel <= logLevel
    }
  }
}
