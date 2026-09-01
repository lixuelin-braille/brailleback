//
//  Copyright 2011 Alex Leffelman
//  Licensed under the Apache License, Version 2.0

package com.leff.midi.event

class NoteOn : ChannelEvent {
    constructor(tick: Long, channel: Int, note: Int, velocity: Int) :
        super(tick, ChannelEvent.NOTE_ON, channel, note, velocity)

    constructor(tick: Long, delta: Long, channel: Int, note: Int, velocity: Int) :
        super(tick, delta, ChannelEvent.NOTE_ON, channel, note, velocity)

    fun getNoteValue(): Int = mValue1

    fun getVelocity(): Int = mValue2

    fun setNoteValue(p: Int) {
        mValue1 = p
    }

    fun setVelocity(v: Int) {
        mValue2 = v
    }
}
