/*
 * 移植说明：TalkBack 的 FeatureSupport（607 行）按机型/平台能力做大量分支。
 * 盲文键盘只用到 4 个判断，这里按"保守可行"实现：
 *   - isWatch / isTv：按 UI 模式判断
 *   - supportEnableDisableIme：API 26+
 *   - supportInputConnectionByA11yService：API 33+（BrailleBack 的 minSdk 是 23，
 *     低版本返回 false，键盘仍可用但不走无障碍 InputConnection 路径）
 */

package com.google.android.accessibility.utils;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.view.accessibility.AccessibilityNodeInfo;

/** 平台能力判断（精简实现）。 */
public final class FeatureSupport {

  private FeatureSupport() {}

  /** 是否为手表设备。 */
  public static boolean isWatch(Context context) {
    return context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_WATCH);
  }

  /** 是否为电视设备。 */
  public static boolean isTv(Context context) {
    return context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_LEANBACK);
  }

  /** 是否为 Automotive。 */
  public static boolean isAutomotive(Context context) {
    return Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
        && context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE);
  }

  /** 设置页是否支持"启用/停用输入法"这两个按钮。 */
  public static boolean supportEnableDisableIme() {
    return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O;
  }

  /**
   * 是否支持通过无障碍服务拿 InputConnection。
   *
   * <p>Android 13 起 AccessibilityService 提供 getInputConnection，盲文键盘可直接读写输入框；
   * 之前版本退回 IME 自己的 InputConnection 通道。
   */
  public static boolean supportInputConnectionByA11yService() {
    return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU;
  }

  /** 是否支持通过无障碍服务执行 ACTION_SET_TEXT。 */
  public static boolean supportSetTextByA11yService() {
    return Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP;
  }

  /** 是否支持节点上的 ACTION_IME_ENTER。 */
  public static boolean supportImeEnterAction() {
    return Build.VERSION.SDK_INT >= 30;
  }

  /** 是否支持自定义光标移动粒度 SENTENCE。 */
  public static boolean supportSentenceGranularity() {
    return Build.VERSION.SDK_INT >= 34;
  }

  /** 用户能否在系统设置里关闭全局动画（Android 8.0+ 提供该开关）。 */
  public static boolean supportsUserDisablingOfGlobalAnimations() {
    return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O;
  }

  /** 兼容旧调用：节点是否可用于文本编辑。 */
  public static boolean isTextNode(AccessibilityNodeInfo node) {
    return node != null && node.isEditable();
  }
}
