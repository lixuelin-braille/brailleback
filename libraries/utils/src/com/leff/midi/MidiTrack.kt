//
//  Copyright 2011 Alex Leffelman
//  Licensed under the Apache License, Version 2.0

package com.leff.midi

import com.leff.midi.event.MidiEvent
import com.leff.midi.event.NoteOn
import com.leff.midi.event.meta.EndOfTrack
import com.leff.midi.event.meta.Tempo
import com.leff.midi.event.meta.TimeSignature
import com.leff.midi.util.MidiUtil
import com.leff.midi.util.VariableLengthInt
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.TreeSet

class MidiTrack {
    companion object {
        private const val VERBOSE = false

        @JvmField
        val IDENTIFIER: ByteArray = byteArrayOf(
            'M'.code.toByte(), 'T'.code.toByte(), 'r'.code.toByte(), 'k'.code.toByte())

        @JvmStatic
        fun createTempoTrack(): MidiTrack {
            val t = MidiTrack()
            t.insertEvent(TimeSignature())
            t.insertEvent(Tempo())
            return t
        }
    }

    private var mSize: Int = 0
    private var mSizeNeedsRecalculating: Boolean = false
    private var mClosed: Boolean = false
    private var mEndOfTrackDelta: Long = 0

    private val mEvents: TreeSet<MidiEvent> = TreeSet()

    constructor()

    @Throws(IOException::class)
    constructor(inputStream: InputStream) {
        var buffer = ByteArray(4)
        inputStream.read(buffer)

        if (!MidiUtil.bytesEqual(buffer, IDENTIFIER, 0, 4)) {
            System.err.println("Track identifier did not match MTrk!")
            return
        }

        inputStream.read(buffer)
        mSize = MidiUtil.bytesToInt(buffer, 0, 4)

        buffer = ByteArray(mSize)
        inputStream.read(buffer)

        readTrackData(buffer)
    }

    private fun readTrackData(data: ByteArray) {
        val input = ByteArrayInputStream(data)

        var totalTicks = 0L

        while (input.available() > 0) {
            val delta = VariableLengthInt(input)
            totalTicks += delta.getValue()

            val e = MidiEvent.parseEvent(totalTicks, delta.getValue().toLong(), input)
            if (e == null) {
                println("Event skipped!")
                continue
            }

            if (VERBOSE) {
                println(e)
            }

            // Not adding the EndOfTrack event here allows the track to be edited
            // after being read in from file.
            if (e.javaClass == EndOfTrack::class.java) {
                mEndOfTrackDelta = e.getDelta()
                break
            }
            mEvents.add(e)
        }
    }

    fun getEvents(): TreeSet<MidiEvent> = mEvents

    fun getEventCount(): Int = mEvents.size

    fun getSize(): Int {
        if (mSizeNeedsRecalculating) {
            recalculateSize()
        }
        return mSize
    }

    fun getLengthInTicks(): Long {
        if (mEvents.size == 0) {
            return 0
        }
        return mEvents.last().getTick()
    }

    fun getEndOfTrackDelta(): Long = mEndOfTrackDelta

    fun setEndOfTrackDelta(delta: Long) {
        mEndOfTrackDelta = delta
    }

    fun insertNote(channel: Int, pitch: Int, velocity: Int, tick: Long, duration: Long) {
        insertEvent(NoteOn(tick, channel, pitch, velocity))
        insertEvent(NoteOn(tick + duration, channel, pitch, 0))
    }

    fun insertEvent(newEvent: MidiEvent?) {
        if (newEvent == null) {
            return
        }

        if (mClosed) {
            System.err.println("Error: Cannot add an event to a closed track.")
            return
        }

        var prev: MidiEvent? = mEvents.floor(newEvent)
        var next: MidiEvent? = mEvents.ceiling(newEvent)

        mEvents.add(newEvent)
        mSizeNeedsRecalculating = true

        // Set its delta time based on the previous event (or itself if no
        // previous event exists)
        if (prev != null) {
            newEvent.setDelta(newEvent.getTick() - prev.getTick())
        } else {
            newEvent.setDelta(newEvent.getTick())
        }

        // Update the next event's delta time relative to the new event.
        if (next != null) {
            next.setDelta(next.getTick() - newEvent.getTick())
        }

        mSize += newEvent.getSize()

        if (newEvent.javaClass == EndOfTrack::class.java) {
            if (next != null) {
                throw IllegalArgumentException(
                    "Attempting to insert EndOfTrack before an existing event. Use closeTrack() when finished with MidiTrack.")
            }
            mClosed = true
        }
    }

    fun removeEvent(e: MidiEvent): Boolean {
        val it = mEvents.iterator()
        var prev: MidiEvent? = null
        var curr: MidiEvent? = null
        var next: MidiEvent? = null

        while (it.hasNext()) {
            next = it.next()

            if (e == curr) {
                break
            }

            prev = curr
            curr = next
            next = null
        }

        if (next == null) {
            // Either the event was not found in the track, or this is the last
            // event in the track. Either way, we won't need to update any delta
            // times
            return mEvents.remove(curr)
        }

        if (!mEvents.remove(curr)) {
            return false
        }

        if (prev != null) {
            next.setDelta(next.getTick() - prev.getTick())
        } else {
            next.setDelta(next.getTick())
        }
        return true
    }

    fun closeTrack() {
        var lastTick = 0L
        if (mEvents.size > 0) {
            lastTick = mEvents.last().getTick()
        }
        val eot = EndOfTrack(lastTick + mEndOfTrackDelta, 0)
        insertEvent(eot)
    }

    fun dumpEvents() {
        val it = mEvents.iterator()
        while (it.hasNext()) {
            println(it.next())
        }
    }

    private fun recalculateSize() {
        mSize = 0

        val it = mEvents.iterator()
        var last: MidiEvent? = null
        while (it.hasNext()) {
            val e = it.next()
            mSize += e.getSize()

            // If an event is of the same type as the previous event, no status
            // byte is written.
            if (last != null && !e.requiresStatusByte(last)) {
                mSize--
            }
            last = e
        }

        mSizeNeedsRecalculating = false
    }

    @Throws(IOException::class)
    fun writeToFile(out: OutputStream) {
        if (!mClosed) {
            closeTrack()
        }

        if (mSizeNeedsRecalculating) {
            recalculateSize()
        }

        out.write(IDENTIFIER)
        out.write(MidiUtil.intToBytes(mSize, 4))

        val it = mEvents.iterator()
        var lastEvent: MidiEvent? = null

        while (it.hasNext()) {
            val event = it.next()
            if (VERBOSE) {
                println("Writing: " + event)
            }

            event.writeToFile(out, event.requiresStatusByte(lastEvent))
            lastEvent = event
        }
    }
}
