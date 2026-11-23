/*
 * 移植说明：原版 AccessibilityServiceCompatUtils 服务于 TalkBack 主模块（检查无障碍服务
 * 是否启用、取服务实例等）。这里只保留盲文键盘用到的两个能力：
 *   1. Constants.BRAILLE_KEYBOARD —— 输入法组件名
 *   2. isAccessibilityServiceEnabled —— 判断本应用的无障碍服务是否开启
 *
 * 关键差异：宿主不再是林夕读屏（com.linxi.screenreader），而是 BrailleBack
 * （com.linxi.brailleback）。包名通过 BuildConfig.HOST_APPLICATION_ID
 * 注入，见 tbcompat/build.gradle；若宿主 applicationId 变更，必须同步那里。
 */

package com.google.android.accessibility.utils

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.text.TextUtils
import android.view.accessibility.AccessibilityManager
import androidx.annotation.Nullable

/** 无障碍服务与常量（精简实现）。 */
class AccessibilityServiceCompatUtils private constructor() {

  /** 与宿主应用相关的组件常量。 */
  class Constants private constructor() {

    companion object {
      /** 盲文键盘 IME 的包名（宿主 applicationId）。 */
      @JvmField
      val HOST_PACKAGE_NAME: String = BuildConfig.HOST_APPLICATION_ID

      /**
       * 唯一注册的盲文输入法组件类名。
       * 单注册合并后不再指向移植键盘 BrailleIme（它已无 service 声明），
       * 而是指向宿主的 BrailleIME——本常量用于 setComponentEnabledSetting、
       * isBrailleKeyboardEnabled / isDefault 等判断，必须与清单里唯一的
       * android.view.InputMethod 服务保持一致。
       */
      @JvmField
      val BRAILLE_IME_CLASS_NAME = "com.linxi.brailleback.BrailleIME"

      /** 盲文键盘 IME 组件。 */
      @JvmField
      val BRAILLE_KEYBOARD = ComponentName(HOST_PACKAGE_NAME, BRAILLE_IME_CLASS_NAME)

      /** 盲文键盘设置页组件。 */
      @JvmField
      val BRAILLE_KEYBOARD_SETTINGS =
        ComponentName(
          HOST_PACKAGE_NAME,
          "com.google.android.accessibility.brailleime.settings.BrailleImePreferencesActivity",
        )

      /** 读屏设置页组件（原版指向 TalkBack 设置；移植后指向 BrailleBack 设置页）。 */
      @JvmField
      val SETTINGS_ACTIVITY =
        ComponentName(HOST_PACKAGE_NAME, "com.linxi.brailleback.SettingsActivity")

      /** 盲文点显器设置页组件（BrailleBack 自有实现）。 */
      @JvmField
      val BRAILLE_DISPLAY_SETTINGS =
        ComponentName(HOST_PACKAGE_NAME, "com.linxi.brailleback.SettingsActivity")

      /** 短信应用包名（盲文键盘对它的输入框有特殊处理）。 */
      @JvmField
      val ANDROID_MESSAGES_PACKAGE_NAME = "com.google.android.apps.messaging"

      /** Gboard 包名（用于判断当前输入法是否 Gboard）。 */
      @JvmField
      val GBOARD_PACKAGE_NAME = "com.google.android.inputmethod.latin"

      /**
       * "读屏服务"组件。
       *
       * <p>原版指向 TalkBack 的 TalkBackService。移植后宿主是 BrailleBack，
       * 因此指向 BrailleBack 自己的无障碍服务；盲文键盘用它在设置页做"读屏是否开启"的引导。
       */
      @JvmField
      val TALKBACK_SERVICE =
        ComponentName(
          HOST_PACKAGE_NAME,
          "com.linxi.brailleback.BrailleBackService",
        )
    }
  }

  companion object {
    /** 判断某个无障碍服务当前是否已启用。 */
    @JvmStatic
    fun isAccessibilityServiceEnabled(
      context: Context,
      service: Class<out android.accessibilityservice.AccessibilityService>,
    ): Boolean {
      val manager =
        context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager?
      if (manager == null) {
        return false
      }
      val enabledServices =
        manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
      if (enabledServices == null) {
        return false
      }
      val expected = ComponentName(context, service).flattenToString()
      for (info in enabledServices) {
        val resolveInfo = info.getResolveInfo()
        if (resolveInfo != null && resolveInfo.serviceInfo != null) {
          val serviceInfo = resolveInfo.serviceInfo
          if (TextUtils.equals(
              expected,
              ComponentName(serviceInfo.packageName, serviceInfo.name).flattenToString(),
            )
          ) {
            return true
          }
        }
      }
      return false
    }

    /** 输入法窗口是否显示在屏幕上。 */
    @JvmStatic
    fun isInputWindowOnScreen(service: android.accessibilityservice.AccessibilityService?): Boolean {
      if (service == null) {
        return false
      }
      val windows = service.windows
      if (windows == null) {
        return false
      }
      for (window in windows) {
        if (window.getType() == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
          return true
        }
      }
      return false
    }

    /** 判断指定包名下是否有已启用的无障碍服务。 */
    @JvmStatic
    fun isAccessibilityServiceEnabled(context: Context, packageName: String): Boolean {
      val manager =
        context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager?
      if (manager == null) {
        return false
      }
      val list =
        manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
      if (list != null) {
        for (serviceInfo in list) {
          val id = serviceInfo.getId()
          if (id != null && id.contains(packageName)) {
            return true
          }
        }
      }
      return false
    }

    /** 无障碍总开关是否打开。 */
    @JvmStatic
    fun isAccessibilityEnabled(@Nullable context: Context?): Boolean {
      if (context == null) {
        return false
      }
      val manager =
        context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager?
      return manager != null && manager.isEnabled
    }

    /** 读系统设置里的无障碍服务列表（调试用）。 */
    @JvmStatic
    @Nullable
    fun getEnabledServicesSetting(context: Context): String? {
      return Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
      )
    }
  }
}
