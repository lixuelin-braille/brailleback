//
//  Copyright 2011 Alex Leffelman
//  Licensed under the Apache License, Version 2.0

package com.leff.midi.util

import com.leff.midi.event.MidiEvent

interface MidiEventListener {
    fun onStart(fromBeginning: Boolean)

    fun onEvent(event: MidiEvent, ms: Long)

    fun onStop(finished: Boolean)
}
