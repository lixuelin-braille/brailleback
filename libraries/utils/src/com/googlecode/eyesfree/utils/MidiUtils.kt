/*
 * Copyright (C) 2013 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.googlecode.eyesfree.utils

import android.content.Context
import android.media.AudioManager
import android.media.MediaPlayer
import com.leff.midi.MidiFile
import com.leff.midi.MidiTrack
import com.leff.midi.event.Controller
import com.leff.midi.event.ProgramChange
import com.leff.midi.event.meta.Tempo
import com.leff.midi.event.meta.TimeSignature
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.util.ArrayList

/**
 * Utilities for loading MIDI files and generating MIDI tracks.
 */
object MidiUtils {
    object ControllerType {
        /**
         * Bank select allows switching between groups of 128 programs. 14-bit
         * coarse/fine resolution (0x0000 to 0x3FFF).
         */
        const val BANK_SELECT = 0
        const val BANK_SELECT_LSB = 32

        /**
         * The modulation wheel typically controls a vibrato effect. 14-bit
         * coarse/fine resolution (0x0000 to 0x3FFF). Use 0 for no modulation
         * effect.
         */
        const val MODULATION_WHEEL = 1

        /**
         * The volume level for a single channel. 14-bit coarse/fine resolution
         * (0x0000 to 0x3FFF) but most devices only implement coarse (0x00 to
         * 0xFF). Use 0 to mute the channel.
         */
        const val VOLUME = 7

        /**
         * The pan position controls where the channel's sound will be placed
         * within the stereo field. 14-bit coarse/fine resolution (0x0000 to
         * 0x3FFF) where 0x2000 is center position, 0x0000 is hard left, and
         * 0x3FFF is hard right.
         *
         * Some devices only respond to coarse adjust where 0x40 is center, 0x00
         * is hard left, and 0xFF is hard right.
         */
        const val PAN = 10

        /**
         * The sustain pedal sustains the currently playing notes, even if they
         * are released. 0x00 is off, 0xFF is on.
         */
        const val SUSTAIN_PEDAL = 64

        /**
         * The effects level for the device. Typically controls reverb or delay.
         * 8-bit resolution (0x00 to 0xFF). Use 0 for no effect.
         */
        const val EFFECTS_LEVEL = 91

        /**
         * The chorus effect level for the device. 8-bit resolution (0x00 to
         * 0xFF). Use 0 for no chorus effect.
         */
        const val CHORUS_LEVEL = 93
    }

    /**
     * Scale types used with [generateMidiScale].
     */
    const val SCALE_TYPE_MAJOR = 1
    const val SCALE_TYPE_NATURAL_MINOR = 2
    const val SCALE_TYPE_HARMONIC_MINOR = 3
    const val SCALE_TYPE_MELODIC_MINOR = 4
    const val SCALE_TYPE_PENTATONIC = 5

    /** The subdirectory name of the location used to store temporary files. */
    const val MIDI_TEMP_DIR_NAME = "midi"

    /** Default beats-per-minute for MIDI tracks. You can dance to 95. */
    private const val DEFAULT_BPM = 95

    /** Default channel for MIDI tracks. This should be 0. */
    private const val DEFAULT_CHANNEL = 0

    /** Default volume for MIDI tracks. Range is [0,127]. */
    private const val DEFAULT_VOLUME = 127

    /** Default tempo track for MIDI compositions. Uses 4/4 signature. */
    private val DEFAULT_TEMPO_TRACK: MidiTrack = MidiTrack().apply {
        val timeSignature = TimeSignature()
        val tempo = Tempo()
        timeSignature.setTimeSignature(
            4, 4, TimeSignature.DEFAULT_METER, TimeSignature.DEFAULT_DIVISION)
        tempo.setBpm(DEFAULT_BPM.toFloat())
        insertEvent(timeSignature)
        insertEvent(tempo)
    }

    /**
     * Writes an array of MIDI notes to a file.
     *
     * The array must begin with a program ID followed by triplets of note
     * pitch, velocity, and duration.
     *
     * @param context The context used to write temporary files.
     * @param notes An array of MIDI notes.
     * @return A temporary MIDI file (.mid), or `null` on error
     */
    @JvmStatic
    fun generateMidiFileFromArray(context: Context, notes: IntArray): File? {
        val track = readMidiTrackFromArray(notes) ?: return null
        return writeMidiTrackToTempFile(context, track)
    }

