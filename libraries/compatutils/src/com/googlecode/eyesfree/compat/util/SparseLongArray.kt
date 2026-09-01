/*
 * Copyright (C) 2011 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.googlecode.eyesfree.compat.util

/**
 * SparseLongArrays map integers to longs. Unlike a normal array of longs, there
 * can be gaps in the indices. It is intended to be more efficient than using a
 * HashMap to map Integers to Longs.
 */
class SparseLongArray : Cloneable {
    private var mKeys: IntArray
    private var mValues: LongArray
    private var mSize: Int

    constructor() : this(10)

    constructor(initialCapacity: Int) {
        val cap = ArrayUtils.idealLongArraySize(initialCapacity)
        mKeys = IntArray(cap)
        mValues = LongArray(cap)
        mSize = 0
    }

    public override fun clone(): SparseLongArray {
        val clone: SparseLongArray = try {
            super.clone() as SparseLongArray
        } catch (cnse: CloneNotSupportedException) {
            SparseLongArray()
        }
        clone.mKeys = mKeys.clone()
        clone.mValues = mValues.clone()
        return clone
    }

    fun get(key: Int): Long = get(key, 0)

    fun get(key: Int, valueIfKeyNotFound: Long): Long {
        val i = binarySearch(mKeys, 0, mSize, key.toLong())
        return if (i < 0) valueIfKeyNotFound else mValues[i]
    }

    fun delete(key: Int) {
        val i = binarySearch(mKeys, 0, mSize, key.toLong())
        if (i >= 0) {
            removeAt(i)
        }
    }

    fun removeAt(index: Int) {
        System.arraycopy(mKeys, index + 1, mKeys, index, mSize - (index + 1))
        System.arraycopy(mValues, index + 1, mValues, index, mSize - (index + 1))
        mSize--
    }

    fun put(key: Int, value: Long) {
        var i = binarySearch(mKeys, 0, mSize, key.toLong())
        if (i >= 0) {
            mValues[i] = value
        } else {
            i = i.inv()
            if (mSize >= mKeys.size) {
                growKeyAndValueArrays(mSize + 1)
            }
            if (mSize - i != 0) {
                System.arraycopy(mKeys, i, mKeys, i + 1, mSize - i)
                System.arraycopy(mValues, i, mValues, i + 1, mSize - i)
            }
            mKeys[i] = key
            mValues[i] = value
            mSize++
        }
    }

    fun size(): Int = mSize

    fun keyAt(index: Int): Int = mKeys[index]

    fun valueAt(index: Int): Long = mValues[index]

    fun indexOfKey(key: Int): Int = binarySearch(mKeys, 0, mSize, key.toLong())

    fun indexOfValue(value: Long): Int {
        for (i in 0 until mSize) {
            if (mValues[i] == value) {
                return i
            }
        }
        return -1
    }

    fun clear() {
        mSize = 0
    }

    fun append(key: Int, value: Long) {
        if (mSize != 0 && key <= mKeys[mSize - 1]) {
            put(key, value)
            return
        }
        val pos = mSize
        if (pos >= mKeys.size) {
            growKeyAndValueArrays(pos + 1)
        }
        mKeys[pos] = key
        mValues[pos] = value
        mSize = pos + 1
    }

    private fun growKeyAndValueArrays(minNeededSize: Int) {
        val n = ArrayUtils.idealLongArraySize(minNeededSize)
        val nkeys = IntArray(n)
        val nvalues = LongArray(n)
        System.arraycopy(mKeys, 0, nkeys, 0, mKeys.size)
        System.arraycopy(mValues, 0, nvalues, 0, mValues.size)
        mKeys = nkeys
        mValues = nvalues
    }

    private fun binarySearch(a: IntArray, start: Int, len: Int, key: Long): Int {
        var high = start + len
        var low = start - 1
        var guess: Int
        while (high - low > 1) {
            guess = (high + low) / 2
            if (a[guess].toLong() < key) {
                low = guess
            } else {
                high = guess
            }
        }
        return if (high == start + len) {
            (start + len).inv()
        } else if (a[high].toLong() == key) {
            high
        } else {
            high.inv()
        }
    }
}
