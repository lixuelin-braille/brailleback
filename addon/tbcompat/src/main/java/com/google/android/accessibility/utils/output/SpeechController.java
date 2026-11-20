/*
 * 移植说明：TalkBack 的 SpeechController 是接口，由主模块的 SpeechControllerImpl 实现。
 * 盲文子系统只用到：队列模式常量、SpeakOptions（队列/标记/完成回调）、
 * UtteranceCompleteRunnable。这里保留这些外形，实际发声由宿主
 * （BrailleBack 的 TalkBackForBrailleCommon 实现）负责。
 */

package com.google.android.accessibility.utils.output;

import android.os.Bundle;
import androidx.annotation.IntDef;
import androidx.annotation.Nullable;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Objects;
import java.util.Set;

/** 语音播报控制器（接口外壳）。 */
public interface SpeechController {

  int QUEUE_MODE_BIT_DEFAULT = 0;
  int QUEUE_MODE_BIT_INTERRUPT = 1 << 0;
  int QUEUE_MODE_BIT_UNINTERRUPTIBLE_BY_NEW_SPEECH = 1 << 1;
  int QUEUE_MODE_BIT_CAN_IGNORE_INTERRUPTS = 1 << 2;
  int QUEUE_MODE_BIT_FLUSH_ALL = 1 << 3;
  int QUEUE_MODE_BIT_GLOBALLY_UNINTERRUPTED = 1 << 4;

  /** 打断当前播报。 */
  int QUEUE_MODE_INTERRUPT = QUEUE_MODE_BIT_INTERRUPT;

  /** 排队等待。 */
  int QUEUE_MODE_QUEUE = 0;

  /** 排队，且不可被新语音打断。 */
  int QUEUE_MODE_UNINTERRUPTIBLE_BY_NEW_SPEECH = QUEUE_MODE_BIT_UNINTERRUPTIBLE_BY_NEW_SPEECH;

  /** 打断当前播报，且新语音不可被后续语音打断。 */
  int QUEUE_MODE_INTERRUPT_AND_UNINTERRUPTIBLE_BY_NEW_SPEECH =
      QUEUE_MODE_BIT_INTERRUPT | QUEUE_MODE_BIT_UNINTERRUPTIBLE_BY_NEW_SPEECH;

  int UTTERANCE_GROUP_DEFAULT = 0;

  /** 播报失败的完成状态。 */
  int STATUS_ERROR = 1;

  /** 播报被中断的完成状态（传给 UtteranceCompleteRunnable）。 */
  int STATUS_INTERRUPTED = 3;

  /** 播报队列模式。 */
  @IntDef({
    QUEUE_MODE_QUEUE,
    QUEUE_MODE_INTERRUPT,
    QUEUE_MODE_UNINTERRUPTIBLE_BY_NEW_SPEECH,
    QUEUE_MODE_INTERRUPT_AND_UNINTERRUPTIBLE_BY_NEW_SPEECH
  })
  @Retention(RetentionPolicy.SOURCE)
  @interface QueueMode {}

  /** 播报分组。 */
  @IntDef({UTTERANCE_GROUP_DEFAULT})
  @Retention(RetentionPolicy.SOURCE)
  @interface UtteranceGroup {}

  /** 播报开始回调。 */
  interface UtteranceStartRunnable {
    void run();
  }

  /** 播报完成回调。 */
  interface UtteranceCompleteRunnable {
    /**
     * @param status 完成状态。
     */
    void run(int status);
  }

  /** 播报范围开始回调。 */
  interface UtteranceRangeStartCallback {
    void onRangeStart(int start, int end);
  }

  /** 一次播报的参数集合。 */
  class SpeakOptions {
    public @Nullable Set<Integer> mEarcons = null;
    public @Nullable Set<Integer> mHaptics = null;
    public int mQueueMode = QUEUE_MODE_BIT_DEFAULT;
    public int mFlags = 0;
    public int mUtteranceGroup = UTTERANCE_GROUP_DEFAULT;
    public @Nullable Bundle mSpeechParams = null;
    public @Nullable Bundle mNonSpeechParams = null;
    public @Nullable UtteranceStartRunnable mStartingAction = null;
    public @Nullable UtteranceRangeStartCallback mRangeStartCallback = null;
    public @Nullable UtteranceCompleteRunnable mCompletedAction = null;

    private SpeakOptions() {}

    public static SpeakOptions create() {
      return new SpeakOptions();
    }

    @CanIgnoreReturnValue
    public SpeakOptions setEarcons(Set<Integer> earcons) {
      mEarcons = earcons;
      return this;
    }

    @CanIgnoreReturnValue
    public SpeakOptions setHaptics(Set<Integer> haptics) {
      mHaptics = haptics;
      return this;
    }

    @CanIgnoreReturnValue
    public SpeakOptions setQueueMode(@QueueMode int queueMode) {
      mQueueMode = queueMode;
      return this;
    }

    @CanIgnoreReturnValue
    public SpeakOptions setFlags(int flags) {
      mFlags = flags;
      return this;
    }

    @CanIgnoreReturnValue
    public SpeakOptions setUtteranceGroup(@UtteranceGroup int utteranceGroup) {
      mUtteranceGroup = utteranceGroup;
      return this;
    }

    @CanIgnoreReturnValue
    public SpeakOptions setSpeechParams(Bundle speechParams) {
      mSpeechParams = speechParams;
      return this;
    }

    @CanIgnoreReturnValue
    public SpeakOptions setNonSpeechParams(Bundle nonSpeechParams) {
      mNonSpeechParams = nonSpeechParams;
      return this;
    }

    @CanIgnoreReturnValue
    public SpeakOptions setStartingAction(UtteranceStartRunnable runnable) {
      mStartingAction = runnable;
      return this;
    }

    @CanIgnoreReturnValue
    public SpeakOptions setRangeStartCallback(UtteranceRangeStartCallback callback) {
      mRangeStartCallback = callback;
      return this;
    }

    @CanIgnoreReturnValue
    public SpeakOptions setCompletedAction(@Nullable UtteranceCompleteRunnable runnable) {
      mCompletedAction = runnable;
      return this;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (!(o instanceof SpeakOptions)) {
        return false;
      }
      SpeakOptions that = (SpeakOptions) o;
      return mQueueMode == that.mQueueMode
          && mFlags == that.mFlags
          && mUtteranceGroup == that.mUtteranceGroup
          && Objects.equals(mEarcons, that.mEarcons)
          && Objects.equals(mHaptics, that.mHaptics)
          && Objects.equals(mSpeechParams, that.mSpeechParams)
          && Objects.equals(mNonSpeechParams, that.mNonSpeechParams)
          && Objects.equals(mStartingAction, that.mStartingAction)
          && Objects.equals(mRangeStartCallback, that.mRangeStartCallback)
          && Objects.equals(mCompletedAction, that.mCompletedAction);
    }

    @Override
    public int hashCode() {
      return Objects.hash(
          mEarcons,
          mHaptics,
          mQueueMode,
          mFlags,
          mUtteranceGroup,
          mSpeechParams,
          mNonSpeechParams,
          mStartingAction,
          mRangeStartCallback,
          mCompletedAction);
    }
  }
}
