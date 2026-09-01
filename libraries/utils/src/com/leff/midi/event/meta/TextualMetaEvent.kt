//
//  Copyright 2011 Alex Leffelman
//  Licensed under the Apache License, Version 2.0

package com.leff.midi.event.meta

import com.leff.midi.event.MidiEvent
import com.leff.midi.util.VariableLengthInt
import java.io.IOException
import java.io.OutputStream

abstract class TextualMetaEvent(
    tick: Long, delta: Long, type: Int, text: String
) : MetaEvent(tick, delta, type, VariableLengthInt(text.length)) {
    protected var mText: String = ""

    init {
        setText(text)
    }

    protected open fun setText(t: String) {
        mText = t
        mLength.setValue(t.toByteArray().size)
    }

    protected open fun getText(): String = mText

    override fun getEventSize(): Int {
        return 1 + 1 + mLength.getByteCount() + mLength.getValue()
    }

    @Throws(IOException::class)
    override fun writeToFile(out: OutputStream) {
        super.writeToFile(out)

        out.write(mLength.getBytes())
        out.write(mText.toByteArray())
    }

    override fun compareTo(other: MidiEvent): Int {
        if (mTick != other.getTick()) {
            return if (mTick < other.getTick()) -1 else 1
        }
        if (mDelta.getValue() != other.getDelta().toInt()) {
            return if (mDelta.getValue() < other.getDelta().toInt()) 1 else -1
        }

        if (other !is TextualMetaEvent) {
            return 1
        }

        val o = other as TextualMetaEvent
        return mText.compareTo(o.mText)
    }

    override fun toString(): String {
        return super.toString() + ": " + mText
    }
}
