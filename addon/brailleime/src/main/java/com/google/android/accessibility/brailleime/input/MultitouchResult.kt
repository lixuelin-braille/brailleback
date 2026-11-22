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

import android.graphics.PointF
import androidx.annotation.IntDef
import com.google.common.collect.ImmutableList
import javax.annotation.Nullable

/**
 * The result produced by a {@link MultitouchHandler} commission.
 *
 * <p>Data fields are non-null depending on the type, as follows:
 *
 * <ul>
 *   <li>For type {@link MultitouchResult#TYPE_TAP}, field {@code releasePoints} is non-null.
 *   <li>For type {@link MultitouchResult#TYPE_SWIPE}, field {@code swipe} is non-null.
 *   <li>For type {@link MultitouchResult#TYPE_HOLD}, field {@code hold} is non-null.
 * </ul>
 */
class MultitouchResult private constructor() {

  @IntDef(
    TYPE_INVALID,
    TYPE_TAP,
    TYPE_SWIPE,
    TYPE_HOLD,
    TYPE_CALIBRATION_HOLD,
    TYPE_HOLD_AND_SWIPE
  )
  @Retention(AnnotationRetention.SOURCE)
  annotation class Type

  @Type @JvmField var type: Int = 0
  @Nullable @JvmField var swipe: Swipe? = null
  @Nullable @JvmField var heldPoints: MutableList<PointF>? = null
  @Nullable @JvmField var releasedPoints: MutableList<PointF>? = null

  override fun toString(): String {
    return "MultitouchResult{" +
      "type=" + type +
      ", releasePoints=" + releasedPoints +
      ", swipe=" + swipe +
      ", heldPoints=" + heldPoints +
      '}'
  }

  companion object {
    const val TYPE_INVALID = -1
    const val TYPE_TAP = 0
    const val TYPE_SWIPE = 1
    const val TYPE_HOLD = 2
    const val TYPE_CALIBRATION_HOLD = 3
    const val TYPE_HOLD_AND_SWIPE = 4

    @JvmStatic
    fun createInvalidGesture(): MultitouchResult {
      val result = MultitouchResult()
      result.type = TYPE_INVALID
      return result
    }

    @JvmStatic
    fun createTap(releaseContributors: ImmutableList<PointF>): MultitouchResult {
      val result = MultitouchResult()
      result.type = TYPE_TAP
      result.releasedPoints = releaseContributors.toMutableList()
      return result
    }

    @JvmStatic
    fun createSwipe(swipe: Swipe, releaseContributors: ImmutableList<PointF>): MultitouchResult {
      val result = MultitouchResult()
      result.type = TYPE_SWIPE
      result.swipe = swipe
      result.releasedPoints = releaseContributors.toMutableList()
      return result
    }

    @JvmStatic
    fun createCalibrationHold(holdContributors: ImmutableList<PointF>): MultitouchResult {
      val result = MultitouchResult()
      result.type = TYPE_CALIBRATION_HOLD
      result.heldPoints = holdContributors.toMutableList()
      return result
    }

    @JvmStatic
    fun createHold(holdContributors: ImmutableList<PointF>): MultitouchResult {
      val result = MultitouchResult()
      result.type = TYPE_HOLD
      result.heldPoints = holdContributors.toMutableList()
      return result
    }

    @JvmStatic
    fun createHoldAndDotSwipe(
      holdContributors: ImmutableList<PointF>,
      swipe: Swipe,
      releaseContributors: ImmutableList<PointF>,
    ): MultitouchResult {
      val result = MultitouchResult()
      result.type = TYPE_HOLD_AND_SWIPE
      result.swipe = swipe
      result.heldPoints = holdContributors.toMutableList()
      result.releasedPoints = releaseContributors.toMutableList()
      return result
    }
  }
}
