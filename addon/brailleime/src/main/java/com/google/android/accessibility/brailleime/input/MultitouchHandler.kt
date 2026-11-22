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

import android.annotation.SuppressLint
import android.content.res.Resources
import android.graphics.PointF
import android.os.Handler
import android.os.SystemClock
import android.util.Range
import android.view.MotionEvent
import android.view.ViewConfiguration
import androidx.annotation.Nullable
import androidx.annotation.VisibleForTesting
import com.google.android.accessibility.brailleime.BrailleImeLog
import com.google.android.accessibility.brailleime.Utils
import com.google.android.accessibility.brailleime.input.Swipe.Direction
import com.google.common.collect.ImmutableList
import com.google.common.collect.ImmutableList.toImmutableList
import com.google.errorprone.annotations.CanIgnoreReturnValue
import java.util.Optional
import java.util.function.Function

/**
 * Provides custom multi-pointer touch input support by processing MotionEvents and returning {@link
 * MultitouchResult} from {@link MultitouchHandler#onTouchEvent} if commission occurred.
 *
 * <p>A client uses this class by continuously forwarding MotionEvents to it via {@link
 * MultitouchHandler#onTouchEvent}. If that method decides that a commission has occurred, then it
 * will return a present {@link MultitouchResult}. There are 3 types of commission that can occur:
 * {@link MultitouchResult#TYPE_TAP}, {@link MultitouchResult#TYPE_SWIPE}, {@link
 * MultitouchResult#TYPE_HOLD}.
 *
 * <p>A {@link MultitouchResult#TYPE_TAP} occurs if the finger(s) press down and eventually all lift
 * up with the final pointer having not travelled too long a distance.
 *
 * <p>A {@link MultitouchResult#TYPE_SWIPE} occurs if the finger(s) press down and eventually all
 * lift up with the final pointer having travelled a sufficient distance, with sufficient speed.
 * Motions that would otherwise produce a Swipe but are not cardinal enough, or not fast enough, do
 * not produce a Swipe, nor do they produce anything else.
 *
 * <p>A {@link MultitouchResult#TYPE_HOLD} occurs if the finger(s) press down and no new pointers
 * are added or removed for a fixed duration, provided that a {@link HoldRecognizer} has been
 * provided to decide how many pointers are needed in order for a Hold to occur.
 */
