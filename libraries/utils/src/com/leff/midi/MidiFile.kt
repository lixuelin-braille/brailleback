//
//  Copyright 2011 Alex Leffelman
//  Licensed under the Apache License, Version 2.0

package com.leff.midi

import com.leff.midi.util.MidiUtil
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.ArrayList

class MidiFile {
    companion object {
        const val HEADER_SIZE = 14
        @JvmField
        val IDENTIFIER: ByteArray = byteArrayOf(
            'M'.code.toByte(), 'T'.code.toByte(), 'h'.code.toByte(), 'd'.code.toByte())

        const val DEFAULT_RESOLUTION = 480
    }

    private var mType: Int = 0
    private var mTrackCount: Int = 0
    private var mResolution: Int = DEFAULT_RESOLUTION
    private var mTracks: MutableList<MidiTrack> = ArrayList()

    constructor() : this(DEFAULT_RESOLUTION)

    constructor(resolution: Int) : this(resolution, ArrayList())

    constructor(resolution: Int, tracks: List<MidiTrack>?) {
        mResolution = if (resolution >= 0) resolution else DEFAULT_RESOLUTION

        mTracks = if (tracks != null) ArrayList(tracks) else ArrayList()
        mTrackCount = mTracks.size
        mType = if (mTrackCount > 1) 1 else 0
    }

    @Throws(FileNotFoundException::class, IOException::class)
    constructor(fileIn: File) : this(FileInputStream(fileIn))

    @Throws(IOException::class)
    constructor(rawIn: InputStream) {
        val input = BufferedInputStream(rawIn)

        val buffer = ByteArray(HEADER_SIZE)
        input.read(buffer)

        initFromBuffer(buffer)

        mTracks = ArrayList()
        for (i in 0 until mTrackCount) {
            mTracks.add(MidiTrack(input))
        }
    }

    fun setType(type: Int) {
        var t = type
        if (t < 0) {
            t = 0
        } else if (t > 2) {
            t = 1
        } else if (t == 0 && mTrackCount > 1) {
            t = 1
        }
        mType = t
    }

    fun getType(): Int = mType

    fun getTrackCount(): Int = mTrackCount

    fun setResolution(res: Int) {
        if (res >= 0) {
            mResolution = res
        }
    }

    fun getResolution(): Int = mResolution

    fun getLengthInTicks(): Long {
        var length = 0L
        for (t in mTracks) {
            val l = t.getLengthInTicks()
            if (l > length) {
                length = l
            }
        }
        return length
    }

    fun getTracks(): List<MidiTrack> = mTracks

    fun addTrack(t: MidiTrack) {
        addTrack(t, mTracks.size)
    }

    fun addTrack(t: MidiTrack, pos: Int) {
        var p = pos
        if (p > mTracks.size) {
            p = mTracks.size
        } else if (p < 0) {
            p = 0
        }

        mTracks.add(p, t)
        mTrackCount = mTracks.size
        mType = if (mTrackCount > 1) 1 else 0
    }

    fun removeTrack(pos: Int) {
        if (pos < 0 || pos >= mTracks.size) {
            return
        }
        mTracks.removeAt(pos)
        mTrackCount = mTracks.size
        mType = if (mTrackCount > 1) 1 else 0
    }

    @Throws(FileNotFoundException::class, IOException::class)
    fun writeToFile(outFile: File) {
        val fout = FileOutputStream(outFile)

        fout.write(IDENTIFIER)
        fout.write(MidiUtil.intToBytes(6, 4))
        fout.write(MidiUtil.intToBytes(mType, 2))
        fout.write(MidiUtil.intToBytes(mTrackCount, 2))
        fout.write(MidiUtil.intToBytes(mResolution, 2))

        for (t in mTracks) {
            t.writeToFile(fout)
        }

        fout.flush()
        fout.close()
    }

    private fun initFromBuffer(buffer: ByteArray) {
        if (!MidiUtil.bytesEqual(buffer, IDENTIFIER, 0, 4)) {
            println("File identifier not MThd. Exiting")
            mType = 0
            mTrackCount = 0
            mResolution = DEFAULT_RESOLUTION
            return
        }

        mType = MidiUtil.bytesToInt(buffer, 8, 2)
        mTrackCount = MidiUtil.bytesToInt(buffer, 10, 2)
        mResolution = MidiUtil.bytesToInt(buffer, 12, 2)
    }
}
