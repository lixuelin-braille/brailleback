/*
 * Copyright 2023 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.android.accessibility.braille.common

/** Log handler which unifies logging under a single, BrailleCommon-specific tag. */
class BrailleCommonLog private constructor() {

  companion object {
    private const val TAG = "BrailleCommon"

    /** Logs at Android "verbose" level. */
    @JvmStatic
    fun v(tag: String, message: String) {
      BrailleLog.v(TAG, tag, message)
    }

    /** Logs at Android "debug" level. Logs a */
    @JvmStatic
    fun d(tag: String, message: String) {
      BrailleLog.d(TAG, tag, message)
    }

    /** Logs at Android "information" level. */
    @JvmStatic
    fun i(tag: String, message: String) {
      BrailleLog.i(TAG, tag, message)
    }

    /** Logs at Android "warning" level. */
    @JvmStatic
    fun w(tag: String, message: String) {
      BrailleLog.w(TAG, tag, message)
    }

    /** Logs at Android "warning" level with {@code Throwable}. */
    @JvmStatic
    fun w(tag: String, message: String, throwable: Throwable) {
      BrailleLog.w(TAG, tag, message, throwable)
    }

    /** Logs at Android "error" level with {@code Throwable}. */
    @JvmStatic
    fun e(tag: String, message: String, throwable: Throwable) {
      BrailleLog.e(TAG, tag, message, throwable)
    }

    /** Logs at Android "error" level. */
    @JvmStatic
    fun e(tag: String, message: String) {
      BrailleLog.e(TAG, tag, message)
    }
  }
}
