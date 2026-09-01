//
//  Copyright 2011 Alex Leffelman
//  Licensed under the Apache License, Version 2.0

package com.leff.midi.event.meta

import com.leff.midi.event.MidiEvent
import com.leff.midi.util.VariableLengthInt
import java.io.IOException
import java.io.OutputStream

class SequenceNumber(tick: Long, delta: Long, number: Int) :
    MetaEvent(tick, delta, MetaEvent.SEQUENCE_NUMBER, VariableLengthInt(2)) {
    private var mNumber: Int = number

    fun getMostSignificantBits(): Int = mNumber shr 8

    fun getLeastSignificantBits(): Int = mNumber and 0xFF

    fun getSequenceNumber(): Int = mNumber

    @Throws(IOException::class)
    override fun writeToFile(out: OutputStream) {
        super.writeToFile(out)

        out.write(2)
        out.write(getMostSignificantBits())
        out.write(getLeastSignificantBits())
    }

    companion object {
        @JvmStatic
        fun parseSequenceNumber(tick: Long, delta: Long, info: MetaEventData): MetaEvent {
            if (info.length.getValue() != 2) {
                return GenericMetaEvent(tick, delta, info)
            }
            val msb = info.data[0].toInt()
            val lsb = info.data[1].toInt()
            val number = (msb shl 8) + lsb
            return SequenceNumber(tick, delta, number)
        }
    }

    override fun getEventSize(): Int = 5

    override fun compareTo(other: MidiEvent): Int {
        if (mTick != other.getTick()) {
            return if (mTick < other.getTick()) -1 else 1
        }
        if (mDelta.getValue() != other.getDelta().toInt()) {
            return if (mDelta.getValue() < other.getDelta().toInt()) 1 else -1
        }
        if (other !is SequenceNumber) {
            return 1
        }
        val o = other as SequenceNumber
        if (mNumber != o.mNumber) {
            return if (mNumber < o.mNumber) -1 else 1
        }
        return 0
    }
}
