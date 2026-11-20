/*
 * Copyright (C) 2014 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.googlecode.eyesfree.utils

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build

/**
 * Helper class for monitoring packages on the system.
 *
 * @author svetoslavganov@google.com (Svetoslav R. Ganov)
 * @author alanv@google.com (Alan Viverette)
 */
abstract class BasePackageMonitor : BroadcastReceiver() {
    /**
     * The intent filter to match package modifications.
     */
    private val packageFilter: IntentFilter = IntentFilter().apply {
        addAction(Intent.ACTION_PACKAGE_ADDED)
        addAction(Intent.ACTION_PACKAGE_REMOVED)
        addAction(Intent.ACTION_PACKAGE_CHANGED)
        addDataScheme("package")
    }

    /**
     * The context in which this monitor is registered.
     */
    private var registeredContext: Context? = null

    /**
     * Register this monitor via the given [context]. Throws an
     * [IllegalStateException] if this monitor was already registered.
     */
    fun register(context: Context) {
        if (registeredContext != null) {
            throw IllegalStateException("Already registered")
        }
        registeredContext = context
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(this, packageFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            context.registerReceiver(this, packageFilter)
        }
    }

    /**
     * Unregister this monitor. Throws an [IllegalStateException] if this
     * monitor wasn't registered.
     */
    fun unregister() {
        val context = registeredContext ?: throw IllegalStateException("Not registered")
        context.unregisterReceiver(this)
        registeredContext = null
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        val packageName = getPackageName(intent)
        val action = intent?.action
        when (action) {
            Intent.ACTION_PACKAGE_ADDED -> onPackageAdded(packageName)
            Intent.ACTION_PACKAGE_REMOVED -> onPackageRemoved(packageName)
            Intent.ACTION_PACKAGE_CHANGED -> onPackageChanged(packageName)
        }
    }

    /**
     * @return The name of the package from an [intent].
     */
    private fun getPackageName(intent: Intent?): String? {
        val uri: Uri = intent?.data ?: return null
        return uri.schemeSpecificPart
    }

    /**
     * Called when a new application package has been installed on the device.
     *
     * @param packageName The name of the package that was added.
     */
    protected abstract fun onPackageAdded(packageName: String?)

    /**
     * Called when an existing application package has been removed from the
     * device.
     *
     * @param packageName The name of the package that was removed.
     */
    protected abstract fun onPackageRemoved(packageName: String?)

    /**
     * Called when an existing application package has been changed (e.g. a
     * component has been disabled or enabled).
     *
     * @param packageName The name of the package that was changed.
     */
    protected abstract fun onPackageChanged(packageName: String?)
}
