//
//  Copyright 2011 Alex Leffelman
//  Licensed under the Apache License, Version 2.0

package com.leff.midi.event

class NoteAftertouch : ChannelEvent {
    constructor(tick: Long, channel: Int, note: Int, amount: Int) :
        super(tick, ChannelEvent.NOTE_AFTERTOUCH, channel, note, amount)

    constructor(tick: Long, delta: Long, channel: Int, note: Int, amount: Int) :
        super(tick, delta, ChannelEvent.NOTE_AFTERTOUCH, channel, note, amount)

    fun getNoteValue(): Int = mValue1

    fun getAmount(): Int = mValue2

    fun setNoteValue(p: Int) {
        mValue1 = p
    }

    fun setAmount(a: Int) {
        mValue2 = a
    }
}
