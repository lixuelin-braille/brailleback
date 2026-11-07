/*
 * Copyright 2019 Google Inc.
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

package com.google.android.accessibility.brailleime.input;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.Log;
import android.util.Size;
import android.view.MotionEvent;
import android.view.View;
import android.view.View.OnAttachStateChangeListener;
import androidx.core.view.ViewCompat;
import com.google.android.accessibility.braille.common.BrailleCommonTalkBackSpeaker;
import com.google.android.accessibility.braille.common.BrailleUserPreferences;
import com.google.android.accessibility.braille.common.BrailleUtils;
import com.google.android.accessibility.braille.common.Constants.BrailleType;
import com.google.android.accessibility.braille.common.TalkBackSpeaker;
import com.google.android.accessibility.braille.common.translate.BrailleLanguages.Code;
import com.google.android.accessibility.braille.common.translate.BrailleTranslateUtils;
import com.google.android.accessibility.braille.interfaces.BrailleCharacter;
import com.google.android.accessibility.brailleime.BrailleIme.OrientationSensitive;
import com.google.android.accessibility.brailleime.BrailleInputOptions;
import com.google.android.accessibility.brailleime.R;
import com.google.android.accessibility.brailleime.Utils;
import com.google.android.accessibility.brailleime.input.BrailleInputPlane.CustomOnGestureListener;
import com.google.android.accessibility.brailleime.input.BrailleInputPlane.DotTarget;
import com.google.android.accessibility.brailleime.input.MultitouchHandler.HoldRecognizer;
import com.google.common.collect.ImmutableList;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.util.List;
import java.util.Optional;

/**
 * View that displays braille dots and handles braille input and gestures. Itself doesn't
 * automatically save dot regions. Please call {@link #savePoints} to save them.
 *
 * <p>Important signals, such as the commission of input, are fed through the {@link Callback},
 * which is a required parameter for the constructor.
 *
 * <p>We do not provide a constructor with {@link android.util.AttributeSet} parameter because we
 * have no need for it and we have a {@link Callback} that we need passed in during construction.
 */
