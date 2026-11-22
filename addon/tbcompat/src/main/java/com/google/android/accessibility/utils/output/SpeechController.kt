/*
 * 移植说明：TalkBack 的 SpeechController 是接口，由主模块的 SpeechControllerImpl 实现。
 * 盲文子系统只用到：队列模式常量、SpeakOptions（队列/标记/完成回调）、
 * UtteranceCompleteRunnable。这里保留这些外形，实际发声由宿主
 * （BrailleBack 的 TalkBackForBrailleCommon 实现）负责。
 */

package com.google.android.accessibility.utils.output

import android.os.Bundle
import androidx.annotation.IntDef
import androidx.annotation.Nullable
import com.google.errorprone.annotations.CanIgnoreReturnValue
import java.lang.annotation.Retention
import java.lang.annotation.RetentionPolicy
import java.util.Objects

/** 语音播报控制器（接口外壳）。 */
interface SpeechController {

  companion object {
    // 位值与原 Java 的 1 << n 表达式逐一对应：INTERRUPT=1, UNINTERRUPTIBLE=2,
    // CAN_IGNORE=4, FLUSH_ALL=8, GLOBALLY_UNINTERRUPTED=16。
    const val QUEUE_MODE_BIT_DEFAULT: Int = 0
    const val QUEUE_MODE_BIT_INTERRUPT: Int = 1
    const val QUEUE_MODE_BIT_UNINTERRUPTIBLE_BY_NEW_SPEECH: Int = 2
    const val QUEUE_MODE_BIT_CAN_IGNORE_INTERRUPTS: Int = 4
    const val QUEUE_MODE_BIT_FLUSH_ALL: Int = 8
    const val QUEUE_MODE_BIT_GLOBALLY_UNINTERRUPTED: Int = 16

    /** 打断当前播报。 */
    const val QUEUE_MODE_INTERRUPT: Int = QUEUE_MODE_BIT_INTERRUPT

    /** 排队等待。 */
    const val QUEUE_MODE_QUEUE: Int = 0

    /** 排队，且不可被新语音打断。 */
    const val QUEUE_MODE_UNINTERRUPTIBLE_BY_NEW_SPEECH: Int =
      QUEUE_MODE_BIT_UNINTERRUPTIBLE_BY_NEW_SPEECH

    /** 打断当前播报，且新语音不可被后续语音打断。 */
    const val QUEUE_MODE_INTERRUPT_AND_UNINTERRUPTIBLE_BY_NEW_SPEECH: Int = 3

    const val UTTERANCE_GROUP_DEFAULT: Int = 0

    /** 播报失败的完成状态。 */
    const val STATUS_ERROR: Int = 1

    /** 播报被中断的完成状态（传给 UtteranceCompleteRunnable）。 */
    const val STATUS_INTERRUPTED: Int = 3
  }

  /** 播报队列模式。 */
  @Retention(RetentionPolicy.SOURCE)
  @IntDef(
    QUEUE_MODE_QUEUE,
    QUEUE_MODE_INTERRUPT,
    QUEUE_MODE_UNINTERRUPTIBLE_BY_NEW_SPEECH,
    QUEUE_MODE_INTERRUPT_AND_UNINTERRUPTIBLE_BY_NEW_SPEECH,
  )
  annotation class QueueMode

  /** 播报分组。 */
  @Retention(RetentionPolicy.SOURCE)
  @IntDef(UTTERANCE_GROUP_DEFAULT)
  annotation class UtteranceGroup

  /** 播报开始回调。 */
  interface UtteranceStartRunnable {
    fun run()
  }

  /** 播报完成回调。 */
  fun interface UtteranceCompleteRunnable {
    /**
     * @param status 完成状态。
     */
    fun run(status: Int)
  }

  /** 播报范围开始回调。 */
  interface UtteranceRangeStartCallback {
    fun onRangeStart(start: Int, end: Int)
  }

  /** 一次播报的参数集合。 */
  class SpeakOptions {
    @JvmField var mEarcons: Set<Int>? = null
    @JvmField var mHaptics: Set<Int>? = null
    @JvmField var mQueueMode: Int = QUEUE_MODE_BIT_DEFAULT
    @JvmField var mFlags: Int = 0
    @JvmField var mUtteranceGroup: Int = UTTERANCE_GROUP_DEFAULT
    @JvmField var mSpeechParams: Bundle? = null
    @JvmField var mNonSpeechParams: Bundle? = null
    @JvmField var mStartingAction: UtteranceStartRunnable? = null
    @JvmField var mRangeStartCallback: UtteranceRangeStartCallback? = null
    @JvmField var mCompletedAction: UtteranceCompleteRunnable? = null

    private constructor()

    @CanIgnoreReturnValue
    fun setEarcons(earcons: Set<Int>): SpeakOptions {
      mEarcons = earcons
      return this
    }

    @CanIgnoreReturnValue
    fun setHaptics(haptics: Set<Int>): SpeakOptions {
      mHaptics = haptics
      return this
    }

    @CanIgnoreReturnValue
    fun setQueueMode(@QueueMode queueMode: Int): SpeakOptions {
      mQueueMode = queueMode
      return this
    }

    @CanIgnoreReturnValue
    fun setFlags(flags: Int): SpeakOptions {
      mFlags = flags
      return this
    }

    @CanIgnoreReturnValue
    fun setUtteranceGroup(@UtteranceGroup utteranceGroup: Int): SpeakOptions {
      mUtteranceGroup = utteranceGroup
      return this
    }

    @CanIgnoreReturnValue
    fun setSpeechParams(speechParams: Bundle): SpeakOptions {
      mSpeechParams = speechParams
      return this
    }

    @CanIgnoreReturnValue
    fun setNonSpeechParams(nonSpeechParams: Bundle): SpeakOptions {
      mNonSpeechParams = nonSpeechParams
      return this
    }

    @CanIgnoreReturnValue
    fun setStartingAction(runnable: UtteranceStartRunnable): SpeakOptions {
      mStartingAction = runnable
      return this
    }

    @CanIgnoreReturnValue
    fun setRangeStartCallback(callback: UtteranceRangeStartCallback): SpeakOptions {
      mRangeStartCallback = callback
      return this
    }

    @CanIgnoreReturnValue
    fun setCompletedAction(@Nullable runnable: UtteranceCompleteRunnable?): SpeakOptions {
      mCompletedAction = runnable
      return this
    }

    override fun equals(other: Any?): Boolean {
      if (this === other) {
        return true
      }
      if (other !is SpeakOptions) {
        return false
      }
      val that: SpeakOptions = other
      return mQueueMode == that.mQueueMode &&
        mFlags == that.mFlags &&
        mUtteranceGroup == that.mUtteranceGroup &&
        Objects.equals(mEarcons, that.mEarcons) &&
        Objects.equals(mHaptics, that.mHaptics) &&
        Objects.equals(mSpeechParams, that.mSpeechParams) &&
        Objects.equals(mNonSpeechParams, that.mNonSpeechParams) &&
        Objects.equals(mStartingAction, that.mStartingAction) &&
        Objects.equals(mRangeStartCallback, that.mRangeStartCallback) &&
        Objects.equals(mCompletedAction, that.mCompletedAction)
    }

    override fun hashCode(): Int =
      Objects.hash(
        mEarcons,
        mHaptics,
        mQueueMode,
        mFlags,
        mUtteranceGroup,
        mSpeechParams,
        mNonSpeechParams,
        mStartingAction,
        mRangeStartCallback,
        mCompletedAction,
      )

    companion object {
      @JvmStatic
      fun create(): SpeakOptions = SpeakOptions()
    }
  }
}
