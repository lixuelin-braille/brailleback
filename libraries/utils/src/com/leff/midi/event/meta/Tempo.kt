//
//  Copyright 2011 Alex Leffelman
//  Licensed under the Apache License, Version 2.0

package com.leff.midi.event.meta

import com.leff.midi.event.MidiEvent
import com.leff.midi.util.MidiUtil
import com.leff.midi.util.VariableLengthInt
import java.io.IOException
import java.io.OutputStream

class Tempo : MetaEvent {
    companion object {
        const val DEFAULT_BPM = 120.0f
        const val DEFAULT_MPQN = 500000

        @JvmStatic
        fun parseTempo(tick: Long, delta: Long, info: MetaEventData): MetaEvent {
            if (info.length.getValue() != 3) {
                return GenericMetaEvent(tick, delta, info)
            }
            val mpqn = MidiUtil.bytesToInt(info.data, 0, 3)
            return Tempo(tick, delta, mpqn)
        }
    }

    private var mMPQN: Int = DEFAULT_MPQN
    private var mBPM: Float = DEFAULT_BPM

    constructor() : this(0, 0, DEFAULT_MPQN)

    constructor(tick: Long, delta: Long, mpqn: Int) :
        super(tick, delta, MetaEvent.TEMPO, VariableLengthInt(3)) {
        setMpqn(mpqn)
    }

    fun getMpqn(): Int = mMPQN

    fun getBpm(): Float = mBPM

    fun setMpqn(m: Int) {
        mMPQN = m
        mBPM = 60000000.0f / mMPQN
    }

    fun setBpm(b: Float) {
        mBPM = b
        mMPQN = (60000000 / mBPM).toInt()
    }

    override fun getEventSize(): Int = 6

    @Throws(IOException::class)
    override fun writeToFile(out: OutputStream) {
        super.writeToFile(out)

        out.write(3)
        out.write(MidiUtil.intToBytes(mMPQN, 3))
    }

    override fun compareTo(other: MidiEvent): Int {
        if (mTick != other.getTick()) {
            return if (mTick < other.getTick()) -1 else 1
        }
        if (mDelta.getValue() != other.getDelta().toInt()) {
            return if (mDelta.getValue() < other.getDelta().toInt()) 1 else -1
        }
        if (other !is Tempo) {
            return 1
        }
        val o = other as Tempo
        if (mMPQN != o.mMPQN) {
            return if (mMPQN < o.mMPQN) -1 else 1
        }
        return 0
    }
}
