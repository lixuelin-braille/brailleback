/*
 * 移植说明：原版 TalkBack FeedbackController 还负责震动（HapticPatternParser）与
 * 多条音效的并发管理。盲文键盘只调用 playAuditory(resId, eventId)，
 * 所以这里保留 SoundPool 缓存播放 + 跟随系统无障碍音量，其余能力裁剪。
 * 与原生 BrailleBack 的 sound/ 子系统互不干扰（后者走 SoundScheme）。
 */

package com.google.android.accessibility.utils.output;

import android.content.Context;
import android.content.res.Resources;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.SoundPool;
import android.os.Build;
import android.util.Log;
import android.util.SparseIntArray;
import androidx.annotation.Nullable;
import com.google.android.accessibility.utils.Performance.EventId;

/** 音效反馈控制器（SoundPool 缓存播放）。 */
public class FeedbackController {

  private static final String TAG = "FeedbackController";

  /** 默认使用无障碍音频流，避免打断用户正在播放的音乐。 */
  public static final int DEFAULT_STREAM =
      (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
          ? AudioManager.STREAM_ACCESSIBILITY
          : AudioManager.STREAM_MUSIC;

  private static final int MAX_STREAMS = 10;

  public static final long NO_SEPARATION = 0;

  private final Context context;
  private final Resources resources;
  private final SoundPool soundPool;
  private final SparseIntArray soundIds = new SparseIntArray();
  private final AudioManager audioManager;

  private boolean auditoryEnabled = true;

  public FeedbackController(Context context) {
    this(context, createSoundPool());
  }

  public FeedbackController(Context context, SoundPool soundPool) {
    this.context = context;
    this.resources = context.getResources();
    this.soundPool = soundPool;
    this.audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
  }

  private static SoundPool createSoundPool() {
    AudioAttributes attributes =
        new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build();
    return new SoundPool.Builder().setMaxStreams(MAX_STREAMS).setAudioAttributes(attributes).build();
  }

  public void setAuditoryFeedbackEnabled(boolean enabled) {
    auditoryEnabled = enabled;
  }

  public boolean isAuditoryFeedbackEnabled() {
    return auditoryEnabled;
  }

  /** 播放指定资源 id 的音效。 */
  public void playAuditory(int resId, @Nullable EventId eventId) {
    playAuditory(resId, /* rate= */ 1.0f, /* volume= */ 1.0f, eventId);
  }

  public void playAuditory(int resId, float rate, float volume, @Nullable EventId eventId) {
    if (!auditoryEnabled || resId == 0) {
      return;
    }
    int soundId = soundIds.get(resId);
    if (soundId == 0) {
      soundId = soundPool.load(context, resId, /* priority= */ 1);
      if (soundId == 0) {
        Log.e(TAG, "Failed to load sound " + resId);
        return;
      }
      soundIds.put(resId, soundId);
    }
    soundPool.play(soundId, volume, volume, /* priority= */ 1, /* loop= */ 0, clampRate(rate));
  }

  private static float clampRate(float rate) {
    // SoundPool 的 rate 合法区间是 [0.5, 2.0]。
    return Math.max(0.5f, Math.min(2.0f, rate));
  }

  /** 当前无障碍音频流的音量比例，供需要手动叠加音量的调用方使用。 */
  public float getStreamVolumeRatio() {
    if (audioManager == null) {
      return 1.0f;
    }
    int max = audioManager.getStreamMaxVolume(DEFAULT_STREAM);
    if (max <= 0) {
      return 1.0f;
    }
    return (float) audioManager.getStreamVolume(DEFAULT_STREAM) / max;
  }

  /** 释放 SoundPool 资源。 */
  public void shutdown() {
    soundPool.release();
    soundIds.clear();
  }
}
