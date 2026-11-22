/*
 * Copyright 2023 Google Inc.
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

import android.content.res.Resources
import androidx.annotation.Nullable
import com.google.android.accessibility.braille.common.translate.BrailleTranslateUtils
import com.google.android.accessibility.braille.interfaces.BrailleCharacter
import com.google.android.accessibility.brailleime.R
import com.google.android.accessibility.brailleime.input.Swipe.Direction
import java.text.NumberFormat
import java.util.Locale
import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * Describes a user-initiated swipe and dots hold on the touch screen.
 *
 * <p>Dot hold and swipe includes the braille dots hold, a swipe direction{@link Direction}, a touch
 * count which is the number of fingers that were touching the screen.
 */
class DotHoldSwipe : Gesture {
  private val swipe: Swipe
  private val heldBrailleCharacter: BrailleCharacter

  constructor(swipe: Swipe, heldBrailleCharacter: BrailleCharacter) {
    this.swipe = swipe
    this.heldBrailleCharacter = heldBrailleCharacter
  }

  constructor(id: String) {
    val matcher = REGEX_PATTERN.matcher(id)
    if (!matcher.matches()) {
      throw IllegalArgumentException("Invalid id")
    }
    this.swipe =
      Swipe(
        Direction.valueOf(matcher.group("direction").uppercase(Locale.ENGLISH)),
        Integer.parseInt(matcher.group("touchpoints")))
    this.heldBrailleCharacter = BrailleCharacter(matcher.group("dot"))
  }

  override fun getSwipe(): Swipe {
    return swipe
  }

  override fun getHeldDots(): BrailleCharacter {
    return BrailleCharacter(heldBrailleCharacter.toString())
  }

  override fun mirrorDots(): Gesture {
    return DotHoldSwipe(swipe, heldBrailleCharacter.toMirror())
  }

  override fun getDescription(resources: Resources): String {
    if (swipe.touchCount > 1) {
      return resources.getString(
        R.string.gesture_hold_and_swipe_multiple_finger,
        BrailleTranslateUtils.getDotsDescription(resources, getHeldDots()),
        swipe.direction.getDescription(resources),
        NumberFormat.getNumberInstance(Locale.getDefault()).format(swipe.touchCount))
    }
    return resources.getString(
      R.string.gesture_hold_and_swipe_one_finger,
      BrailleTranslateUtils.getDotsDescription(resources, getHeldDots()),
      swipe.direction.getDescription(resources))
  }

  override fun toString(): String {
    return "DotHoldSwipe{hold=" +
      heldBrailleCharacter +
      ", direction=" +
      swipe.direction +
      ", touchCount=" +
      swipe.touchCount +
      "}"
  }

  override fun getId(): String {
    return "hold_dot" + heldBrailleCharacter + "_" + swipe.getId()
  }

  override fun hashCode(): Int {
    /*
     * Hashing function taken from an example in "Effective Java" page 38/39. The number 13 is
     * arbitrary, but choosing non-zero number to start decreases the number of collisions. 37
     * is used as it's an odd prime. If multiplication overflowed and the 37 was an even number,
     * it would be equivalent to bit shifting. The fact that 37 is prime is standard practice.
     */
    var result = 13
    result = 37 * result + swipe.direction.hashCode()
    result = 37 * result + heldBrailleCharacter.hashCode()
    result = 37 * result + swipe.touchCount
    return result
  }

  override fun equals(@Nullable obj: Any?): Boolean {
    val that = obj as? DotHoldSwipe ?: return false
    return swipe == that.swipe && heldBrailleCharacter == that.heldBrailleCharacter
  }

  companion object {
    private val REGEX_PATTERN =
      Pattern.compile("hold_dot(?<dot>\\d+)_swipe_(?<direction>[A-Za-z]+)_(?<touchpoints>\\d+)")
  }
}
