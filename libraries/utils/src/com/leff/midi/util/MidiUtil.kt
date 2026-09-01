//
//  Copyright 2011 Alex Leffelman
//  Licensed under the Apache License, Version 2.0

package com.leff.midi.util

object MidiUtil {
    /** MIDI Unit Conversions */
    @JvmStatic
    fun ticksToMs(ticks: Long, mpqn: Int, resolution: Int): Long {
        return ((ticks * mpqn) / resolution) / 1000
    }

    @JvmStatic
    fun ticksToMs(ticks: Long, bpm: Float, resolution: Int): Long {
        return ticksToMs(ticks, bpmToMpqn(bpm), resolution)
    }

    @JvmStatic
    fun msToTicks(ms: Long, mpqn: Int, ppq: Int): Double {
        return ((ms * 1000.0) * ppq) / mpqn
    }

    @JvmStatic
    fun msToTicks(ms: Long, bpm: Float, ppq: Int): Double {
        return msToTicks(ms, bpmToMpqn(bpm), ppq)
    }

    @JvmStatic
    fun bpmToMpqn(bpm: Float): Int {
        return (bpm * 60000000).toInt()
    }

    @JvmStatic
    fun mpqnToBpm(mpqn: Int): Float {
        return mpqn / 60000000.0f
    }

    /** Utility methods for working with bytes and byte buffers */
    @JvmStatic
    fun bytesToInt(buff: ByteArray, off: Int, len: Int): Int {
        var num = 0
        var shift = 0
        for (i in off + len - 1 downTo off) {
            num += (buff[i].toInt() and 0xFF) shl shift
            shift += 8
        }
        return num
    }

    @JvmStatic
    fun intToBytes(origVal: Int, byteCount: Int): ByteArray {
        val buffer = ByteArray(byteCount)
        val ints = IntArray(byteCount)
        var value = origVal

        for (i in 0 until byteCount) {
            ints[i] = value and 0xFF
            buffer[byteCount - i - 1] = ints[i].toByte()

            value = value shr 8
            if (value == 0) {
                break
            }
        }
        return buffer
    }

    @JvmStatic
    fun bytesEqual(buf1: ByteArray, buf2: ByteArray, off: Int, len: Int): Boolean {
        for (i in off until off + len) {
            if (i >= buf1.size || i >= buf2.size) {
                return false
            }
            if (buf1[i] != buf2[i]) {
                return false
            }
        }
        return true
    }

    @JvmStatic
    fun extractBytes(buffer: ByteArray, off: Int, len: Int): ByteArray {
        val ret = ByteArray(len)
        for (i in 0 until len) {
            ret[i] = buffer[off + i]
        }
        return ret
    }

    private const val HEX = "0123456789ABCDEF"

    @JvmStatic
    fun byteToHex(b: Byte): String {
        val high = (b.toInt() and 0xF0) shr 4
        val low = b.toInt() and 0x0F
        return "" + HEX[high] + HEX[low]
    }

    @JvmStatic
    fun bytesToHex(b: ByteArray): String {
        val sb = StringBuilder()
        for (i in b.indices) {
            sb.append(byteToHex(b[i])).append(" ")
        }
        return sb.toString()
    }
}
