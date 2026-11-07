/*
 * 移植说明：原版 PreferenceSettingsUtils（252 行）还包含把设置项高亮跳转、
 * 查找嵌套 PreferenceGroup 等能力。盲文设置页只用到三个入口，这里保留它们。
 */

package com.google.android.accessibility.utils;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import androidx.annotation.Nullable;
import androidx.annotation.XmlRes;
import androidx.fragment.app.FragmentActivity;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;

/** 设置页工具（精简实现）。 */
public final class PreferenceSettingsUtils {

  private PreferenceSettingsUtils() {}

  /** 为 PreferenceFragmentCompat 加载 XML 偏好项。 */
  public static void addPreferencesFromResource(
      PreferenceFragmentCompat preferenceFragment, @XmlRes int preferencesResId) {
    preferenceFragment.addPreferencesFromResource(preferencesResId);
  }

  /** 在 Activity 已挂载的 Fragment 中按 key 查偏好项。 */
  public static @Nullable Preference findPreference(Activity activity, CharSequence key) {
    if (!(activity instanceof FragmentActivity fragmentActivity)) {
      return null;
    }
    for (androidx.fragment.app.Fragment fragment :
        fragmentActivity.getSupportFragmentManager().getFragments()) {
      if (fragment instanceof PreferenceFragmentCompat preferenceFragment) {
        Preference preference = preferenceFragment.findPreference(key);
        if (preference != null) {
          return preference;
        }
      }
    }
    return null;
  }

  /**
   * 给 Intent 挂上"高亮某个设置项"的参数，使系统设置跳转后能定位到对应条目。
   *
   * <p>这是 Android Settings 的私有协议（":settings:fragment_args_key"），
   * 在这里保留是为了让"从设置页跳到本应用设置"的行为与 TalkBack 一致。
   */
  public static void attachSettingsHighlightBundle(Intent intent, ComponentName componentName) {
    Bundle bundle = new Bundle();
    bundle.putString(":settings:fragment_args_key", componentName.flattenToString());
    intent.putExtra(":settings:show_fragment_args", bundle);
  }
}
