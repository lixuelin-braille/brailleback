//
//  Copyright 2011 Alex Leffelman
//  Licensed under the Apache License, Version 2.0

package com.leff.midi.event.meta

class Marker(tick: Long, delta: Long, text: String) :
    TextualMetaEvent(tick, delta, MetaEvent.MARKER, text) {
    override fun setText(t: String) {
        super.setText(t)
    }

    override fun getText(): String {
        return super.getText()
    }
}
