/*
 * Copyright (C) 2010 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.googlecode.eyesfree.utils

import android.content.Context
import android.text.SpannableStringBuilder
import android.text.TextUtils
import android.view.accessibility.AccessibilityEvent

/**
 * This class contains utility methods.
 *
 * @author svetoslavganov@google.com (Svetoslav R. Ganov)
 * @author alanv@google.com (Alan Viverette)
 */
object AccessibilityEventUtils {
    /**
     * Determines if the generating class of an [AccessibilityEvent] is an
     * instance of a given [Class].
     *
     * @param context The application context.
     * @param event An [AccessibilityEvent] dispatched by the accessibility
     *            framework.
     * @param referenceClassName The name of a [Class] to match by type or
     *            inherited type.
     * @return `true` if the [AccessibilityEvent] object matches the [Class] by
     *         type or inherited type, `false` otherwise.
     */
    @JvmStatic
    fun eventMatchesClass(
        context: Context,
        event: AccessibilityEvent?,
        referenceClassName: String?
    ): Boolean {
        if (event == null) {
            return false
        }
        val loader = ClassLoadingManager.getInstance()
        val eventClassName = event.className
        val appPackage = event.packageName
        return loader.checkInstanceOf(context, eventClassName, appPackage, referenceClassName)
    }

    /**
     * Determines if an accessibility event is of a type defined by a mask of
     * qualifying event types.
     *
     * @param event The event to evaluate
     * @param typeMask A mask of event types that will cause this method to
     *            accept the event as matching
     * @return `true` if [event]'s type is one of types defined in [typeMask],
     *         `false` otherwise
     */
    @JvmStatic
    fun eventMatchesAnyType(event: AccessibilityEvent?, typeMask: Int): Boolean {
        if (event == null) {
            return false
        }
        return (event.eventType and typeMask) != 0
    }

    /**
     * Gets the text of an `event` by returning the content description (if
     * available) or by concatenating the text members (regardless of their
     * priority) using space as a delimiter.
     *
     * @param event The event.
     * @return The event text.
     */
    @JvmStatic
    fun getEventTextOrDescription(event: AccessibilityEvent?): CharSequence? {
        if (event == null) {
            return null
        }
        val contentDescription = event.contentDescription
        if (!TextUtils.isEmpty(contentDescription)) {
            return contentDescription
        }
        return getEventAggregateText(event)
    }

    /**
     * Gets the text of an `event` by concatenating the text members (regardless
     * of their priority) using space as a delimiter.
     *
     * @param event The event.
     * @return The event text.
     */
    @JvmStatic
    fun getEventAggregateText(event: AccessibilityEvent?): CharSequence? {
        if (event == null) {
            return null
        }
        val aggregator = SpannableStringBuilder()
        val eventText = event.text
        val it = eventText.iterator()
        while (it.hasNext()) {
            StringBuilderUtils.appendWithSeparator(aggregator, it.next())
        }
        return aggregator
    }

    /**
     * @return If the `first` event is equal to the `second`.
     */
    @JvmStatic
    fun eventEquals(first: AccessibilityEvent?, second: AccessibilityEvent?): Boolean {
        // TODO: The framework should implement AccessibilityEvent#equals()
        if (first == null || second == null) {
            return false
        }
        if (first.eventType != second.eventType) {
            return false
        }
        if (first.packageName == null) {
            if (second.packageName != null) {
                return false
            }
        } else if (first.packageName != second.packageName) {
            return false
        }
        if (first.className == null) {
            if (second.className != null) {
                return false
            }
        } else if (first.className != second.className) {
            return false
        }
        // The result of getText() is never null.
        if (first.text != second.text) {
            return false
        }
        if (first.contentDescription == null) {
            if (second.contentDescription != null) {
                return false
            }
        } else if (first.contentDescription != second.contentDescription) {
            return false
        }
        if (first.beforeText == null) {
            if (second.beforeText != null) {
                return false
            }
        } else if (first.beforeText != second.beforeText) {
            return false
        }
        if (first.parcelableData != null) {
            // Parcelable data may not implement equals() correctly.
            return false
        }
        if (first.addedCount != second.addedCount) {
            return false
        }
        if (first.isChecked != second.isChecked) {
            return false
        }
        if (first.isEnabled != second.isEnabled) {
            return false
        }
        if (first.fromIndex != second.fromIndex) {
            return false
        }
        if (first.isFullScreen != second.isFullScreen) {
            return false
        }
        if (first.currentItemIndex != second.currentItemIndex) {
            return false
        }
        if (first.itemCount != second.itemCount) {
            return false
        }
        if (first.isPassword != second.isPassword) {
            return false
        }
        if (first.removedCount != second.removedCount) {
            return false
        }
        if (first.eventTime != second.eventTime) {
            return false
        }
        return true
    }
}
