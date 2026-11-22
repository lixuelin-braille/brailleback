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
import android.animation.AnimatorSet
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.Typeface
import android.util.Log
import android.util.Size
import android.view.MotionEvent
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.annotation.ColorInt
import androidx.annotation.VisibleForTesting
import com.google.android.accessibility.braille.common.Constants.BrailleType
import com.google.android.accessibility.braille.interfaces.BrailleCharacter
import com.google.android.accessibility.brailleime.BrailleImeLog
import com.google.android.accessibility.brailleime.BrailleInputOptions
import com.google.android.accessibility.brailleime.R
import com.google.android.accessibility.brailleime.Utils
import com.google.android.accessibility.brailleime.input.MultitouchHandler.HoldRecognizer
import com.google.android.accessibility.brailleime.input.MultitouchHandler.MultitouchResultListener
import com.google.common.collect.ImmutableList
import com.google.common.collect.ImmutableMap
import java.util.Optional

/**
 * Renders visual braille dots, along with their pressed state, and processes touch input for them
 * (for use in BrailleInputView).
 *
 * <p>BrailleInputPlane holds a two-dimensional array of dot targets. Generally speaking, each
 * DotTarget serves as the centroid of polygonal region that is associated with specific dot number
 * (such as Dot 1). The union of these disjoint regions covers a rectangular region (which could be
 * the entire display, for example).
 *
 * <p>The primary methods are {@link BrailleInputPlane#onDraw}, which handles the rendering, and
 * {@link BrailleInputPlane#onTouchEvent(MotionEvent)}, which handles touch input and returns a
 * {@link BrailleInputPlaneResult} in case commission occurs.
 */
