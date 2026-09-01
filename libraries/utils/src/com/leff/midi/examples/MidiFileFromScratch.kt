//
//  Copyright 2011 Alex Leffelman
//  Licensed under the Apache License, Version 2.0

package com.leff.midi.examples

import com.leff.midi.MidiFile
import com.leff.midi.MidiTrack
import com.leff.midi.event.NoteOff
import com.leff.midi.event.NoteOn
import com.leff.midi.event.meta.Tempo
import com.leff.midi.event.meta.TimeSignature
import java.io.File
import java.io.IOException
import java.util.ArrayList

class MidiFileFromScratch {
    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            // 1. Create some MidiTracks
            val tempoTrack = MidiTrack()
            val noteTrack = MidiTrack()

            // 2. Add events to the tracks
            // 2a. Track 0 is typically the tempo map
            val ts = TimeSignature()
            ts.setTimeSignature(4, 4, TimeSignature.DEFAULT_METER, TimeSignature.DEFAULT_DIVISION)

            val t = Tempo()
            t.setBpm(228f)

            tempoTrack.insertEvent(ts)
            tempoTrack.insertEvent(t)

            // 2b. Track 1 will have some notes in it
            for (i in 0 until 80) {
                val channel = 0
                val pitch = 1 + i
                val velocity = 100
                val on = NoteOn((i * 480).toLong(), channel, pitch, velocity)
                val off = NoteOff((i * 480 + 120).toLong(), channel, pitch, 0)

                noteTrack.insertEvent(on)
                noteTrack.insertEvent(off)

                // There is also a utility function for notes that you should use
                // instead of the above.
                noteTrack.insertNote(channel, pitch + 2, velocity, (i * 480).toLong(), 120)
            }

            // It's best not to manually insert EndOfTrack events; MidiTrack will
            // call closeTrack() on itself before writing itself to a file

            // 3. Create a MidiFile with the tracks we created
            val tracks = ArrayList<MidiTrack>()
            tracks.add(tempoTrack)
            tracks.add(noteTrack)

            val midi = MidiFile(MidiFile.DEFAULT_RESOLUTION, tracks)

            // 4. Write the MIDI data to a file
            val output = File("exampleout.mid")
            try {
                midi.writeToFile(output)
            } catch (e: IOException) {
                System.err.println(e)
            }
        }
    }
}
