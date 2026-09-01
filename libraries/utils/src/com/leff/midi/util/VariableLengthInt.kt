//
//  Copyright 2011 Alex Leffelman
//  Licensed under the Apache License, Version 2.0

package com.leff.midi.util

import java.io.IOException
import java.io.InputStream

class VariableLengthInt {
    private var mValue = 0
    private var mBytes = ByteArray(0)
    private var mSizeInBytes = 0

    constructor(value: Int) {
        setValue(value)
    }

    constructor(inputStream: InputStream) {
        parseBytes(inputStream)
    }

    fun setValue(value: Int) {
        mValue = value
        buildBytes()
    }

    fun getValue(): Int = mValue

    fun getByteCount(): Int = mSizeInBytes

    fun getBytes(): ByteArray = mBytes

    private fun parseBytes(inputStream: InputStream) {
        val ints = IntArray(4)

        mSizeInBytes = 0
        mValue = 0
        var shift = 0

        var b = inputStream.read()
        while (mSizeInBytes < 4) {
            mSizeInBytes++

            val variable = (b and 0x80) > 0
            if (!variable) {
                ints[mSizeInBytes - 1] = b and 0x7F
                break
            }
            ints[mSizeInBytes - 1] = b and 0x7F

            b = inputStream.read()
        }

        for (i in 1 until mSizeInBytes) {
            shift += 7
        }

        mBytes = ByteArray(mSizeInBytes)
        for (i in 0 until mSizeInBytes) {
            mBytes[i] = ints[i].toByte()

            mValue += ints[i] shl shift
            shift -= 7
        }
    }

    private fun buildBytes() {
        if (mValue == 0) {
            mBytes = byteArrayOf(0x00)
            mSizeInBytes = 1
            return
        }

        mSizeInBytes = 0
        val vals = IntArray(4)
        var tmpVal = mValue

        while (mSizeInBytes < 4 && tmpVal > 0) {
            vals[mSizeInBytes] = tmpVal and 0x7F

            mSizeInBytes++
            tmpVal = tmpVal shr 7
        }

        for (i in 1 until mSizeInBytes) {
            vals[i] = vals[i] or 0x80
        }

        mBytes = ByteArray(mSizeInBytes)
        for (i in 0 until mSizeInBytes) {
            mBytes[i] = vals[mSizeInBytes - i - 1].toByte()
        }
    }

    override fun toString(): String {
        return MidiUtil.bytesToHex(mBytes) + " (" + mValue + ")"
    }
}
