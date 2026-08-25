/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 */

package com.googlecode.eyesfree.brailleback.utils

import android.text.Editable

/**
 * Utilities for [String]s and other [CharSequence]s (Kotlin 版).
 */
object StringUtils {
    private const val SPACE = ' '

    @JvmStatic
    fun appendWithSpaces(editable: Editable, vararg args: Any?): Editable {
        for (arg in args) {
            if (arg == null) {
                continue
            }
            if (editable.length > 0 && !endsWithSpace(editable)) {
                editable.append(SPACE)
            }
            if (arg is CharSequence) {
                editable.append(arg)
            } else {
                editable.append(arg.toString())
            }
        }
        return editable
    }

    @JvmStatic
    fun appendWithSpaces(builder: StringBuilder, vararg args: Any?): StringBuilder {
        for (arg in args) {
            if (arg == null) {
                continue
            }
            if (builder.length > 0 && !endsWithSpace(builder)) {
                builder.append(SPACE)
            }
            builder.append(arg)
        }
        return builder
    }

    /** Returns `true` if the last code point of `seq` is a space character. */
    @JvmStatic
    fun endsWithSpace(seq: CharSequence): Boolean {
        if (seq.length == 0) {
            return false
        }
        return Character.isSpaceChar(Character.codePointBefore(seq, seq.length))
    }
}
