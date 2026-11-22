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

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Paint.Style
import android.graphics.Path
import android.util.Size
import android.view.View
import com.google.android.accessibility.braille.common.BrailleUtils
import com.google.android.accessibility.brailleime.BrailleIme.OrientationSensitive
import com.google.android.accessibility.brailleime.BrailleInputOptions
import com.google.android.accessibility.brailleime.R
import com.google.android.accessibility.brailleime.input.BrailleInputPlane
import com.google.android.accessibility.brailleime.tutorial.TutorialView

/** View that draws yellow rectangles to separate braille dots. */
internal class DotBlockView(
    context: Context,
    private var orientation: Int,
    private val isTabletop: Boolean,
    private val options: BrailleInputOptions,
) : View(context), OrientationSensitive {

  private val blocksPaint: Paint
  private val dashPathPaint: Paint
  private val dashPathEffectInColor1: DashPathEffect
  private val path: Path

  init {
    path = Path()
    val pathIntervalInPixels =
        resources.getDimensionPixelSize(R.dimen.dot_block_dash_path_interval).toFloat()

    blocksPaint = Paint()
    blocksPaint.color = context.getColor(R.color.braille_block_background)

    dashPathPaint = Paint()
    dashPathPaint.style = Style.STROKE
    dashPathPaint.strokeWidth =
        resources.getDimensionPixelSize(R.dimen.dot_block_dash_path_stroke_width).toFloat()
    dashPathPaint.alpha = 100

    // Use these two dash paths to draw color interleaving border.
    dashPathEffectInColor1 =
        DashPathEffect(
            floatArrayOf(pathIntervalInPixels, pathIntervalInPixels), /* phase= */ 0f)
  }

  override fun onOrientationChanged(orientation: Int, screenSize: Size) {
    this.orientation = orientation
    invalidate()
    requestLayout()
  }

  override fun onDraw(canvas: Canvas) {
    var width = width
    var height = height
    canvas.save()
    if (BrailleUtils.isPhoneSizedDevice(resources) &&
        orientation == Configuration.ORIENTATION_PORTRAIT) {
      width = height
      height = this.width
      canvas.rotate(TutorialView.ROTATION_270.toFloat())
      canvas.translate(/* dx= */ (-this.height).toFloat(), /* dy= */ 0f)
    }

    val columnCount =
        if (isTabletop) options.brailleType().getDotCount() else BrailleInputPlane.NUMBER_OF_COLUMNS_SCREEN_AWAY
    val rowCount =
        if (isTabletop) BrailleInputPlane.NUMBER_OF_ROWS_TABLETOP
        else options.brailleType().getDotCount() / BrailleInputPlane.NUMBER_OF_COLUMNS_SCREEN_AWAY
    val dotDiameter =
        2 * resources.getDimensionPixelSize(R.dimen.input_plane_dot_radius)
    val columnSpace = (width - columnCount * dotDiameter).toFloat() / (columnCount + 1)
    val rowSpace = (height - rowCount * dotDiameter).toFloat() / (rowCount + 1)
    val padding = resources.getDimensionPixelSize(R.dimen.dot_block_padding)
    for (column in 0 until columnCount) {
      val left = getPairedPosition1(dotDiameter, columnSpace, column, padding)
      val right = getPairedPosition2(dotDiameter, columnSpace, column, columnCount, padding)
      for (row in 0 until rowCount) {
        // Draw braille block.
        val top = getPairedPosition1(dotDiameter, rowSpace, row, padding)
        val bottom = getPairedPosition2(dotDiameter, rowSpace, row, rowCount, padding)
        canvas.drawRect(left, top, right, bottom, blocksPaint)

        // Draw the border of braille block.
        path.reset()
        path.moveTo(left, top)
        path.lineTo(right, top)
        path.lineTo(right, bottom)
        path.lineTo(left, bottom)
        path.lineTo(left, top)
        dashPathPaint.setPathEffect(dashPathEffectInColor1)
        dashPathPaint.color = context.getColor(R.color.braille_block_dash_path)
        canvas.drawPath(path, dashPathPaint)
      }
    }
    canvas.restore()
  }

  private fun getPairedPosition1(
      dotDiameter: Int,
      columnOrRowSpace: Float,
      columnOrRowIndex: Int,
      padding: Int,
  ): Float {
    return columnOrRowSpace * columnOrRowIndex +
        (columnOrRowSpace / 2) *
            ((columnOrRowIndex + 1) /
                (2 + 2 *
                    (columnOrRowIndex / (options.brailleType().getDotCount() / 2)))) +
        dotDiameter * columnOrRowIndex +
        padding
  }

  private fun getPairedPosition2(
      dotDiameter: Int,
      columnOrRowSpace: Float,
      columnOrRowIndex: Int,
      columnOrRowCount: Int,
      padding: Int,
  ): Float {
    return columnOrRowSpace * (columnOrRowIndex + 1) +
        (columnOrRowSpace / 2) +
        (columnOrRowSpace / 2) * ((columnOrRowIndex + 1) / columnOrRowCount) +
        dotDiameter * (columnOrRowIndex + 1) -
        padding
  }
}
