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

import com.google.android.accessibility.braille.interfaces.BrailleCharacter
import javax.annotation.Nullable

/**
 * The result produced by a {@link BrailleInputPlane} commission.
 *
 * <p>Data fields are non-null depending on the type, as follows:
 *
 * <ul>
 *   <li>For type {@link MultitouchResult#TYPE_TAP}, field {@code brailleCharacter} is non-null.
 *   <li>For type {@link MultitouchResult#TYPE_SWIPE}, field {@code swipe} is non-null.
 *   <li>For type {@link MultitouchResult#TYPE_HOLD}, field {@code pointersHeldCount} is non-null.
 * </ul>
 */
class BrailleInputPlaneResult private constructor() {
  @MultitouchResult.Type @JvmField var type: Int = 0
  @Nullable @JvmField var releasedBrailleCharacter: BrailleCharacter? = null
  @Nullable @JvmField var heldBrailleCharacter: BrailleCharacter? = null
  @Nullable @JvmField var swipe: Swipe? = null
  @JvmField var pointersHeldCount: Int = 0
  @JvmField var isLeft: Boolean = false

  override fun toString(): String {
    return "BrailleInputPlaneResult{" +
      "type=" + type +
      ", releasedBrailleCharacter=" + releasedBrailleCharacter +
      ", swipe=" + swipe +
      ", heldBrailleCharacter=" + heldBrailleCharacter +
      '}'
  }

  companion object {
    @JvmStatic
    fun createTapAndRelease(brailleCharacter: BrailleCharacter): BrailleInputPlaneResult {
      val result = BrailleInputPlaneResult()
      result.type = MultitouchResult.TYPE_TAP
      result.releasedBrailleCharacter = brailleCharacter
      return result
    }

    @JvmStatic
    fun createCalibration(isLeft: Boolean, pointersHeldCount: Int): BrailleInputPlaneResult {
      val result = BrailleInputPlaneResult()
      result.type = MultitouchResult.TYPE_CALIBRATION_HOLD
      result.pointersHeldCount = pointersHeldCount
      result.isLeft = isLeft
      return result
    }

    @JvmStatic
    fun createDotHoldAndDotSwipe(
      swipe: Swipe,
      heldBrailleCharacter: BrailleCharacter,
    ): BrailleInputPlaneResult {
      val result = BrailleInputPlaneResult()
      result.type = MultitouchResult.TYPE_HOLD_AND_SWIPE
      result.heldBrailleCharacter = heldBrailleCharacter
      result.swipe = swipe
      return result
    }

    @JvmStatic
    fun createHold(pointersHeldCount: Int): BrailleInputPlaneResult {
      val result = BrailleInputPlaneResult()
      result.type = MultitouchResult.TYPE_HOLD
      result.pointersHeldCount = pointersHeldCount
      return result
    }

    @JvmStatic
    fun createSwipe(swipe: Swipe): BrailleInputPlaneResult {
      val result = BrailleInputPlaneResult()
      result.type = MultitouchResult.TYPE_SWIPE
      result.swipe = swipe
      return result
    }

    @JvmStatic
    fun createInvalidGesture(): BrailleInputPlaneResult {
      val result = BrailleInputPlaneResult()
      result.type = MultitouchResult.TYPE_INVALID
      return result
    }
  }
}
