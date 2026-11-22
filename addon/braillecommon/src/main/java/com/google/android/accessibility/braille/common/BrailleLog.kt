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

import com.google.android.libraries.accessibility.utils.log.LogUtils

/** A logging handler for braille. */
class BrailleLog private constructor() {

  companion object {
    /** Logs at Android "verbose" level. */
    @JvmStatic
    fun v(logTag: String, tag: String, message: String) {
      LogUtils.v(logTag, "%s: %s", tag, message)
    }

    /** Logs at Android "debug" level. Logs a */
    @JvmStatic
    fun d(logTag: String, tag: String, message: String) {
      LogUtils.d(logTag, "%s: %s", tag, message)
    }

    /** Logs at Android "information" level. */
    @JvmStatic
    fun i(logTag: String, tag: String, message: String) {
      LogUtils.i(logTag, "%s: %s", tag, message)
    }

    /** Logs at Android "warning" level. */
    @JvmStatic
    fun w(logTag: String, tag: String, message: String) {
      LogUtils.w(logTag, "%s: %s", tag, message)
    }

    /** Logs at Android "warning" level with {@code Throwable}. */
    @JvmStatic
    fun w(logTag: String, tag: String, message: String, throwable: Throwable) {
      LogUtils.w(logTag, throwable, "%s: %s", tag, message)
    }

    /** Logs at Android "error" level with {@code Throwable}. */
    @JvmStatic
    fun e(logTag: String, tag: String, message: String, throwable: Throwable) {
      LogUtils.e(logTag, throwable, "%s: %s", tag, message)
    }

    /** Logs at Android "error" level. */
    @JvmStatic
    fun e(logTag: String, tag: String, message: String) {
      LogUtils.e(logTag, null as Throwable?, "%s: %s", tag, message)
    }
  }
}
