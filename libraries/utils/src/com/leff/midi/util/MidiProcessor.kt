//
//  Copyright 2011 Alex Leffelman
//  Licensed under the Apache License, Version 2.0

package com.leff.midi.util

import com.leff.midi.MidiFile
import com.leff.midi.MidiTrack
import com.leff.midi.event.MidiEvent
import com.leff.midi.event.meta.Tempo
import com.leff.midi.event.meta.TimeSignature
import java.util.ArrayList
import java.util.HashMap
import java.util.Iterator

class MidiProcessor(input: MidiFile) {
    companion object {
        private const val PROCESS_RATE_MS = 8
    }

    private var mEventsToListeners = HashMap<Class<out MidiEvent>, MutableList<MidiEventListener>>()
    private var mListenersToEvents = HashMap<MidiEventListener, MutableList<Class<out MidiEvent>>>()

    private var mMidiFile: MidiFile = input
    private var mRunning = false
    private var mTicksElapsed = 0.0
    private var mMsElapsed = 0L

    private var mMPQN: Int = Tempo.DEFAULT_MPQN
    private var mPPQ: Int = mMidiFile.getResolution()

    private var mMetronome: MetronomeTick = MetronomeTick(TimeSignature(), mPPQ)
    private var mEventQueues: Array<MidiTrackEventQueue?>? = null

    init {
        reset()
    }

    @Synchronized
    fun start() {
        if (mRunning) {
            return
        }
        mRunning = true
        Thread { process() }.start()
    }

    fun stop() {
        mRunning = false
    }

    fun reset() {
        mRunning = false
        mTicksElapsed = 0.0
        mMsElapsed = 0

        mMetronome.setTimeSignature(TimeSignature())

        val tracks = mMidiFile.getTracks()

        if (mEventQueues == null) {
            mEventQueues = arrayOfNulls(tracks.size)
        }

        for (i in tracks.indices) {
            mEventQueues!![i] = MidiTrackEventQueue(tracks[i])
        }
    }

    fun isStarted(): Boolean = mTicksElapsed > 0

    fun isRunning(): Boolean = mRunning

    protected fun onStart(fromBeginning: Boolean) {
        val it = mListenersToEvents.keys.iterator()
        while (it.hasNext()) {
            val mel = it.next()
            mel.onStart(fromBeginning)
        }
    }

    protected fun onStop(finished: Boolean) {
        val it = mListenersToEvents.keys.iterator()
        while (it.hasNext()) {
            val mel = it.next()
            mel.onStop(finished)
        }
    }

    fun registerEventListener(mel: MidiEventListener, event: Class<out MidiEvent>) {
        var listeners = mEventsToListeners[event]
        if (listeners == null) {
            listeners = ArrayList()
            listeners.add(mel)
            mEventsToListeners[event] = listeners
        } else {
            listeners.add(mel)
        }

        var events = mListenersToEvents[mel]
        if (events == null) {
            events = ArrayList()
            events.add(event)
            mListenersToEvents[mel] = events
        } else {
            events.add(event)
        }
    }

    fun unregisterEventListener(mel: MidiEventListener) {
        val events = mListenersToEvents[mel] ?: return

        for (event in events) {
            mEventsToListeners[event]?.remove(mel)
        }
        mListenersToEvents.remove(mel)
    }

    fun unregisterEventListener(mel: MidiEventListener, event: Class<out MidiEvent>) {
        mEventsToListeners[event]?.remove(mel)
        mListenersToEvents[mel]?.remove(event)
    }

    fun unregisterAllEventListeners() {
        mEventsToListeners.clear()
        mListenersToEvents.clear()
    }

    protected fun dispatch(event: MidiEvent) {
        // Tempo and Time Signature events are always needed by the processor
        if (event.javaClass == Tempo::class.java) {
            mMPQN = (event as Tempo).getMpqn()
        } else if (event.javaClass == TimeSignature::class.java) {
            val shouldDispatch = mMetronome.getBeatNumber() != 1
            mMetronome.setTimeSignature(event as TimeSignature)

            if (shouldDispatch) {
                dispatch(mMetronome)
            }
        }

        sendOnEventForClass(event, event.javaClass)
        sendOnEventForClass(event, MidiEvent::class.java)
    }

    private fun sendOnEventForClass(event: MidiEvent, eventClass: Class<out MidiEvent>) {
        val listeners = mEventsToListeners[eventClass] ?: return
        for (mel in listeners) {
            mel.onEvent(event, mMsElapsed)
        }
    }

    private fun process() {
        onStart(mTicksElapsed < 1)

        var lastMs = System.currentTimeMillis()
        var finished = false

        while (mRunning) {
            val now = System.currentTimeMillis()
            var msElapsed = now - lastMs

            if (msElapsed < PROCESS_RATE_MS) {
                try {
                    Thread.sleep(PROCESS_RATE_MS - msElapsed)
                } catch (e: Exception) {
                }
                continue
            }

            val ticksElapsed = MidiUtil.msToTicks(msElapsed, mMPQN, mPPQ)

            if (ticksElapsed < 1) {
                continue
            }

            if (mMetronome.update(ticksElapsed)) {
                dispatch(mMetronome)
            }

            lastMs = now
            mMsElapsed += msElapsed
            mTicksElapsed += ticksElapsed

            var more = false
            for (i in mEventQueues!!.indices) {
                val queue = mEventQueues!![i] ?: continue
                if (!queue.hasMoreEvents()) {
                    continue
                }

                val events = queue.getNextEventsUpToTick(mTicksElapsed)
                for (event in events) {
                    dispatch(event)
                }

                if (queue.hasMoreEvents()) {
                    more = true
                }
            }

            if (!more) {
                finished = true
                break
            }
        }

        mRunning = false
        onStop(finished)
    }

    private class MidiTrackEventQueue(track: MidiTrack) {
        private var mTrack: MidiTrack = track
        private var mIterator: MutableIterator<MidiEvent> = track.getEvents().iterator()
        private var mEventsToDispatch = ArrayList<MidiEvent>()
        private var mNext: MidiEvent? = if (mIterator.hasNext()) mIterator.next() else null

        fun getNextEventsUpToTick(tick: Double): ArrayList<MidiEvent> {
            mEventsToDispatch.clear()

            while (mNext != null) {
                if (mNext!!.getTick() <= tick) {
                    mEventsToDispatch.add(mNext!!)

                    mNext = if (mIterator.hasNext()) mIterator.next() else null
                } else {
                    break
                }
            }
            return mEventsToDispatch
        }

        fun hasMoreEvents(): Boolean = mNext != null
    }
}
