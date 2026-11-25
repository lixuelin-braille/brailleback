/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
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

package com.linxi.brailleback

import android.text.TextUtils
import android.view.accessibility.AccessibilityEvent

/**
 * 无障碍事件单行摘要：从 [BrailleBackService] 伴生对象拆出的纯函数层。
 *
 * 只做事件字段的裁剪、拼接与长度截断，不持有服务状态、不触碰回调链路；
 * 方法体与拆分前逐字一致。
 */

/** Upper bound on the characters kept for one field of the summary. */
internal const val EVENT_TEXT_LIMIT = 120

/** Builds the single-line summary logged for every accessibility event. */
internal fun describeEvent(event: AccessibilityEvent): String {
    val summary = StringBuilder(96)
    summary.append(AccessibilityEvent.eventTypeToString(event.eventType))
    appendEventField(summary, "pkg", event.packageName)
    appendEventField(summary, "cls", event.className)
    appendEventField(summary, "text", joinEventText(event.text))
    appendEventField(summary, "desc", event.contentDescription)
    return summary.toString()
}

private fun appendEventField(
        summary: StringBuilder, name: String, value: CharSequence?) {
    if (TextUtils.isEmpty(value)) {
        return
    }
    summary.append(' ').append(name).append('=').append(clipEventText(value))
}

private fun joinEventText(parts: List<CharSequence>?): String? {
    if (parts == null || parts.isEmpty()) {
        return null
    }
    val joined = StringBuilder()
    for (part in parts) {
        if (TextUtils.isEmpty(part)) {
            continue
        }
        if (joined.length > 0) {
            joined.append(" | ")
        }
        joined.append(part)
    }
    return if (joined.length == 0) null else joined.toString()
}

private fun clipEventText(value: CharSequence?): String {
    val text = value.toString().replace('\n', ' ').replace('\r', ' ').trim()
    if (text.length <= EVENT_TEXT_LIMIT) {
        return text
    }
    return text.substring(0, EVENT_TEXT_LIMIT) + "..."
}
