/*
 * Copyright (C) 2012 Google Inc.
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

package com.googlecode.eyesfree.brailleback

import android.view.accessibility.AccessibilityEvent
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.googlecode.eyesfree.braille.display.BrailleInputEvent

/**
 * A way of navigating the android user interface.
 */
interface NavigationMode {
    fun onActivate()
    fun onDeactivate()
    fun onObserveAccessibilityEvent(event: AccessibilityEvent)
    fun onAccessibilityEvent(event: AccessibilityEvent): Boolean
    fun onInvalidateAccessibilityNode(node: AccessibilityNodeInfoCompat)
    fun onPanLeftOverflow(content: DisplayManager.Content): Boolean
    fun onPanRightOverflow(content: DisplayManager.Content): Boolean
    fun onMappedInputEvent(event: BrailleInputEvent, content: DisplayManager.Content): Boolean
}
