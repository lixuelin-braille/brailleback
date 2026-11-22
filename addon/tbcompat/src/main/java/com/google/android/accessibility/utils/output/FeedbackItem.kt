/*
 * 移植说明：只保留 braille 子系统引用到的语义标记常量。
 */

package com.google.android.accessibility.utils.output

/** 反馈条目语义标记。 */
class FeedbackItem private constructor() {

  companion object {
    /** 即使当前有音频播放，也要强制输出本条反馈。 */
    @JvmField
    val FLAG_FORCE_FEEDBACK_EVEN_IF_AUDIO_PLAYBACK_ACTIVE: Int = 1

    /** 即使麦克风占用中，也要强制输出本条反馈。 */
    @JvmField
    val FLAG_FORCE_FEEDBACK_EVEN_IF_MICROPHONE_ACTIVE: Int = 1 shl 1

    /** 即使屏幕朗读（SSB）占用中，也要强制输出本条反馈。 */
    @JvmField
    val FLAG_FORCE_FEEDBACK_EVEN_IF_SSB_ACTIVE: Int = 1 shl 2
  }
}
