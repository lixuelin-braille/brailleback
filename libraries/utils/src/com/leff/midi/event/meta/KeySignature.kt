//
//  Copyright 2011 Alex Leffelman
//  Licensed under the Apache License, Version 2.0

package com.leff.midi.event.meta

import com.leff.midi.event.MidiEvent
import com.leff.midi.util.VariableLengthInt
import java.io.IOException
import java.io.OutputStream

class KeySignature : MetaEvent {
    companion object {
        const val SCALE_MAJOR = 0
        const val SCALE_MINOR = 1

        @JvmStatic
        fun parseKeySignature(tick: Long, delta: Long, info: MetaEventData): MetaEvent {
            if (info.length.getValue() != 2) {
                return GenericMetaEvent(tick, delta, info)
            }
            val key = info.data[0].toInt()
            val scale = info.data[1].toInt()
            return KeySignature(tick, delta, key, scale)
        }
    }

    private var mKey: Int = 0
    private var mScale: Int = SCALE_MAJOR

    constructor(tick: Long, delta: Long, key: Int, scale: Int) :
        super(tick, delta, MetaEvent.KEY_SIGNATURE, VariableLengthInt(2)) {
        setKey(key)
        mScale = scale
    }

    fun setKey(key: Int) {
        mKey = key.toByte().toInt()
        if (mKey < -7) {
            mKey = -7
        } else if (mKey > 7) {
            mKey = 7
        }
    }

    fun getKey(): Int = mKey

    fun setScale(scale: Int) {
        mScale = scale
    }

    fun getScale(): Int = mScale

    override fun getEventSize(): Int = 5

    @Throws(IOException::class)
    override fun writeToFile(out: OutputStream) {
        super.writeToFile(out)

        out.write(2)
        out.write(mKey)
        out.write(mScale)
    }

    override fun compareTo(other: MidiEvent): Int {
        if (mTick != other.getTick()) {
            return if (mTick < other.getTick()) -1 else 1
        }
        if (mDelta.getValue() != other.getDelta().toInt()) {
            return if (mDelta.getValue() < other.getDelta().toInt()) 1 else -1
        }
        if (other !is KeySignature) {
            return 1
        }
        val o = other as KeySignature
        if (mKey != o.mKey) {
            return if (mKey < o.mKey) -1 else 1
        }
        if (mScale != o.mScale) {
            return if (mKey < o.mScale) -1 else 1
        }
        return 0
    }
}
