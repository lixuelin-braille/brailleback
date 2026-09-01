//
//  Copyright 2011 Alex Leffelman
//  Licensed under the Apache License, Version 2.0

package com.leff.midi.examples

import com.leff.midi.MidiFile
import com.leff.midi.MidiTrack
import com.leff.midi.event.MidiEvent
import com.leff.midi.event.NoteOff
import com.leff.midi.event.NoteOn
import com.leff.midi.event.meta.Tempo
import java.io.File
import java.io.IOException
import java.util.ArrayList

class MidiManipulation {
    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            // 1. Open up a MIDI file
            var mf: MidiFile? = null
            val input = File("example.mid")

            try {
                mf = MidiFile(input)
            } catch (e: IOException) {
                System.err.println("Error parsing MIDI file:")
                e.printStackTrace()
                return
            }

            // 2. Do some editing to the file
            // 2a. Strip out anything but notes from track 1
            var t = mf!!.getTracks()[1]

            // It's a bad idea to modify a set while iterating, so we'll collect
            // the events first, then remove them afterwards
            val it = t.getEvents().iterator()
            val eventsToRemove = ArrayList<MidiEvent>()

            while (it.hasNext()) {
                val e = it.next()
                if (e.javaClass != NoteOn::class.java && e.javaClass != NoteOff::class.java) {
                    eventsToRemove.add(e)
                }
            }

            for (e in eventsToRemove) {
                t.removeEvent(e)
            }

            // 2b. Completely remove track 2
            mf.removeTrack(2)

            // 2c. Reduce the tempo by half
            t = mf.getTracks()[0]

            val it2 = t.getEvents().iterator()
            while (it2.hasNext()) {
                val e = it2.next()
                if (e.javaClass == Tempo::class.java) {
                    val tempo = e as Tempo
                    tempo.setBpm(tempo.getBpm() / 2)
                }
            }

            // 3. Save the file back to disk
            try {
                mf.writeToFile(input)
            } catch (e: IOException) {
                System.err.println("Error writing MIDI file:")
                e.printStackTrace()
            }
        }
    }
}
