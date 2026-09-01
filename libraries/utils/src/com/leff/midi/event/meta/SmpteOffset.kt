//
//  Copyright 2011 Alex Leffelman
//  Licensed under the Apache License, Version 2.0

package com.leff.midi.event.meta

import com.leff.midi.event.MidiEvent
import com.leff.midi.util.VariableLengthInt
import java.io.IOException
import java.io.OutputStream

class SmpteOffset : MetaEvent {
    companion object {
        const val FRAME_RATE_24 = 0
        const val FRAME_RATE_25 = 1
        const val FRAME_RATE_30_DROP = 2
        const val FRAME_RATE_30 = 3

        @JvmStatic
        fun parseSmpteOffset(tick: Long, delta: Long, info: MetaEventData): MetaEvent {
            if (info.length.getValue() != 5) {
                return GenericMetaEvent(tick, delta, info)
            }

            val rrHours = info.data[0].toInt()
            val rr = rrHours shr 5
            val fps = FrameRate.fromInt(rr) ?: FrameRate.FRAME_RATE_24
            val hour = rrHours and 0x1F

            val min = info.data[1].toInt()
            val sec = info.data[2].toInt()
            val frm = info.data[3].toInt()
            val sub = info.data[4].toInt()

            return SmpteOffset(tick, delta, fps, hour, min, sec, frm, sub)
        }
    }

    enum class FrameRate(val value: Int) {
        FRAME_RATE_24(0x00), FRAME_RATE_25(0x01), FRAME_RATE_30_DROP(0x02), FRAME_RATE_30(0x03);

        companion object {
            @JvmStatic
            fun fromInt(value: Int): FrameRate? {
                return when (value) {
                    0 -> FRAME_RATE_24
                    1 -> FRAME_RATE_25
                    2 -> FRAME_RATE_30_DROP
                    3 -> FRAME_RATE_30
                    else -> null
                }
            }
        }
    }

    private var mFrameRate: FrameRate
    private var mHours: Int
    private var mMinutes: Int
    private var mSeconds: Int
    private var mFrames: Int
    private var mSubFrames: Int

    constructor(
        tick: Long, delta: Long, fps: FrameRate, hour: Int,
        min: Int, sec: Int, fr: Int, subfr: Int
    ) : super(tick, delta, MetaEvent.SMPTE_OFFSET, VariableLengthInt(5)) {
        mFrameRate = fps
        mHours = hour
        mMinutes = min
        mSeconds = sec
        mFrames = fr
        mSubFrames = subfr
    }

    fun setFrameRate(fps: FrameRate) { mFrameRate = fps }
    fun getFrameRate(): FrameRate = mFrameRate
    fun setHours(h: Int) { mHours = h }
    fun getHours(): Int = mHours
    fun setMinutes(m: Int) { mMinutes = m }
    fun getMinutes(): Int = mMinutes
    fun setSeconds(s: Int) { mSeconds = s }
    fun getSeconds(): Int = mSeconds
    fun setFrames(f: Int) { mFrames = f }
    fun getFrames(): Int = mFrames
    fun setSubFrames(s: Int) { mSubFrames = s }
    fun getSubFrames(): Int = mSubFrames

    override fun getEventSize(): Int = 8

    @Throws(IOException::class)
    override fun writeToFile(out: OutputStream) {
        super.writeToFile(out)

        out.write(5)
        out.write(mHours)
        out.write(mMinutes)
        out.write(mSeconds)
        out.write(mFrames)
        out.write(mSubFrames)
    }

    override fun compareTo(other: MidiEvent): Int {
        if (mTick != other.getTick()) {
            return if (mTick < other.getTick()) -1 else 1
        }
        if (mDelta.getValue() != other.getDelta().toInt()) {
            return if (mDelta.getValue() < other.getDelta().toInt()) 1 else -1
        }
        return if (other !is SmpteOffset) 1 else 0
    }
}