@SuppressWarnings("ViewConstructor")
public class BrailleInputView extends View
    implements OrientationSensitive, OnAttachStateChangeListener {

  /** A callback for receiving signals from BrailleInputView. */
  public interface Callback {
    /** Signals that invalid gesture has been produced. Returns true if the action is consumed. */
    default boolean onInvalidGesture() {
      return false;
    }

    /**
     * Signals that {@link Swipe} input has been produced. Returns true if the action is consumed.
     */
    @CanIgnoreReturnValue
    boolean onSwipeProduced(Swipe swipe);

    /**
     * Signals that hold and dot swipe input has been produced. Returns true if the action is
     * consumed.
     */
    @CanIgnoreReturnValue
    boolean onDotHoldAndDotSwipe(DotHoldSwipe dotHoldSwipe);

    /**
     * Allows the client to state which potential hold events it cares about. If the client returns
     * true, the BrailleInputView will invoke onHoldProduced, and then it (along with the input
     * chain with which it interfaces) will halt subsequent callbacks until the user releases (lifts
     * up) the press.
     */
    @CanIgnoreReturnValue
    default boolean isCalibrationHoldRecognized(
        boolean inTwoStepCalibration, int pointersHeldCount) {
      return false;
    }

    /** Signals that hold has been produced. Returns true if the action is consumed. */
    @CanIgnoreReturnValue
    boolean onHoldProduced(int pointersHeldCount);

    /**
     * Signals that {@link BrailleCharacter} input has been produced.
     *
     * @return the client's notion of the latest update to the print text so that this View can
     *     render it (for low-vision users).
     */
    default String onBrailleProduced(BrailleCharacter brailleCharacter) {
      return "";
    }

    /** Signals that calibration has been produced. Returns true if the action is consumed. */
    @CanIgnoreReturnValue
    default boolean onCalibration(CalibrationTriggeredType type, FingersPattern hand) {
      return false;
    }

    /** Signals that calibration has failed. */
    default void onCalibrationFailed(CalibrationTriggeredType calibration) {}

    /** Signals that two step calibration needs retry. */
    default void onTwoStepCalibrationRetry(boolean isFirstStep) {}
  }

  /** Indicates which finger pattern. */
  public enum FingersPattern {
    NO_FINGERS,
    SIX_FINGERS,
    SEVEN_FINGERS,
    EIGHT_FINGERS,
    FIVE_FINGERS,
    FIRST_THREE_FINGERS,
    REMAINING_THREE_FINGERS,
    FIRST_FOUR_FINGERS,
    REMAINING_FOUR_FINGERS,
  }

  /** Calibration triggered types. */
  public enum CalibrationTriggeredType {
    FIVE_FINGERS,
    SIX_FINGERS,
    SEVEN_FINGERS,
    EIGHT_FINGERS,
    MANUAL
  }

  private static final String TAG = "BrailleInputView";
  private static final boolean DRAW_DEBUG_BACKGROUND = false; // Leave as false in version control.

  private final Callback callback;
  private final InputViewCaption inputViewCaption;
  private final BrailleInputPlane inputPlane;
  private CaptionText captionText;
  private Size screenSizeInPixels;
  private int orientation;
  private boolean tabletopMode;
  private AutoPerformer autoPerformer;
  private BrailleInputOptions options;
  private boolean touchInteracting;
  private CalibrationTriggeredType calibrationType;

  /**
   * 单点累积的停顿阈值：超过这个时间没有新点按下，就把已攒的点结算成一格。
   *
   * <p>取值要大于"手指从一格移到下一格"的时间（实测跨越上下两行约 0.3~0.6 秒），否则攒到
   * 一半就被结算；同时也不能太大，否则每格都要干等。800 毫秒是这两者之间的折中。
   */
  private static final long DOT_ACCUMULATE_SETTLE_MS = 800;

  /**
   * 逐点累积模式下，当前这一格已经攒下的点。
   *
   * <p>盲文的"一格"在上游等于<b>一次触摸手势</b>：{@link MultitouchHandler} 在最后一根手指
   * 抬起（ACTION_UP）时做 tap 判定并 {@code clearPointerCollections()}，跨手势不累积。累积
   * 模式只解决"同一手势里某根手指先松开"，不解决"分成几次点击"。而中文一个音节要 2~3 格，
   * 用户把一格的几个点分开按，就会变成几个单格，永远拼不出一个汉字。
   *
   * <p>这里把单点点击攒进同一格，停顿 {@link #DOT_ACCUMULATE_SETTLE_MS} 后结算；多指同时
   * 按下仍然是"一次就是一格、立刻结算"，不会给现有的和弦输入方式增加延迟。
   */
  private BrailleCharacter pendingCell = BrailleCharacter.EMPTY_CELL;

  /** 是否启用逐点累积（跟随"包含所有按下的点"设置）。关闭时行为与上游完全一致。 */
  private boolean accumulateDotsByTap;

  private final Runnable settlePendingCell;

  /**
   * Construct a BrailleInputView.
   *
   * <p>We do not provide a constructor with {@link android.util.AttributeSet} because we have no
   * need for it and we have a Callback that we need passed in during construction.
   */
  public BrailleInputView(
      Context context,
      Callback callback,
      Size screenSizeInPixels,
      BrailleInputOptions options,
      boolean tabletopMode) {
    super(context);
    this.callback = callback;
    this.screenSizeInPixels = screenSizeInPixels;
    this.orientation = getResources().getConfiguration().orientation;
    this.options = options;
    this.inputPlane = getInputPlane(context);
    this.inputPlane.setTableTopMode(tabletopMode);
    this.tabletopMode = tabletopMode;
    this.settlePendingCell = this::emitPendingCell;
    setBackgroundColor(getResources().getColor(R.color.input_plane_background));
    this.inputViewCaption = new InputViewCaption(context.getString(R.string.input_view_caption));
    addOnAttachStateChangeListener(this);
  }

  private BrailleInputPlane getInputPlane(Context context) {
    HoldRecognizer holdRecognizer =
        new HoldRecognizer() {
          @Override
          public boolean isCalibrationHoldRecognized(int pointersHeldCount) {
            return callback.isCalibrationHoldRecognized(
                inputPlane.inTwoStepCalibration(), pointersHeldCount);
          }

          @Override
          public boolean isHoldRecognized(int pointersHeldCount) {
            return !inputPlane.inTwoStepCalibration() && pointersHeldCount <= 3;
          }
        };
    return BrailleUtils.isPhoneSizedDevice(context.getResources())
        ? new BrailleInputPlanePhone(
            context,
            screenSizeInPixels,
            holdRecognizer,
            orientation,
            options,
            customOnGestureListener)
        : new BrailleInputPlaneTablet(
            context,
            screenSizeInPixels,
            holdRecognizer,
            orientation,
            options,
            customOnGestureListener);
  }

  @Override
  public void onOrientationChanged(int orientation, Size screenSize) {
    this.orientation = orientation;
    this.screenSizeInPixels = screenSize;
    this.inputPlane.setOrientation(orientation, screenSizeInPixels);
    invalidate();
    requestLayout();
  }

  @Override
  public void onViewAttachedToWindow(View view) {}

  @Override
  public void onViewDetachedFromWindow(View view) {
    if (inTwoStepCalibration()) {
      callback.onCalibrationFailed(calibrationType);
    }
    // 视图被摘下（切语言、旋转、收键盘）时，攒着的半格不能无声丢掉。
    flushPendingCell();
    removeCallbacks(settlePendingCell);
    removeOnAttachStateChangeListener(this);
  }

  public void setOptions(BrailleInputOptions options) {
    this.options = options;
    inputPlane.setOptions(options);
    invalidate();
    requestLayout();
  }

  /** Sets keyboard to table layout. */
  public void setTabletopMode(boolean enabled) {
    if (tabletopMode != enabled) {
      inputPlane.setTableTopMode(enabled);
      tabletopMode = enabled;
      invalidate();
      requestLayout();
    }
  }

  /** Stores points positions in the SharedPreference. */
  public void savePoints() {
    inputPlane.storeLayoutPoints();
  }

  /**
   * Sets the accumulation mode.
   *
   * <p>An accumulation mode set to true means that all press-and-releases contribute to a potential
   * commission even if the release occurred far before the release of the final touch point.
   */
  public void setAccumulationMode(boolean accumulationMode) {
    inputPlane.setAccumulationMode(accumulationMode);
    accumulateDotsByTap = accumulationMode;
    if (!accumulationMode) {
      // 关闭累积时不能留着半格，否则这一格永远等不到结算。
      flushPendingCell();
    }
  }

  /** Gets the accumulation mode. */
  public boolean isAccumulationMode() {
    return inputPlane.isAccumulationMode();
  }

  /** Returns whether in the process of two step calibration. */
  public boolean inTwoStepCalibration() {
    return inputPlane.inTwoStepCalibration();
  }

  public List<DotTarget> getDotTargets() {
    return inputPlane.getDotTargets();
  }

  /** Gets braille input view dot count. */
  public int getBrailleDotCount() {
    return options.brailleType().getDotCount();
  }

  /** Boolean value for whether a finger is currently touching the braille input view. */
  public boolean isTouchInteracting() {
    return touchInteracting;
  }

  @Override
  protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
    super.onLayout(changed, left, top, right, bottom);
    if (changed) {
      // 诊断：确认视图实际尺寸与算点阵用的 screenSizeInPixels 是否一致。
      // 若两者不同，画出来的点和判定用的点就会错位，表现为"点位映射有问题"。
      Log.d(
          "BBDots",
          "onLayout view="
              + (right - left)
              + "x"
              + (bottom - top)
              + " left="
              + left
              + " top="
              + top
              + " screenSizeInPixels="
              + screenSizeInPixels.getWidth()
              + "x"
              + screenSizeInPixels.getHeight());
      reduceSystemGestureArea();
    }
  }

  @Override
  protected void onDraw(Canvas canvas) {
    super.onDraw(canvas);
    if (DRAW_DEBUG_BACKGROUND) {
      drawDebugBackground(canvas);
    }
    inputPlane.onDraw(canvas);
    if (captionText != null) {
      captionText.onDraw(canvas);
    }
    inputViewCaption.onDraw(canvas);
  }

  private final CustomOnGestureListener customOnGestureListener =
      new CustomOnGestureListener() {
        @Override
        public boolean detect(Optional<BrailleInputPlaneResult> resultOptional) {
          if (resultOptional.isPresent()) {
            if (shouldPerformCalibrationAnimation(
                resultOptional.get().type, resultOptional.get().pointersHeldCount)) {
              inputPlane.createAnimator(BrailleInputView.this);
            }
            boolean result = processResult(resultOptional.get());
            invalidate();
            return result;
          }
          return false;
        }

        @Override
        public void onTwoStepCalibrationFailed() {
          callback.onCalibrationFailed(calibrationType);
          invalidate();
        }

        @Override
        public void onTwoStepCalibrationRetry(boolean isFirstStep) {
          callback.onTwoStepCalibrationRetry(isFirstStep);
        }
      };

  @Override
  @SuppressWarnings("ClickableViewAccessibility")
  public boolean onTouchEvent(MotionEvent event) {
    logTouchForDiagnosis(event);
    updateTouchAction(event);
    boolean result = inputPlane.onTouchEvent(event);
    invalidate();
    return result;
  }

  /**
   * 临时诊断日志（tag=BBTouch）：记录真正到达键盘视图的原始触摸事件，用于判断"多指同时按下"是
   * 系统没有下发多指，还是下发后被识别逻辑否掉。定位完成后应删除。
   */
  private static void logTouchForDiagnosis(MotionEvent event) {
    int action = event.getActionMasked();
    if (action == MotionEvent.ACTION_MOVE) {
      return; // 移动事件过密，只记录按下 / 抬起 / 取消
    }
    StringBuilder sb = new StringBuilder();
    sb.append(MotionEvent.actionToString(action))
        .append(" pointers=")
        .append(event.getPointerCount())
        .append(" downTime=")
        .append(event.getDownTime());
    for (int i = 0; i < event.getPointerCount(); i++) {
      sb.append(" {id=")
          .append(event.getPointerId(i))
          .append(" x=")
          .append((int) event.getX(i))
          .append(" y=")
          .append((int) event.getY(i))
          .append("}");
    }
    Log.d("BBTouch", sb.toString());
  }

  /** Calibrates dots positions. */
  public void calibrateByTwoSteps() {
    inputPlane.calibrateByTwoSteps();
    calibrationType = CalibrationTriggeredType.MANUAL;
    callback.onCalibration(CalibrationTriggeredType.MANUAL, FingersPattern.NO_FINGERS);
    invalidate();
  }

  private void updateTouchAction(MotionEvent event) {
    switch (event.getAction()) {
      case MotionEvent.ACTION_DOWN -> touchInteracting = true;
      case MotionEvent.ACTION_UP -> touchInteracting = false;
      default -> {}
    }
  }

  private boolean shouldPerformCalibrationAnimation(int type, int heldCount) {
    if (type != MultitouchResult.TYPE_CALIBRATION_HOLD) {
      return false;
    }
    if (options.brailleType() == BrailleType.EIGHT_DOT) {
      return heldCount == BrailleType.EIGHT_DOT.getDotCount()
          || heldCount == BrailleType.EIGHT_DOT.getDotCount() / 2;
    } else {
      return heldCount == BrailleType.SIX_DOT.getDotCount()
          || heldCount == BrailleType.SIX_DOT.getDotCount() / 2;
    }
  }

  @CanIgnoreReturnValue
  private boolean processResult(BrailleInputPlaneResult result) {
    if (result.type != MultitouchResult.TYPE_TAP) {
      // 非点击手势（滑动/长按/标定）一律先把攒着的半格结算掉：
      // 它们后面可能马上要提交文本（空格）、删字或切换粒度，留着半格会让顺序错乱。
      flushPendingCell();
    }
    if (result.type == MultitouchResult.TYPE_INVALID) {
      return callback.onInvalidGesture();
    } else if (result.type == MultitouchResult.TYPE_TAP) {
      BrailleCharacter released = result.releasedBrailleCharacter;
      if (shouldAccumulateDots() && released.getOnCount() == 1) {
        // 单点点击：攒进当前格，等停顿后结算。多指同时按（≥2 点）走下面的"立刻出格"，
        // 保证原有的和弦输入方式一点不变慢。
        accumulateDot(released);
        return true;
      }
      flushPendingCell();
      showCaption(callback.onBrailleProduced(released));
    } else if (result.type == MultitouchResult.TYPE_SWIPE) {
      boolean processed = callback.onSwipeProduced(result.swipe);
      if (Utils.isDebugBuild()) {
        if (result.swipe.getTouchCount() == 5) {
          if (autoPerformer == null) {
            this.autoPerformer = new AutoPerformer(getContext(), new AutoPerformerCallback());
            this.autoPerformer.start();
          }
        }
      }
      return processed;
    } else if (result.type == MultitouchResult.TYPE_CALIBRATION_HOLD) {
      if (result.pointersHeldCount == 5) {
        calibrationType = CalibrationTriggeredType.FIVE_FINGERS;
        return callback.onCalibration(calibrationType, FingersPattern.FIVE_FINGERS);
      } else if (result.pointersHeldCount == 6) {
        calibrationType = CalibrationTriggeredType.SIX_FINGERS;
        return callback.onCalibration(calibrationType, FingersPattern.SIX_FINGERS);
      } else if (result.pointersHeldCount == 7) {
        calibrationType = CalibrationTriggeredType.SEVEN_FINGERS;
        return callback.onCalibration(calibrationType, FingersPattern.SEVEN_FINGERS);
      } else if (result.pointersHeldCount == 8) {
        calibrationType = CalibrationTriggeredType.EIGHT_FINGERS;
        return callback.onCalibration(calibrationType, FingersPattern.EIGHT_FINGERS);
      } else if (result.pointersHeldCount == 3) {
        return callback.onCalibration(
            calibrationType,
            result.isLeft
                ? FingersPattern.FIRST_THREE_FINGERS
                : FingersPattern.REMAINING_THREE_FINGERS);
      } else if (result.pointersHeldCount == 4) {
        return callback.onCalibration(
            calibrationType,
            result.isLeft
                ? FingersPattern.FIRST_FOUR_FINGERS
                : FingersPattern.REMAINING_FOUR_FINGERS);
      } else {
        callback.onCalibrationFailed(calibrationType);
      }
    } else if (result.type == MultitouchResult.TYPE_HOLD) {
      return callback.onHoldProduced(result.pointersHeldCount);
    } else if (result.type == MultitouchResult.TYPE_HOLD_AND_SWIPE) {
      return callback.onDotHoldAndDotSwipe(
          new DotHoldSwipe(result.swipe, result.heldBrailleCharacter));
    }
    return false;
  }

  /**
   * 逐点累积只在"一个字符要好几格才能拼出来"的盲文里启用，目前是中文。
   *
   * <p>英文一级盲文里 {@code a} 就是点 1 一格，本身已经完整；若也延迟 800 毫秒才结算，
   * 每个字母都会变慢。中文一个音节 2~3 格，单点分开按才有"攒成一格"的需求。
   */
  private boolean shouldAccumulateDots() {
    if (!accumulateDotsByTap) {
      return false;
    }
    Code code = BrailleUserPreferences.readCurrentActiveInputCodeAndCorrect(getContext());
    return code.name().startsWith("CHINESE");
  }

  /**
   * 把一个单点并进当前格，立刻播报这个点，并重置停顿计时。
   *
   * <p>必须在这里就播报：这一格还没结算，编辑缓冲拿不到任何输入，也就不会按"点位播报"
   * 反馈。用户是盲人，看不到手指落在哪，没有这句播报就等于没按到。
   */
  private void accumulateDot(BrailleCharacter dot) {
    pendingCell = pendingCell.union(dot);
    announceDots(dot);
    removeCallbacks(settlePendingCell);
    postDelayed(settlePendingCell, DOT_ACCUMULATE_SETTLE_MS);
    Log.d(
        TAG,
        "accumulate dot=" + dot + " pending=" + pendingCell + " mode=" + accumulateDotsByTap);
  }

  /** 停顿到点：把攒下的点结算成一格，交给编辑缓冲。 */
  private void emitPendingCell() {
    if (pendingCell.getOnCount() == 0) {
      return;
    }
    BrailleCharacter cell = pendingCell;
    pendingCell = BrailleCharacter.EMPTY_CELL;
    Log.d(TAG, "emit accumulated cell=" + cell);
    showCaption(callback.onBrailleProduced(cell));
  }

  /** 立刻结算当前格（收到非点击手势时调用，避免半格串到后面的命令里）。 */
  private void flushPendingCell() {
    removeCallbacks(settlePendingCell);
    emitPendingCell();
  }

  private void announceDots(BrailleCharacter dots) {
    BrailleCommonTalkBackSpeaker.getInstance()
        .speak(
            BrailleTranslateUtils.getDotsText(getResources(), dots),
            TalkBackSpeaker.AnnounceType.INTERRUPT);
  }

  private void showCaption(String text) {
    if (text == null) {
      return;
    }
    if (captionText != null) {
      captionText.animator.cancel();
    }
    captionText = new CaptionText(text);
    captionText.animator.start();
  }

  private void drawDebugBackground(Canvas canvas) {
    // Drawing the debug background helps expose problems with coordinates and screensize.
    Paint paint = new Paint();
    paint.setColor(getResources().getColor(R.color.input_plane_debug_background));
    int backgroundInset =
        getResources().getDimensionPixelSize(R.dimen.input_plane_debug_background_inset);
    RectF rect =
        new RectF(
            backgroundInset,
            backgroundInset,
            screenSizeInPixels.getWidth() - backgroundInset,
            screenSizeInPixels.getHeight() - backgroundInset);
    canvas.drawRect(rect, paint);
  }

  private void reduceSystemGestureArea() {
    // Reduce the possibility of triggering system-level gestures; note that the exclusion zone
    // is restricted to 200dp, and is counted from the bottom edge upward.
    Rect boundingBox = new Rect();
    boundingBox.set(getLeft(), getTop(), getRight(), getBottom());
    ViewCompat.setSystemGestureExclusionRects(this, ImmutableList.of(boundingBox));
  }

  /** Draws the print translation of the most recently inputted text (for low-vision users). */
  private class CaptionText {
    private final String text;
    private final ValueAnimator animator;
    private final Paint textPaint;
    private static final int ANIMATION_DURATION_MS = 400;

    private CaptionText(String text) {
      this.text = text;
      Resources resources = getResources();
      animator = ValueAnimator.ofFloat(1f, 0f);
      animator.setDuration(ANIMATION_DURATION_MS);
      animator.addListener(
          new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
              BrailleInputView.this.captionText = null;
            }
          });
      animator.addUpdateListener(valueAnimator -> BrailleInputView.this.invalidate());

      textPaint = new Paint();
      textPaint.setColor(resources.getColor(R.color.input_plane_most_recent_animation));
      textPaint.setTextAlign(Paint.Align.CENTER);
      textPaint.setTextSize(
          resources.getDimensionPixelSize(R.dimen.input_view_most_recent_text_size));
      textPaint.setStyle(Paint.Style.FILL_AND_STROKE);
    }

    private void onDraw(Canvas canvas) {
      int alpha = (int) (((Float) animator.getAnimatedValue()) * 255);
      textPaint.setAlpha(alpha);

      Rect textBounds = new Rect();
      textPaint.getTextBounds(text, 0, text.length(), textBounds);
      PointF textPoint = inputPlane.getCaptionCenterPoint(screenSizeInPixels);
      canvas.save();
      canvas.rotate(inputPlane.getRotateDegree(), textPoint.x, textPoint.y);
      canvas.drawText(text, textPoint.x, textPoint.y, textPaint);
      canvas.restore();
    }
  }

  /** Draws the caption text on the bottom-center of the screen. */
  private class InputViewCaption {
    private final String text;
    private final Paint textPaint;
    private final int captionBottomMarginInPixels;

    private InputViewCaption(String text) {
      this.text = text;
      Resources resources = getResources();
      textPaint = new Paint();
      textPaint.setColor(resources.getColor(R.color.input_plane_caption));
      textPaint.setTextAlign(Paint.Align.CENTER);
      textPaint.setTextSize(resources.getDimensionPixelSize(R.dimen.input_view_caption_text_size));
      textPaint.setStyle(Paint.Style.FILL_AND_STROKE);
      textPaint.setTypeface(
          Typeface.create(getContext().getString(R.string.accessibility_font), Typeface.NORMAL));
      captionBottomMarginInPixels =
          resources.getDimensionPixelOffset(R.dimen.input_view_caption_bottom_margin);
    }

    private void onDraw(Canvas canvas) {
      Rect textBounds = new Rect();
      canvas.save();
      textPaint.getTextBounds(text, 0, text.length(), textBounds);
      Size screenSize = inputPlane.getInputViewCaptionScreenSize(screenSizeInPixels);
      canvas.rotate(inputPlane.getRotateDegree());
      int[] dxy = inputPlane.getInputViewCaptionTranslate(screenSize);
      canvas.translate(/* dx= */ dxy[0], /* dy= */ dxy[1]);
      drawText(canvas, screenSize);
      canvas.restore();
    }

    private void drawText(Canvas canvas, Size screenSize) {
      float textX = screenSize.getWidth() / 2.0f;
      float textY = screenSize.getHeight() - captionBottomMarginInPixels;
      canvas.drawText(text, textX, textY, textPaint);
    }
  }

  private class AutoPerformerCallback implements AutoPerformer.Callback {

    @Override
    public void onPerform(BrailleInputPlaneResult bipr) {
      processResult(bipr);
    }

    @Override
    public BrailleInputPlaneResult createSwipe(Swipe swipe) {
      return inputPlane.createSwipe(swipe);
    }

    @Override
    public void onFinish() {
      autoPerformer = null;
    }
  }
}
