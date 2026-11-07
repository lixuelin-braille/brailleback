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

import android.content.Context;
import android.media.AudioAttributes;
import android.os.Build;
import android.os.VibrationAttributes;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.util.Log;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

/**
 * Singleton class which presents vibrations in braille keyboard.
 *
 * <p>震动按能力分四级，从好到差依次尝试：
 *
 * <ol>
 *   <li><b>原语组合</b>（API 30+，且 {@code areAllPrimitivesSupported()} 为真）：给线性马达
 *       （LRA）用，CLICK / THUD / TICK 原语是被厂商调过的波形，短、干净、不拖尾。
 *   <li><b>预置效果</b>（API 29+，且 {@code areAllEffectsSupported()} 为真）：给转子马达
 *       （ERM）用。{@code EFFECT_CLICK / TICK / HEAVY_CLICK} 在 MIUI/HyperOS 上会走厂商的
 *       "Prebaked" 调音，比我们自己搓的 25ms 方波好听得多。
 *   <li><b>振幅方波</b>（API 26+）：{@code createOneShot(duration, amplitude)}，即旧版行为。
 *   <li><b>纯时长</b>（API 26 以下）：只有 {@code vibrate(long)}。
 * </ol>
 *
 * <p>API 33 起额外把震动标成 {@link VibrationAttributes#USAGE_ACCESSIBILITY}：这样会走系统的
 * "无障碍"强度档（设置-声音与振动-触感反馈里能单独调），静音/免打扰策略也与读屏一致。
 *
 * <p>注意：本机（红米 24117RK2CC / zorn）实测 {@code supportedPrimitives = []}，
 * 但 {@code supportedEffects} 含 CLICK/TICK/THUD/DOUBLE_CLICK/HEAVY_CLICK —— 所以这台机器走
 * 第二级"预置效果"，不是第一级。
 */
public class BrailleImeVibrator {

  private static final String TAG = "BrailleImeVibrator";

  // VibrationEffect.Composition 原语（API 30 起）。编译期常量，低版本设备上引用不会崩，
  // 只要不在低版本真的调用 startComposition() 即可。
  private static final int PRIMITIVE_CLICK = VibrationEffect.Composition.PRIMITIVE_CLICK;
  private static final int PRIMITIVE_THUD = VibrationEffect.Composition.PRIMITIVE_THUD;
  private static final int PRIMITIVE_TICK = VibrationEffect.Composition.PRIMITIVE_TICK;
  private static final int PRIMITIVE_LOW_TICK = VibrationEffect.Composition.PRIMITIVE_LOW_TICK;

  /** 没有可用的预置效果。 */
  private static final int NO_PREDEFINED = -1;

  /**
   * Vibration type in braille keyboard.
   *
   * <p>字段含义：
   *
   * <ul>
   *   <li>{@code duration}/{@code amplitude}：第 3、4 级兜底参数，沿用原版数值；
   *   <li>{@code predefinedEffect}：第 2 级预置效果，{@link #NO_PREDEFINED} 表示没有；
   *   <li>{@code primitives}/{@code scales}/{@code delaysMs}：第 1 级原语配方，三个数组等长。
   * </ul>
   */
  public enum VibrationType {
    /** 落点（一个盲文方打完）。最轻的一档，只要"有触感"，不要吵。 */
    BRAILLE_COMMISSION(
        /* duration= */ 25,
        /* amplitude= */ 120,
        VibrationEffect.EFFECT_TICK,
        new int[] {PRIMITIVE_CLICK},
        new float[] {0.55f},
        new int[] {0}),
    /** 空格 / 删除 / 移光标 / 切粒度。单次干脆的"哒"，和落点区分开。 */
    SPACE_DELETE_OR_MOVE_CURSOR_OR_GRANULARITY(
        /* duration= */ 70,
        /* amplitude= */ 150,
        VibrationEffect.EFFECT_CLICK,
        new int[] {PRIMITIVE_CLICK, PRIMITIVE_TICK},
        new float[] {0.85f, 0.5f},
        new int[] {0, 45}),
    /** 换行 / 删词。厚重一击，表示"整段级别"的操作。 */
    NEWLINE_OR_DELETE_WORD(
        /* duration= */ 120,
        /* amplitude= */ 180,
        VibrationEffect.EFFECT_HEAVY_CLICK,
        new int[] {PRIMITIVE_THUD},
        new float[] {1.0f},
        new int[] {0}),
    /** 长按触发。双响，明确"长按已被识别"。 */
    HOLD(
        /* duration= */ 25,
        /* amplitude= */ 200,
        VibrationEffect.EFFECT_DOUBLE_CLICK,
        new int[] {PRIMITIVE_LOW_TICK},
        new float[] {0.6f},
        new int[] {0}),
    /**
     * 其它手势（无效操作、错误）。没有合适的预置效果（THUD/POP 不是公开常量），
     * 所以这一档在转子马达上走"最长最重的一次方波"，在有原语支持的设备上走 轻+重 双脉冲。
     */
    OTHER_GESTURES(
        /* duration= */ 190,
        /* amplitude= */ 210,
        NO_PREDEFINED,
        new int[] {PRIMITIVE_CLICK, PRIMITIVE_THUD},
        new float[] {0.35f, 0.9f},
        new int[] {0, 40});

