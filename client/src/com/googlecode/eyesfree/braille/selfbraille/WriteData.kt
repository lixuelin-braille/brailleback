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

package com.googlecode.eyesfree.braille.selfbraille

import android.os.Bundle
import android.os.Parcel
import android.os.Parcelable
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Represents what should be shown on the braille display for a
 * part of the accessibility node tree.
 */
class WriteData private constructor(
    var accessibilityNodeInfo: AccessibilityNodeInfo?,
    var text: CharSequence?,
    private var properties: Bundle
) : Parcelable {

    constructor() : this(null, null, Bundle.EMPTY)

    private constructor(`in`: Parcel) : this(
        AccessibilityNodeInfo.CREATOR.createFromParcel(`in`),
        `in`.readString(),
        `in`.readBundle()!!
    )

    val selectionStart: Int
        get() = properties.getInt(PROP_SELECTION_START, -1)

    val selectionEnd: Int
        get() = properties.getInt(PROP_SELECTION_END, -1)

    fun setSelectionStart(v: Int): WriteData {
        writableProperties().putInt(PROP_SELECTION_START, v)
        return this
    }

    fun setSelectionEnd(v: Int): WriteData {
        writableProperties().putInt(PROP_SELECTION_END, v)
        return this
    }

    private fun writableProperties(): Bundle {
        if (properties == Bundle.EMPTY) {
            properties = Bundle()
        }
        return properties
    }

    /**
     * Checks constraints on the fields that must be satisfied before sending
     * this instance to the self braille service.
     * @throws IllegalStateException
     */
    fun validate() {
        if (accessibilityNodeInfo == null) {
            throw IllegalStateException("Accessibility node info can't be null")
        }
        val selStart = selectionStart
        val selEnd = selectionEnd
        if (text == null) {
            if (selStart > 0 || selEnd > 0) {
                throw IllegalStateException("Selection can't be set without text")
            }
        } else {
            if (selStart < 0 && selEnd >= 0) {
                throw IllegalStateException("Selection end without start")
            }
            val textLength = text!!.length
            if (selStart > textLength || selEnd > textLength) {
                throw IllegalStateException("Selection out of bounds")
            }
        }
    }

    override fun describeContents(): Int = 0

    override fun writeToParcel(out: Parcel, flags: Int) {
        accessibilityNodeInfo!!.writeToParcel(out, flags)
        // The above call recycles the node, so make sure we don't use it
        // anymore.
        accessibilityNodeInfo = null
        out.writeString(text?.toString())
        out.writeBundle(properties)
    }

    companion object {
        private val PROP_SELECTION_START = "selectionStart"
        private val PROP_SELECTION_END = "selectionEnd"

        /**
         * Returns a new [WriteData] instance for the given [view].
         */
        @JvmStatic
        fun forView(view: View): WriteData {
            val node = AccessibilityNodeInfo.obtain(view)
            val writeData = WriteData()
            writeData.accessibilityNodeInfo = node
            return writeData
        }

        @JvmField
        val CREATOR: Parcelable.Creator<WriteData> =
            object : Parcelable.Creator<WriteData> {
                override fun createFromParcel(`in`: Parcel): WriteData = WriteData(`in`)
                override fun newArray(size: Int): Array<WriteData?> = arrayOfNulls(size)
            }
    }
}
