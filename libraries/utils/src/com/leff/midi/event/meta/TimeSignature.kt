//
//  Copyright 2011 Alex Leffelman
//  Licensed under the Apache License, Version 2.0

package com.leff.midi.event.meta

import com.leff.midi.event.MidiEvent
import com.leff.midi.util.VariableLengthInt
import java.io.IOException
import java.io.OutputStream

class TimeSignature : MetaEvent {
    companion object {
        const val METER_EIGHTH = 12
        const val METER_QUARTER = 24
        const val METER_HALF = 48
        const val METER_WHOLE = 96

        const val DEFAULT_METER = METER_QUARTER
        const val DEFAULT_DIVISION = 8

        @JvmStatic
        fun parseTimeSignature(tick: Long, delta: Long, info: MetaEventData): MetaEvent {
            if (info.length.getValue() != 4) {
                return GenericMetaEvent(tick, delta, info)
            }

            val num = info.data[0].toInt()
            var den = info.data[1].toInt()
            val met = info.data[2].toInt()
            val fps = info.data[3].toInt()

            den = Math.pow(2.0, den.toDouble()).toInt()

            return TimeSignature(tick, delta, num, den, met, fps)
        }
    }

    private var mNumerator: Int = 4
    private var mDenominator: Int = 2
    private var mMeter: Int = DEFAULT_METER
    private var mDivision: Int = DEFAULT_DIVISION

    constructor() : this(0, 0, 4, 4, DEFAULT_METER, DEFAULT_DIVISION)

    constructor(tick: Long, delta: Long, num: Int, den: Int, meter: Int, div: Int) :
        super(tick, delta, MetaEvent.TIME_SIGNATURE, VariableLengthInt(4)) {
        setTimeSignature(num, den, meter, div)
    }

    fun setTimeSignature(num: Int, den: Int, meter: Int, div: Int) {
        mNumerator = num
        mDenominator = log2(den)
        mMeter = meter
        mDivision = div
    }

    fun getNumerator(): Int = mNumerator

    fun getDenominatorValue(): Int = mDenominator

    fun getRealDenominator(): Int = Math.pow(2.0, mDenominator.toDouble()).toInt()

    fun getMeter(): Int = mMeter

    fun getDivision(): Int = mDivision

    override fun getEventSize(): Int = 7

    @Throws(IOException::class)
    override fun writeToFile(out: OutputStream) {
        super.writeToFile(out)

        out.write(4)
        out.write(mNumerator)
        out.write(mDenominator)
        out.write(mMeter)
        out.write(mDivision)
    }

    private fun log2(den: Int): Int {
        return when (den) {
            2 -> 1
            4 -> 2
            8 -> 3
            16 -> 4
            32 -> 5
            else -> 0
        }
    }

    override fun toString(): String {
        return super.toString() + " " + mNumerator + "/" + getRealDenominator()
    }

    override fun compareTo(other: MidiEvent): Int {
        if (mTick != other.getTick()) {
            return if (mTick < other.getTick()) -1 else 1
        }
        if (mDelta.getValue() != other.getDelta().toInt()) {
            return if (mDelta.getValue() < other.getDelta().toInt()) 1 else -1
        }
        if (other !is TimeSignature) {
            return 1
        }
        val o = other as TimeSignature
        if (mNumerator != o.mNumerator) {
            return if (mNumerator < o.mNumerator) -1 else 1
        }
        if (mDenominator != o.mDenominator) {
            return if (mDenominator < o.mDenominator) -1 else 1
        }
        return 0
    }
}
