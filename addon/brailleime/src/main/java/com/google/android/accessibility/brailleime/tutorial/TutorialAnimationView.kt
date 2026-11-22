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

package com.google.android.accessibility.brailleime.tutorial

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Point
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.Shader.TileMode
import android.util.Size
import android.view.View
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.view.animation.Animation.INFINITE
import android.widget.FrameLayout
import android.widget.TextView
import androidx.annotation.ColorInt
import com.google.android.accessibility.braille.common.BrailleUtils
import com.google.android.accessibility.brailleime.BrailleIme.OrientationSensitive
import com.google.android.accessibility.brailleime.R
import com.google.android.accessibility.brailleime.tutorial.TutorialAnimationView.CanvasView.Painter
import com.google.android.accessibility.brailleime.tutorial.TutorialAnimationView.SwipeAnimation.Direction

/** View that draws instruction text, gesture animation and action result text. */
internal class TutorialAnimationView(
  context: Context,
  orientation: Int,
  screenSize: Size,
  private val isTabletop: Boolean,
) : FrameLayout(context), OrientationSensitive {
  private val canvasView: CanvasView
  private val hintToast: TextView
  private val actionResult: TextView
  private val swipeAnimation: SwipeAnimation
  private val actionResultAnimation: Animation
  private val hintToastAnimation: Animation

  init {
    View.inflate(context, R.layout.tutorial_animation_view, this)
    canvasView = CanvasView(context, orientation, screenSize, isTabletop)
    canvasView.layoutParams =
      FrameLayout.LayoutParams(screenSize.width, screenSize.height)
    addView(canvasView)
    hintToast = findViewById<TextView>(R.id.hint_toast)
    actionResult = findViewById<TextView>(R.id.action_result)
    swipeAnimation = SwipeAnimation(context, canvasView::invalidate)

    actionResultAnimation = AlphaAnimation(/* fromAlpha= */ 0.0f, /* toAlpha= */ 1.0f)
    actionResultAnimation.setDuration(ACTION_RESULT_ANIMATION_DURATION_MS.toLong())
    hintToastAnimation = AlphaAnimation(/* fromAlpha= */ 0.0f, /* toAlpha= */ 1.0f)
    hintToastAnimation.setDuration(HINT_TOAST_ANIMATION_DURATION_MS.toLong())
  }

  override fun onOrientationChanged(orientation: Int, screenSize: Size) {
    canvasView.layoutParams =
      FrameLayout.LayoutParams(screenSize.width, screenSize.height)
    canvasView.onOrientationChanged(orientation, screenSize)
    invalidate()
    requestLayout()
  }

  internal fun startHintToastAnimation(text: String) {
    hintToast.setText(text)
    hintToast.setVisibility(VISIBLE)
    hintToast.startAnimation(hintToastAnimation)
  }

  internal fun startActionResultAnimation(text: String) {
    actionResult.setText(text)
    actionResult.setVisibility(VISIBLE)
    actionResult.startAnimation(actionResultAnimation)
  }

  internal fun startSwipeAnimation(fingerCount: Int, swipeDirection: Direction) {
    swipeAnimation.updatePaint(
      fingerCount,
      canvasView.getCanvasSize(),
      if (isTabletop) mirror(swipeDirection) else swipeDirection,
    )
    canvasView.addPainter(swipeAnimation)
    swipeAnimation.startAnimation()
  }

  private fun mirror(oldDirection: Direction): Direction {
    if (oldDirection == Direction.LEFT_TO_RIGHT) {
      return Direction.RIGHT_TO_LEFT
    } else if (oldDirection == Direction.RIGHT_TO_LEFT) {
      return Direction.LEFT_TO_RIGHT
    }
    return oldDirection
  }

  internal fun stopSwipeAnimation() {
    canvasView.removePainter(swipeAnimation)
    swipeAnimation.stopAnimation()
  }

  internal fun reset() {
    hintToast.setVisibility(INVISIBLE)
    actionResult.setVisibility(INVISIBLE)
    canvasView.removePainter(swipeAnimation)
  }

  /** View for drawing with canvas. */
  internal class CanvasView(
    context: Context,
    private var orientation: Int,
    screenSize: Size,
    private val isTabletop: Boolean,
  ) : View(context), OrientationSensitive {
    /** Painter drawing on this canvas view. */
    interface Painter {
      fun onDraw(canvas: Canvas, canvasSize: Size)
    }

    private val painters: MutableList<Painter> = mutableListOf()
    private var canvasSize: Size

    init {
      canvasSize = calculateCanvasSize(screenSize)
    }

    internal fun getCanvasSize(): Size {
      return canvasSize
    }

    internal fun addPainter(painter: Painter) {
      if (!painters.contains(painter)) {
        painters.add(painter)
      }
    }

    internal fun removePainter(painter: Painter) {
      painters.remove(painter)
    }

    override fun onOrientationChanged(orientation: Int, screenSize: Size) {
      this.orientation = orientation
      this.canvasSize = calculateCanvasSize(screenSize)
      invalidate()
      requestLayout()
    }

    override fun onDraw(canvas: Canvas) {
      canvas.save()
      if (BrailleUtils.isPhoneSizedDevice(resources)) {
        if (orientation == Configuration.ORIENTATION_PORTRAIT) {
          canvas.rotate(
            (if (isTabletop) TutorialView.ROTATION_90 else TutorialView.ROTATION_270).toFloat(),
          )
          canvas.translate(
            /* dx= */ if (isTabletop) 0f else -canvasSize.width.toFloat(),
            /* dy= */ if (isTabletop) -canvasSize.height.toFloat() else 0f,
          )
        } else {
          canvas.rotate(
            (if (isTabletop) TutorialView.ROTATION_180 else TutorialView.ROTATION_0).toFloat(),
          )
          canvas.translate(
            /* dx= */ if (isTabletop) -canvasSize.width.toFloat() else 0f,
            /* dy= */ if (isTabletop) -canvasSize.height.toFloat() else 0f,
          )
        }
      }

      for (painter in painters) {
        painter.onDraw(canvas, canvasSize)
      }
      canvas.restore()
    }

    private fun calculateCanvasSize(screenSize: Size): Size {
      var result: Size = screenSize
      if (BrailleUtils.isPhoneSizedDevice(resources) &&
        orientation == Configuration.ORIENTATION_PORTRAIT
      ) {
        result = Size(/* width= */ screenSize.height, /* height= */ screenSize.width)
      }
      return result
    }
  }

  /** Draws the swipe instruction animation in the middle of screen. */
  internal class SwipeAnimation(context: Context, private val invalidate: Runnable) : Painter {
    enum class Direction {
      TOP_TO_BOTTOM,
      BOTTOM_TO_TOP,
      LEFT_TO_RIGHT,
      RIGHT_TO_LEFT,
    }

    private val circlePaint: Paint
    private val roundRectPaint: Paint
    private var animator: ValueAnimator? = null
    private var direction: Direction = Direction.TOP_TO_BOTTOM
    private val dotRadiusInPixels: Int

    @ColorInt private val gestureGradientColor: Int
    private val gesturesCircleCoordinates: MutableList<Point> = mutableListOf()

    init {
      circlePaint = Paint()
      circlePaint.setColor(context.getColor(R.color.gesture_circle))

      gestureGradientColor = context.getColor(R.color.gesture_circle_gradient)
      roundRectPaint = Paint()
      roundRectPaint.setColor(gestureGradientColor)
      dotRadiusInPixels =
        context.resources.getDimensionPixelSize(R.dimen.tutorial_gesture_dot_radius)
    }

    internal fun updatePaint(
      fingerCount: Int,
      canvasSize: Size,
      swipeDirection: Direction,
    ) {
      this.direction = swipeDirection
      val canvasLength: Float =
        when (swipeDirection) {
          Direction.TOP_TO_BOTTOM, Direction.BOTTOM_TO_TOP ->
            canvasSize.height * SCALE_FACTOR
          Direction.LEFT_TO_RIGHT, Direction.RIGHT_TO_LEFT ->
            canvasSize.width * SCALE_FACTOR
        }
      updateGesturesCoordinates(fingerCount, canvasLength.toInt(), canvasSize, swipeDirection)
      val gradientVariation =
        determineGradientVariation(canvasLength.toInt(), swipeDirection)
      val shader: Shader =
        LinearGradient(
          gradientVariation.left.toFloat(),
          gradientVariation.top.toFloat(),
          gradientVariation.right.toFloat(),
          gradientVariation.bottom.toFloat(),
          Color.TRANSPARENT,
          gestureGradientColor,
          TileMode.CLAMP,
        )
      roundRectPaint.setShader(shader)

      animator = ValueAnimator.ofFloat(0f, canvasLength, canvasLength)
      animator!!.setDuration(ANIMATION_DURATION_MS.toLong())
      animator!!.setRepeatCount(INFINITE)
      animator!!.addUpdateListener { animation -> invalidate.run() }
    }

    internal fun startAnimation() {
      animator!!.start()
    }

    internal fun stopAnimation() {
      animator!!.cancel()
    }

    /** Determines the start coordinates of swipe animation. */
    internal fun updateGesturesCoordinates(
      fingerCount: Int,
      canvasLength: Int,
      canvasSize: Size,
      swipeDirection: Direction,
    ) {
      gesturesCircleCoordinates.clear()
      val gestureInterval = DISTANCE_BETWEEN_GESTURES_IN_PIXELS + 2 * dotRadiusInPixels
      val distanceToStartPoint = (fingerCount - 1) * gestureInterval / 2
      for (i in 0 until fingerCount) {
        var x = 0
        var y = 0
        when (swipeDirection) {
          Direction.TOP_TO_BOTTOM -> {
            x = canvasSize.width / 2 - distanceToStartPoint + i * gestureInterval
            y = (canvasSize.height - canvasLength) / 2
          }
          Direction.BOTTOM_TO_TOP -> {
            x = canvasSize.width / 2 - distanceToStartPoint + i * gestureInterval
            y = (canvasSize.height - canvasLength) / 2 + canvasLength
          }
          Direction.LEFT_TO_RIGHT -> {
            x = (canvasSize.width - canvasLength) / 2
            y = canvasSize.height / 2 - distanceToStartPoint + i * gestureInterval
          }
          Direction.RIGHT_TO_LEFT -> {
            x = (canvasSize.width - canvasLength) / 2 + canvasLength
            y = canvasSize.height / 2 - distanceToStartPoint + i * gestureInterval
          }
        }
        gesturesCircleCoordinates.add(Point(x, y))
      }
    }

    internal fun determineGradientVariation(
      canvasLength: Int,
      swipeDirection: Direction,
    ): Rect {
      val gradientVariation = Rect()
      gradientVariation.left = gesturesCircleCoordinates[0].x - dotRadiusInPixels
      gradientVariation.top = gesturesCircleCoordinates[0].y - dotRadiusInPixels
      when (swipeDirection) {
        Direction.TOP_TO_BOTTOM -> {
          gradientVariation.right = gradientVariation.left
          gradientVariation.bottom = gradientVariation.top + canvasLength
        }
        Direction.BOTTOM_TO_TOP -> {
          gradientVariation.right = gradientVariation.left
          gradientVariation.top = gesturesCircleCoordinates[0].y + dotRadiusInPixels
          gradientVariation.bottom = gradientVariation.top - canvasLength
        }
        Direction.LEFT_TO_RIGHT -> {
          gradientVariation.right = gradientVariation.left + canvasLength
          gradientVariation.bottom = gradientVariation.top
        }
        Direction.RIGHT_TO_LEFT -> {
          gradientVariation.left = gesturesCircleCoordinates[0].x + dotRadiusInPixels
          gradientVariation.right = gradientVariation.left - canvasLength
          gradientVariation.bottom = gradientVariation.top
        }
      }
      return gradientVariation
    }

    override fun onDraw(canvas: Canvas, canvasSize: Size) {
      val range = animator!!.animatedValue as Float
      val radius = dotRadiusInPixels.toFloat()
      for (point in gesturesCircleCoordinates) {
        when (direction) {
          Direction.TOP_TO_BOTTOM -> {
            canvas.drawRoundRect(
              (point.x - dotRadiusInPixels).toFloat(),
              (point.y - dotRadiusInPixels).toFloat(),
              (point.x + dotRadiusInPixels).toFloat(),
              point.y + dotRadiusInPixels + range,
              radius,
              radius,
              roundRectPaint,
            )
            canvas.drawCircle(point.x.toFloat(), point.y + range, radius, circlePaint)
          }
          Direction.BOTTOM_TO_TOP -> {
            canvas.drawRoundRect(
              (point.x - dotRadiusInPixels).toFloat(),
              (point.y + dotRadiusInPixels).toFloat(),
              (point.x + dotRadiusInPixels).toFloat(),
              point.y - dotRadiusInPixels - range,
              radius,
              radius,
              roundRectPaint,
            )
            canvas.drawCircle(point.x.toFloat(), point.y - range, radius, circlePaint)
          }
          Direction.LEFT_TO_RIGHT -> {
            canvas.drawRoundRect(
              (point.x - dotRadiusInPixels).toFloat(),
              (point.y - dotRadiusInPixels).toFloat(),
              point.x + dotRadiusInPixels + range,
              (point.y + dotRadiusInPixels).toFloat(),
              radius,
              radius,
              roundRectPaint,
            )
            canvas.drawCircle(point.x + range, point.y.toFloat(), radius, circlePaint)
          }
          Direction.RIGHT_TO_LEFT -> {
            canvas.drawRoundRect(
              (point.x + dotRadiusInPixels).toFloat(),
              (point.y - dotRadiusInPixels).toFloat(),
              point.x - dotRadiusInPixels - range,
              (point.y + dotRadiusInPixels).toFloat(),
              radius,
              radius,
              roundRectPaint,
            )
            canvas.drawCircle(point.x - range, point.y.toFloat(), radius, circlePaint)
          }
        }
      }
    }

    companion object {
      private const val DISTANCE_BETWEEN_GESTURES_IN_PIXELS = 75
      private const val ANIMATION_DURATION_MS = 1500
      private const val SCALE_FACTOR = 0.5f
    }
  }

  companion object {
    private const val HINT_TOAST_ANIMATION_DURATION_MS = 200
    private const val ACTION_RESULT_ANIMATION_DURATION_MS = 300
  }
}
