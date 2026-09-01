//
//  Copyright 2011 Alex Leffelman
//  Licensed under the Apache License, Version 2.0

package com.leff.midi.event.meta

import com.leff.midi.event.MidiEvent
import com.leff.midi.util.VariableLengthInt
import java.io.IOException
import java.io.OutputStream

class MidiChannelPrefix(tick: Long, delta: Long, channel: Int) :
    MetaEvent(tick, delta, MetaEvent.MIDI_CHANNEL_PREFIX, VariableLengthInt(4)) {
    private var mChannel: Int = channel

    fun setChannel(c: Int) {
        mChannel = c
    }

    fun getChannel(): Int = mChannel

    override fun getEventSize(): Int = 4

    @Throws(IOException::class)
    override fun writeToFile(out: OutputStream) {
        super.writeToFile(out)

        out.write(1)
        out.write(mChannel)
    }

    companion object {
        @JvmStatic
        fun parseMidiChannelPrefix(tick: Long, delta: Long, info: MetaEventData): MetaEvent {
            if (info.length.getValue() != 1) {
                return GenericMetaEvent(tick, delta, info)
            }
            val channel = info.data[0].toInt()
            return MidiChannelPrefix(tick, delta, channel)
        }
    }

    override fun compareTo(other: MidiEvent): Int {
        if (mTick != other.getTick()) {
            return if (mTick < other.getTick()) -1 else 1
        }
        if (mDelta.getValue() != other.getDelta().toInt()) {
            return if (mDelta.getValue() < other.getDelta().toInt()) 1 else -1
        }
        if (other !is MidiChannelPrefix) {
            return 1
        }
        val o = other as MidiChannelPrefix
        if (mChannel != o.mChannel) {
            return if (mChannel < o.mChannel) -1 else 1
        }
        return 0
    }
}
