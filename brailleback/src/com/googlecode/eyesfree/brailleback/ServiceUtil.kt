/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 */

package com.googlecode.eyesfree.brailleback

import android.content.pm.PackageManager
import android.os.Process

/**
 * Utilities for use by services.
 */
class ServiceUtil(private val mPackageManager: PackageManager) {

    /**
     * Verifies that the caller is signed with the same certificate as this app.
     *
     * The self-braille service is exported so that companion accessibility
     * apps can use it, but it must not be callable by arbitrary apps.  Same-UID
     * callers are accepted directly; otherwise the platform compares the
     * signing certificates.
     */
    fun verifyCaller(callingUid: Int): Boolean {
        if (callingUid == Process.myUid()) {
            return true
        }
        return try {
            mPackageManager.checkSignatures(callingUid, Process.myUid()) ==
                PackageManager.SIGNATURE_MATCH
        } catch (e: RuntimeException) {
            false
        }
    }
}
