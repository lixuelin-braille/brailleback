/*
 * 移植说明：原版 AccessibilityServiceCompatUtils 服务于 TalkBack 主模块（检查无障碍服务
 * 是否启用、取服务实例等）。这里只保留盲文键盘用到的两个能力：
 *   1. Constants.BRAILLE_KEYBOARD —— 输入法组件名
 *   2. isAccessibilityServiceEnabled —— 判断本应用的无障碍服务是否开启
 *
 * 关键差异：宿主不再是林夕读屏（com.linxi.screenreader），而是 BrailleBack
 * （com.googlecode.eyesfree.brailleback）。包名通过 BuildConfig.HOST_APPLICATION_ID
 * 注入，见 tbcompat/build.gradle；若宿主 applicationId 变更，必须同步那里。
 */

package com.google.android.accessibility.utils;

import android.content.ComponentName;
import android.content.Context;
import android.provider.Settings;
import android.text.TextUtils;
import androidx.annotation.Nullable;
import java.util.List;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.view.accessibility.AccessibilityManager;

/** 无障碍服务与常量（精简实现）。 */
public final class AccessibilityServiceCompatUtils {

  private AccessibilityServiceCompatUtils() {}

  /** 与宿主应用相关的组件常量。 */
  public static class Constants {

    private Constants() {}

    /** 盲文键盘 IME 的包名（宿主 applicationId）。 */
    public static final String HOST_PACKAGE_NAME = BuildConfig.HOST_APPLICATION_ID;

    /** 盲文键盘 IME 的服务类全名（移植后包名保持不变）。 */
    public static final String BRAILLE_IME_CLASS_NAME =
        "com.google.android.accessibility.brailleime.BrailleIme";

    /** 盲文键盘 IME 组件。 */
    public static final ComponentName BRAILLE_KEYBOARD =
        new ComponentName(HOST_PACKAGE_NAME, BRAILLE_IME_CLASS_NAME);

    /** 盲文键盘设置页组件。 */
    public static final ComponentName BRAILLE_KEYBOARD_SETTINGS =
        new ComponentName(
            HOST_PACKAGE_NAME,
            "com.google.android.accessibility.brailleime.settings.BrailleImePreferencesActivity");

    /** 读屏设置页组件（原版指向 TalkBack 设置；移植后指向 BrailleBack 设置页）。 */
    public static final ComponentName SETTINGS_ACTIVITY =
        new ComponentName(HOST_PACKAGE_NAME, "com.googlecode.eyesfree.brailleback.SettingsActivity");

    /** 盲文点显器设置页组件（BrailleBack 自有实现）。 */
    public static final ComponentName BRAILLE_DISPLAY_SETTINGS =
        new ComponentName(HOST_PACKAGE_NAME, "com.googlecode.eyesfree.brailleback.SettingsActivity");

    /** 短信应用包名（盲文键盘对它的输入框有特殊处理）。 */
    public static final String ANDROID_MESSAGES_PACKAGE_NAME = "com.google.android.apps.messaging";

    /** Gboard 包名（用于判断当前输入法是否 Gboard）。 */
    public static final String GBOARD_PACKAGE_NAME = "com.google.android.inputmethod.latin";

    /**
     * "读屏服务"组件。
     *
     * <p>原版指向 TalkBack 的 TalkBackService。移植后宿主是 BrailleBack，
     * 因此指向 BrailleBack 自己的无障碍服务；盲文键盘用它在设置页做"读屏是否开启"的引导。
     */
    public static final ComponentName TALKBACK_SERVICE =
        new ComponentName(
            HOST_PACKAGE_NAME, "com.googlecode.eyesfree.brailleback.BrailleBackService");
  }

  /** 判断某个无障碍服务当前是否已启用。 */
  public static boolean isAccessibilityServiceEnabled(
      Context context, Class<? extends android.accessibilityservice.AccessibilityService> service) {
    AccessibilityManager manager =
        (AccessibilityManager) context.getSystemService(Context.ACCESSIBILITY_SERVICE);
    if (manager == null) {
      return false;
    }
    List<AccessibilityServiceInfo> enabledServices =
        manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
    if (enabledServices == null) {
      return false;
    }
    String expected = new ComponentName(context, service).flattenToString();
    for (AccessibilityServiceInfo info : enabledServices) {
      if (info.getResolveInfo() != null
          && info.getResolveInfo().serviceInfo != null
          && TextUtils.equals(
              expected,
              new ComponentName(
                      info.getResolveInfo().serviceInfo.packageName,
                      info.getResolveInfo().serviceInfo.name)
                  .flattenToString())) {
        return true;
      }
    }
    return false;
  }

  /** 输入法窗口是否显示在屏幕上。 */
  public static boolean isInputWindowOnScreen(
      android.accessibilityservice.AccessibilityService service) {
    if (service == null) {
      return false;
    }
    List<android.view.accessibility.AccessibilityWindowInfo> windows = service.getWindows();
    if (windows == null) {
      return false;
    }
    for (android.view.accessibility.AccessibilityWindowInfo window : windows) {
      if (window.getType() == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
        return true;
      }
    }
    return false;
  }

  /** 判断指定包名下是否有已启用的无障碍服务。 */
  public static boolean isAccessibilityServiceEnabled(Context context, String packageName) {
    AccessibilityManager manager =
        (AccessibilityManager) context.getSystemService(Context.ACCESSIBILITY_SERVICE);
    if (manager == null) {
      return false;
    }
    List<AccessibilityServiceInfo> list =
        manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
    if (list != null) {
      for (AccessibilityServiceInfo serviceInfo : list) {
        if (serviceInfo.getId() != null && serviceInfo.getId().contains(packageName)) {
          return true;
        }
      }
    }
    return false;
  }

  /** 无障碍总开关是否打开。 */
  public static boolean isAccessibilityEnabled(@Nullable Context context) {
    if (context == null) {
      return false;
    }
    AccessibilityManager manager =
        (AccessibilityManager) context.getSystemService(Context.ACCESSIBILITY_SERVICE);
    return manager != null && manager.isEnabled();
  }

  /** 读系统设置里的无障碍服务列表（调试用）。 */
  public static @Nullable String getEnabledServicesSetting(Context context) {
    return Settings.Secure.getString(
        context.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
  }
}