class MultitouchHandler(
  resources: Resources,
  @Nullable holdRecognizer: HoldRecognizer?,
  multitouchResultListener: MultitouchResultListener,
) {

  /** Maps pointerId's to active PointerWithHistory objects. */
  @SuppressLint("UseSparseArrays")
  private val activePointers: HashMap<Int, PointerWithHistory> = HashMap()

  /** Maps pointerId's to inactive PointerWithHistory objects. */
  @SuppressLint("UseSparseArrays")
  private val inactivePointers: HashMap<Int, PointerWithHistory> = HashMap()

  private val handler: Handler = Handler()
  private val holdRecognizer: HoldRecognizer?
  private val multitouchResultListener: MultitouchResultListener

  private var isAccumulationMode: Boolean
  private var isProcessed: Boolean = false

  private var tapMaxDistancePixels: Float
  private var swipeMinSpeedPixelsPerSecond: Float
  private var swipeMinDistancePixels: Float

  private var longHoldDurationMinMillis: Long

  /** Interface for recognizing a Hold. */
  interface HoldRecognizer {
    /**
     * Returns true if, in the current context, a Hold for calibration should occur.
     *
     * @param pointersHeldCount how many pointers are currently being held
     */
    fun isCalibrationHoldRecognized(pointersHeldCount: Int): Boolean

    /**
     * Return true if, in the current context, a Hold should occur.
     *
     * @param pointersHeldCount how many pointers are currently being held
     */
    fun isHoldRecognized(pointersHeldCount: Int): Boolean
  }

  /** Listens multi-touch events. */
  interface MultitouchResultListener {
    @CanIgnoreReturnValue
    fun detect(resultOptional: Optional<MultitouchResult>): Boolean
  }

  init {
    tapMaxDistancePixels = Utils.mmToPixels(resources, TAP_MAX_DISTANCE_MM)
    swipeMinSpeedPixelsPerSecond = Utils.mmToPixels(resources, SWIPE_MIN_SPEED_MM_PER_SECOND)
    swipeMinDistancePixels = Utils.mmToPixels(resources, SWIPE_MIN_DISTANCE_MM)
    longHoldDurationMinMillis = HOLD_MIN_DURATION_MS
    this.multitouchResultListener = multitouchResultListener
    isAccumulationMode = false
    this.holdRecognizer = holdRecognizer
  }

  /**
   * Sets the accumulation mode on or off.
   *
   * <p>In accumulation mode, all pointers pressed after the initial pointer will contribute to a
   * potential final commission regardless of when they are released. With accumulation mode off,
   * only those pointers released just prior to the release of the initial pointer will contribute
   * to a potential final commission.
   */
  fun setAccumulationMode(accumulationMode: Boolean) {
    isAccumulationMode = accumulationMode
  }

  /** Gets the accumulation mode. */
  fun isAccumulationMode(): Boolean {
    return isAccumulationMode
  }

  /** Gets a copy of the currently active touch points. */
  fun getActivePoints(): ImmutableList<PointF> {
    return activePointers.values.stream()
      .map { pointerWithHistory -> pointerWithHistory.pointCurrent }
      .collect(toImmutableList())
  }

  private val tapOrSwipeRunnable: Runnable =
    object : Runnable {
      override fun run() {
        BrailleImeLog.d(TAG, "tap or swipe task is running.")
        val swipe: Optional<Swipe> =
          createSwipe(
            getLastRecentlyInactivatedPointsHistory(SystemClock.uptimeMillis()),
            getRecentlyInactivatedPointsHistory(SystemClock.uptimeMillis()))
        BrailleImeLog.d(TAG, "swipe is present: " + swipe.isPresent())
        if (swipe.isPresent()) {
          isProcessed =
            multitouchResultListener.detect(
              Optional.of(
                MultitouchResult.createSwipe(
                  swipe.get(),
                  getRecentlyInactivatedInitialPoints(SystemClock.uptimeMillis()))))
          BrailleImeLog.d(TAG, "swipe result: " + isProcessed)
        } else if (getMaximumDistanceMovedAmongInactivePointers() <= tapMaxDistancePixels.toDouble()) {
          // Check if the motion is a legitimate tap.
          isProcessed =
            multitouchResultListener.detect(
              Optional.of(
                MultitouchResult.createTap(
                  getRecentlyInactivatedCurrentPoints(SystemClock.uptimeMillis()))))
          BrailleImeLog.d(TAG, "tap result: " + isProcessed)
        } else {
          isProcessed =
            multitouchResultListener.detect(
              Optional.of(MultitouchResult.createInvalidGesture()))
          BrailleImeLog.d(TAG, "invalid gesture result: " + isProcessed)
        }
        clearPointerCollections()
        handler.removeCallbacksAndMessages(null)
      }
    }

  private val holdRunnable: Runnable =
    object : Runnable {
      override fun run() {
        BrailleImeLog.d(TAG, "hold task is running.")
        if (holdRecognizer != null && holdRecognizer!!.isHoldRecognized(getActivePoints().size)) {
          for (value in activePointers.values) {
            value.isHoldInProgress = true
          }
          val inactivePointHistory: Optional<PointerWithHistory> =
            getLastRecentlyInactivatedPointsHistory(
              SystemClock.uptimeMillis() - ViewConfiguration.getLongPressTimeout())
          if (inactivePointHistory.isPresent &&
            !inactivePointHistory.get().isHoldInProgress &&
            !holdRecognizer!!.isHoldRecognized(getActivePoints().size + 1)
          ) {
            return
          }
          isProcessed =
            multitouchResultListener.detect(Optional.of(MultitouchResult.createHold(getHeldPoints())))
          BrailleImeLog.d(TAG, "hold result: " + isProcessed)
        }
      }
    }

  private val longHoldRunnable: Runnable =
    object : Runnable {
      override fun run() {
        BrailleImeLog.d(TAG, "long hold task is running.")
        if (holdRecognizer != null &&
          holdRecognizer!!.isCalibrationHoldRecognized(getActivePoints().size)
        ) {
          val result = MultitouchResult.createCalibrationHold(getActivePoints())
          isProcessed = multitouchResultListener.detect(Optional.of(result))
          BrailleImeLog.d(TAG, "long hold result: " + isProcessed)
        }
      }
    }

  private val holdAndSwipeRunnable: Runnable =
    object : Runnable {
      override fun run() {
        BrailleImeLog.d(TAG, "hold and swipe is running.")
        val heldPoints: ImmutableList<PointF> = getHeldPoints()
        if (!heldPoints.isEmpty()) {
          if (heldPoints.size == getActivePoints().size) {
            val swipe: Optional<Swipe> =
              createSwipe(
                getLastRecentlyInactivatedPointsHistory(SystemClock.uptimeMillis()),
                getRecentlyInactivatedPointsHistory(SystemClock.uptimeMillis()))
            BrailleImeLog.d(
              TAG, "swipe is present: " + (if (swipe.isPresent) swipe.get() else false))
            if (swipe.isPresent()) {
              val result =
                MultitouchResult.createHoldAndDotSwipe(
                  getActivePoints(),
                  swipe.get(),
                  getRecentlyInactivatedInitialPoints(SystemClock.uptimeMillis()))
              isProcessed = multitouchResultListener.detect(Optional.of(result))
              BrailleImeLog.d(TAG, "hold and swipe result: " + isProcessed)
              handler.removeCallbacksAndMessages(null)
            }
          }
        }
      }
    }

  /**
   * Processes touch input and returns a {@link MultitouchResult} if commission occurred.
   *
   * <p>The types of commission are discussed atop this file. If no commission has occurred, then an
   * empty {@link Optional} is returned.
   *
   * <p>If a {@code TYPE_HOLD} has occurred, then all subsequent calls to this method will return
   * null until the motion is resolved by either being cancelled or the final pointer has come up.
   *
   * @param event the {@link MotionEvent} as received by the owning view.
   */
  fun onTouchEvent(event: MotionEvent): Boolean {
    val action = event.actionMasked
    val actionPointerIndex = event.actionIndex
    val actionPointerId = event.getPointerId(actionPointerIndex)
    val eventTime = event.eventTime

    if (activePointers.isNotEmpty()) {
      // Update the active pointers
      // All of the PointerWithHistory objects get updated because ACTION_MOVE events are not sent
      // on a per pointer basis (instead they ride along the initial 'action' pointer).
      for (pointerIndex in 0 until event.pointerCount) {
        val pointerId = event.getPointerId(pointerIndex)
        if (activePointers.containsKey(pointerId)) {
          val pointerWithHistory = activePointers[pointerId]
          pointerWithHistory!!.updateCurrentPoint(
            event.getX(pointerIndex).toInt(), event.getY(pointerIndex).toInt())
          activePointers.replace(pointerId, pointerWithHistory)
        }
      }
    }

    if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
      isProcessed = false
      handler.removeCallbacksAndMessages(null)
      val point = PointF(event.getX(actionPointerIndex), event.getY(actionPointerIndex))
      val pointerWithHistory = PointerWithHistory(actionPointerId, point, eventTime)
      activePointers.put(actionPointerId, pointerWithHistory)
      handler.postDelayed(holdRunnable, ViewConfiguration.getLongPressTimeout().toLong())
      handler.postDelayed(longHoldRunnable, longHoldDurationMinMillis)
    } else if (action == MotionEvent.ACTION_CANCEL) {
      clearPointerCollections()
      handler.removeCallbacksAndMessages(null)
    } else if (action == MotionEvent.ACTION_POINTER_UP) {
      // ACTION_POINTER_UP: non-final pointer was released.
      transferPointerToInactive(actionPointerId, eventTime)
      handler.removeCallbacksAndMessages(null)
      handler.post(holdAndSwipeRunnable)
      handler.postDelayed(holdRunnable, ViewConfiguration.getLongPressTimeout().toLong())
      handler.postDelayed(longHoldRunnable, longHoldDurationMinMillis)
    } else if (action == MotionEvent.ACTION_UP) {
      // ACTION_UP: final pointer was released.
      if (isProcessed) {
        clearPointerCollections()
      } else {
        // If no previous callback is processed.
        transferPointerToInactive(actionPointerId, eventTime)
        handler.post(tapOrSwipeRunnable)
      }
    }
    // Must be true so that next touch event would come.
    return true
  }

  private fun createSwipe(
    finalPointer: Optional<PointerWithHistory>,
    recentlyInactivatedPoints: List<PointerWithHistory>,
  ): Optional<Swipe> {
    if (finalPointer.isEmpty) {
      return Optional.empty()
    }
    val xDiff = finalPointer.get().pointCurrent.x - finalPointer.get().pointInitial.x
    val yDiff = finalPointer.get().pointCurrent.y - finalPointer.get().pointInitial.y
    val xExcess = Math.abs(xDiff) - swipeMinDistancePixels
    val yExcess = Math.abs(yDiff) - swipeMinDistancePixels
    // Check if the motion is a legitimate swipe.
    if (xExcess > 0 &&
      yExcess > 0 &&
      !Utils.isVectorNearlyCardinal(PointF(xDiff, yDiff), SWIPE_MIN_RATIO)
    ) {
      // Both x and y displacement thresholds were met, but the vector is too diagonal.
      return Optional.empty()
    }
    val optionalDirection: Optional<Direction> = getDirection(xExcess, yExcess, xDiff, yDiff)
    if (optionalDirection.isEmpty) {
      return Optional.empty()
    }
    val direction = optionalDirection.get()
    val speed = inactivePointers[finalPointer.get().pointerId]!!.computeSpeed()
    if (direction == Direction.LEFT || direction == Direction.RIGHT) {
      // X displacement threshold was met, and exceeds y displacement.
      if (speed.x < swipeMinSpeedPixelsPerSecond ||
        !fingersTravelSameDirection(
          recentlyInactivatedPoints,
          Function { pointer -> pointer.pointCurrent.x - pointer.pointInitial.x })
      ) {
        // Not quick enough or fingers moving different directions.
        return Optional.empty()
      }
    } else if (direction == Direction.UP || direction == Direction.DOWN) {
      // Y displacement threshold was met, and exceeds x displacement.
      if (speed.y < swipeMinSpeedPixelsPerSecond ||
        !fingersTravelSameDirection(
          recentlyInactivatedPoints,
          Function { pointer -> pointer.pointCurrent.y - pointer.pointInitial.y })
      ) {
        // Not quick enough or fingers moving different directions.
        return Optional.empty()
      }
    }
    return Optional.of(Swipe(direction, recentlyInactivatedPoints.size))
  }

  private fun getDirection(
    xExcess: Float,
    yExcess: Float,
    xDiff: Float,
    yDiff: Float,
  ): Optional<Direction> {
    if (xExcess > 0 && xExcess > yExcess) {
      return Optional.of(if (xDiff < 0) Direction.LEFT else Direction.RIGHT)
    }
    if (yExcess > 0 && yExcess > xExcess) {
      return Optional.of(if (yDiff < 0) Direction.UP else Direction.DOWN)
    }
    return Optional.empty()
  }

  private fun fingersTravelSameDirection(
    recentlyInactivatedPoints: List<PointerWithHistory>,
    directionProvider: Function<PointerWithHistory, Float>,
  ): Boolean {
    val signedAccumulation =
      recentlyInactivatedPoints.stream()
        .mapToInt { point -> Integer.signum(directionProvider.apply(point).toInt()) }
        .sum()
    return Math.abs(signedAccumulation) == recentlyInactivatedPoints.size
  }

  private fun getMaximumDistanceMovedAmongInactivePointers(): Double {
    return inactivePointers.values.stream()
      .mapToDouble(PointerWithHistory::distanceMoved)
      .max()
      .orElse(Double.MAX_VALUE)
  }

  private fun clearPointerCollections() {
    activePointers.clear()
    inactivePointers.clear()
    isProcessed = false
  }

  private fun getRecentlyInactivatedCurrentPoints(eventTime: Long): ImmutableList<PointF> {
    val recentRange = Range<Long>(eventTime - RECENCY_MAX_MS.toLong(), eventTime)
    return inactivePointers.values.stream()
      .filter { pointerWithHistory ->
        isAccumulationMode || recentRange.contains(pointerWithHistory.momentMadeInactive)
      }
      .map { pointerWithHistory -> pointerWithHistory.pointCurrent }
      .collect(toImmutableList())
  }

  private fun getRecentlyInactivatedInitialPoints(eventTime: Long): ImmutableList<PointF> {
    val recentRange = Range<Long>(eventTime - RECENCY_MAX_MS.toLong(), eventTime)
    return inactivePointers.values.stream()
      .filter { pointerWithHistory -> recentRange.contains(pointerWithHistory.momentMadeInactive) }
      .map { pointerWithHistory -> pointerWithHistory.pointInitial }
      .collect(toImmutableList())
  }

  private fun getRecentlyInactivatedPointsHistory(
    eventTime: Long,
  ): ImmutableList<PointerWithHistory> {
    val now = eventTime
    val recentRange = Range<Long>(now - RECENCY_MAX_MS.toLong(), now)
    return inactivePointers.values.stream()
      .filter { pointerWithHistory -> recentRange.contains(pointerWithHistory.momentMadeInactive) }
      .collect(toImmutableList())
  }

  private fun getLastRecentlyInactivatedPointsHistory(
    eventTime: Long,
  ): Optional<PointerWithHistory> {
    val recentRange = Range<Long>(eventTime - RECENCY_MAX_MS.toLong(), eventTime)
    return inactivePointers.values.stream()
      .filter { pointerWithHistory -> recentRange.contains(pointerWithHistory.momentMadeInactive) }
      .findFirst()
  }

  private fun getHeldPoints(): ImmutableList<PointF> {
    return activePointers.values.stream()
      .filter { pointerWithHistory -> pointerWithHistory.isHoldInProgress }
      .map { pointerWithHistory -> pointerWithHistory.pointCurrent }
      .collect(toImmutableList())
  }

  private fun transferPointerToInactive(pointerId: Int, eventTime: Long) {
    val pointerWithHistory = activePointers[pointerId]
    if (pointerWithHistory != null) {
      pointerWithHistory.markReleased(eventTime)
      inactivePointers.put(pointerId, pointerWithHistory)
      activePointers.remove(pointerId)
    }
  }

  private class Speed(val x: Float, val y: Float)

  private class PointerWithHistory(
    pointerId: Int,
    pointInitial: PointF,
    initialEventTime: Long,
  ) {
    val pointerId: Int
    val pointInitial: PointF
    val pointCurrent: PointF
    var momentMadeInactive: Long = 0
    val momentMadeInitial: Long
    var isHoldInProgress: Boolean = false

    init {
      this.pointerId = pointerId
      this.pointInitial = PointF(pointInitial.x, pointInitial.y)
      this.pointCurrent = PointF(pointInitial.x, pointInitial.y)
      momentMadeInitial = initialEventTime
    }

    fun markReleased(eventTime: Long) {
      momentMadeInactive = eventTime
    }

    fun updateCurrentPoint(x: Int, y: Int) {
      pointCurrent.x = x.toFloat()
      pointCurrent.y = y.toFloat()
    }

    fun distanceMoved(): Double {
      return Utils.distance(pointCurrent, pointInitial)
    }

    fun computeSpeed(): Speed {
      val pointerDurationInSeconds = pointerDurationInMillis() / 1000.0
      return try {
        Speed(
          Math.abs(displacementX() / pointerDurationInSeconds).toFloat(),
          Math.abs(displacementY() / pointerDurationInSeconds).toFloat())
      } catch (exception: ArithmeticException) {
        BrailleImeLog.e(
          TAG, "Divided by zero: pointerDurationInSeconds = " + pointerDurationInSeconds)
        Speed(0f, 0f)
      }
    }

    fun displacementX(): Float {
      return pointCurrent.x - pointInitial.x
    }

    fun displacementY(): Float {
      return pointCurrent.y - pointInitial.y
    }

    fun pointerDurationInMillis(): Long {
      return momentMadeInactive - momentMadeInitial
    }
  }

  @VisibleForTesting
  fun testing_set_holdDurationMinMillis(holdDurationMinMillis: Int) {
    longHoldDurationMinMillis = holdDurationMinMillis.toLong()
  }

  @VisibleForTesting
  fun testing_set_tapMaxDistancePixels(tapMaxDistancePixels: Int) {
    this.tapMaxDistancePixels = tapMaxDistancePixels.toFloat()
  }

  @VisibleForTesting
  fun testing_set_swipeMinDistance(swipeMinDistancePixels: Int) {
    this.swipeMinDistancePixels = swipeMinDistancePixels.toFloat()
  }

  @VisibleForTesting
  fun testing_set_swipeMinSpeedPixelsPerSecond(swipeMinSpeedPixelsPerSecond: Int) {
    this.swipeMinSpeedPixelsPerSecond = swipeMinSpeedPixelsPerSecond.toFloat()
  }

  companion object {
    private const val TAG = "MultitouchHandler"

    /**
     * Pointers that were released this close in time to the final pointer release are considered
     * recent enough to contribute to a {@link MultitouchResult#TYPE_TAP} or {@link
     * MultitouchResult#TYPE_SWIPE}, even if accumulation mode is false.
     */
    private const val RECENCY_MAX_MS = 250

    /** A Tap is not recognizable if any of the pointers travelled more than this distance. */
    private const val TAP_MAX_DISTANCE_MM = 5

    /**
     * A Swipe is not recognizable if the max ratio of the x and y components of the absolute
     * displacement vector (between final and initial points) is less than the following threshold.
     * For example, a value of 1.0f means that a perfectly diagonal swipe is recognizable. A value
     * of 2.0f means that the distance travelled in the dominant direction must be twice as long as
     * the distance travelled in the non-dominant direction. This value must exceed 1.0f.
     */
    private const val SWIPE_MIN_RATIO = 2.0f

    /**
     * A Swipe is not recognizable if the speed in the dominant direction does not exceed the
     * following threshold.
     */
    private const val SWIPE_MIN_SPEED_MM_PER_SECOND = 45

    /**
     * A Swipe is not recognizable if the distance from initial to final point in the dominant
     * direction does not exceed the following threshold.
     */
    private const val SWIPE_MIN_DISTANCE_MM = 9

    /**
     * Pointers held for this duration, without additional pointers added or removed, qualify as a
     * possible hold. Whether a hold is actually produced depends on the {@link HoldRecognizer}.
     */
    private const val HOLD_MIN_DURATION_MS = 2000L
  }
}
