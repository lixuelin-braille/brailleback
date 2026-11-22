/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.google.android.accessibility.utils

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.res.Configuration
import android.provider.Settings
import android.text.TextUtils
import android.view.inputmethod.InputMethodInfo
import android.view.inputmethod.InputMethodManager

/** Helper class for keyboard utility functions */
class KeyboardUtils private constructor() {

  companion object {
    /**
     * Returns true if either soft or hard keyboard is active.
     *
     * @param service Accessibility Service that is currently trying to get keyboard state.
     * @return {@code true} if either soft or hard keyboard is active, else {@code false}.
     */
    // TODO: Move the logic of updating keyboard state into WindowTracker.
    @JvmStatic
    fun isKeyboardActive(service: AccessibilityService): Boolean {
      return isSoftKeyboardActive(service) || isHardKeyboardActive(service)
    }

    /** Returns {@code true} if soft keyboard is active. */
    @JvmStatic
    fun isSoftKeyboardActive(service: AccessibilityService): Boolean {
      return AccessibilityServiceCompatUtils.isInputWindowOnScreen(service)
    }

    /** Returns {@code true} if hard keyboard is active. */
    @JvmStatic
    fun isHardKeyboardActive(service: AccessibilityService?): Boolean {
      if (service == null) {
        return false
      }
      val config = service.resources.configuration

      return config.hardKeyboardHidden == Configuration.HARDKEYBOARDHIDDEN_NO
    }

    /** Returns {@code true} if {@code componentName} is an enabled input method */
    @JvmStatic
    fun isImeEnabled(context: Context, componentName: ComponentName): Boolean {
      for (inputMethodInfo in getEnabledInputMethodList(context)) {
        if (inputMethodInfo.component.equals(componentName)) {
          return true
        }
      }
      return false
    }

    /** Returns {@code true} if the system has more than one IME enabled. */
    @JvmStatic
    fun areMultipleImesEnabled(context: Context): Boolean {
      val list = getEnabledInputMethodList(context)
      return list.size > 1
    }

    /** Gets id of first enabled {@link InputMethodInfo}whose package matches {@code packageName}. */
    @JvmStatic
    fun getEnabledImeId(context: Context, packageName: String): String {
      for (inputMethodInfo in getEnabledInputMethodList(context)) {
        if (inputMethodInfo.packageName.equals(packageName)) {
          return inputMethodInfo.id
        }
      }
      return ""
    }

    /** Gets id of first {@link InputMethodInfo} whose package matches {@code packageName}. */
    @JvmStatic
    fun getImeId(context: Context, packageName: String): String {
      val inputMethodManager =
        context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager?
      if (inputMethodManager != null) {
        val inputMethodInfoList = inputMethodManager.inputMethodList ?: emptyList()
        for (inputMethodInfo in inputMethodInfoList) {
          if (inputMethodInfo.packageName.equals(packageName)) {
            return inputMethodInfo.id
          }
        }
      }
      return ""
    }

    /**
     * Returns next enabled ime id. If it's the tail of enabled list, return the first enabled ime.
     * Empty if it's the only enabled ime.
     */
    @JvmStatic
    fun getNextEnabledImeId(context: Context): String {
      var id = ""
      var firstId = ""
      var next = false
      for (inputMethodInfo in getEnabledInputMethodList(context)) {
        if (next) {
          id = inputMethodInfo.id
          break
        } else if (inputMethodInfo.packageName.equals(context.packageName)) {
          next = true
        } else if (TextUtils.isEmpty(firstId)) {
          firstId = inputMethodInfo.id
        }
      }
      return if (next && TextUtils.isEmpty(id)) firstId else id
    }

    private fun getEnabledInputMethodList(context: Context): List<InputMethodInfo> {
      val inputMethodManager =
        context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager?
      if (inputMethodManager != null) {
        val inputMethodInfoList = inputMethodManager.enabledInputMethodList
        if (inputMethodInfoList != null) {
          return inputMethodInfoList
        }
      }
      return mutableListOf()
    }

    /** Returns current input method. */
    @JvmStatic
    fun getCurrentInputMethod(context: Context): String? {
      return Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.DEFAULT_INPUT_METHOD,
      )
    }
  }
}
