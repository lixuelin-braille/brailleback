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

import com.google.android.accessibility.utils.BuildVersionUtils

import android.animation.Animator
import android.animation.ValueAnimator
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Paint
import android.util.Size
import android.view.View
import com.google.android.accessibility.braille.common.BrailleUtils
import com.google.android.accessibility.braille.interfaces.BrailleCharacter
import com.google.android.accessibility.brailleime.BrailleIme.OrientationSensitive
import com.google.android.accessibility.brailleime.R
import com.google.android.accessibility.brailleime.Utils
import com.google.android.accessibility.brailleime.input.BrailleInputPlane.DotTarget
import com.google.android.accessibility.brailleime.tutorial.TutorialView

/**
 * View that draws flashing dots animation. The given DotTargets should be the same with the ones in
 * {@link BrailleInputPlane}
 */
internal class DotsFlashingAnimationView(
    context: Context,
    private var dotTargets: List<DotTarget>,
    private var orientation: Int,
    private val isTabletop: Boolean,
) : View(context), OrientationSensitive {

  private val dotBackgroundPaint: Paint
  private val dotNumberPaint: Paint
  private val dotFlashingPaint: Paint
  private val animator: ValueAnimator
  private val dotRadiusInPixels: Int

  init {
    dotRadiusInPixels =
        context.resources.getDimensionPixelSize(R.dimen.input_plane_dot_radius)

    dotBackgroundPaint = Paint()
    dotBackgroundPaint.style = Paint.Style.FILL_AND_STROKE
    dotBackgroundPaint.color = context.getColor(R.color.input_plane_dot_background_default)

    dotNumberPaint = Paint()
    dotNumberPaint.textAlign = Paint.Align.CENTER
    val scaleFactor =
        Utils.getResourcesFloat(resources, R.dimen.input_plane_dot_number_size_multiplier)
    dotNumberPaint.textSize = scaleFactor * dotRadiusInPixels
    val textStrokeWidth =
        resources.getDimension(R.dimen.input_plane_dot_number_stroke_width)
    dotNumberPaint.strokeWidth = textStrokeWidth
    dotNumberPaint.style = Paint.Style.FILL_AND_STROKE
    dotNumberPaint.color = context.getColor(R.color.input_plane_dot_number_default)

    dotFlashingPaint = Paint()
    dotFlashingPaint.color = context.getColor(R.color.gesture_circle)
    animator =
        ValueAnimator.ofFloat(
            CIRCLE_ANIMATION_MIN_SCALE, CIRCLE_ANIMATION_MAX_SCALE, CIRCLE_ANIMATION_MIN_SCALE)
    animator.duration = ANIMATION_DURATION_MS.toLong()
    animator.addUpdateListener { animation -> invalidate() }
    animator.addListener(
        object : Animator.AnimatorListener {
          override fun onAnimationStart(animator: Animator) {}

          override fun onAnimationEnd(animator: Animator) {
            animator.startDelay = START_DELAY_DURATION_MS.toLong()
            // This might not be a good solution but it works, do not start animator because
            // Robolectric tests run on main thread also, otherwise animator will repeat in endless
            // loop and cause the test timeout.
            if (!BuildVersionUtils.isRobolectric()) {
              animator.start()
            }
          }

          override fun onAnimationCancel(animator: Animator) {}

          override fun onAnimationRepeat(animator: Animator) {}
        })
    animator.start()
  }

  /** Returns the flashing braille character. */
  internal fun getFlashingCharacter(): BrailleCharacter {
    val dotNumbers = ArrayList<Int>()
    for (dotTarget in dotTargets) {
      dotNumbers.add(dotTarget.getDotNumber())
    }
    return BrailleCharacter(dotNumbers)
  }

  /** Updates the location of flashing character. */
  internal fun updateDotTarget(dotTargets: List<DotTarget>) {
    this.dotTargets = dotTargets
  }

  override fun onOrientationChanged(orientation: Int, screenSize: Size) {
    this.orientation = orientation
    invalidate()
    requestLayout()
  }

  override fun onDraw(canvas: Canvas) {
    for (dot in dotTargets) {
      canvas.save()
      var degree = 0
      if (BrailleUtils.isPhoneSizedDevice(resources)) {
        if (orientation == Configuration.ORIENTATION_PORTRAIT) {
          degree = if (isTabletop) TutorialView.ROTATION_90 else TutorialView.ROTATION_270
        } else {
          degree = if (isTabletop) TutorialView.ROTATION_180 else TutorialView.ROTATION_0
        }
      }
      canvas.rotate(degree.toFloat(), dot.getCenter().x, dot.getCenter().y)
      // Draw dot background and number (text).
      val textBaseline = Utils.getPaintTextBaselineInPixels(dotNumberPaint)
      canvas.drawCircle(
          dot.getCenter().x, dot.getCenter().y, dotRadiusInPixels.toFloat(), dotBackgroundPaint)
      canvas.drawText(
          dot.getDotNumber().toString(),
          dot.getCenter().x,
          dot.getCenter().y + textBaseline,
          dotNumberPaint)

      // Draw dot flashing background.
      val scale = animator.animatedValue as Float
      val animationRadius = scale * dotRadiusInPixels
      val alpha =
          (ANIMATION_DEFAULT_ALPHA *
              (scale - CIRCLE_ANIMATION_MIN_SCALE) /
              (CIRCLE_ANIMATION_MAX_SCALE - CIRCLE_ANIMATION_MIN_SCALE)).toInt()
      dotFlashingPaint.alpha = alpha
      canvas.drawCircle(dot.getCenter().x, dot.getCenter().y, animationRadius, dotFlashingPaint)
      canvas.restore()
    }
  }

  companion object {
    private const val ANIMATION_DURATION_MS = 1200
    private const val START_DELAY_DURATION_MS = 1000
    private const val ANIMATION_DEFAULT_ALPHA = 180
    private const val CIRCLE_ANIMATION_MAX_SCALE = 1.3f
    private const val CIRCLE_ANIMATION_MIN_SCALE = 0.7f
  }
}
