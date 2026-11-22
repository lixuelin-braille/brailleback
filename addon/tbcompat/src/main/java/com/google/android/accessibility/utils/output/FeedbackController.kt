/*
 * 移植说明：原版 TalkBack FeedbackController 还负责震动（HapticPatternParser）与
 * 多条音效的并发管理。盲文键盘只调用 playAuditory(resId, eventId)，
 * 所以这里保留 SoundPool 缓存播放 + 跟随系统无障碍音量，其余能力裁剪。
 * 与原生 BrailleBack 的 sound/ 子系统互不干扰（后者走 SoundScheme）。
 */

package com.google.android.accessibility.utils.output

import android.content.Context
import android.content.res.Resources
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import android.os.Build
import android.util.Log
import android.util.SparseIntArray
import androidx.annotation.Nullable
import com.google.android.accessibility.utils.Performance.EventId

/** 音效反馈控制器（SoundPool 缓存播放）。 */
class FeedbackController {
  private val context: Context
  private val resources: Resources
  private val soundPool: SoundPool
  private val soundIds: SparseIntArray = SparseIntArray()
  private val audioManager: AudioManager?
  private var auditoryEnabled = true

  constructor(context: Context) : this(context, createSoundPool())

  constructor(context: Context, soundPool: SoundPool) {
    this.context = context
    this.resources = context.resources
    this.soundPool = soundPool
    this.audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager?
  }

  fun setAuditoryFeedbackEnabled(enabled: Boolean) {
    auditoryEnabled = enabled
  }

  fun isAuditoryFeedbackEnabled(): Boolean = auditoryEnabled

  /** 播放指定资源 id 的音效。 */
  fun playAuditory(resId: Int, @Nullable eventId: EventId?) {
    playAuditory(resId, /* rate= */ 1.0f, /* volume= */ 1.0f, eventId)
  }

  fun playAuditory(resId: Int, rate: Float, volume: Float, @Nullable eventId: EventId?) {
    if (!auditoryEnabled || resId == 0) {
      return
    }
    var soundId = soundIds.get(resId)
    if (soundId == 0) {
      soundId = soundPool.load(context, resId, /* priority= */ 1)
      if (soundId == 0) {
        Log.e(TAG, "Failed to load sound $resId")
        return
      }
      soundIds.put(resId, soundId)
    }
    soundPool.play(soundId, volume, volume, /* priority= */ 1, /* loop= */ 0, clampRate(rate))
  }

  /** 当前无障碍音频流的音量比例，供需要手动叠加音量的调用方使用。 */
  fun getStreamVolumeRatio(): Float {
    if (audioManager == null) {
      return 1.0f
    }
    val max = audioManager.getStreamMaxVolume(DEFAULT_STREAM)
    if (max <= 0) {
      return 1.0f
    }
    return audioManager.getStreamVolume(DEFAULT_STREAM).toFloat() / max
  }

  /** 释放 SoundPool 资源。 */
  fun shutdown() {
    soundPool.release()
    soundIds.clear()
  }

  companion object {
    private const val TAG = "FeedbackController"

    /** 默认使用无障碍音频流，避免打断用户正在播放的音乐。 */
    @JvmField
    val DEFAULT_STREAM: Int =
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        AudioManager.STREAM_ACCESSIBILITY
      } else {
        AudioManager.STREAM_MUSIC
      }

    private const val MAX_STREAMS = 10

    const val NO_SEPARATION: Long = 0

    @JvmStatic
    private fun createSoundPool(): SoundPool {
      val attributes =
        AudioAttributes.Builder()
          .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
          .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
          .build()
      return SoundPool.Builder()
        .setMaxStreams(MAX_STREAMS)
        .setAudioAttributes(attributes)
        .build()
    }

    @JvmStatic
    private fun clampRate(rate: Float): Float {
      // SoundPool 的 rate 合法区间是 [0.5, 2.0]。
      return maxOf(0.5f, minOf(2.0f, rate))
    }
  }
}
