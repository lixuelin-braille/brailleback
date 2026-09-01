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

/**
 * Represents a binding between a combination of braille device keys and a
 * command as declared in [BrailleInputEvent].
 */
class BrailleKeyBinding : Parcelable {
    private val FLAG_LONG_PRESS = 0x00000001

    var command: Int = 0
    var keyNames: Array<String> = emptyArray()
    private var flags: Int = 0

    var isLongPress: Boolean
        get() = flags and FLAG_LONG_PRESS != 0
        set(value) {
            flags = if (value) {
                flags or FLAG_LONG_PRESS
            } else {
                flags and FLAG_LONG_PRESS.inv()
            }
        }

    constructor()

    constructor(command: Int, keyNames: Array<String>?, longPress: Boolean) {
        this.command = command
        this.keyNames = keyNames ?: emptyArray()
        this.flags = if (longPress) FLAG_LONG_PRESS else 0
    }

    /**
     * Constructor used by the native BRLTTY driver (BrlttyWrapper.c).
     * `unifiedKeyBinding` is a TalkBack concept that BrailleBack does
     * not use; it is accepted only to match the native JNI contract.
     */
    @Suppress("UNUSED_PARAMETER")
    constructor(
        command: Int,
        keyNames: Array<String>?,
        longPress: Boolean,
        unifiedKeyBinding: Boolean
    ) : this(command, keyNames, longPress)

    private constructor(`in`: Parcel) {
        command = `in`.readInt()
        keyNames = `in`.createStringArray() ?: emptyArray()
        flags = `in`.readInt()
    }

    override fun describeContents(): Int = 0

    override fun writeToParcel(out: Parcel, flags: Int) {
        out.writeInt(command)
        out.writeStringArray(keyNames)
        out.writeInt(this.flags)
    }

    companion object {
        @JvmField
        val CREATOR: Parcelable.Creator<BrailleKeyBinding> =
            object : Parcelable.Creator<BrailleKeyBinding> {
                override fun createFromParcel(`in`: Parcel): BrailleKeyBinding =
                    BrailleKeyBinding(`in`)

                override fun newArray(size: Int): Array<BrailleKeyBinding?> =
                    arrayOfNulls(size)
            }
    }
}