    /**
     * Attempts to remove any temporarily generated MIDI files from the MIDI
     * cache directory.
     *
     * @param context The application context to use for locating the cache
     *            directory.
     */
    @JvmStatic
    fun purgeMidiTempFiles(context: Context) {
        val midiDir = File(context.cacheDir, MIDI_TEMP_DIR_NAME)
        if (midiDir.exists()) {
            val tempMidiFiles = midiDir.listFiles()
            if (tempMidiFiles != null) {
                for (f in tempMidiFiles) {
                    f.delete()
                }
            }
        }
        midiDir.delete()
    }

    /**
     * Generates and plays a MIDI scale.
     *
     * @param program The MIDI program ID to use
     * @param velocity The MIDI velocity to use for each note
     * @param duration The duration in milliseconds of each note
     * @param startingPitch The MIDI pitch value on which the scale should begin
     * @param pitchesToPlay The number of pitches to play. 7 pitches (or 5
     *            pentatonic) is a complete scale. 8 (or 6 pentatonic) for a
     *            resolved scale.
     * @param scaleType The MIDI_SCALE_TYPE_* constant associated with the type
     *            of scale to play as defined in [MidiUtils].
     * @param context Application or service context to get resources and files.
     *
     * @return `true` if successful, `false` otherwise.
     */
    @JvmStatic
    fun playMidiScale(
        program: Int, velocity: Int, duration: Int, startingPitch: Int,
        pitchesToPlay: Int, scaleType: Int, context: Context
    ): Boolean {
        val midiSequence =
            generateMidiScale(program, velocity, duration, startingPitch, pitchesToPlay, scaleType)
                ?: return false

        val file = generateMidiFileFromArray(context, midiSequence) ?: return false

        val scalePlayer = MediaPlayer()
        scalePlayer.setOnPreparedListener(MediaPlayer.OnPreparedListener { scalePlayer.start() })
        scalePlayer.setOnCompletionListener(MediaPlayer.OnCompletionListener {
            scalePlayer.release()
            file.delete()
        })

        // Use the FD method of setting the MediaPlayer data source for
        // backwards compatibility.
        return try {
            FileInputStream(file).use { inputStream ->
                scalePlayer.setAudioStreamType(AudioManager.STREAM_MUSIC)
                scalePlayer.setDataSource(inputStream.fd)
                scalePlayer.prepareAsync()
            }
            true
        } catch (e: IOException) {
            e.printStackTrace()
            false
        }
    }

    /**
     * Generates a MIDI scale using the specified parameters.
     *
     * @param program The MIDI program ID to use
     * @param velocity The MIDI velocity to use for each note
     * @param duration The duration in milliseconds of each note
     * @param startingPitch The MIDI pitch value on which the scale should begin
     * @param pitchesToPlay The number of pitches to play. 7 pitches (or 5
     *            pentatonic) is a complete scale. 8 (or 6 pentatonic) for a
     *            resolved scale.
     * @param scaleType The MIDI_SCALE_TYPE_* constant associated with the type
     *            of scale to play.
     * @return An array of MIDI properties compatible with
     *         [generateMidiFileFromArray]
     */
    private fun generateMidiScale(
        program: Int, velocity: Int, duration: Int,
        startingPitch: Int, pitchesToPlay: Int, scaleType: Int
    ): IntArray? {
        var pitches = pitchesToPlay
        if (pitches <= 0 || duration <= 0) {
            return null
        }

        if (scaleType == SCALE_TYPE_PENTATONIC) {
            // Pentatonic are 5-note scales that drop the 4th and 7th notes in
            // each scale. To play the correct number of pitches, we must add
            // the number of notes to be dropped to the original major scale.
            val completeScales = pitches / 5
            val notesInPartialScale = pitches % 5
            pitches += (completeScales * 2) + (if (notesInPartialScale > 3) 1 else 0)
        }

        val notes = ArrayList<Int>()

        // Generate as much of a major scale is needed.
        var nextPitch = startingPitch
        for (i in 1..pitches) {
            notes.add(nextPitch)

            // Calculate the next pitch based on scale position.
            val noteInScale = i % 7
            when (noteInScale) {
                1, 2, 4, 5, 6 -> nextPitch += 2
                0, 3 -> nextPitch += 1
            }
        }

        if (scaleType == SCALE_TYPE_NATURAL_MINOR ||
            scaleType == SCALE_TYPE_HARMONIC_MINOR ||
            scaleType == SCALE_TYPE_MELODIC_MINOR
        ) {
            for (i in notes.indices) {
                val noteInScale = (i + 1) % 7

                // Lower the 3rd, 6th, and 7th of each scale by a half step
                // based on the type of minor scale.
                when (noteInScale) {
                    3 -> notes.add(i, notes.removeAt(i) - 1)
                    6 -> if (scaleType == SCALE_TYPE_NATURAL_MINOR ||
                        scaleType == SCALE_TYPE_HARMONIC_MINOR
                    ) {
                        notes.add(i, notes.removeAt(i) - 1)
                    }
                    0 -> if (scaleType == SCALE_TYPE_NATURAL_MINOR) {
                        notes.add(i, notes.removeAt(i) - 1)
                    }
                }
            }
        } else if (scaleType == SCALE_TYPE_PENTATONIC) {
            val indiciesToRemove = ArrayList<Int>()
            for (i in notes.indices) {
                val noteInScale = (i + 1) % 7

                // Pentatonic scales are derived by removing the 4th and 7th
                // from each scale.
                if (noteInScale == 4 || noteInScale == 0) {
                    indiciesToRemove.add(i)
                }
            }

            for (i in indiciesToRemove.size downTo 1) {
                notes.removeAt(indiciesToRemove[i - 1])
            }
        }

        // Generate the MIDI sequence array from the derived notes
        val midiSequence = IntArray(notes.size * 3 + 1)
        midiSequence[0] = program
        var i = 1
        while (i < midiSequence.size) {
            midiSequence[i] = notes.removeAt(0)
            midiSequence[i + 1] = velocity
            midiSequence[i + 2] = duration
            i += 3
        }

        return midiSequence
    }