    private final int duration;
    private final int amplitude;
    private final int predefinedEffect;
    private final int[] primitives;
    private final float[] scales;
    private final int[] delaysMs;

    VibrationType(
        int duration,
        int amplitude,
        int predefinedEffect,
        int[] primitives,
        float[] scales,
        int[] delaysMs) {
      this.duration = duration;
      this.amplitude = amplitude;
      this.predefinedEffect = predefinedEffect;
      this.primitives = primitives;
      this.scales = scales;
      this.delaysMs = delaysMs;
    }
  }

  private static BrailleImeVibrator instance;

  @Nullable private final Vibrator vibrator;
  private final boolean hasVibrator;
  /** 设备是否支持本键盘用到的全部震动原语（线性马达）。 */
  private final boolean primitivesSupported;
  /** 设备是否支持本键盘用到的全部预置效果（转子马达的厂商调音）。 */
  private final boolean predefinedEffectsSupported;

  @Nullable private final AudioAttributes accessibilityAudioAttributes;
  private boolean enabled = false;

  public static BrailleImeVibrator getInstance(Context context) {
    if (instance == null) {
      instance = new BrailleImeVibrator(context.getApplicationContext());
    }
    return instance;
  }

  private BrailleImeVibrator(Context context) {
    Vibrator resolved = null;
    boolean has = false;
    try {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        VibratorManager manager =
            (VibratorManager) context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
        if (manager != null) {
          resolved = manager.getDefaultVibrator();
        }
      }
      if (resolved == null) {
        resolved = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
      }
      has = resolved != null && resolved.hasVibrator();
    } catch (RuntimeException e) {
      Log.w(TAG, "resolve vibrator failed", e);
    }
    vibrator = resolved;
    hasVibrator = has;
    primitivesSupported = computePrimitivesSupported();
    predefinedEffectsSupported = computePredefinedEffectsSupported();
    accessibilityAudioAttributes = buildAccessibilityAudioAttributes();
    Log.i(
        TAG,
        "vibrator hasVibrator=" + hasVibrator
            + " primitivesSupported=" + primitivesSupported
            + " predefinedEffectsSupported=" + predefinedEffectsSupported
            + " sdk=" + Build.VERSION.SDK_INT);
  }

  private boolean computePrimitivesSupported() {
    if (vibrator == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
      return false;
    }
    try {
      return vibrator.areAllPrimitivesSupported(
          PRIMITIVE_CLICK, PRIMITIVE_THUD, PRIMITIVE_TICK, PRIMITIVE_LOW_TICK);
    } catch (RuntimeException e) {
      Log.w(TAG, "areAllPrimitivesSupported failed", e);
      return false;
    }
  }

  private boolean computePredefinedEffectsSupported() {
    if (vibrator == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
      return false;
    }
    try {
      return vibrator.areAllEffectsSupported(
              VibrationEffect.EFFECT_CLICK,
              VibrationEffect.EFFECT_TICK,
              VibrationEffect.EFFECT_DOUBLE_CLICK,
              VibrationEffect.EFFECT_HEAVY_CLICK)
          == Vibrator.VIBRATION_EFFECT_SUPPORT_YES;
    } catch (RuntimeException e) {
      Log.w(TAG, "areAllEffectsSupported failed", e);
      return false;
    }
  }

  private static AudioAttributes buildAccessibilityAudioAttributes() {
    return new AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build();
  }

  public void enable() {
    enabled = true;
  }

  public void disable() {
    enabled = false;
  }

  /** 设备是否有可用的震动马达（设置页可据此隐藏震动相关选项）。 */
  public boolean hasVibrator() {
    return hasVibrator;
  }

  /**
   * Vibrates with {@link Vibrator}.
   *
   * @param vibrationType specific vibration type.
   */
  public void vibrate(VibrationType vibrationType) {
    if (!enabled || !hasVibrator || vibrator == null || vibrationType == null) {
      return;
    }
    try {
      if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
        // API 26 以下没有 VibrationEffect，只能按毫秒震动。
        vibrator.vibrate(vibrationType.duration);
        return;
      }
      VibrationEffect effect = buildEffect(vibrationType);
      if (effect == null) {
        return;
      }
      vibrateWithEffect(effect);
    } catch (RuntimeException e) {
      // 个别 ROM 会对震动参数抛 IllegalArgumentException，静默降级，绝不让键盘崩。
      Log.w(TAG, "vibrate failed: " + vibrationType, e);
    }
  }

  private void vibrateWithEffect(VibrationEffect effect) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      vibrator.vibrate(effect, accessibilityVibrationAttributes());
    } else if (accessibilityAudioAttributes != null) {
      vibrator.vibrate(effect, accessibilityAudioAttributes);
    } else {
      vibrator.vibrate(effect);
    }
  }

  @RequiresApi(Build.VERSION_CODES.TIRAMISU)
  private static VibrationAttributes accessibilityVibrationAttributes() {
    return VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ACCESSIBILITY);
  }

  @Nullable
  private VibrationEffect buildEffect(VibrationType type) {
    // 1. 原语组合（线性马达）
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && primitivesSupported) {
      VibrationEffect composition = buildComposition(type);
      if (composition != null) {
        return composition;
      }
    }
    // 2. 预置效果（转子马达的厂商调音）
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        && predefinedEffectsSupported
        && type.predefinedEffect != NO_PREDEFINED) {
      try {
        return VibrationEffect.createPredefined(type.predefinedEffect);
      } catch (RuntimeException e) {
        Log.w(TAG, "createPredefined failed, fallback to one-shot", e);
      }
    }
    // 3. 振幅方波（旧版行为）
    int amplitude =
        vibrator != null && vibrator.hasAmplitudeControl()
            ? type.amplitude
            : VibrationEffect.DEFAULT_AMPLITUDE;
    return VibrationEffect.createOneShot(type.duration, amplitude);
  }

  /** 用原语组合出一段震动；任何一步失败都返回 null，由调用方降级。 */
  @Nullable
  private VibrationEffect buildComposition(VibrationType type) {
    try {
      VibrationEffect.Composition composition = VibrationEffect.startComposition();
      for (int i = 0; i < type.primitives.length; i++) {
        float scale = clampScale(type.scales[i]);
        // 第三个参数是该原语开始前的延迟（毫秒），第一个原语为 0。
        composition.addPrimitive(type.primitives[i], scale, type.delaysMs[i]);
      }
      return composition.compose();
    } catch (RuntimeException e) {
      Log.w(TAG, "buildComposition failed", e);
      return null;
    }
  }

  private static float clampScale(float scale) {
    if (scale < 0f) {
      return 0f;
    }
    return Math.min(scale, 1f);
  }
}
