/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 */

package com.googlecode.eyesfree.brailleback.utils

import android.text.TextUtils
import android.view.accessibility.AccessibilityEvent

/**
 * Utility methods for [AccessibilityEvent] (Kotlin 版).
 */
object AccessibilityEventUtils {

    /**
     * Gets the text of an `event` by returning the content description
     * (if available) or by concatenating the text members (regardless of their
     * priority) using space as a delimiter.
     *
     * @param event The event.
     * @return The event text.
     */
    @JvmStatic
    fun getEventText(event: AccessibilityEvent?): CharSequence? {
        if (event == null) {
            return null
        }

        val contentDescription = event.contentDescription

        return if (!TextUtils.isEmpty(contentDescription)) {
            contentDescription
        } else {
            getEventAggregateText(event)
        }
    }

    /**
     * Gets the text of an `event` by concatenating the text members
     * (regardless of their priority) using space as a delimiter.
     *
     * @param event The event.
     * @return The event text.
     */
    private fun getEventAggregateText(event: AccessibilityEvent): CharSequence {
        val aggregator = StringBuilder()
        val eventText = event.text
        val it = eventText.iterator()

        while (it.hasNext()) {
            val text: CharSequence = it.next()

            if (it.hasNext()) {
                StringUtils.appendWithSpaces(aggregator, text)
            } else {
                aggregator.append(text)
            }
        }

        return aggregator
    }
}
