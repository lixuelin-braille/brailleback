//
//  Copyright 2011 Alex Leffelman
//  Licensed under the Apache License, Version 2.0

package com.leff.midi.event.meta

import com.leff.midi.event.MidiEvent
import java.io.IOException
import java.io.OutputStream

class GenericMetaEvent private constructor(
    tick: Long, delta: Long,
    @JvmField var mData: ByteArray,
    type: Int, length: com.leff.midi.util.VariableLengthInt
) : MetaEvent(tick, delta, type, length) {

    constructor(tick: Long, delta: Long, info: MetaEventData) :
        this(tick, delta, info.data, info.type, info.length) {
        println("Warning: GenericMetaEvent used because type (" + info.type +
            ") wasn't recognized or unexpected data length (" + info.length.getValue() + ") for type.")
    }

    override fun getEventSize(): Int {
        return 1 + 1 + mLength.getByteCount() + mLength.getValue()
    }

    @Throws(IOException::class)
    override fun writeToFile(out: OutputStream) {
        super.writeToFile(out)
        out.write(mLength.getBytes())
        out.write(mData)
    }

    override fun compareTo(other: MidiEvent): Int {
        if (mTick != other.getTick()) {
            return if (mTick < other.getTick()) -1 else 1
        }
        if (mDelta.getValue() != other.getDelta().toInt()) {
            return if (mDelta.getValue() < other.getDelta().toInt()) 1 else -1
        }
        return 1
    }
}
