/*
 * Copyright (C) 2026 The BrailleBack Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.googlecode.eyesfree.braille.service.display

import com.googlecode.eyesfree.utils.LogUtils

/**
 * Receives BRLTTY's native log records.
 *
 * <p>brltty writes to logcat only, which is not part of the in-app log export.
 * {@code log.c} forwards each record here so USB initialization failures also
 * reach {@code brailleback.log}. Called from native code.
 */
object BrlttyNativeLog {
    private const val TAG = "brltty"

    /** Called from {@code AppLogBridge.c}. */
    @Suppress("unused")
    @JvmStatic
    fun log(priority: Int, message: String?) {
        if (message == null || message.isEmpty()) {
            return
        }

        // Respect the user's level here too. brltty is configured with
        // LOG_DEBUG, so an unconditional file write would reinstate the
        // verbose flood that the level filter exists to prevent.
        LogUtils.logWithTag(priority, TAG, null as Throwable?, "%s", message)
    }
}
