//
//  Copyright 2011 Alex Leffelman
//  Licensed under the Apache License, Version 2.0

package com.leff.midi.examples

import com.leff.midi.MidiFile
import com.leff.midi.event.MidiEvent
import com.leff.midi.event.NoteOn
import com.leff.midi.event.meta.Tempo
import com.leff.midi.util.MidiEventListener
import com.leff.midi.util.MidiProcessor
import java.io.File
import java.io.IOException

class EventPrinter(private val mLabel: String) : MidiEventListener {
    // 0. Implement the listener functions that will be called by the MidiProcessor
    override fun onStart(fromBeginning: Boolean) {
        if (fromBeginning) {
            println(mLabel + " Started!")
        } else {
            println(mLabel + " resumed")
        }
    }

    override fun onEvent(event: MidiEvent, ms: Long) {
        println(mLabel + " received event: " + event)
    }

    override fun onStop(finished: Boolean) {
        if (finished) {
            println(mLabel + " Finished!")
        } else {
            println(mLabel + " paused")
        }
    }

    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            // 1. Read in a MidiFile
            var midi: MidiFile? = null
            try {
                midi = MidiFile(File("inputmid.mid"))
            } catch (e: IOException) {
                System.err.println(e)
                return
            }

            // 2. Create a MidiProcessor
            val processor = MidiProcessor(midi)

            // 3. Register listeners for the events you're interested in
            val ep = EventPrinter("Individual Listener")
            processor.registerEventListener(ep, Tempo::class.java)
            processor.registerEventListener(ep, NoteOn::class.java)

            // or listen for all events:
            val ep2 = EventPrinter("Listener For All")
            processor.registerEventListener(ep2, MidiEvent::class.java)

            // 4. Start the processor
            processor.start()

            // Listeners will be triggered in real time with the MIDI events
            // And you can pause/resume with stop() and start()
            try {
                Thread.sleep(10 * 1000)
                processor.stop()

                Thread.sleep(10 * 1000)
                processor.start()
            } catch (e: Exception) {
            }
        }
    }
}