abstract class BrailleInputPlane(
  context: Context,
  sizeInPixels: Size,
  holdRecognizer: HoldRecognizer?,
  orientation: Int,
  options: BrailleInputOptions,
  customGestureDetector: CustomOnGestureListener,
) {
  protected val context: Context = context
  @JvmField protected var isTableTopMode: Boolean = false
  protected var orientation: Int = orientation

  private val resources: Resources = context.resources
  private val multitouchHandler: MultitouchHandler
  private val dotRadius: Int

  private lateinit var dotBackgroundPaint: Paint
  @ColorInt private var dotBackgroundColorDefault: Int = 0
  @ColorInt private var dotBackgroundColorDefaultCalibration: Int = 0
  @ColorInt private var dotBackgroundColorPressedDefault: Int = 0
  @ColorInt private var dotBackgroundColorPressedCalibration: Int = 0

  private lateinit var dotNumberPaint: Paint
  @ColorInt private var dotNumberColorDefaultCalibration: Int = 0
  @ColorInt private var dotNumberColorDefault: Int = 0
  @ColorInt private var dotNumberColorPressedDefault: Int = 0
  @ColorInt private var dotNumberColorPressedCalibration: Int = 0
  private lateinit var touchCirclesPaint: Paint
  private var textBaseline: Int = 0

  private lateinit var currentlyPressedDots: MutableSet<DotTarget>
  private var oldDotTargets: MutableList<DotTarget>? = null
  private lateinit var dotTargets: MutableList<DotTarget>
  private var sizeInPixels: Size = sizeInPixels
  private var numberOfColumnsTabletop: Int = 0
  private var numberOfRowsScreenAway: Int = 0
  private var options: BrailleInputOptions = options
  private var calibrationFailCount: Int = 0

  private lateinit var textPosition: Array<PointF?>
  private lateinit var dotCenterPosition: Array<PointF?>
  private var twoStepCalibrationState: TwoStepCalibrationState = TwoStepCalibrationState.NONE
  private val customOnGestureListener: CustomOnGestureListener = customGestureDetector
  private val multitouchResultListener: MultitouchResultListener =
    object : MultitouchResultListener {
      override fun detect(touchResultOptional: Optional<MultitouchResult>): Boolean {
        BrailleImeLog.d(
          TAG,
          "detect: " +
            (if (touchResultOptional.isPresent) touchResultOptional.get().type else false))
        // Update the dots state even if we received an empty result.
        currentlyPressedDots = matchTouchToTargets(multitouchHandler.getActivePoints())
        if (touchResultOptional.isPresent()) {
          return processMultitouchResult(touchResultOptional.get())
        }
        return false
      }
    }

  /** state of two step calibration. */
  enum class TwoStepCalibrationState {
    NONE,
    STEP1, // If reverse dot, it's right 3 fingers hold; otherwise, left 3 fingers hold.
    STEP2, // If reverse dot, it's left 3 fingers hold; otherwise, right 3 fingers hold.
  }

  /** Listens custom gestures events. */
  interface CustomOnGestureListener {

    /** Signals that a gesture is detected. */
    fun detect(resultOptional: Optional<BrailleInputPlaneResult>): Boolean

    /** Signals that two step calibration failed. */
    fun onTwoStepCalibrationFailed()

    /** Signals to retry the steps in two step calibration. */
    fun onTwoStepCalibrationRetry(isFirstStep: Boolean)
  }

  private enum class InputDotType {
    SCREEN_AWAY,
    SCREEN_AWAY_EIGHT_DOT,
    TABLE_TOP,
    TABLE_TOP_EIGHT_DOT,
  }

  init {
    this.multitouchHandler =
      MultitouchHandler(resources, holdRecognizer, multitouchResultListener)
    this.sizeInPixels = sizeInPixels
    this.options = options
    numberOfColumnsTabletop = options.brailleType().getDotCount()
    numberOfRowsScreenAway =
      options.brailleType().getDotCount() / NUMBER_OF_COLUMNS_SCREEN_AWAY
    dotRadius = resources.getDimensionPixelSize(R.dimen.input_plane_dot_radius)
    dotNumberPaint = Paint()
    dotNumberPaint.textAlign = Paint.Align.CENTER
    dotNumberPaint.typeface =
      Typeface.create(context.getString(R.string.accessibility_font), Typeface.NORMAL)
    val scaleFactor =
      Utils.getResourcesFloat(resources, R.dimen.input_plane_dot_number_size_multiplier)
    dotNumberPaint.textSize = scaleFactor * dotRadius
    val textStrokeWidth = resources.getDimension(R.dimen.input_plane_dot_number_stroke_width)
    dotNumberPaint.strokeWidth = textStrokeWidth
    dotNumberPaint.style = Paint.Style.FILL_AND_STROKE
    textBaseline = Utils.getPaintTextBaselineInPixels(dotNumberPaint)
    dotTargets = buildDotTargets(sizeInPixels)
    currentlyPressedDots = HashSet()

    dotBackgroundPaint = Paint()
    dotBackgroundPaint.style = Paint.Style.FILL_AND_STROKE
    val background = context.getColor(R.color.input_plane_background)
    dotBackgroundColorDefault = resources.getColor(R.color.input_plane_dot_background_default)
    dotBackgroundColorPressedDefault =
      resources.getColor(R.color.input_plane_dot_background_pressed)
    dotBackgroundColorDefaultCalibration =
      Utils.averageColors(dotBackgroundColorDefault, background)
    dotBackgroundColorPressedCalibration =
      Utils.averageColors(dotBackgroundColorPressedDefault, background)

    dotNumberColorDefault = resources.getColor(R.color.input_plane_dot_number_default)
    dotNumberColorPressedDefault = resources.getColor(R.color.input_plane_dot_number_pressed)
    dotNumberColorPressedCalibration =
      Utils.averageColors(dotNumberColorPressedDefault, background)
    dotNumberColorDefaultCalibration = Utils.averageColors(dotNumberColorDefault, background)

    touchCirclesPaint = Paint()
    touchCirclesPaint.color = resources.getColor(R.color.input_plane_touch_circle)
    touchCirclesPaint.style = Paint.Style.STROKE
    touchCirclesPaint.strokeWidth =
      resources.getDimension(R.dimen.input_plane_touch_circle_stroke_width)
  }

  /** Reads saved points from SharedPreference. */
  abstract fun readLayoutPoints(screenSize: Size): List<PointF>

  /** Writes points to SharedPreference. */
  abstract fun writeLayoutPoints(centerPoints: List<PointF>, size: Size)

  /** Creates default dot points. */
  abstract fun buildDotTargetCenters(screenSize: Size): List<PointF>

  /** Sorts dot points to divide them into two groups. */
  abstract fun sortDotCentersFirstTime(dotCenters: MutableList<PointF>)

  /** Sorts grouped dot points. */
  abstract fun sortDotCentersByGroup(group: MutableList<PointF>, isFirstGroup: Boolean)

  /** Recreates swipe result to match expectation */
  abstract fun createSwipe(swipe: Swipe): BrailleInputPlaneResult

  /** Recreates dot hold and swipe result to match expectation */
  abstract fun createDotHoldAndSwipe(
    swipe: Swipe,
    heldBrailleCharacter: BrailleCharacter,
  ): BrailleInputPlaneResult

  abstract fun getRotateDegree(): Int

  abstract fun getCaptionCenterPoint(screenSize: Size): PointF

  abstract fun getInputViewCaptionTranslate(screenSize: Size): IntArray

  abstract fun getInputViewCaptionScreenSize(screenSize: Size): Size

  fun setTableTopMode(isTableTopMode: Boolean) {
    // 诊断：桌面模式/屏幕朝外模式的切换会整体改变点阵布局，这里是"点位突然变了"的第一嫌疑。
    Log.d(
      "BBDots",
      "setTableTopMode " + this.isTableTopMode + " -> " + isTableTopMode
        + " orientation=" + orientation)
    this.isTableTopMode = isTableTopMode
    calibrationFailCount = 0
    refresh()
    // We allow user to change orientation in first step of two-step calibration because it takes
    // some time for user to change to the hold position or put it on the table after selects
    // calibration in the context menu,
    if (twoStepCalibrationState == TwoStepCalibrationState.STEP2) {
      // Cancel calibration.
      twoStepCalibrationState = TwoStepCalibrationState.NONE
      calibrationFailCount = 0
      customOnGestureListener.onTwoStepCalibrationFailed()
    }
  }

  fun setOptions(options: BrailleInputOptions) {
    this.options = options
    refresh()
  }

  /** Whether keyboard is in the process of two step calibration. */
  fun inTwoStepCalibration(): Boolean {
    return twoStepCalibrationState != TwoStepCalibrationState.NONE
  }

  fun getDotTargets(): List<DotTarget> {
    return ImmutableList.copyOf(dotTargets)
  }

  private fun refresh() {
    numberOfColumnsTabletop = options.brailleType().getDotCount()
    numberOfRowsScreenAway =
      options.brailleType().getDotCount() / NUMBER_OF_COLUMNS_SCREEN_AWAY
    dotTargets = buildDotTargets(sizeInPixels)
  }

  private fun buildCalibratedDotTargets(points: Collection<PointF>): MutableList<DotTarget> {
    val dotTargets: MutableList<DotTarget> = ArrayList()
    val pointList: MutableList<PointF> = ArrayList(points)
    sortDotCenters(pointList)
    val dotNumber = getDotNumberOrder()
    for (i in pointList.indices) {
      dotTargets.add(DotTarget(dotNumber[i], pointList[i].x, pointList[i].y))
    }
    return dotTargets
  }

  @VisibleForTesting
  fun buildDotTargets(screenSize: Size): MutableList<DotTarget> {
    var dotCenters: MutableList<PointF> = ArrayList()
    if (!options.tutorialMode()) {
      dotCenters = readLayoutPoints(screenSize).toMutableList()
    }
    if (dotCenters.isEmpty()) {
      dotCenters = buildDotTargetCenters(screenSize).toMutableList()
    }
    sortDotCenters(dotCenters)

    // Now we create the DotTarget.
    val dotTargets: MutableList<DotTarget> = ArrayList()
    val dotNumber = getDotNumberOrder()
    for (i in dotCenters.indices) {
      val center = dotCenters[i]
      dotTargets.add(DotTarget(dotNumber[i], center.x, center.y))
    }
    updateDotPosition(dotTargets)
    logDotTargets("build", screenSize, dotTargets)
    return dotTargets
  }

  /**
   * 诊断日志（tag=BBDots）：把算出来的点阵坐标整体打印出来，用于核对"点位映射位置是否正确"。
   * 判定方法：日志里的 1..6 坐标应当与用户手指实际落点一一对应。定位完成后应删除。
   */
  private fun logDotTargets(reason: String, screenSize: Size, targets: List<DotTarget>) {
    val sb = StringBuilder()
    sb.append(reason)
      .append(" screen=")
      .append(screenSize.width)
      .append("x")
      .append(screenSize.height)
      .append(" orientation=")
      .append(if (orientation == Configuration.ORIENTATION_PORTRAIT) "portrait" else "landscape")
      .append(" tabletop=")
      .append(isTableTopMode)
      .append(" reverse=")
      .append(options.reverseDots())
      .append(" dotCount=")
      .append(options.brailleType().getDotCount())
      .append(" radius=")
      .append(dotRadius)
      .append(" |")
    for (t in targets) {
      sb.append(" ")
        .append(t.dotNumber)
        .append("=(")
        .append(Math.round(t.center.x))
        .append(",")
        .append(Math.round(t.center.y))
        .append(")")
    }
    Log.d("BBDots", sb.toString())
  }

  private fun updateDotPosition(dotTargets: List<DotTarget>) {
    dotCenterPosition = arrayOfNulls(options.brailleType().getDotCount())
    textPosition = arrayOfNulls(options.brailleType().getDotCount())
    for (i in 0 until options.brailleType().getDotCount()) {
      dotCenterPosition[i] = PointF(dotTargets[i].center.x, dotTargets[i].center.y)
      textPosition[i] =
        PointF(dotTargets[i].center.x, dotTargets[i].center.y + textBaseline)
    }
  }

  private fun getDotNumberOrder(): IntArray {
    var dotNumberOrder = dotNumberOrderMap[getInputDotType(isTableTopMode)]!!
    if (options.reverseDots()) {
      dotNumberOrder = reverseDotNumberOrder(dotNumberOrder)
    }
    return dotNumberOrder
  }

  private fun getInputDotType(tableTopMode: Boolean): InputDotType {
    if (options.brailleType() == BrailleType.EIGHT_DOT) {
      return if (tableTopMode) InputDotType.TABLE_TOP_EIGHT_DOT
      else InputDotType.SCREEN_AWAY_EIGHT_DOT
    } else if (options.brailleType() == BrailleType.SIX_DOT) {
      return if (tableTopMode) InputDotType.TABLE_TOP else InputDotType.SCREEN_AWAY
    }
    throw IllegalArgumentException("dotCount should be either 6 or 8.")
  }

  private fun reverseDotNumberOrder(dotNumberOrder: IntArray): IntArray {
    val result = IntArray(dotNumberOrder.size)
    if (isTableTopMode) {
      for (i in 0 until dotNumberOrder.size / 2) {
        result[i] = dotNumberOrder[dotNumberOrder.size - 1 - i]
        result[dotNumberOrder.size - 1 - i] = dotNumberOrder[i]
      }
    } else {
      for (i in 0 until dotNumberOrder.size / 2) {
        result[i] = dotNumberOrder[i + dotNumberOrder.size / 2]
        result[i + dotNumberOrder.size / 2] = dotNumberOrder[i]
      }
    }
    return result
  }

  @VisibleForTesting
  fun sortDotCenters(dotCenters: MutableList<PointF>) {
    // Sort first time to decide rough position.
    sortDotCentersFirstTime(dotCenters)
    // Sort second time to deal with same vertical/horizontal position dots.
    val copy: MutableList<PointF> = ArrayList(dotCenters)
    dotCenters.clear()
    for (i in 0 until GROUP_COUNT) {
      val group: MutableList<PointF> =
        ArrayList(
          copy.subList(
            i * options.brailleType().getDotCount() / GROUP_COUNT,
            options.brailleType().getDotCount() / GROUP_COUNT * (i + 1)))
      sortDotCentersByGroup(group, i == 0)
      dotCenters.addAll(group)
    }
  }

  fun buildDotTargetCentersLandscape(screenSize: Size): List<PointF> {
    val dotCenters: MutableList<PointF> = ArrayList()
    val width = screenSize.width
    val height = screenSize.height
    val dotDiameter = 2 * dotRadius
    val columnCount =
      if (isTableTopMode) numberOfColumnsTabletop else NUMBER_OF_COLUMNS_SCREEN_AWAY
    val rowCount =
      if (isTableTopMode) NUMBER_OF_ROWS_TABLETOP else numberOfRowsScreenAway

    // Variables rS and cS are rowSpacer and columnSpacer, respectively.
    val rS = (height.toFloat() - rowCount * dotDiameter) / (rowCount + 1)
    val cS = (width.toFloat() - columnCount * dotDiameter) / (columnCount + 1)

    // Build the center point of each dot as if we are in landscape mode.
    for (column in 0 until columnCount) {
      val centerX = width - (cS + dotRadius + column * (dotDiameter + cS))
      for (row in 0 until rowCount) {
        val centerY = rS + dotRadius + row * (dotDiameter + rS)
        dotCenters.add(PointF(centerX, centerY))
      }
    }
    return dotCenters
  }

  /**
   * Sets orientation of BrailleInputPlane
   *
   * @param orientation the orientation of the view
   * @param screenSize used as the height and width of the canvas
   */
  fun setOrientation(orientation: Int, screenSize: Size) {
    this.orientation = orientation
    this.sizeInPixels = screenSize
    dotTargets = buildDotTargets(screenSize)
  }

  /** Set the accumulation mode. See {@link MultitouchHandler#setAccumulationMode(boolean)}. */
  fun setAccumulationMode(accumulationMode: Boolean) {
    multitouchHandler.setAccumulationMode(accumulationMode)
  }

  /** Get the accumulation mode. See {@link MultitouchHandler#isAccumulationMode()}. */
  fun isAccumulationMode(): Boolean {
    return multitouchHandler.isAccumulationMode()
  }

  /**
   * Process touch input and return a {@link BrailleInputPlaneResult} if commission occurred.
   *
   * @param event the MotionEvent as received by the owning View's onTouchEvent() method.
   */
  fun onTouchEvent(event: MotionEvent): Boolean {
    val result = multitouchHandler.onTouchEvent(event)
    currentlyPressedDots = matchTouchToTargets(multitouchHandler.getActivePoints())
    return result
  }

  private fun processMultitouchResult(touchResult: MultitouchResult): Boolean {
    if (twoStepCalibrationState != TwoStepCalibrationState.NONE) {
      if (touchResult.type != MultitouchResult.TYPE_CALIBRATION_HOLD
        || touchResult.heldPoints!!.size != options.brailleType().getDotCount() / 2
      ) {
        if (calibrationFailCount < CALIBRATION_MAXIMUM_FAIL_COUNT) {
          calibrationFailCount++
          customOnGestureListener.onTwoStepCalibrationRetry(
            twoStepCalibrationState == TwoStepCalibrationState.STEP1)
        } else {
          dotTargets = buildDotTargets(sizeInPixels)
          calibrationFailCount = 0
          twoStepCalibrationState = TwoStepCalibrationState.NONE
          customOnGestureListener.onTwoStepCalibrationFailed()
        }
        // Return true otherwise unexpected dots will be produced after fingers are released.
        return true
      }
    }
    when (touchResult.type) {
      MultitouchResult.TYPE_TAP -> {
        // 临时诊断：记录本次点击的落点坐标与识别出的点号，用于核对点位映射。
        val tapLog = StringBuilder()
        tapLog.append("TAP releasedPoints=").append(touchResult.releasedPoints!!.size)
        for (p in touchResult.releasedPoints!!) {
          tapLog
            .append(" (")
            .append(Math.round(p.x))
            .append(",")
            .append(Math.round(p.y))
            .append(")")
        }
        tapLog.append(" dots=").append(matchTouchToTargetNumbers(touchResult.releasedPoints!!))
        Log.d("BBTouch", tapLog.toString())
        return customOnGestureListener.detect(
          Optional.of(
            BrailleInputPlaneResult.createTapAndRelease(
              BrailleCharacter(matchTouchToTargetNumbers(touchResult.releasedPoints!!)))))
      }
      MultitouchResult.TYPE_SWIPE -> {
        return customOnGestureListener.detect(Optional.of(createSwipe(touchResult.swipe!!)))
      }
      MultitouchResult.TYPE_HOLD -> {
        // 临时诊断：1~3 指按住超过长按时会走到这里，此时松手不再产生字符（原版行为）。
        Log.d("BBTouch", "HOLD heldPoints=" + touchResult.heldPoints!!.size)
        return customOnGestureListener.detect(
          Optional.of(BrailleInputPlaneResult.createHold(touchResult.heldPoints!!.size)))
      }
      MultitouchResult.TYPE_CALIBRATION_HOLD -> {
        if (twoStepCalibrationState != TwoStepCalibrationState.NONE) {
          val result: Optional<BrailleInputPlaneResult> =
            Optional.of(
              BrailleInputPlaneResult.createCalibration(
                twoStepCalibrationState == TwoStepCalibrationState.STEP1,
                touchResult.heldPoints!!.size))
          doTwoStepCalibration(touchResult.heldPoints!!)
          calibrationFailCount = 0
          return customOnGestureListener.detect(result)
        } else if (5 <= touchResult.heldPoints!!.size && touchResult.heldPoints!!.size <= 8) {
          val result: Optional<BrailleInputPlaneResult> =
            Optional.of(
              BrailleInputPlaneResult.createCalibration(
                /* isLeft= */ false, touchResult.heldPoints!!.size))
          doCalibration(touchResult.heldPoints!!)
          calibrationFailCount = 0
          return customOnGestureListener.detect(result)
        }
      }
      MultitouchResult.TYPE_HOLD_AND_SWIPE -> {
        return customOnGestureListener.detect(
          Optional.of(
            createDotHoldAndSwipe(
              touchResult.swipe!!,
              BrailleCharacter(matchTouchToTargetNumbers(touchResult.heldPoints!!)))))
      }
      MultitouchResult.TYPE_INVALID -> {
        // 临时诊断：落点位移过大被判为非法手势时也会吞掉字符。
        Log.d("BBTouch", "INVALID gesture")
        return customOnGestureListener.detect(
          Optional.of(BrailleInputPlaneResult.createInvalidGesture()))
      }
      else -> {}
    }
    return false
  }

  /**
   * Draw on to the given {@link Canvas}
   *
   * @param canvas the Canvas as received by the owning View.
   */
  fun onDraw(canvas: Canvas) {
    drawDots(canvas)
    drawTouchCircles(canvas, multitouchHandler.getActivePoints())
  }

  /** Calibrates dots positions by two steps. */
  fun calibrateByTwoSteps() {
    twoStepCalibrationState = TwoStepCalibrationState.STEP1
    calibrationFailCount = 0
  }

  /**
   * Figure which dots are pressed by the given points.
   *
   * <p>We iterate through the touch points and at each iteration identify, and remove the point
   * after saving an association link between that point at the dot closest to it.
   */
  private fun matchTouchToTargets(points: Collection<PointF>): MutableSet<DotTarget> {
    val result: MutableSet<DotTarget> = HashSet()
    val pointsYetToBeProcessed: MutableSet<PointF> = HashSet(points)
    val dotsYetToBeProcessed: MutableSet<DotTarget> = HashSet(dotTargets)
    while (pointsYetToBeProcessed.isNotEmpty() && dotsYetToBeProcessed.isNotEmpty()) {
      var bestDistance = Double.MAX_VALUE
      var bestDot: DotTarget? = null
      var bestPoint: PointF? = null
      for (dot in dotsYetToBeProcessed) {
        for (point in pointsYetToBeProcessed) {
          val thisDistance = Utils.distance(dot.center, point)
          if (thisDistance < bestDistance) {
            bestDistance = thisDistance
            bestDot = dot
            bestPoint = point
          }
        }
      }
      result.add(DotTarget(bestDot!!.dotNumber, bestPoint!!.x, bestPoint!!.y))
      pointsYetToBeProcessed.remove(bestPoint)
      dotsYetToBeProcessed.remove(bestDot)
    }
    return result
  }

  /** Figure which dot numbers are pressed by the given points. */
  private fun matchTouchToTargetNumbers(points: Collection<PointF>): Set<Int> {
    return matchTouchToTargets(points).map { dotTarget -> dotTarget.dotNumber }.toSet()
  }

  private fun drawDots(canvas: Canvas) {
    for (i in dotTargets.indices) {
      var pressed = false
      for (dotTarget in currentlyPressedDots) {
        pressed = dotTarget.dotNumber == dotTargets[i].dotNumber
        if (pressed) {
          break
        }
      }
      val useDefaultColor =
        twoStepCalibrationState == TwoStepCalibrationState.NONE
          || (twoStepCalibrationState == TwoStepCalibrationState.STEP2
            && (options.reverseDots()
              == (i >= options.brailleType().getDotCount() / GROUP_COUNT)))
      // Draw dot background.
      val dotBackgroundColor =
        if (useDefaultColor) dotBackgroundColorDefault else dotBackgroundColorDefaultCalibration
      val dotBackgroundColorPressed =
        if (useDefaultColor) dotBackgroundColorPressedDefault
        else dotBackgroundColorPressedCalibration
      dotBackgroundPaint.color = if (pressed) dotBackgroundColorPressed else dotBackgroundColor
      canvas.drawCircle(
        dotCenterPosition[i]!!.x, dotCenterPosition[i]!!.y, dotRadius.toFloat(), dotBackgroundPaint)
      val dotNumberColor =
        if (useDefaultColor) dotNumberColorDefault else dotNumberColorDefaultCalibration
      val dotNumberColorPressed =
        if (useDefaultColor) dotNumberColorPressedDefault else dotNumberColorPressedCalibration
      // Draw dot number (text).
      dotNumberPaint.color = if (pressed) dotNumberColorPressed else dotNumberColor
      val text = Integer.toString(dotTargets[i].dotNumber)
      canvas.save()
      canvas.rotate(getRotateDegree().toFloat(), dotCenterPosition[i]!!.x, dotCenterPosition[i]!!.y)
      canvas.drawText(text, textPosition[i]!!.x, textPosition[i]!!.y, dotNumberPaint)
      canvas.restore()
    }
  }

  fun createAnimator(view: View) {
    if (options.tutorialMode()) {
      return
    }
    dotCenterPosition = arrayOfNulls(options.brailleType().getDotCount())
    textPosition = arrayOfNulls(options.brailleType().getDotCount())
    for (i in 0 until options.brailleType().getDotCount()) {
      dotCenterPosition[i] = PointF()
      textPosition[i] = PointF()
    }
    val animatorSet = AnimatorSet()
    val animatorList: MutableList<Animator> = ArrayList()
    for (i in dotTargets.indices) {
      val propertyX =
        PropertyValuesHolder.ofFloat(
          "ScaleX", oldDotTargets!![i].center.x, dotTargets[i].center.x)
      val propertyY =
        PropertyValuesHolder.ofFloat(
          "ScaleY", oldDotTargets!![i].center.y, dotTargets[i].center.y)
      val propertyAlpha = PropertyValuesHolder.ofInt("alpha", 0, 255)
      val animator = ValueAnimator()
      animator.setValues(propertyX, propertyY, propertyAlpha)
      animator.duration = ANIMATION_DURATION_MS.toLong()
      animator.interpolator = LinearInterpolator()
      val finalI = i
      animator.addUpdateListener { valueAnimator ->
        val xPosition = valueAnimator.getAnimatedValue("ScaleX") as Float
        val yPosition = valueAnimator.getAnimatedValue("ScaleY") as Float
        textPosition[finalI]!!.x = xPosition
        textPosition[finalI]!!.y = yPosition + textBaseline
        dotCenterPosition[finalI]!!.x = xPosition
        dotCenterPosition[finalI]!!.y = yPosition
        view.invalidate()
      }
      animatorList.add(animator)
    }
    animatorSet.playTogether(animatorList)
    animatorSet.start()
  }

  private fun drawTouchCircles(canvas: Canvas, points: Collection<PointF>) {
    val scaleFactor =
      Utils.getResourcesFloat(resources, R.dimen.input_plane_touch_circle_size_multiplier)
    val touchCircleRadius = (scaleFactor * dotRadius).toInt()
    for (point in points) {
      canvas.drawCircle(point.x, point.y, touchCircleRadius.toFloat(), touchCirclesPaint)
    }
  }

  /** Stores points positions in the SharedPreference. */
  fun storeLayoutPoints() {
    val centerPoints: MutableList<PointF> = ArrayList()
    for (dotTarget in dotTargets) {
      centerPoints.add(dotTarget.center)
    }
    writeLayoutPoints(centerPoints, sizeInPixels)
  }

  private fun doTwoStepCalibration(pointList: MutableList<PointF>) {
    if (!options.tutorialMode()) {
      sortDotCentersByGroup(pointList, twoStepCalibrationState == TwoStepCalibrationState.STEP1)
      oldDotTargets = ArrayList(dotTargets)
      val dotNumber = getDotNumberOrder()
      for (i in pointList.indices) {
        val index =
          if (options.reverseDots()) {
            if (twoStepCalibrationState == TwoStepCalibrationState.STEP1) {
              options.brailleType().getDotCount() / GROUP_COUNT + i
            } else {
              i
            }
          } else {
            if (twoStepCalibrationState == TwoStepCalibrationState.STEP1) {
              i
            } else {
              options.brailleType().getDotCount() / GROUP_COUNT + i
            }
          }
        dotTargets.set(
          index, DotTarget(dotNumber[index], pointList[i].x, pointList[i].y))
      }
    }
    if (twoStepCalibrationState == TwoStepCalibrationState.STEP1) {
      twoStepCalibrationState = TwoStepCalibrationState.STEP2
    } else if (twoStepCalibrationState == TwoStepCalibrationState.STEP2) {
      twoStepCalibrationState = TwoStepCalibrationState.NONE
    }
  }

  private fun doCalibration(holdPoints: List<PointF>) {
    // Do 2-step calibration for 5/6/7 dots.
    if (holdPoints.size == 5
      || ((holdPoints.size == BrailleType.SIX_DOT.getDotCount()
        && options.brailleType() == BrailleType.EIGHT_DOT)
        || (holdPoints.size == 7 && options.brailleType() == BrailleType.EIGHT_DOT))
    ) {
      twoStepCalibrationState = TwoStepCalibrationState.STEP1
    } else if (!options.tutorialMode()
      && (holdPoints.size == BrailleType.SIX_DOT.getDotCount()
        || holdPoints.size == BrailleType.EIGHT_DOT.getDotCount())
    ) {
      oldDotTargets = ArrayList(dotTargets)
      dotTargets = buildCalibratedDotTargets(holdPoints)
    }
  }

  /** Contains dot coordinate and its dot number. */
  class DotTarget internal constructor(
    internal val center: PointF,
    internal val dotNumber: Int,
  ) {
    internal constructor(dotNumber: Int, centerX: Float, centerY: Float) :
      this(PointF(centerX, centerY), dotNumber)

    fun getCenter(): PointF {
      return PointF(center.x, center.y)
    }

    fun getDotNumber(): Int {
      return dotNumber
    }
  }

  companion object {
    private const val TAG = "BrailleInputPlane"
    const val NUMBER_OF_COLUMNS_SCREEN_AWAY: Int = 2
    const val NUMBER_OF_ROWS_TABLETOP: Int = 1
    private const val ANIMATION_DURATION_MS = 100
    private const val GROUP_COUNT = 2
    private const val CALIBRATION_MAXIMUM_FAIL_COUNT = 3

    private val dotNumberOrderMap: ImmutableMap<InputDotType, IntArray> =
      ImmutableMap.builder<InputDotType, IntArray>()
        .put(InputDotType.SCREEN_AWAY, intArrayOf(1, 2, 3, 4, 5, 6))
        .put(InputDotType.SCREEN_AWAY_EIGHT_DOT, intArrayOf(1, 2, 3, 7, 4, 5, 6, 8))
        .put(InputDotType.TABLE_TOP, intArrayOf(3, 2, 1, 4, 5, 6))
        .put(InputDotType.TABLE_TOP_EIGHT_DOT, intArrayOf(7, 3, 2, 1, 4, 5, 6, 8))
        .buildOrThrow()
  }
}
