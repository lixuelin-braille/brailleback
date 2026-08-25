/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 */

package com.googlecode.eyesfree.brailleback

import android.text.Spannable
import android.text.Spanned
import android.util.Log
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.googlecode.eyesfree.utils.LogUtils
import java.nio.ByteBuffer

/**
 * Static utilities for text spans that control how text is displayed on the
 * braille display.
 */
object DisplaySpans {

    /** Marks a part of the content that has focus. */
    class FocusSpan

    /** Marks a text selection or cursor on the display. */
    class SelectionSpan

    /** Associates a span of text with a specific braille translation. */
    class BrailleSpan {

        @JvmField
        val braille: ByteArray

        /**
         * Constructs a BrailleSpan whose `braille` member is populated from
         * the contents of a [ByteBuffer], from index 0 to the current
         * [ByteBuffer.position] (exclusive).
         */
        constructor(buffer: ByteBuffer) {
            val len = buffer.position()
            braille = ByteArray(len)
            // Copy buffer from [0, position) and then restore buffer state.
            buffer.position(0)
            buffer.get(braille, 0, len)
            buffer.position(len)
        }
    }

    /** Marks a region of `spanned` as having focus. */
    @JvmStatic
    fun addFocus(spannable: Spannable, start: Int, end: Int) {
        spannable.setSpan(FocusSpan(), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    /**
     * Marks a portion of `spannable` as containing text selection.  If `start`
     * and `end` are equal, then the added span marks a cursor.
     */
    @JvmStatic
    fun addSelection(spannable: Spannable, start: Int, end: Int) {
        val flags: Int
        if (start == end) {
            flags = Spanned.SPAN_EXCLUSIVE_INCLUSIVE
        } else {
            flags = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        }
        // If start and end are out of order... swap start and end. Required by setSpan().
        var s = start
        var e = end
        if (e < s) {
            val oldStart = s
            s = e
            e = oldStart
        }
        spannable.setSpan(SelectionSpan(), s, e, flags)
    }

    /**
     * Marks a portion of `spannable` as being represented by the cells in
     * `buffer` from [0, position). When rendering braille, this portion should
     * not be translated, but should be represented by the given cells.
     */
    @JvmStatic
    fun addBraille(spannable: Spannable, start: Int, end: Int, buffer: ByteBuffer) {
        spannable.setSpan(BrailleSpan(buffer), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    /**
     * Marks the whole of `spannable` as containing the content coming from
     * `node`.  A copy of `node` is stored.
     *
     * @see #recycleSpans
     */
    @JvmStatic
    fun setAccessibilityNode(spannable: Spannable, node: AccessibilityNodeInfoCompat) {
        spannable.setSpan(
            AccessibilityNodeInfoCompat.obtain(node),
            0, spannable.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    /**
     * Finds the shortest accessibility node span that overlaps `position` in
     * `chars`.  If a node is found, it is returned, otherwise `null` is
     * returned.  If a node is returned, it is still owned by `chars` for the
     * purpose of recycling.
     */
    @JvmStatic
    fun getAccessibilityNodeFromPosition(
        position: Int, chars: CharSequence
    ): AccessibilityNodeInfoCompat? {
        if (chars !is Spanned) {
            return null
        }
        val spans = chars.getSpans(position, position, AccessibilityNodeInfoCompat::class.java)
        if (spans.isEmpty()) {
            return null
        }
        var found = spans[0]
        var foundLength = chars.getSpanEnd(found) - chars.getSpanStart(found)
        for (i in 1 until spans.size) {
            val span = spans[i]
            val length = chars.getSpanEnd(span) - chars.getSpanStart(span)
            if (length < foundLength) {
                found = span
                foundLength = length
            }
        }
        return found
    }

    /** Utility function to log what accessibility nodes are attached to what parts. */
    @JvmStatic
    fun logNodes(chars: CharSequence) {
        if (chars !is Spanned) {
            LogUtils.log(DisplaySpans::class.java, Log.VERBOSE, "Not a Spanned")
            return
        }
        val spans = chars.getSpans(0, chars.length, AccessibilityNodeInfoCompat::class.java)
        for (node in spans) {
            LogUtils.log(
                DisplaySpans::class.java, Log.VERBOSE,
                chars.subSequence(chars.getSpanStart(node), chars.getSpanEnd(node)).toString())
            LogUtils.log(DisplaySpans::class.java, Log.VERBOSE, node.info.toString())
        }
    }

    /**
     * Recycles objects owned by the spannable.  In particular, any
     * accessibility nodes that have been associated with `spannable` are
     * recycled and removed.
     */
    @JvmStatic
    fun recycleSpans(chars: CharSequence) {
        if (chars !is Spannable) {
            return
        }
        val nodes = chars.getSpans(0, chars.length, AccessibilityNodeInfoCompat::class.java)
        for (node in nodes) {
            node.recycle()
            chars.removeSpan(node)
        }
    }

    /**
     * Returns a span in `spanned` that is [Object.equals] to `obj`.
     */
    @JvmStatic
    fun getEqualSpan(spanned: Spanned, obj: Any): Any? {
        val spans = spanned.getSpans(0, spanned.length, obj.javaClass)
        for (span in spans) {
            if (obj == span) {
                return span
            }
        }
        return null
    }
}
