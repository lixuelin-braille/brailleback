//
//  Copyright 2011 Alex Leffelman
//  Licensed under the Apache License, Version 2.0

package com.leff.midi.event

class Controller : ChannelEvent {
    constructor(tick: Long, channel: Int, controllerType: Int, value: Int) :
        super(tick, ChannelEvent.CONTROLLER, channel, controllerType, value)

    constructor(tick: Long, delta: Long, channel: Int, controllerType: Int, value: Int) :
        super(tick, delta, ChannelEvent.CONTROLLER, channel, controllerType, value)

    fun getControllerType(): Int = mValue1

    fun getValue(): Int = mValue2

    fun setControllerType(t: Int) {
        mValue1 = t
    }

    fun setValue(v: Int) {
        mValue2 = v
    }
}
