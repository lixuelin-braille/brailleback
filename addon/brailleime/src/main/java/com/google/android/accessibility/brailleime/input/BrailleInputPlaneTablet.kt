/*
 * Copyright (C) 2023 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.google.android.accessibility.brailleime.input

import android.content.Context
import android.content.res.Configuration
import android.graphics.PointF
import android.util.Size
import com.google.android.accessibility.braille.common.BrailleUserPreferences
import com.google.android.accessibility.braille.interfaces.BrailleCharacter
import com.google.android.accessibility.brailleime.BrailleImeLog
import com.google.android.accessibility.brailleime.BrailleInputOptions
import com.google.android.accessibility.brailleime.input.MultitouchHandler.HoldRecognizer
import java.text.ParseException

/** {@link BrailleInputPlane} for tablet. */
class BrailleInputPlaneTablet(
  context: Context,
  sizeInPixels: Size,
  holdRecognizer: HoldRecognizer?,
  orientation: Int,
  options: BrailleInputOptions,
  customGestureDetector: CustomOnGestureListener,
) : BrailleInputPlane(
  context,
  sizeInPixels,
  holdRecognizer,
  orientation,
  options,
  customGestureDetector,
) {
  companion object {
    private const val TAG = "BrailleInputPlaneTablet"
  }

  /**
   * Constructs a BrailleInputPlane.
   *
   * <p>The orientation argument should be one of {@link Configuration#ORIENTATION_LANDSCAPE} or
   * {@link Configuration#ORIENTATION_PORTRAIT}, depending on the current orientation of the device.
   */

  override fun readLayoutPoints(screenSize: Size): List<PointF> {
    return try {
      BrailleUserPreferences.readCalibrationPointsTablet(
        context, isTableTopMode, orientation)
    } catch (e: ParseException) {
      BrailleImeLog.e(TAG, "Read saved dots failed.", e)
      ArrayList()
    }
  }

  override fun writeLayoutPoints(centerPoints: List<PointF>, screenSize: Size) {
    try {
      BrailleUserPreferences.writeCalibrationPointsTablet(
        context, isTableTopMode, orientation, centerPoints, screenSize)
    } catch (e: ParseException) {
      BrailleImeLog.e(TAG, "Write points failed.")
    }
  }

  override fun buildDotTargetCenters(screenSize: Size): List<PointF> {
    // First we build the centers of the DotTargets in landscape coordinates (even if in
    // portrait).
    return buildDotTargetCentersLandscape(screenSize)
  }

  override fun sortDotCentersFirstTime(dotCenters: MutableList<PointF>) {
    dotCenters.sortWith(Comparator { o1, o2 ->
      if (isTableTopMode) o1.x.compareTo(o2.x) else o2.x.compareTo(o1.x)
    })
  }

  override fun sortDotCentersByGroup(group: MutableList<PointF>, isFirstGroup: Boolean) {
    group.sortWith(Comparator { o1, o2 ->
      var result: Int
      if (orientation == Configuration.ORIENTATION_PORTRAIT) {
        result =
          if (isTableTopMode) o1.x.compareTo(o2.x) else o1.y.compareTo(o2.y)
        if (result != 0) {
          return@Comparator result
        }
        if (isTableTopMode) {
          result =
            if (isFirstGroup) o1.y.compareTo(o2.y) else o2.y.compareTo(o1.y)
        } else {
          result =
            if (isFirstGroup) o1.x.compareTo(o2.x) else o2.y.compareTo(o1.y)
        }
      } else {
        result =
          if (isTableTopMode) o1.x.compareTo(o2.x) else o1.y.compareTo(o2.y)
        if (result != 0) {
          return@Comparator result
        }
        if (isTableTopMode) {
          result =
            if (isFirstGroup) o1.y.compareTo(o2.y) else o2.y.compareTo(o1.y)
        } else {
          result = o1.x.compareTo(o2.x)
        }
      }
      return@Comparator result
    })
  }

  override fun createSwipe(swipe: Swipe): BrailleInputPlaneResult {
    return BrailleInputPlaneResult.createSwipe(getReorientedSwipe(swipe))
  }

  override fun createDotHoldAndSwipe(
    swipe: Swipe,
    heldBrailleCharacter: BrailleCharacter,
  ): BrailleInputPlaneResult {
    return BrailleInputPlaneResult.createDotHoldAndDotSwipe(
      getReorientedSwipe(swipe), heldBrailleCharacter)
  }

  override fun getRotateDegree(): Int {
    return 0
  }

  override fun getCaptionCenterPoint(screenSize: Size): PointF {
    val pointF = PointF()
    pointF.x = screenSize.width / 2.0f
    pointF.y =
      if (isTableTopMode) screenSize.height / 3.0f else screenSize.height / 2.0f
    return pointF
  }

  override fun getInputViewCaptionTranslate(screenSize: Size): IntArray {
    return IntArray(2)
  }

  override fun getInputViewCaptionScreenSize(screenSize: Size): Size {
    return screenSize
  }

  /** Change the direction of the swipe from device-facing to user-facing. */
  private fun getReorientedSwipe(swipe: Swipe): Swipe {
    return if (isTableTopMode) Swipe(swipe) else Swipe.createFromMirror(swipe)
  }
}