    /**
     * Reads a MIDI track from an array of notes. The array format must be:
     * - Program ID,
     * - Note pitch, velocity, duration,
     * - (additional notes)
     *
     * @param notes The array to read as a MIDI track.
     * @return A MIDI track.
     */
    private fun readMidiTrackFromArray(notes: IntArray): MidiTrack? {
        val noteTrack = MidiTrack()
        var tick = 0

        val program = notes[0]
        require(program in 0..127) { "MIDI track program must be in the range [0,127]" }

        // Set the channel volume to maximum.
        noteTrack.insertEvent(
            Controller(0, 0, DEFAULT_CHANNEL, ControllerType.VOLUME, DEFAULT_VOLUME))

        // Mute the reverb effect.
        noteTrack.insertEvent(
            Controller(0, 0, DEFAULT_CHANNEL, ControllerType.EFFECTS_LEVEL, 0))

        // Mute the chorus effect.
        noteTrack.insertEvent(
            Controller(0, 0, DEFAULT_CHANNEL, ControllerType.CHORUS_LEVEL, 0))

        noteTrack.insertEvent(ProgramChange(0, 0, program))

        require(notes.size % 3 == 1) {
            "MIDI note array must contain a single integer followed by triplets"
        }

        var i = 1
        while (i < notes.size - 2) {
            val pitch = notes[i]
            require(pitch in 21..108) { "MIDI note pitch must be in the range [21,108]" }

            val velocity = notes[i + 1]
            require(velocity in 0..127) { "MIDI note velocity must be in the range [0,127]" }

            val duration = notes[i + 2]
            noteTrack.insertNote(DEFAULT_CHANNEL, pitch, velocity, tick.toLong(), duration.toLong())
            tick += duration
            i += 3
        }

        return noteTrack
    }

    private fun writeMidiTrackToTempFile(context: Context, noteTrack: MidiTrack): File? {
        // Always add the default tempo track first.
        val tracks = ArrayList<MidiTrack>()
        tracks.add(DEFAULT_TEMPO_TRACK)
        tracks.add(noteTrack)

        // Attempt to write the track to a file and return it.
        return try {
            val midiDir = File(context.cacheDir, MIDI_TEMP_DIR_NAME)
            if (!midiDir.exists() && !midiDir.mkdirs()) {
                null
            } else {
                val midi = MidiFile(MidiFile.DEFAULT_RESOLUTION, tracks)
                val output = File.createTempFile("talkback", ".mid", midiDir)
                midi.writeToFile(output)
                output
            }
        } catch (e: IOException) {
            e.printStackTrace()
            null
        }
    }
}
