/*
 * 移植说明：原版 PackageManagerUtils 依赖 Google Play Services 的 ConnectionResult 做
 * 版本兼容判断。盲文键盘只用 hasGmsCorePackage（决定是否提示安装 GMS），
 * 这里改为纯 PackageManager 判断，避免把 GMS 依赖引入移植层。
 */

package com.google.android.accessibility.utils;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.text.TextUtils;
import androidx.annotation.Nullable;

/** 包管理工具（精简实现）。 */
public final class PackageManagerUtils {

  /** GMS Core 包名。 */
  public static final String GMS_CORE_PACKAGE = "com.google.android.gms";

  /** 宿主应用包名（由 BuildConfig 注入，与 ApplicationId 保持一致）。 */
  public static final String HOST_PACKAGE = BuildConfig.HOST_APPLICATION_ID;

  private PackageManagerUtils() {}

  /** 是否安装了 GMS Core。 */
  public static boolean hasGmsCorePackage(Context context) {
    return isPackageInstalled(context, GMS_CORE_PACKAGE);
  }

  /** 指定包是否已安装。 */
  public static boolean isPackageInstalled(Context context, String packageName) {
    if (context == null || TextUtils.isEmpty(packageName)) {
      return false;
    }
    try {
      context.getPackageManager().getPackageInfo(packageName, 0);
      return true;
    } catch (PackageManager.NameNotFoundException e) {
      return false;
    }
  }

  /** 取版本名；未安装返回 null。 */
  public static @Nullable String getPackageVersionName(Context context, String packageName) {
    try {
      PackageInfo info = context.getPackageManager().getPackageInfo(packageName, 0);
      return info.versionName;
    } catch (PackageManager.NameNotFoundException e) {
      return null;
    }
  }

  /** 取版本号；未安装返回 -1。 */
  public static int getPackageVersionCode(Context context, String packageName) {
    try {
      PackageInfo info = context.getPackageManager().getPackageInfo(packageName, 0);
      return info.versionCode;
    } catch (PackageManager.NameNotFoundException e) {
      return -1;
    }
  }

  /** 是否为系统应用。 */
  public static boolean isSystemApp(Context context, String packageName) {
    try {
      PackageInfo info = context.getPackageManager().getPackageInfo(packageName, 0);
      return (info.applicationInfo.flags & android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0;
    } catch (PackageManager.NameNotFoundException e) {
      return false;
    }
  }
}
