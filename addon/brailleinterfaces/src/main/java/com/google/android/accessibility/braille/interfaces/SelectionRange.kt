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

package com.google.android.accessibility.braille.interfaces

import java.util.Objects

/** Index of start and end of selected text. */
class SelectionRange(
  /** The start selection index. */
  @JvmField val start: Int,
  /** The end selection index. */
  @JvmField val end: Int,
) {
  init {
    if (start < NO_CURSOR) {
      throw IllegalArgumentException("Cursor index not supported: $start")
    }
    if (end < NO_CURSOR) {
      throw IllegalArgumentException("Cursor index not supported: $end")
    }
  }

  /** Gets the upper index within {@link SelectionRange#start} and {@link SelectionRange#end}. */
  fun getUpper(): Int {
    return Math.max(start, end)
  }

  /** Gets the lower index within {@link SelectionRange#start} and {@link SelectionRange#end}. */
  fun getLower(): Int {
    return Math.min(start, end)
  }

  override fun hashCode(): Int {
    return Objects.hash(start, end)
  }

  override fun equals(o: Any?): Boolean {
    if (this === o) {
      return true
    }
    if (o !is SelectionRange) {
      return false
    }
    val selection: SelectionRange = o
    return start == selection.start && end == selection.end
  }

  override fun toString(): String {
    return String.format("SelectionRange {start=%s, end=%s}", start, end)
  }

  companion object {
    private const val NO_CURSOR = -1
  }
}
