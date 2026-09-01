//
//  Copyright 2011 Alex Leffelman
//  Licensed under the Apache License, Version 2.0

package com.leff.midi.event

import com.leff.midi.event.meta.MetaEvent
import com.leff.midi.util.VariableLengthInt
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

abstract class MidiEvent(@JvmField var mTick: Long, @JvmField var mDelta: VariableLengthInt) :
    Comparable<MidiEvent> {

    constructor(tick: Long, delta: Long) : this(tick, VariableLengthInt(delta.toInt()))

    fun getTick(): Long = mTick

    fun getDelta(): Long = mDelta.getValue().toLong()

    fun setDelta(d: Long) {
        mDelta.setValue(d.toInt())
    }

    protected abstract fun getEventSize(): Int

    open fun getSize(): Int {
        return getEventSize() + mDelta.getByteCount()
    }

    open fun requiresStatusByte(prevEvent: MidiEvent?): Boolean {
        if (prevEvent == null) {
            return true
        }
        if (this is MetaEvent) {
            return true
        }
        return this.javaClass != prevEvent.javaClass
    }

    @Throws(IOException::class)
    open fun writeToFile(out: OutputStream, writeType: Boolean) {
        out.write(mDelta.getBytes())
    }

    companion object {
        private var sId = -1
        private var sType = -1
        private var sChannel = -1

        @JvmStatic
        fun parseEvent(tick: Long, delta: Long, inputStream: InputStream): MidiEvent? {
            inputStream.mark(1)
            var reset = false

            var id = inputStream.read()
            if (!verifyIdentifier(id)) {
                inputStream.reset()
                reset = true
            }

            if (sType >= 0x8 && sType <= 0xE) {
                return ChannelEvent.parseChannelEvent(tick, delta, sType, sChannel, inputStream)
            } else if (sId == 0xFF) {
                return MetaEvent.parseMetaEvent(tick, delta, inputStream)
            } else if (sId == 0xF0 || sId == 0xF7) {
                val size = VariableLengthInt(inputStream)
                val data = ByteArray(size.getValue())
                inputStream.read(data)
                return SystemExclusiveEvent(sId, tick, delta, data)
            } else {
                println("Unable to handle status byte, skipping: " + sId)
                if (reset) {
                    inputStream.read()
                }
            }
            return null
        }

        private fun verifyIdentifier(id: Int): Boolean {
            sId = id

            val type = id shr 4
            val channel = id and 0x0F

            if (type >= 0x8 && type <= 0xE) {
                sId = id
                sType = type
                sChannel = channel
            } else if (id == 0xFF) {
                sId = id
                sType = -1
                sChannel = -1
            } else if (type == 0xF) {
                sId = id
                sType = type
                sChannel = -1
            } else {
                return false
            }
            return true
        }
    }

    override fun toString(): String {
        return "" + mTick + " (" + mDelta.getValue() + "): " + this.javaClass.simpleName
    }
}
