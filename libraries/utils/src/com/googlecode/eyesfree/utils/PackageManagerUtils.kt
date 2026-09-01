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

package com.googlecode.eyesfree.utils

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.text.TextUtils
import android.util.Log

/**
 * Utilities for interacting with the [PackageManager].
 *
 * @author alanv@google.com (Alan Viverette)
 */
object PackageManagerUtils {
    /** Invalid version code for a package. */
    const val INVALID_VERSION_CODE = -1

    /**
     * @return The package version code or [INVALID_VERSION_CODE] if the
     *         package does not exist.
     */
    @JvmStatic
    fun getVersionCode(context: Context, packageName: CharSequence?): Int {
        if (TextUtils.isEmpty(packageName)) {
            return INVALID_VERSION_CODE
        }
        val packageInfo = getPackageInfo(context, packageName)
        if (packageInfo == null) {
            LogUtils.log(
                PackageManagerUtils::class.java, Log.ERROR,
                "Could not find package: %s", packageName)
            return INVALID_VERSION_CODE
        }
        return packageInfo.versionCode
    }

    /**
     * @return The package version name or `null` if the package does not
     *         exist.
     */
    @JvmStatic
    fun getVersionName(context: Context, packageName: CharSequence?): String? {
        if (TextUtils.isEmpty(packageName)) {
            return null
        }
        val packageInfo = getPackageInfo(context, packageName)
        if (packageInfo == null) {
            LogUtils.log(
                PackageManagerUtils::class.java, Log.ERROR,
                "Could not find package: %s", packageName)
            return null
        }
        return packageInfo.versionName
    }

    /**
     * @return Whether the package is installed on the device.
     */
    @JvmStatic
    fun hasPackage(context: Context, packageName: String?): Boolean {
        return getPackageInfo(context, packageName) != null
    }

    /**
     * Returns a developer-provided description for the primary application of
     * the provided package name. If the developer provided no name, the
     * package name is used. If the package is not installed on the system,
     * `null` is returned.
     *
     * @param context [Context] used to resolve package information
     * @param packageName The package name of the application
     * @return A developer-provided description of the application, or `null`
     *         if the package is not installed on the system.
     */
    @JvmStatic
    fun getPackageDescription(context: Context?, packageName: CharSequence?): CharSequence? {
        if (context == null || TextUtils.isEmpty(packageName)) {
            return null
        }
        return try {
            val pm = context.packageManager
            val info: ApplicationInfo = pm.getApplicationInfo(packageName.toString(), 0)
            info.loadLabel(pm)
        } catch (nnfe: PackageManager.NameNotFoundException) {
            null
        }
    }

    private fun getPackageInfo(context: Context, packageName: CharSequence?): PackageInfo? {
        if (packageName == null) {
            return null
        }
        val packageManager = context.packageManager
        return try {
            packageManager.getPackageInfo(packageName.toString(), 0)
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
    }
}
