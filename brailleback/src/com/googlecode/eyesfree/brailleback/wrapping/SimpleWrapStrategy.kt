/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 */

package com.googlecode.eyesfree.brailleback.wrapping

/**
 * A simple wrap strategy in which lines are split by character if they are too
 * long to fit on a single line of the braille display. In this strategy, words
 * can be split across lines.
 */
class SimpleWrapStrategy : WrapStrategy() {

    override fun calculateBreakPoints() {
        // No explicit break points: fall back to character-level splitting.
    }
}
