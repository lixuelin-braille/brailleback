/*
 * Copyright (C) 2017 The Android Open Source Project
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

import android.os.Build
import android.text.TextUtils
import androidx.annotation.ChecksSdkIntAtLeast
import java.util.Objects

/**
 * This file provides a wrapper for the Build versions. Everytime an android version number gets
 * fixed, this file should be updated. Generally, BuildCompat.isAtLeast*() works before android
 * release is finalized, Build.VERSION_CODES.* works after.
 */
class BuildVersionUtils private constructor() {

  companion object {
    @JvmStatic
    fun isM(): Boolean {
      return Build.VERSION.SDK_INT == Build.VERSION_CODES.M
    }

    @JvmStatic
    fun isAtLeastN(): Boolean {
      return Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
    }

    @JvmStatic
    fun isAtLeastNMR1(): Boolean {
      return Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1
    }

    @JvmStatic
    fun isAtLeastO(): Boolean {
      return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
    }

    @JvmStatic
    fun isAtLeastOMR1(): Boolean {
      return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1
    }

    @JvmStatic
    fun isAtLeastP(): Boolean {
      return Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
    }

    @JvmStatic
    fun isAtLeastQ(): Boolean {
      return Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
    }

    @JvmStatic
    fun isAtLeastR(): Boolean {
      return Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
    }

    @JvmStatic
    fun isAtLeastS(): Boolean {
      return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    }

    @ChecksSdkIntAtLeast(api = Build.VERSION_CODES.S_V2)
    @JvmStatic
    fun isAtLeastS2(): Boolean {
      return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S_V2
    }

    @ChecksSdkIntAtLeast(api = 33)
    @JvmStatic
    fun isAtLeastT(): Boolean {
      return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    }

    @ChecksSdkIntAtLeast(api = 34)
    @JvmStatic
    fun isAtLeastU(): Boolean {
      return Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
    }

    @ChecksSdkIntAtLeast(api = 35)
    @JvmStatic
    fun isAtLeastV(): Boolean {
      return Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM
    }

    @JvmStatic
    fun isSystemPreload(): Boolean {
      return TextUtils.equals(BuildConfig.IS_SYSTEM_PRELOAD, "True")
    }

    @ChecksSdkIntAtLeast(api = 36)
    @JvmStatic
    fun isAtLeastBaklava(): Boolean {
      // TODO: Update to Build.VERSION_CODES.BAKLAVA when it's available
      // to compile in all builds.
      return Build.VERSION.SDK_INT >= 36
    }

    /** Returns true if device is running Robolectric. */
    @JvmStatic
    fun isRobolectric(): Boolean {
      return Objects.equals(Build.FINGERPRINT, "robolectric")
    }
  }
}
