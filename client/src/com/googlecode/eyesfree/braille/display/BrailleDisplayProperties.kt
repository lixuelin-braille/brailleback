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

package com.googlecode.eyesfree.braille.display

import android.os.Parcel
import android.os.Parcelable
import java.util.Collections
import java.util.HashMap

/**
 * Properties of a braille display such as dimensions and keyboard
 * configuration.
 */
class BrailleDisplayProperties(
    val numTextCells: Int,
    val numStatusCells: Int,
    val keyBindings: Array<BrailleKeyBinding>,
    val friendlyKeyNames: Map<String, String>
) : Parcelable {

    override fun toString(): String = String.format(
        "BrailleDisplayProperties [numTextCells: %d, numStatusCells: %d, " +
            "keyBindings: %d]",
        numTextCells, numStatusCells, keyBindings.size
    )

    override fun describeContents(): Int = 0

    override fun writeToParcel(out: Parcel, flags: Int) {
        out.writeInt(numTextCells)
        out.writeInt(numStatusCells)
        out.writeTypedArray(keyBindings, flags)
        out.writeInt(friendlyKeyNames.size)
        for ((key, value) in friendlyKeyNames) {
            out.writeString(key)
            out.writeString(value)
        }
    }

    private constructor(`in`: Parcel) : this(
        numTextCells = `in`.readInt(),
        numStatusCells = `in`.readInt(),
        keyBindings = `in`.createTypedArray(BrailleKeyBinding.CREATOR)!!,
        friendlyKeyNames = readFriendlyKeyNames(`in`)
    )

    companion object {
        private fun readFriendlyKeyNames(`in`: Parcel): Map<String, String> {
            val size = `in`.readInt()
            val names = HashMap<String, String>(size)
            for (i in 0 until size) {
                names[`in`.readString()!!] = `in`.readString()!!
            }
            return Collections.unmodifiableMap(names)
        }

        @JvmField
        val CREATOR: Parcelable.Creator<BrailleDisplayProperties> =
            object : Parcelable.Creator<BrailleDisplayProperties> {
                override fun createFromParcel(`in`: Parcel): BrailleDisplayProperties =
                    BrailleDisplayProperties(`in`)

                override fun newArray(size: Int): Array<BrailleDisplayProperties?> =
                    arrayOfNulls(size)
            }
    }
}
