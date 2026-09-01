//
//  Copyright 2011 Alex Leffelman
//  Licensed under the Apache License, Version 2.0

package com.leff.midi.event.meta

import com.leff.midi.event.MidiEvent
import com.leff.midi.util.VariableLengthInt
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

abstract class MetaEvent(
    tick: Long, delta: Long,
    @JvmField var mType: Int,
    @JvmField var mLength: VariableLengthInt
) : MidiEvent(tick, delta) {

    override abstract fun getEventSize(): Int

    override fun writeToFile(out: OutputStream, writeType: Boolean) {
        writeToFile(out)
    }

    @Throws(IOException::class)
    protected open fun writeToFile(out: OutputStream) {
        super.writeToFile(out, true)
        out.write(0xFF)
        out.write(mType)
    }

    class MetaEventData(inputStream: InputStream) {
        @JvmField val type: Int
        @JvmField val length: VariableLengthInt
        @JvmField val data: ByteArray

        init {
            type = inputStream.read()
            length = VariableLengthInt(inputStream)
            data = ByteArray(length.getValue())
            if (length.getValue() > 0) {
                inputStream.read(data)
            }
        }
    }

    companion object {
        @JvmStatic
        fun parseMetaEvent(tick: Long, delta: Long, inputStream: InputStream): MetaEvent? {
            val eventData = MetaEventData(inputStream)

            var isText = false
            when (eventData.type) {
                SEQUENCE_NUMBER, MIDI_CHANNEL_PREFIX, END_OF_TRACK, TEMPO,
                SMPTE_OFFSET, TIME_SIGNATURE, KEY_SIGNATURE -> {}
                TEXT_EVENT, COPYRIGHT_NOTICE, TRACK_NAME, INSTRUMENT_NAME, LYRICS,
                MARKER, CUE_POINT, SEQUENCER_SPECIFIC -> {
                    // Not technically text for sequencer specific, but same structure
                    isText = true
                }
                else -> {
                    // Also not technically text, but it should follow
                    isText = true
                }
            }

            if (isText) {
                val text = String(eventData.data)

                return when (eventData.type) {
                    TEXT_EVENT -> Text(tick, delta, text)
                    COPYRIGHT_NOTICE -> CopyrightNotice(tick, delta, text)
                    TRACK_NAME -> TrackName(tick, delta, text)
                    INSTRUMENT_NAME -> InstrumentName(tick, delta, text)
                    LYRICS -> Lyrics(tick, delta, text)
                    MARKER -> Marker(tick, delta, text)
                    CUE_POINT -> CuePoint(tick, delta, text)
                    SEQUENCER_SPECIFIC -> SequencerSpecificEvent(tick, delta, eventData.data)
                    else -> GenericMetaEvent(tick, delta, eventData)
                }
            }

            return when (eventData.type) {
                SEQUENCE_NUMBER -> SequenceNumber.parseSequenceNumber(tick, delta, eventData)
                MIDI_CHANNEL_PREFIX -> MidiChannelPrefix.parseMidiChannelPrefix(tick, delta, eventData)
                END_OF_TRACK -> EndOfTrack(tick, delta)
                TEMPO -> Tempo.parseTempo(tick, delta, eventData)
                SMPTE_OFFSET -> SmpteOffset.parseSmpteOffset(tick, delta, eventData)
                TIME_SIGNATURE -> TimeSignature.parseTimeSignature(tick, delta, eventData)
                KEY_SIGNATURE -> KeySignature.parseKeySignature(tick, delta, eventData)
                else -> {
                    println("Completely broken in MetaEvent.parseMetaEvent()")
                    null
                }
            }
        }

        const val SEQUENCE_NUMBER = 0
        const val TEXT_EVENT = 1
        const val COPYRIGHT_NOTICE = 2
        const val TRACK_NAME = 3
        const val INSTRUMENT_NAME = 4
        const val LYRICS = 5
        const val MARKER = 6
        const val CUE_POINT = 7
        const val MIDI_CHANNEL_PREFIX = 0x20
        const val END_OF_TRACK = 0x2F
        const val TEMPO = 0x51
        const val SMPTE_OFFSET = 0x54
        const val TIME_SIGNATURE = 0x58
        const val KEY_SIGNATURE = 0x59
        const val SEQUENCER_SPECIFIC = 0x7F
    }
}
