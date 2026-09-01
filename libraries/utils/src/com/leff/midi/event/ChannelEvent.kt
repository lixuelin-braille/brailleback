//
//  Copyright 2011 Alex Leffelman
//  Licensed under the Apache License, Version 2.0

package com.leff.midi.event

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

open class ChannelEvent : MidiEvent {
    protected var mType: Int
    protected var mChannel: Int
    protected var mValue1: Int
    protected var mValue2: Int

    protected constructor(tick: Long, type: Int, channel: Int, param1: Int, param2: Int) :
        this(tick, 0L, type, channel, param1, param2)

    protected constructor(
        tick: Long, delta: Long, type: Int, channel: Int, param1: Int, param2: Int
    ) : super(tick, delta) {
        mType = type and 0x0F
        mChannel = channel and 0x0F
        mValue1 = param1 and 0xFF
        mValue2 = param2 and 0xFF
    }

    fun getType(): Int = mType

    fun setChannel(c: Int) {
        mChannel = c.coerceIn(0, 15)
    }

    fun getChannel(): Int = mChannel

    override fun getEventSize(): Int {
        return when (mType) {
            PROGRAM_CHANGE, CHANNEL_AFTERTOUCH -> 2
            else -> 3
        }
    }

    override fun compareTo(other: MidiEvent): Int {
        if (mTick != other.getTick()) {
            return if (mTick < other.getTick()) -1 else 1
        }
        if (mDelta.getValue() != other.mDelta.getValue()) {
            return if (mDelta.getValue() < other.mDelta.getValue()) 1 else -1
        }

        if (other !is ChannelEvent) {
            return 1
        }

        val o = other as ChannelEvent
        if (mType != o.getType()) {
            if (mOrderMap == null) {
                buildOrderMap()
            }
            val order1 = mOrderMap!![mType]!!
            val order2 = mOrderMap!![o.getType()]!!
            return if (order1 < order2) -1 else 1
        }
        if (mValue1 != o.mValue1) {
            return if (mValue1 < o.mValue1) -1 else 1
        }
        if (mValue2 != o.mValue2) {
            return if (mValue2 < o.mValue2) -1 else 1
        }
        if (mChannel != o.getChannel()) {
            return if (mChannel < o.getChannel()) -1 else 1
        }
        return 0
    }

    override fun requiresStatusByte(prevEvent: MidiEvent?): Boolean {
        if (prevEvent == null) {
            return true
        }
        if (prevEvent !is ChannelEvent) {
            return true
        }
        val ce = prevEvent as ChannelEvent
        return !(mType == ce.getType() && mChannel == ce.getChannel())
    }

    override fun writeToFile(out: OutputStream, writeType: Boolean) {
        super.writeToFile(out, writeType)

        if (writeType) {
            val typeChannel = (mType shl 4) + mChannel
            out.write(typeChannel)
        }

        out.write(mValue1)
        if (mType != PROGRAM_CHANGE && mType != CHANNEL_AFTERTOUCH) {
            out.write(mValue2)
        }
    }

    companion object {
        fun parseChannelEvent(
            tick: Long, delta: Long, type: Int, channel: Int, inputStream: InputStream
        ): ChannelEvent {
            var val1 = inputStream.read()
            var val2 = 0
            if (type != PROGRAM_CHANGE && type != CHANNEL_AFTERTOUCH) {
                val2 = inputStream.read()
            }

            return when (type) {
                NOTE_OFF -> NoteOff(tick, delta, channel, val1, val2)
                NOTE_ON -> NoteOn(tick, delta, channel, val1, val2)
                NOTE_AFTERTOUCH -> NoteAftertouch(tick, delta, channel, val1, val2)
                CONTROLLER -> Controller(tick, delta, channel, val1, val2)
                PROGRAM_CHANGE -> ProgramChange(tick, delta, channel, val1)
                CHANNEL_AFTERTOUCH -> ChannelAftertouch(tick, delta, channel, val1)
                PITCH_BEND -> PitchBend(tick, delta, channel, val1, val2)
                else -> ChannelEvent(tick, delta, type, channel, val1, val2)
            }
        }

        private var mOrderMap: MutableMap<Int, Int>? = null

        private fun buildOrderMap() {
            mOrderMap = HashMap()
            mOrderMap!![PROGRAM_CHANGE] = 0
            mOrderMap!![CONTROLLER] = 1
            mOrderMap!![NOTE_ON] = 2
            mOrderMap!![NOTE_OFF] = 3
            mOrderMap!![NOTE_AFTERTOUCH] = 4
            mOrderMap!![CHANNEL_AFTERTOUCH] = 5
            mOrderMap!![PITCH_BEND] = 6
        }

        const val NOTE_OFF = 0x8
        const val NOTE_ON = 0x9
        const val NOTE_AFTERTOUCH = 0xA
        const val CONTROLLER = 0xB
        const val PROGRAM_CHANGE = 0xC
        const val CHANNEL_AFTERTOUCH = 0xD
        const val PITCH_BEND = 0xE
    }
}
