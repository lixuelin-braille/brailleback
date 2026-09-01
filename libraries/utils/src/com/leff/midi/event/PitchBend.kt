//
//  Copyright 2011 Alex Leffelman
//  Licensed under the Apache License, Version 2.0

package com.leff.midi.event

class PitchBend : ChannelEvent {
    constructor(tick: Long, channel: Int, lsb: Int, msb: Int) :
        super(tick, ChannelEvent.PITCH_BEND, channel, lsb, msb)

    constructor(tick: Long, delta: Long, channel: Int, lsb: Int, msb: Int) :
        super(tick, delta, ChannelEvent.PITCH_BEND, channel, lsb, msb)

    fun getLeastSignificantBits(): Int = mValue1

    fun getMostSignificantBits(): Int = mValue2

    fun getBendAmount(): Int {
        val y = (mValue2 and 0x7F) shl 7
        val x = mValue1
        return y + x
    }

    fun setLeastSignificantBits(p: Int) {
        mValue1 = p and 0x7F
    }

    fun setMostSignificantBits(p: Int) {
        mValue2 = p and 0x7F
    }

    fun setBendAmount(amount: Int) {
        var amt = amount and 0x3FFF
        mValue1 = amt and 0x7F
        mValue2 = amt shr 7
    }
}
