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
 * License for specific language governing permissions and limitations under
 * the License.
 */

package com.googlecode.eyesfree.braille.translate

import android.os.Parcel
import android.os.Parcelable

/**
 * The result of translating text to braille, including character to cell
 * mappings in both directions.
 *
 * Migrated to Kotlin as an idiomatic data class; callers use `result.cells`
 * / `result.textToBraillePositions` etc.
 */
data class TranslationResult(
    /** The braille cells corresponding to the original text. */
    val cells: ByteArray,
    /** Maps a position in the original text to the corresponding braille cell. */
    val textToBraillePositions: IntArray,
    /** Maps a position in the braille cells to the corresponding text position. */
    val brailleToTextPositions: IntArray,
    /**
     * The cursor position corresponding to the cursor position specified when
     * translating the text, or -1 if there was no cursor position specified.
     */
    val cursorPosition: Int
) : Parcelable {
    constructor(parcel: Parcel) : this(
        parcel.createByteArray() ?: ByteArray(0),
        parcel.createIntArray() ?: IntArray(0),
        parcel.createIntArray() ?: IntArray(0),
        parcel.readInt()
    )

    override fun describeContents(): Int = 0

    override fun writeToParcel(out: Parcel, flags: Int) {
        out.writeByteArray(cells)
        out.writeIntArray(textToBraillePositions)
        out.writeIntArray(brailleToTextPositions)
        out.writeInt(cursorPosition)
    }

    companion object {
        @JvmField
        val CREATOR: Parcelable.Creator<TranslationResult> =
            object : Parcelable.Creator<TranslationResult> {
                override fun createFromParcel(parcel: Parcel): TranslationResult =
                    TranslationResult(parcel)

                override fun newArray(size: Int): Array<TranslationResult?> =
                    arrayOfNulls(size)
            }
    }
}
