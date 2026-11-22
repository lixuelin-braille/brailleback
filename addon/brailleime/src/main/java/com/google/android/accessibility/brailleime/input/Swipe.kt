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

import android.content.res.Resources
import androidx.annotation.Nullable
import androidx.annotation.StringRes
import com.google.android.accessibility.braille.interfaces.BrailleCharacter
import com.google.android.accessibility.brailleime.R
import java.text.NumberFormat
import java.util.Locale
import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * Describes a user-initiated swipe on the touch screen.
 *
 * <p>A swipe has both a {@link Direction} and a touch count, which is the number of fingers that
 * were touching the screen while the swipe was occurring.
 */
class Swipe : Gesture {
  /** The direction in which the swipe occurred. */
  enum class Direction(@StringRes private val descriptionRes: Int) {
    UP(R.string.gesture_direction_up),
    DOWN(R.string.gesture_direction_down),
    LEFT(R.string.gesture_direction_left),
    RIGHT(R.string.gesture_direction_right);

    /** Gets the description of the {@link Direction}. */
    fun getDescription(resources: Resources): String {
      return resources.getString(descriptionRes)
    }
  }

  @JvmField val direction: Direction
  @JvmField val touchCount: Int

  constructor(direction: Direction, touchCount: Int) {
    this.direction = direction
    this.touchCount = touchCount
  }

  constructor(swipe: Swipe) {
    this.direction = swipe.direction
    this.touchCount = swipe.touchCount
  }

  constructor(id: String) {
    val matcher = REGEX_PATTERN.matcher(id)
    if (!matcher.matches()) {
      throw IllegalArgumentException("Invalid id")
    }
    this.direction = Direction.valueOf(matcher.group("direction").uppercase(Locale.ENGLISH))
    this.touchCount = Integer.parseInt(matcher.group("touchpoints"))
  }

  fun getDirection(): Direction {
    return direction
  }

  fun getTouchCount(): Int {
    return touchCount
  }

  override fun getSwipe(): Swipe {
    return Swipe(this)
  }

  override fun getHeldDots(): BrailleCharacter {
    return BrailleCharacter()
  }

  override fun mirrorDots(): Gesture {
    return Swipe(this)
  }

  override fun getDescription(resources: Resources): String {
    if (touchCount > 1) {
      return resources.getString(
        R.string.gesture_swipe_multiple_fingers,
        direction.getDescription(resources),
        NumberFormat.getNumberInstance(Locale.getDefault()).format(touchCount))
    }
    return resources.getString(
      R.string.gesture_swipe_one_finger, direction.getDescription(resources))
  }

  override fun getId(): String {
    return "swipe_" + direction.toString().lowercase(Locale.ENGLISH) + "_" + touchCount
  }

  override fun toString(): String {
    return "Swipe{direction=" + direction + ", touchCount=" + touchCount + "}"
  }

  override fun equals(@Nullable obj: Any?): Boolean {
    val that = obj as? Swipe ?: return false
    return direction == that.direction && touchCount == that.touchCount
  }

  override fun hashCode(): Int {
    /*
     * Hashing function taken from an example in "Effective Java" page 38/39. The number 13 is
     * arbitrary, but choosing non-zero number to start decreases the number of collisions. 37
     * is used as it's an odd prime. If multiplication overflowed and the 37 was an even number,
     * it would be equivalent to bit shifting. The fact that 37 is prime is standard practice.
     */
    var result = 13
    result = 37 * result + direction.hashCode()
    result = 37 * result + touchCount
    return result
  }

  companion object {
    private val REGEX_PATTERN =
      Pattern.compile("swipe_(?<direction>[A-Za-z]+)_(?<touchpoints>\\d+)")

    @JvmStatic
    fun createFromRotation90(swipe: Swipe): Swipe {
      return Swipe(rotate90Degrees(swipe.direction), swipe.touchCount)
    }

    @JvmStatic
    fun createFromUpSideDown(swipe: Swipe): Swipe {
      return Swipe(turnDirectionUpsideDown(swipe.direction), swipe.touchCount)
    }

    @JvmStatic
    fun createFromMirror(swipe: Swipe): Swipe {
      return Swipe(turnDirectionMirror(swipe.direction), swipe.touchCount)
    }

    private fun rotate90Degrees(oldDirection: Direction): Direction {
      if (oldDirection == Direction.UP) {
        return Direction.RIGHT
      } else if (oldDirection == Direction.DOWN) {
        return Direction.LEFT
      } else if (oldDirection == Direction.LEFT) {
        return Direction.UP
      } else if (oldDirection == Direction.RIGHT) {
        return Direction.DOWN
      }
      throw IllegalArgumentException("unknown direction " + oldDirection)
    }

    private fun turnDirectionUpsideDown(oldDirection: Direction): Direction {
      if (oldDirection == Direction.UP) {
        return Direction.DOWN
      } else if (oldDirection == Direction.DOWN) {
        return Direction.UP
      }
      return oldDirection
    }

    private fun turnDirectionMirror(oldDirection: Direction): Direction {
      if (oldDirection == Direction.LEFT) {
        return Direction.RIGHT
      } else if (oldDirection == Direction.RIGHT) {
        return Direction.LEFT
      }
      return oldDirection
    }
  }
}
