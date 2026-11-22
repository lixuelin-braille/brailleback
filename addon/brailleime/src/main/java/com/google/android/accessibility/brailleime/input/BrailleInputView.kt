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

package com.google.android.accessibility.brailleime.input

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.content.res.Resources
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.util.Log
import android.util.Size
import android.view.MotionEvent
import android.view.View
import android.view.View.OnAttachStateChangeListener
import androidx.core.view.ViewCompat
import com.google.android.accessibility.braille.common.BrailleCommonTalkBackSpeaker
import com.google.android.accessibility.braille.common.BrailleUserPreferences
import com.google.android.accessibility.braille.common.BrailleUtils
import com.google.android.accessibility.braille.common.Constants.BrailleType
import com.google.android.accessibility.braille.common.TalkBackSpeaker
import com.google.android.accessibility.braille.common.translate.BrailleLanguages.Code
import com.google.android.accessibility.braille.common.translate.BrailleTranslateUtils
import com.google.android.accessibility.braille.common.translate.ChineseBrailleSignNames
import com.google.android.accessibility.braille.interfaces.BrailleCharacter
import com.google.android.accessibility.brailleime.BrailleIme.OrientationSensitive
import com.google.android.accessibility.brailleime.BrailleInputOptions
import com.google.android.accessibility.brailleime.R
import com.google.android.accessibility.brailleime.Utils
import com.google.android.accessibility.brailleime.input.BrailleInputPlane.CustomOnGestureListener
import com.google.android.accessibility.brailleime.input.BrailleInputPlane.DotTarget
import com.google.android.accessibility.brailleime.input.MultitouchHandler.HoldRecognizer
import com.google.common.collect.ImmutableList
import com.google.errorprone.annotations.CanIgnoreReturnValue
import java.util.Optional

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
class BrailleInputView(
  context: Context,
  private val callback: Callback,
  private var screenSizeInPixels: Size,
  private var options: BrailleInputOptions,
  tabletopMode: Boolean,
) : View(context), OrientationSensitive, OnAttachStateChangeListener {

  /** A callback for receiving signals from BrailleInputView. */
  interface Callback {
    /** Signals that invalid gesture has been produced. Returns true if the action is consumed. */
    fun onInvalidGesture(): Boolean {
      return false
    }

    /**
     * Signals that {@link Swipe} input has been produced. Returns true if the action is consumed.
     */
    @CanIgnoreReturnValue
    fun onSwipeProduced(swipe: Swipe): Boolean

    /**
     * Signals that hold and dot swipe input has been produced. Returns true if the action is
     * consumed.
     */
    @CanIgnoreReturnValue
    fun onDotHoldAndDotSwipe(dotHoldSwipe: DotHoldSwipe): Boolean

    /**
     * Allows the client to state which potential hold events it cares about. If the client returns
     * true, the BrailleInputView will invoke onHoldProduced, and then it (along with the input
     * chain with which it interfaces) will halt subsequent callbacks until the user releases (lifts
     * up) the press.
     */
    @CanIgnoreReturnValue
    fun isCalibrationHoldRecognized(
      inTwoStepCalibration: Boolean,
      pointersHeldCount: Int,
    ): Boolean {
      return false
    }

    /** Signals that hold has been produced. Returns true if the action is consumed. */
    @CanIgnoreReturnValue
    fun onHoldProduced(pointersHeldCount: Int): Boolean

    /**
     * Signals that {@link BrailleCharacter} input has been produced.
     *
     * @return the client's notion of the latest update to the print text so that this View can
     *     render it (for low-vision users).
     */
    fun onBrailleProduced(brailleCharacter: BrailleCharacter): String? {
      return ""
    }

    /**
     * 把连续按下的点流切成已确定的音节，<b>不使用任何等待时间</b>。
     *
     * <p>中文盲文一格是若干点的集合，而点位之间存在包含关系（{@code 12} 是波，加 3 成勒、加 4
     * 成佛），所以"这一格按完没有"在结构上无法判定，只能靠停顿猜。猜早了把一格切断、猜晚了把
     * 两格粘住，用户报的"打慢一点就拼不成"就是这个。改按音节整串识别后，判据是"这串点是不是
     * 一个不能再长的音节"，与手速无关。
     *
     * @param dotStream 还没分格的点流，例如 {@code 1451362}
     * @param force {@code false} 表示用户还在打（绝不能猜）；{@code true} 表示用户已经示意这串点
     *     打完了（按空格上屏、做手势），此时按最长匹配直接切
     * @return {@code null} 表示还得继续按才能判断；空列表表示这串点已经不可能成字；否则返回
     *     已确定音节的格，调用方逐个 {@link #onBrailleProduced} 送出
     */
    fun segmentDotStream(dotStream: String, force: Boolean): List<BrailleCharacter>? {
      return null
    }

    /** Signals that calibration has been produced. Returns true if the action is consumed. */
    @CanIgnoreReturnValue
    fun onCalibration(type: CalibrationTriggeredType, hand: FingersPattern): Boolean {
      return false
    }

    /** Signals that calibration has failed. */
    fun onCalibrationFailed(calibration: CalibrationTriggeredType) {}

    /** Signals that two step calibration needs retry. */
    fun onTwoStepCalibrationRetry(isFirstStep: Boolean) {}
  }

  /** Indicates which finger pattern. */
  enum class FingersPattern {
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
  enum class CalibrationTriggeredType {
    FIVE_FINGERS,
    SIX_FINGERS,
    SEVEN_FINGERS,
    EIGHT_FINGERS,
    MANUAL,
  }

  private var captionText: CaptionText? = null
  private var orientation: Int = 0
  private var tabletopMode: Boolean = false
  private var autoPerformer: AutoPerformer? = null
  private var touchInteracting: Boolean = false
  private var calibrationType: CalibrationTriggeredType? = null

  /**
   * 攒下的点串，<b>可以跨好几格</b>，直到能判定出一个完整音节才切出去。
   *
   * <p>盲文的"一格"在上游等于<b>一次触摸手势</b>：{@link MultitouchHandler} 在最后一根手指
   * 抬起（ACTION_UP）时做 tap 判定并 {@code clearPointerCollections()}，跨手势不累积。累积
   * 模式只解决"同一手势里某根手指先松开"，不解决"分成几次点击"。而中文一个音节要 2~3 格，
   * 用户把一格的几个点分开按，就会变成几个单格，永远拼不出一个汉字。
   *
   * <p><b>不再用等待时间判断这一格结束没有</b>。原因见 {@link Callback#segmentDotStream}：
   * 点位之间的包含关系使得"格是否结束"在结构上无法判定，猜早了切断、猜晚了粘连。现在改成
   * 攒整串点、按音节识别，攒多久都不影响正确性。
   */
  private var pendingCell: BrailleCharacter = BrailleCharacter.EMPTY_CELL

  /**
   * 当前还没归属到任何音节的全部点，按先后顺序。
   *
   * <p>为什么要单独记顺序而不是只看 {@link #pendingCell}：中文一格要按 3 个点，按错最后一个
   * 点时想改，只能把整格退掉重打——三个点重来一遍，慢且容易再错。有了顺序就能只退最后按的
   * 那一个点，和普通打字机"退格"的手感一致。
   */
  private val pendingDotOrder: MutableList<BrailleCharacter> = ArrayList()

  /** 是否启用逐点累积（跟随"包含所有按下的点"设置）。关闭时行为与上游完全一致。 */
  private var accumulateDotsByTap: Boolean = false

  private val inputPlane: BrailleInputPlane
  private val inputViewCaption: InputViewCaption

  private val customOnGestureListener: CustomOnGestureListener =
    object : CustomOnGestureListener {
      override fun detect(resultOptional: Optional<BrailleInputPlaneResult>): Boolean {
        if (resultOptional.isPresent) {
          if (shouldPerformCalibrationAnimation(
              resultOptional.get().type, resultOptional.get().pointersHeldCount)) {
            inputPlane.createAnimator(this@BrailleInputView)
          }
          val result = processResult(resultOptional.get())
          invalidate()
          return result
        }
        return false
      }

      override fun onTwoStepCalibrationFailed() {
        callback.onCalibrationFailed(calibrationType!!)
        invalidate()
      }

      override fun onTwoStepCalibrationRetry(isFirstStep: Boolean) {
        callback.onTwoStepCalibrationRetry(isFirstStep)
      }
    }

  /**
   * Construct a BrailleInputView.
   *
   * <p>We do not provide a constructor with {@link android.util.AttributeSet} because we have no
   * need for it and we have a Callback that we need passed in during construction.
   */
  init {
    orientation = resources.configuration.orientation
    inputPlane = getInputPlane(context)
    inputPlane.setTableTopMode(tabletopMode)
    this.tabletopMode = tabletopMode
    setBackgroundColor(resources.getColor(R.color.input_plane_background))
    inputViewCaption = InputViewCaption(context.getString(R.string.input_view_caption))
    addOnAttachStateChangeListener(this)
  }

  private fun getInputPlane(context: Context): BrailleInputPlane {
    val holdRecognizer: HoldRecognizer =
      object : HoldRecognizer {
        override fun isCalibrationHoldRecognized(pointersHeldCount: Int): Boolean {
          return callback.isCalibrationHoldRecognized(
            inputPlane.inTwoStepCalibration(), pointersHeldCount)
        }

        override fun isHoldRecognized(pointersHeldCount: Int): Boolean {
          return !inputPlane.inTwoStepCalibration() && pointersHeldCount <= 3
        }
      }
    return if (BrailleUtils.isPhoneSizedDevice(context.resources))
      BrailleInputPlanePhone(
        context,
        screenSizeInPixels,
        holdRecognizer,
        orientation,
        options,
        customOnGestureListener)
    else
      BrailleInputPlaneTablet(
        context,
        screenSizeInPixels,
        holdRecognizer,
        orientation,
        options,
        customOnGestureListener)
  }

  override fun onOrientationChanged(orientation: Int, screenSize: Size) {
    this.orientation = orientation
    screenSizeInPixels = screenSize
    inputPlane.setOrientation(orientation, screenSizeInPixels)
    invalidate()
    requestLayout()
  }

  override fun onViewAttachedToWindow(view: View) {}

  override fun onViewDetachedFromWindow(view: View) {
    if (inTwoStepCalibration()) {
      callback.onCalibrationFailed(calibrationType!!)
    }
    // 视图被摘下（切语言、旋转、收键盘）时，攒着的半格不能无声丢掉。
    flushPendingCell()
    removeOnAttachStateChangeListener(this)
  }

  fun setOptions(options: BrailleInputOptions) {
    this.options = options
    inputPlane.setOptions(options)
    invalidate()
    requestLayout()
  }

  /** Sets keyboard to table layout. */
  fun setTabletopMode(enabled: Boolean) {
    if (tabletopMode != enabled) {
      inputPlane.setTableTopMode(enabled)
      tabletopMode = enabled
      invalidate()
      requestLayout()
    }
  }

  /** Stores points positions in the SharedPreference. */
  fun savePoints() {
    inputPlane.storeLayoutPoints()
  }

  /**
   * Sets the accumulation mode.
   *
   * <p>An accumulation mode set to true means that all press-and-releases contribute to a potential
   * commission even if the release occurred far before the release of the final touch point.
   */
  fun setAccumulationMode(accumulationMode: Boolean) {
    inputPlane.setAccumulationMode(accumulationMode)
    accumulateDotsByTap = accumulationMode
    if (!accumulationMode) {
      // 关闭累积时不能留着半格，否则这一格永远等不到结算。
      flushPendingCell()
    }
  }

  /** Gets the accumulation mode. */
  fun isAccumulationMode(): Boolean {
    return inputPlane.isAccumulationMode()
  }

  /** Returns whether in the process of two step calibration. */
  fun inTwoStepCalibration(): Boolean {
    return inputPlane.inTwoStepCalibration()
  }

  fun getDotTargets(): List<DotTarget> {
    return inputPlane.getDotTargets()
  }

  /** Gets braille input view dot count. */
  fun getBrailleDotCount(): Int {
    return options.brailleType().getDotCount()
  }

  /** Boolean value for whether a finger is currently touching the braille input view. */
  fun isTouchInteracting(): Boolean {
    return touchInteracting
  }

  override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
    super.onLayout(changed, left, top, right, bottom)
    if (changed) {
      // 诊断：确认视图实际尺寸与算点阵用的 screenSizeInPixels 是否一致。
      // 若两者不同，画出来的点和判定用的点就会错位，表现为"点位映射有问题"。
      Log.d(
        "BBDots",
        "onLayout view=" +
          (right - left) +
          "x" +
          (bottom - top) +
          " left=" +
          left +
          " top=" +
          top +
          " screenSizeInPixels=" +
          screenSizeInPixels.width +
          "x" +
          screenSizeInPixels.height)
      reduceSystemGestureArea()
    }
  }

  override fun onDraw(canvas: Canvas) {
    super.onDraw(canvas)
    if (DRAW_DEBUG_BACKGROUND) {
      drawDebugBackground(canvas)
    }
    inputPlane.onDraw(canvas)
    if (captionText != null) {
      captionText!!.onDraw(canvas)
    }
    inputViewCaption.onDraw(canvas)
  }

  @SuppressWarnings("ClickableViewAccessibility")
  override fun onTouchEvent(event: MotionEvent): Boolean {
    logTouchForDiagnosis(event)
    updateTouchAction(event)
    val result = inputPlane.onTouchEvent(event)
    invalidate()
    return result
  }

  /** Calibrates dots positions. */
  fun calibrateByTwoSteps() {
    inputPlane.calibrateByTwoSteps()
    calibrationType = CalibrationTriggeredType.MANUAL
    callback.onCalibration(CalibrationTriggeredType.MANUAL, FingersPattern.NO_FINGERS)
    invalidate()
  }

  private fun updateTouchAction(event: MotionEvent) {
    when (event.action) {
      MotionEvent.ACTION_DOWN -> touchInteracting = true
      MotionEvent.ACTION_UP -> touchInteracting = false
      else -> {}
    }
  }

  private fun shouldPerformCalibrationAnimation(type: Int, heldCount: Int): Boolean {
    if (type != MultitouchResult.TYPE_CALIBRATION_HOLD) {
      return false
    }
    if (options.brailleType() == BrailleType.EIGHT_DOT) {
      return heldCount == BrailleType.EIGHT_DOT.getDotCount() ||
        heldCount == BrailleType.EIGHT_DOT.getDotCount() / 2
    } else {
      return heldCount == BrailleType.SIX_DOT.getDotCount() ||
        heldCount == BrailleType.SIX_DOT.getDotCount() / 2
    }
  }

  @CanIgnoreReturnValue
  private fun processResult(result: BrailleInputPlaneResult): Boolean {
    var producedSyllable = false
    if (result.type != MultitouchResult.TYPE_TAP) {
      // 单指左滑 + 还有没结算的点 = 用户想改掉刚按错的那个点，不是想删掉整格。
      // 中文一格要按 3 个点，整格重打的代价太大，而且很容易再错一次。
      // 这条让左滑始终表示同一个意思："撤销最后一次输入动作"——有点就退点，没点才退格。
      if (shouldUndoLastDot(result)) {
        undoLastDot()
        return true
      }
      // 其余非点击手势（滑动/长按/标定）一律先把攒着的点结算掉：
      // 它们后面可能马上要上屏、删字或切换粒度，留着半截会让顺序错乱。
      producedSyllable = flushPendingCell()
    }
    if (result.type == MultitouchResult.TYPE_INVALID) {
      return callback.onInvalidGesture()
    } else if (result.type == MultitouchResult.TYPE_TAP) {
      val released = result.releasedBrailleCharacter
      if (shouldAccumulateDots() && released!!.getOnCount() == 1) {
        // 单点点击：攒进点流，等上屏键结算。多指同时按（≥2 点）走下面的"立刻出格"，
        // 保证原有的和弦输入方式一点不变慢。
        accumulateDot(released!!)
        return true
      }
      flushPendingCell()
      showCaption(callback.onBrailleProduced(released!!))
    } else if (result.type == MultitouchResult.TYPE_SWIPE) {
      // 上屏键（单指右滑）分两步，跟普通输入法"输入和选字 / 上屏"一致：
      //   第 1 次：把攒的点切成音节，编辑缓冲给出候选 —— 本次不再转发给动作层，
      //            否则 appendSpace() 会立刻把刚产生的首选字上屏，用户就没机会换候选了。
      //   第 2 次：已经没有点流、只有候选，正常转发 appendSpace() 把选中的字上屏。
      // 其它滑动（删字、切粒度…）不吞手势，行为不变。
      if (producedSyllable &&
        result.swipe!!.touchCount == 1 &&
        result.swipe!!.direction == Swipe.Direction.RIGHT
      ) {
        return true
      }
      val processed = callback.onSwipeProduced(result.swipe!!)
      if (Utils.isDebugBuild()) {
        if (result.swipe!!.touchCount == 5) {
          if (autoPerformer == null) {
            autoPerformer = AutoPerformer(context, AutoPerformerCallback())
            autoPerformer!!.start()
          }
        }
      }
      return processed
    } else if (result.type == MultitouchResult.TYPE_CALIBRATION_HOLD) {
      if (result.pointersHeldCount == 5) {
        calibrationType = CalibrationTriggeredType.FIVE_FINGERS
        return callback.onCalibration(calibrationType!!, FingersPattern.FIVE_FINGERS)
      } else if (result.pointersHeldCount == 6) {
        calibrationType = CalibrationTriggeredType.SIX_FINGERS
        return callback.onCalibration(calibrationType!!, FingersPattern.SIX_FINGERS)
      } else if (result.pointersHeldCount == 7) {
        calibrationType = CalibrationTriggeredType.SEVEN_FINGERS
        return callback.onCalibration(calibrationType!!, FingersPattern.SEVEN_FINGERS)
      } else if (result.pointersHeldCount == 8) {
        calibrationType = CalibrationTriggeredType.EIGHT_FINGERS
        return callback.onCalibration(calibrationType!!, FingersPattern.EIGHT_FINGERS)
      } else if (result.pointersHeldCount == 3) {
        return callback.onCalibration(
          calibrationType!!,
          if (result.isLeft) FingersPattern.FIRST_THREE_FINGERS
          else FingersPattern.REMAINING_THREE_FINGERS)
      } else if (result.pointersHeldCount == 4) {
        return callback.onCalibration(
          calibrationType!!,
          if (result.isLeft) FingersPattern.FIRST_FOUR_FINGERS
          else FingersPattern.REMAINING_FOUR_FINGERS)
      } else {
        callback.onCalibrationFailed(calibrationType!!)
      }
    } else if (result.type == MultitouchResult.TYPE_HOLD) {
      return callback.onHoldProduced(result.pointersHeldCount)
    } else if (result.type == MultitouchResult.TYPE_HOLD_AND_SWIPE) {
      return callback.onDotHoldAndDotSwipe(
        DotHoldSwipe(result.swipe!!, result.heldBrailleCharacter!!))
    }
    return false
  }

  /**
   * 逐点累积只在"一个字符要好几格才能拼出来"的盲文里启用，目前是中文。
   *
   * <p>英文一级盲文里 {@code a} 就是点 1 一格，本身已经完整；若也延迟 800 毫秒才结算，
   * 每个字母都会变慢。中文一个音节 2~3 格，单点分开按才有"攒成一格"的需求。
   */
  private fun shouldAccumulateDots(): Boolean {
    if (!accumulateDotsByTap) {
      return false
    }
    val code: Code = BrailleUserPreferences.readCurrentActiveInputCodeAndCorrect(context)
    return code.name.startsWith("CHINESE")
  }

  /**
   * 把一个单点并进当前点串，立刻播报，然后试着切出已经能确定的音节。
   *
   * <p>必须在这里就播报：点还在缓冲区里，编辑缓冲拿不到输入，也就不会给出任何反馈。用户是
   * 盲人，看不到手指落在哪，没有这句播报就等于没按到。
   *
   * <p><b>没有计时器</b>。判断"这一格/这个音节打完没有"不再依赖停顿，见
   * {@link #drainDecidedSyllables}。
   */
  private fun accumulateDot(dot: BrailleCharacter) {
    pendingDotOrder.add(dot)
    pendingCell = pendingCell.union(dot)
    announceStream()
    Log.d(TAG, "accumulate dot=" + dot + " stream=" + dotsStream())
    drainDecidedSyllables()
  }

  /** 当前还没归属到任何音节的全部点，拼成点流（每个字符一个点号）。 */
  private fun dotsStream(): String {
    val builder = StringBuilder(pendingDotOrder.size)
    for (dot in pendingDotOrder) {
      builder.append(dot.toString())
    }
    return builder.toString()
  }

  /**
   * 把点流里<b>已经能确定</b>的音节切出去，剩下的继续攒着。
   *
   * <p>这是"不再用时间限制"的落点：判据不是"停了多久"，而是"这串点是不是已经是一个不能再长的
   * 音节"。例如打"读"({@code 145-136-2}) 时，按到 {@code 1451} 不会出字——因为 {@code 1451}
   * 既是"搭"、也是"读"的开头；按到 {@code 1451362} 时整串落在表里、且没有更长的音节以它开头，
   * 才整段切出来。用户按多慢都不会被切断，按多快也不会粘在一起。
   *
   * <p>有多个音节可以立刻切时循环处理，这样连续输入时不必每次退回一次按键。
   */
  private fun drainDecidedSyllables() {
    while (pendingDotOrder.isNotEmpty()) {
      val stream = dotsStream()
      val cells: List<BrailleCharacter>? = callback.segmentDotStream(stream, /* force= */ false)
      if (cells == null) {
        // 还得接着按，这一串点先留着。
        return
      }
      if (cells.isEmpty()) {
        // 这串点已经不可能构成任何音节（按错了）。退掉最早的那个点，
        // 让用户从下一个点重新开始，而不是一直卡在这串死路上。
        pendingDotOrder.removeAt(0)
        rebuildPendingCell()
        Log.d(TAG, "dot stream dead end, drop first dot, remain=" + dotsStream())
        continue
      }
      var consumed = 0
      for (cell in cells) {
        consumed += cell.getOnCount()
        showCaption(callback.onBrailleProduced(cell))
      }
      var i = 0
      while (i < consumed && pendingDotOrder.isNotEmpty()) {
        pendingDotOrder.removeAt(0)
        i++
      }
      rebuildPendingCell()
    }
  }

  /** 按当前点序重算合并后的点位（只用于播报与兜底提交）。 */
  private fun rebuildPendingCell() {
    var rebuilt = BrailleCharacter.EMPTY_CELL
    for (dot in pendingDotOrder) {
      rebuilt = rebuilt.union(dot)
    }
    pendingCell = rebuilt
  }

  /** 这次手势是不是"退掉刚按的那个点"。判定条件见 {@link DotUndoPolicy}。 */
  private fun shouldUndoLastDot(result: BrailleInputPlaneResult): Boolean {
    return DotUndoPolicy.shouldUndoDot(
      result.type == MultitouchResult.TYPE_SWIPE,
      if (result.swipe == null) 0 else result.swipe!!.touchCount,
      result.swipe != null && result.swipe!!.direction == Swipe.Direction.LEFT,
      shouldAccumulateDots(),
      pendingDotOrder.size)
  }

  /**
   * 退掉最后按下的那一个点，并把剩下的点重新播报一遍。
   *
   * <p>必须播报结果而不是只说"已删除"：用户是盲人，看不到这一格现在还剩哪几个点，
   * 不念出来他就不知道刚才那一下退掉的是不是他想退的。
   */
  private fun undoLastDot() {
    pendingDotOrder.removeAt(pendingDotOrder.size - 1)
    rebuildPendingCell()
    if (pendingCell.getOnCount() == 0) {
      // 已经退空：没有可结算的内容。
      // 不能走 announceDots：空格的点位文本是"点 "，念出来像没说完。
      BrailleCommonTalkBackSpeaker.getInstance()
        .speak(
          getContext().getString(R.string.bb_cell_cleared),
          TalkBackSpeaker.AnnounceType.INTERRUPT)
      Log.d(TAG, "undo dot -> cell empty")
      return
    }
    announceStream()
    Log.d(TAG, "undo dot -> stream=" + dotsStream())
  }

  /**
   * 用户做了非点击手势（右滑上屏、删字、切粒度…）：把攒着的点按上屏键的语义结算。
   *
   * <p>一次只切<b>一个</b>音节。剩下的点继续留在点流里，下次按上屏键再切下一个——用户
   * 连打了好几个字才按上屏键时，这样能逐字出，且每次都是确定的最长匹配。
   *
   * <p>绝不能像旧版那样把剩余点按"每格 6 点"硬切送出去：那会把后面还没打完的字切碎，
   * 而编辑缓冲会把这些碎格当成合法单格去翻译，吐出莫名其妙的字母和大写符。
   * @return 这次是否切出了一个音节。上屏键靠它区分"第一次按：出候选"和"第二次按：上屏"，
   *     见 {@link #processResult}。
   */
  private fun flushPendingCell(): Boolean {
    if (pendingDotOrder.isEmpty()) {
      return false
    }
    val decided: List<BrailleCharacter>? =
      callback.segmentDotStream(dotsStream(), /* force= */ true)
    if (decided != null && !decided.isEmpty()) {
      var consumed = 0
      for (cell in decided) {
        consumed += cell.getOnCount()
        showCaption(callback.onBrailleProduced(cell))
      }
      var i = 0
      while (i < consumed && pendingDotOrder.isNotEmpty()) {
        pendingDotOrder.removeAt(0)
        i++
      }
      rebuildPendingCell()
      if (pendingDotOrder.isNotEmpty()) {
        // 还有没上屏的点：明确说出来，否则用户以为一次上屏键就把整串都交出去了。
        BrailleCommonTalkBackSpeaker.getInstance()
          .speak(
            getContext()
              .getString(R.string.bb_pending_points_remaining, pendingDotOrder.size),
            TalkBackSpeaker.AnnounceType.INTERRUPT)
      }
      return true
    }
    // 连强制切分都切不出音节：这串点确实按错了，或者只打了半截。清掉，别让它一直挂着
    // 挡住后面的输入。原来会硬切送出去，那是"吐大写符/私用区字符"的来源。
    pendingDotOrder.clear()
    rebuildPendingCell()
    BrailleCommonTalkBackSpeaker.getInstance()
      .speak(
        getContext().getString(R.string.bb_incomplete_braille_cleared),
        TalkBackSpeaker.AnnounceType.INTERRUPT)
    return false
  }

  /**
   * 播报当前这串点现在读作什么。
   *
   * <p>盲文是记音的，用户记的是"1-2 点是波"，不是"点 1 点 2"。所以优先播单格中文读名
   * （{@link ChineseBrailleSignNames}）；一串点不构成单独一格时（打了一半），播最后按下的那个点，
   * 让用户确认这一下按到的是他想按的点位。
   *
   * <p>这里必须播，因为点还没交给编辑缓冲，那边不会有任何反馈；用户看不见手指落点，
   * 没有这句就等于没按到。
   */
  private fun announceStream() {
    val name = ChineseBrailleSignNames.describe(dotsStream())
    if (name != null) {
      BrailleCommonTalkBackSpeaker.getInstance().speak(name, TalkBackSpeaker.AnnounceType.INTERRUPT)
      return
    }
    val last = pendingDotOrder[pendingDotOrder.size - 1]
    val lastName = ChineseBrailleSignNames.describe(last.toString())
    BrailleCommonTalkBackSpeaker.getInstance()
      .speak(
        lastName ?: BrailleTranslateUtils.getDotsText(resources, last),
        TalkBackSpeaker.AnnounceType.INTERRUPT)
  }

  private fun showCaption(text: String?) {
    if (text == null) {
      return
    }
    if (captionText != null) {
      captionText!!.animator.cancel()
    }
    captionText = CaptionText(text)
    captionText!!.animator.start()
  }

  private fun drawDebugBackground(canvas: Canvas) {
    // Drawing the debug background helps expose problems with coordinates and screensize.
    val paint = Paint()
    paint.color = resources.getColor(R.color.input_plane_debug_background)
    val backgroundInset =
      resources.getDimensionPixelSize(R.dimen.input_plane_debug_background_inset)
    val rect =
      RectF(
        backgroundInset.toFloat(),
        backgroundInset.toFloat(),
        (screenSizeInPixels.width - backgroundInset).toFloat(),
        (screenSizeInPixels.height - backgroundInset).toFloat())
    canvas.drawRect(rect, paint)
  }

  private fun reduceSystemGestureArea() {
    // Reduce the possibility of triggering system-level gestures; note that the exclusion zone
    // is restricted to 200dp, and is counted from the bottom edge upward.
    val boundingBox = Rect()
    boundingBox.set(left, top, right, bottom)
    ViewCompat.setSystemGestureExclusionRects(this, ImmutableList.of(boundingBox))
  }

  /** Draws the print translation of the most recently inputted text (for low-vision users). */
  private inner class CaptionText(
    private val text: String,
  ) {
    val animator: ValueAnimator
    private val textPaint: Paint

    init {
      val resources: Resources = resources
      animator = ValueAnimator.ofFloat(1f, 0f)
      animator.duration = ANIMATION_DURATION_MS.toLong()
      animator.addListener(
        object : AnimatorListenerAdapter() {
          override fun onAnimationEnd(animation: Animator) {
            this@BrailleInputView.captionText = null
          }
        })
      animator.addUpdateListener { valueAnimator -> this@BrailleInputView.invalidate() }

      textPaint = Paint()
      textPaint.color = resources.getColor(R.color.input_plane_most_recent_animation)
      textPaint.textAlign = Paint.Align.CENTER
      textPaint.textSize =
        resources.getDimensionPixelSize(R.dimen.input_view_most_recent_text_size).toFloat()
      textPaint.style = Paint.Style.FILL_AND_STROKE
    }

    fun onDraw(canvas: Canvas) {
      val alpha = ((animator.animatedValue as Float) * 255).toInt()
      textPaint.alpha = alpha

      val textBounds = Rect()
      textPaint.getTextBounds(text, 0, text.length, textBounds)
      val textPoint = inputPlane.getCaptionCenterPoint(screenSizeInPixels)
      canvas.save()
      canvas.rotate(inputPlane.getRotateDegree().toFloat(), textPoint.x, textPoint.y)
      canvas.drawText(text, textPoint.x, textPoint.y, textPaint)
      canvas.restore()
    }
  }

  /** Draws the caption text on the bottom-center of the screen. */
  private inner class InputViewCaption(
    private val text: String,
  ) {
    private val textPaint: Paint
    private val captionBottomMarginInPixels: Int

    init {
      val resources: Resources = resources
      textPaint = Paint()
      textPaint.color = resources.getColor(R.color.input_plane_caption)
      textPaint.textAlign = Paint.Align.CENTER
      textPaint.textSize =
        resources.getDimensionPixelSize(R.dimen.input_view_caption_text_size).toFloat()
      textPaint.style = Paint.Style.FILL_AND_STROKE
      textPaint.typeface =
        Typeface.create(getContext().getString(R.string.accessibility_font), Typeface.NORMAL)
      captionBottomMarginInPixels =
        resources.getDimensionPixelOffset(R.dimen.input_view_caption_bottom_margin)
    }

    fun onDraw(canvas: Canvas) {
      val textBounds = Rect()
      canvas.save()
      textPaint.getTextBounds(text, 0, text.length, textBounds)
      val screenSize = inputPlane.getInputViewCaptionScreenSize(screenSizeInPixels)
      canvas.rotate(inputPlane.getRotateDegree().toFloat())
      val dxy = inputPlane.getInputViewCaptionTranslate(screenSize)
      canvas.translate(/* dx= */ dxy[0].toFloat(), /* dy= */ dxy[1].toFloat())
      drawText(canvas, screenSize)
      canvas.restore()
    }

    private fun drawText(canvas: Canvas, screenSize: Size) {
      val textX = screenSize.width / 2.0f
      val textY = (screenSize.height - captionBottomMarginInPixels).toFloat()
      canvas.drawText(text, textX, textY, textPaint)
    }
  }

  private inner class AutoPerformerCallback : AutoPerformer.Callback {

    override fun onPerform(bipr: BrailleInputPlaneResult) {
      processResult(bipr)
    }

    override fun createSwipe(swipe: Swipe): BrailleInputPlaneResult {
      return inputPlane.createSwipe(swipe)
    }

    override fun onFinish() {
      autoPerformer = null
    }
  }

  companion object {
    private const val TAG = "BrailleInputView"
    private const val DRAW_DEBUG_BACKGROUND = false // Leave as false in version control.
    private const val ANIMATION_DURATION_MS = 400

    /**
     * 临时诊断日志（tag=BBTouch）：记录真正到达键盘视图的原始触摸事件，用于判断"多指同时按下"是
     * 系统没有下发多指，还是下发后被识别逻辑否掉。定位完成后应删除。
     */
    @JvmStatic
    private fun logTouchForDiagnosis(event: MotionEvent) {
      val action = event.actionMasked
      if (action == MotionEvent.ACTION_MOVE) {
        return // 移动事件过密，只记录按下 / 抬起 / 取消
      }
      val sb = StringBuilder()
      sb.append(MotionEvent.actionToString(action))
        .append(" pointers=")
        .append(event.pointerCount)
        .append(" downTime=")
        .append(event.downTime)
      for (i in 0 until event.pointerCount) {
        sb.append(" {id=")
          .append(event.getPointerId(i))
          .append(" x=")
          .append(event.getX(i).toInt())
          .append(" y=")
          .append(event.getY(i).toInt())
          .append("}")
      }
      Log.d("BBTouch", sb.toString())
    }
  }
}
