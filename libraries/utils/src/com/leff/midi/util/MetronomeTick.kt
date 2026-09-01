//
//  Copyright 2011 Alex Leffelman
//  Licensed under the Apache License, Version 2.0

package com.leff.midi.util

import com.leff.midi.event.MidiEvent
import com.leff.midi.event.meta.TimeSignature

/**
 * An event specifically for MidiProcessor to broadcast metronome ticks so that
 * observers need not rely on time conversions or measure tracking
 */
class MetronomeTick(sig: TimeSignature, private val mResolution: Int) : MidiEvent(0, 0) {
    private var mSignature: TimeSignature
    private var mCurrentMeasure = 1
    private var mCurrentBeat = 0
    private var mMetronomeProgress = 0.0
    private var mMetronomeFrequency = 0

    init {
        mSignature = sig
        setTimeSignature(sig)
    }

    fun setTimeSignature(sig: TimeSignature) {
        mSignature = sig
        mCurrentBeat = 0
        setMetronomeFrequency(sig.getMeter())
    }

    fun update(ticksElapsed: Double): Boolean {
        mMetronomeProgress += ticksElapsed

        if (mMetronomeProgress >= mMetronomeFrequency) {
            mMetronomeProgress %= mMetronomeFrequency

            mCurrentBeat = (mCurrentBeat + 1) % mSignature.getNumerator()
            if (mCurrentBeat == 0) {
                mCurrentMeasure++
            }
            return true
        }
        return false
    }

    fun setMetronomeFrequency(meter: Int) {
        when (meter) {
            TimeSignature.METER_EIGHTH -> mMetronomeFrequency = mResolution / 2
            TimeSignature.METER_QUARTER -> mMetronomeFrequency = mResolution
            TimeSignature.METER_HALF -> mMetronomeFrequency = mResolution * 2
            TimeSignature.METER_WHOLE -> mMetronomeFrequency = mResolution * 4
        }
    }

    fun getBeatNumber(): Int = mCurrentBeat + 1

    fun getMeasure(): Int = mCurrentMeasure

    override fun toString(): String {
        return "Metronome: " + mCurrentMeasure + "\t" + getBeatNumber()
    }

    override fun compareTo(o: MidiEvent): Int = 0

    override fun getEventSize(): Int = 0

    override fun getSize(): Int = 0
}
