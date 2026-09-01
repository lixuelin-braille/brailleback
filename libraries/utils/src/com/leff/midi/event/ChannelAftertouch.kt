//
//  Copyright 2011 Alex Leffelman
//  Licensed under the Apache License, Version 2.0

package com.leff.midi.event

class ChannelAftertouch : ChannelEvent {
    constructor(tick: Long, channel: Int, amount: Int) :
        super(tick, ChannelEvent.CHANNEL_AFTERTOUCH, channel, amount, 0)

    constructor(tick: Long, delta: Long, channel: Int, amount: Int) :
        super(tick, delta, ChannelEvent.CHANNEL_AFTERTOUCH, channel, amount, 0)

    fun getAmount(): Int = mValue1

    fun setAmount(p: Int) {
        mValue1 = p
    }
}
