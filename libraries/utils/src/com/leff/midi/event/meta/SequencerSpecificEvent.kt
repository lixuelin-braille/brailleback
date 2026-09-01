//
//  Copyright 2011 Alex Leffelman
//  Licensed under the Apache License, Version 2.0

package com.leff.midi.event.meta

import com.leff.midi.event.MidiEvent
import com.leff.midi.util.MidiUtil
import com.leff.midi.util.VariableLengthInt
import java.io.IOException
import java.io.OutputStream

class SequencerSpecificEvent(tick: Long, delta: Long, data: ByteArray) :
    MetaEvent(tick, delta, MetaEvent.SEQUENCER_SPECIFIC, VariableLengthInt(data.size)) {
    private var mData: ByteArray = data

    fun setData(data: ByteArray) {
        mData = data
        mLength.setValue(mData.size)
    }

    fun getData(): ByteArray = mData

    override fun getEventSize(): Int {
        return 1 + 1 + mLength.getByteCount() + mData.size
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

        if (other !is SequencerSpecificEvent) {
            return 1
        }

        val o = other as SequencerSpecificEvent
        return if (MidiUtil.bytesEqual(mData, o.mData, 0, mData.size)) 0 else 1
    }
}
