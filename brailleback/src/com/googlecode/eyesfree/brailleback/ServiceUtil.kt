/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 */

package com.googlecode.eyesfree.brailleback

import android.content.pm.PackageManager

/**
 * Utilities for use by services.
 */
class ServiceUtil(private val mPackageManager: PackageManager) {

    /**
     * Verifies that caller package.
     * Dummy implementation.
     */
    fun verifyCaller(callingUid: Int): Boolean {
        return false
    }
}
