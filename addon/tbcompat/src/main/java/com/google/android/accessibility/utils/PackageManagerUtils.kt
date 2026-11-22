/*
 * 移植说明：原版 PackageManagerUtils 依赖 Google Play Services 的 ConnectionResult 做
 * 版本兼容判断。盲文键盘只用 hasGmsCorePackage（决定是否提示安装 GMS），
 * 这里改为纯 PackageManager 判断，避免把 GMS 依赖引入移植层。
 */

package com.google.android.accessibility.utils

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.text.TextUtils
import androidx.annotation.Nullable

/** 包管理工具（精简实现）。 */
class PackageManagerUtils private constructor() {

  companion object {
    /** GMS Core 包名。 */
    @JvmField
    val GMS_CORE_PACKAGE = "com.google.android.gms"

    /** 宿主应用包名（由 BuildConfig 注入，与 ApplicationId 保持一致）。 */
    @JvmField
    val HOST_PACKAGE: String = BuildConfig.HOST_APPLICATION_ID

    /** 是否安装了 GMS Core。 */
    @JvmStatic
    fun hasGmsCorePackage(context: Context): Boolean {
      return isPackageInstalled(context, GMS_CORE_PACKAGE)
    }

    /** 指定包是否已安装。 */
    @JvmStatic
    fun isPackageInstalled(context: Context?, packageName: String?): Boolean {
      if (context == null || TextUtils.isEmpty(packageName)) {
        return false
      }
      val pkg = packageName ?: return false
      try {
        context.packageManager.getPackageInfo(pkg, 0)
        return true
      } catch (e: PackageManager.NameNotFoundException) {
        return false
      }
    }

    /** 取版本名；未安装返回 null。 */
    @JvmStatic
    @Nullable
    fun getPackageVersionName(context: Context, packageName: String): String? {
      try {
        val info: PackageInfo = context.packageManager.getPackageInfo(packageName, 0)
        return info.versionName
      } catch (e: PackageManager.NameNotFoundException) {
        return null
      }
    }

    /** 取版本号；未安装返回 -1。 */
    @JvmStatic
    fun getPackageVersionCode(context: Context, packageName: String): Int {
      try {
        val info: PackageInfo = context.packageManager.getPackageInfo(packageName, 0)
        return info.versionCode
      } catch (e: PackageManager.NameNotFoundException) {
        return -1
      }
    }

    /** 是否为系统应用。 */
    @JvmStatic
    fun isSystemApp(context: Context, packageName: String): Boolean {
      try {
        val info: PackageInfo = context.packageManager.getPackageInfo(packageName, 0)
        val applicationInfo = info.applicationInfo ?: return false
        return (applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
      } catch (e: PackageManager.NameNotFoundException) {
        return false
      }
    }
  }
}
