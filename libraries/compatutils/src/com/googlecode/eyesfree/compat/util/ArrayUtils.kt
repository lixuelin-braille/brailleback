/*
 * Copyright (C) 2006 The Android Open Source Project
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

package com.googlecode.eyesfree.compat.util


/**
 * ArrayUtils contains some methods that you can call to find out the most
 * efficient increments by which to grow arrays.
 *
 * Pulled from //frameworks/base/core/java/com/android/internal/util on
 * 6/11/2013 by caseyburkhardt@google.com.
 */
object ArrayUtils {
    private val EMPTY = arrayOfNulls<Any>(0)
    private const val CACHE_SIZE = 73
    private val sCache = arrayOfNulls<Any>(CACHE_SIZE)

    @JvmStatic
    fun idealByteArraySize(need: Int): Int {
        for (i in 4 until 32) {
            if (need <= (1 shl i) - 12) {
                return (1 shl i) - 12
            }
        }
        return need
    }

    @JvmStatic
    fun idealBooleanArraySize(need: Int): Int = idealByteArraySize(need)

    @JvmStatic
    fun idealShortArraySize(need: Int): Int = idealByteArraySize(need * 2) / 2

    @JvmStatic
    fun idealCharArraySize(need: Int): Int = idealByteArraySize(need * 2) / 2

    @JvmStatic
    fun idealIntArraySize(need: Int): Int = idealByteArraySize(need * 4) / 4

    @JvmStatic
    fun idealFloatArraySize(need: Int): Int = idealByteArraySize(need * 4) / 4

    @JvmStatic
    fun idealObjectArraySize(need: Int): Int = idealByteArraySize(need * 4) / 4

    @JvmStatic
    fun idealLongArraySize(need: Int): Int = idealByteArraySize(need * 8) / 8

    /**
     * Checks if the beginnings of two byte arrays are equal.
     *
     * @param array1 the first byte array
     * @param array2 the second byte array
     * @param length the number of bytes to check
     * @return true if they're equal, false otherwise
     */
    @JvmStatic
    fun equals(array1: ByteArray?, array2: ByteArray?, length: Int): Boolean {
        require(length >= 0)
        if (array1 === array2) {
            return true
        }
        if (array1 == null || array2 == null ||
            array1.size < length || array2.size < length
        ) {
            return false
        }
        for (i in 0 until length) {
            if (array1[i] != array2[i]) {
                return false
            }
        }
        return true
    }

    /**
     * Returns an empty array of the specified type.  The intent is that it
     * will return the same empty array every time to avoid reallocation,
     * although this is not guaranteed.
     */
    @JvmStatic
    @Suppress("UNCHECKED_CAST")
    fun <T> emptyArray(kind: Class<T>): Array<T> {
        if (kind == Any::class.java) {
            return EMPTY as Array<T>
        }
        val bucket = (System.identityHashCode(kind) / 8 and 0x7FFFFFFF) % CACHE_SIZE
        var cache = sCache[bucket]
        if (cache == null || cache.javaClass.componentType != kind) {
            cache = java.lang.reflect.Array.newInstance(kind, 0)
            sCache[bucket] = cache
        }
        return cache as Array<T>
    }

    /**
     * Checks that value is present as at least one of the elements of the array.
     *
     * @param array the array to check in
     * @param value the value to check for
     * @return true if the value is present in the array
     */
    @JvmStatic
    fun <T> contains(array: Array<T>, value: T): Boolean = indexOf(array, value) != -1

    /**
     * Return first index of [value] in [array], or `-1` if not found.
     */
    @JvmStatic
    fun <T> indexOf(array: Array<T>, value: T): Int {
        for (i in array.indices) {
            if (array[i] == null) {
                if (value == null) {
                    return i
                }
            } else {
                if (value != null && array[i] == value) {
                    return i
                }
            }
        }
        return -1
    }

    /**
     * Test if all [check] items are contained in [array].
     */
    @JvmStatic
    fun <T> containsAll(array: Array<T>, check: Array<T>): Boolean {
        for (checkItem in check) {
            if (!contains(array, checkItem)) {
                return false
            }
        }
        return true
    }

    @JvmStatic
    fun contains(array: IntArray, value: Int): Boolean {
        for (element in array) {
            if (element == value) {
                return true
            }
        }
        return false
    }

    @JvmStatic
    fun total(array: LongArray): Long {
        var total = 0L
        for (value in array) {
            total += value
        }
        return total
    }

    /**
     * Appends an element to a copy of the array and returns the copy.
     *
     * @param array The original array, or null to represent an empty array.
     * @param element The element to add.
     * @return A new array that contains all of the elements of the original
     *         array with the specified element added at the end.
     */
    @JvmStatic
    @Suppress("UNCHECKED_CAST")
    fun <T> appendElement(kind: Class<T>, array: Array<T>?, element: T): Array<T> {
        val end: Int
        val result: Array<T>
        if (array != null) {
            end = array.size
            result = java.lang.reflect.Array.newInstance(kind, end + 1) as Array<T>
            System.arraycopy(array, 0, result, 0, end)
        } else {
            end = 0
            result = java.lang.reflect.Array.newInstance(kind, 1) as Array<T>
        }
        result[end] = element
        return result
    }

    /**
     * Removes an element from a copy of the array and returns the copy. If the
     * element is not present, then the original array is returned unmodified.
     *
     * @param array The original array, or null to represent an empty array.
     * @param element The element to remove.
     * @return A new array that contains all of the elements of the original
     *         array except the first copy of the specified element removed.
     *         If the specified element was not present, then returns the
     *         original array. Returns null if the result would be an empty
     *         array.
     */
    @JvmStatic
    @Suppress("UNCHECKED_CAST")
    fun <T> removeElement(kind: Class<T>, array: Array<T>?, element: T): Array<T>? {
        if (array != null) {
            val length = array.size
            for (i in 0 until length) {
                if (array[i] == element) {
                    if (length == 1) {
                        return null
                    }
                    val result = java.lang.reflect.Array.newInstance(kind, length - 1) as Array<T>
                    System.arraycopy(array, 0, result, 0, i)
                    System.arraycopy(array, i + 1, result, i, length - i - 1)
                    return result
                }
            }
        }
        return array
    }

    @JvmStatic
    fun appendInt(cur: IntArray?, value: Int): IntArray {
        if (cur == null) {
            return intArrayOf(value)
        }
        val n = cur.size
        for (i in 0 until n) {
            if (cur[i] == value) {
                return cur
            }
        }
        val ret = IntArray(n + 1)
        System.arraycopy(cur, 0, ret, 0, n)
        ret[n] = value
        return ret
    }

    @JvmStatic
    fun removeInt(cur: IntArray?, value: Int): IntArray? {
        if (cur == null) {
            return null
        }
        val n = cur.size
        for (i in 0 until n) {
            if (cur[i] == value) {
                val ret = IntArray(n - 1)
                if (i > 0) {
                    System.arraycopy(cur, 0, ret, 0, i)
                }
                if (i < n - 1) {
                    System.arraycopy(cur, i + 1, ret, i, n - i - 1)
                }
                return ret
            }
        }
        return cur
    }
}
