//
//  Copyright 2011 Alex Leffelman
//  Licensed under the Apache License, Version 2.0

package com.leff.midi.event.meta

class InstrumentName(tick: Long, delta: Long, text: String) :
    TextualMetaEvent(tick, delta, MetaEvent.INSTRUMENT_NAME, text) {
    override fun setText(t: String) {
        super.setText(t)
    }

    override fun getText(): String {
        return super.getText()
    }
}
