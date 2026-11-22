/*
 * Copyright 2025 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
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
import com.google.android.accessibility.braille.interfaces.BrailleCharacter
import com.google.android.accessibility.brailleime.R
import java.util.Objects

/** A gesture that is not assigned to any action. */
class UnassignedGesture : Gesture {
  @Nullable
  override fun getSwipe(): Swipe? {
    return null
  }

  @Nullable
  override fun getHeldDots(): BrailleCharacter? {
    return null
  }

  @Nullable
  override fun mirrorDots(): Gesture? {
    return null
  }

  override fun getDescription(resources: Resources): String {
    return resources.getString(R.string.bk_gesture_unassigned)
  }

  override fun getId(): String {
    return ID
  }

  override fun equals(@Nullable obj: Any?): Boolean {
    val that = obj as? UnassignedGesture ?: return false
    return that.getId() == getId()
  }

  override fun hashCode(): Int {
    return Objects.hashCode(getId())
  }

  companion object {
    private const val ID = "unassigned"
  }
}
