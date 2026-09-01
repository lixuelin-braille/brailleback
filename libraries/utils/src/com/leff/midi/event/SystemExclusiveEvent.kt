//
//  Copyright 2011 Alex Leffelman
//  Licensed under the Apache License, Version 2.0

package com.leff.midi.event

import com.leff.midi.util.VariableLengthInt
import java.io.IOException
import java.io.OutputStream

/**
 * Full Disclosure, SysEx events may not be properly handled by this library.
 */
class SystemExclusiveEvent : MidiEvent {
    private var mType: Int
    private var mLength: VariableLengthInt
    private var mData: ByteArray

    constructor(type: Int, tick: Long, data: ByteArray) : this(type, tick, 0L, data)

    constructor(type: Int, tick: Long, delta: Long, data: ByteArray) : super(tick, delta) {
        mType = type and 0xFF
        if (mType != 0xF0 && mType != 0xF7) {
            mType = 0xF0
        }

        mLength = VariableLengthInt(data.size)
        mData = data
    }

    fun getData(): ByteArray = mData

    fun setData(data: ByteArray) {
        mLength.setValue(data.size)
        mData = data
    }

    override fun requiresStatusByte(prevEvent: MidiEvent?): Boolean = true

    override fun writeToFile(out: OutputStream, writeType: Boolean) {
        super.writeToFile(out, writeType)

        out.write(mType)
        out.write(mLength.getBytes())
        out.write(mData)
    }

    override fun compareTo(other: MidiEvent): Int {
        if (this.mTick < other.mTick) {
            return -1
        }
        if (this.mTick > other.mTick) {
            return 1
        }

        if (this.mDelta.getValue() > other.mDelta.getValue()) {
            return -1
        }
        if (this.mDelta.getValue() < other.mDelta.getValue()) {
            return 1
        }

        if (other is SystemExclusiveEvent) {
            val curr = String(mData)
            val comp = String((other as SystemExclusiveEvent).mData)
            return curr.compareTo(comp)
        }
        return 1
    }

    override fun getEventSize(): Int {
        return 1 + mLength.getByteCount() + mData.size
    }
}
