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
import java.util.Locale

/**
 * Meta-data for a braille translation table.
 *
 * Migrated to Kotlin as an idiomatic property class (no Java-style getters);
 * callers use `tableInfo.locale` / `tableInfo.isEightDot` etc.
 */
class TableInfo : Parcelable {
    /** The locale for which this table is typically used. */
    val locale: Locale
    /** `true` if this is an 8 dot (computer braille) table. */
    val isEightDot: Boolean
    /** The contraction 'grade' for this table. Lower grades mean fewer contractions. */
    val grade: Int
    /** The unique, stable identifier for this table. */
    val id: String?
    /** The variant description, used to distinguish similar tables. */
    val variant: String

    constructor(
        locale: Locale,
        isEightDot: Boolean,
        grade: Int,
        id: String?,
        variant: String?
    ) {
        this.locale = locale
        this.isEightDot = isEightDot
        this.grade = grade
        this.id = id
        this.variant = variant ?: ""
    }

    private constructor(parcel: Parcel) : this(
        Locale(parcel.readString(), parcel.readString(), parcel.readString()),
        parcel.readInt() != 0,
        parcel.readInt(),
        parcel.readString(),
        parcel.readString()
    )

    override fun describeContents(): Int = 0

    override fun writeToParcel(out: Parcel, flags: Int) {
        out.writeString(locale.language)
        out.writeString(locale.country)
        out.writeString(locale.variant)
        out.writeInt(if (isEightDot) 1 else 0)
        out.writeInt(grade)
        out.writeString(id)
        out.writeString(variant)
    }

    companion object {
        @JvmField
        val CREATOR: Parcelable.Creator<TableInfo> = object : Parcelable.Creator<TableInfo> {
            override fun createFromParcel(parcel: Parcel): TableInfo = TableInfo(parcel)

            override fun newArray(size: Int): Array<TableInfo?> = arrayOfNulls(size)
        }
    }
}
