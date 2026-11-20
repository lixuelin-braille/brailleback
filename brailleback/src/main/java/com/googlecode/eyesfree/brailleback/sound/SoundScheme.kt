/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 */

package com.googlecode.eyesfree.brailleback.sound

/**
 * A named sound scheme: a set of built-in earcon variants that can be switched
 * as a whole. Only built-in schemes remain after the custom-scheme feature was
 * removed; there are no per-event overrides.
 */
class SoundScheme(
    /** Stable key for this scheme. */
    val key: String,
    /** Display name. */
    val name: String,
    /** Built-in resource variant. */
    val variant: Int,
    /** Whether this is a user-created scheme (always false now). */
    val isUserScheme: Boolean
) {

    companion object {
        /** Built-in resource variant: 天坦默认. */
        const val VARIANT_DEFAULT = 0
        /** Built-in resource variant: 苹果. */
        const val VARIANT_APPLE = 1
        /** Built-in resource variant: 天坦. */
        const val VARIANT_TATANS = 2
    }
}
