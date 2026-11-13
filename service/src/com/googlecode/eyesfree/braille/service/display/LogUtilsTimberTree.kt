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

import timber.log.Timber

/**
 * Forwards Timber events to {@link LogUtils}, which writes to logcat and to
 * the app's persistent log file. The display service shares the app process,
 * so it reuses the single file sink installed in {@code DisplayService#onCreate()}.
 */
class LogUtilsTimberTree : Timber.Tree() {
    companion object {
        private const val FALLBACK_TAG = "BrailleService"
    }

    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        LogUtils.logWithTag(priority, tag ?: FALLBACK_TAG,
                t, message)
    }
}
