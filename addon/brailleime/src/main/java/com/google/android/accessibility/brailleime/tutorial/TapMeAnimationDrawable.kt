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
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import com.google.android.accessibility.brailleime.R

/** Drawable that draws a flashing dot. */
internal class TapMeAnimationDrawable(context: Context) : Drawable() {

  private val circlePaint: Paint
  private val animator: ValueAnimator

  init {
    circlePaint = Paint()
    circlePaint.color = context.getColor(R.color.text_highlight_color)

    // These values are the scale parameters of radius vary in the animation.
    animator = ValueAnimator.ofFloat(0f, 1f, 0f, 1f, 0f)
    animator.duration = ANIMATION_DURATION_MS.toLong()
    animator.addUpdateListener { animation -> invalidateSelf() }
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

  override fun draw(canvas: Canvas) {
    val radius = (animator.animatedValue as Float) * bounds.height() / 2f
    canvas.drawCircle(bounds.width() / 2f, bounds.height() / 2f, radius, circlePaint)
  }

  override fun setAlpha(alpha: Int) {
    circlePaint.alpha = alpha
  }

  override fun setColorFilter(colorFilter: ColorFilter?) {}

  override fun getOpacity(): Int {
    return PixelFormat.TRANSPARENT
  }

  companion object {
    private const val ANIMATION_DURATION_MS = 1200
    private const val START_DELAY_DURATION_MS = 1000
  }
}
