/*
 * Copyright (C) 2013 Google Inc.
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

package com.googlecode.eyesfree.compat.view.accessibility

object AccessibilityServiceInfoCompatUtils {
    const val FLAG_INCLUDE_NOT_IMPORTANT_VIEWS: Int = 0x0000002
    const val FLAG_REQUEST_TOUCH_EXPLORATION_MODE: Int = 0x0000004
    const val FLAG_REQUEST_ENHANCED_WEB_ACCESSIBILITY: Int = 0x00000008
    const val FLAG_REPORT_VIEW_IDS: Int = 0x00000010
    const val FLAG_REQUEST_FILTER_KEY_EVENTS: Int = 0x00000020
}
