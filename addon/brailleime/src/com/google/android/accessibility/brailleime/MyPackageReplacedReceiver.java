/*
 * Copyright 2020 Google Inc.
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

package com.google.android.accessibility.brailleime;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import com.google.android.accessibility.braille.common.BrailleUserPreferences;
import com.google.android.accessibility.utils.AccessibilityServiceCompatUtils;
import com.google.android.accessibility.utils.AccessibilityServiceCompatUtils.Constants;

/** Listens to {@link Intent#ACTION_MY_PACKAGE_REPLACED} and takes appropriate action. */
public class MyPackageReplacedReceiver extends BroadcastReceiver {
  private static final String TAG = "MyPackageReplacedReceiver";

  @Override
  public void onReceive(Context context, Intent intent) {
    BrailleImeLog.d(TAG, "MY_PACKAGE_REPLACED received.");
    // 移植版修正：原版 TalkBack 的逻辑是
    //   「无障碍服务未启用 且 尚未看过教程」→ 把输入法组件设为 disabled，
    // 目的是避免用户在没开读屏时误选一个用不了的键盘。
    //
    // 但这个前提在 BrailleBack 上不成立：国内 ROM（实测小米）会在应用更新后自动把
    // 第三方无障碍服务从 enabled_accessibility_services 里移除，于是本条广播到达时
    // isAccessibilityServiceEnabled() 恒为 false；再叠加“尚未看过教程”，就会走到
    // else 分支把键盘组件禁用。结果是 install -r 之后键盘从此从系统的输入法列表里
    // 消失，用户再也看不到、也选不中（实测 disabledComponents 里出现该组件）。
    //
    // 因此这里改为无条件保持组件启用。服务未开启时切到该键盘不会崩溃：
    // BrailleIme.onStartInputView 已有守卫，会提示「请先开启盲文读屏服务」并自动收起。
    Utils.setComponentEnabled(context, Constants.BRAILLE_KEYBOARD, true);
    BrailleUserPreferences.migrateIfNecessary(context);
  }
}
