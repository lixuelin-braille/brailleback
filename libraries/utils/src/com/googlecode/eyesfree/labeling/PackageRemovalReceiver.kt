/*
 * Copyright (C) 2015 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.googlecode.eyesfree.labeling

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import com.googlecode.eyesfree.labeling.PackageLabelsFetchRequest.OnLabelsFetchedListener
import com.googlecode.eyesfree.utils.LogUtils

/**
 * {@link BroadcastReceiver} used to remove {@link Label}s when their
 * originating application is removed from the system.
 */
class PackageRemovalReceiver : BroadcastReceiver() {
    companion object {
        const val MIN_API_LEVEL: Int = CustomLabelManager.MIN_API_LEVEL

        private val INTENT_FILTER: IntentFilter = IntentFilter(Intent.ACTION_PACKAGE_REMOVED).apply {
            addDataScheme("package")
        }
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        if (Intent.ACTION_PACKAGE_REMOVED == intent.action) {
            val isUpgrade = intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)
            if (!isUpgrade) {
                val labelManager = CustomLabelManager(ctx)
                val packageName = intent.data.toString()
                LogUtils.log(
                    this, Log.VERBOSE, "Package %s removed.  Discarding associated labels.",
                    packageName
                )

                labelManager.getLabelsForPackageFromDatabase(packageName) { results: Map<String, Label>? ->
                    if (results != null && !results.isEmpty()) {
                        val labels = results.values
                        LogUtils.log(
                            this@PackageRemovalReceiver, Log.VERBOSE,
                            "Removing %d labels.", labels.size
                        )
                        for (l in labels) {
                            labelManager.removeLabel(l)
                        }
                    }
                    labelManager.shutdown()
                }
            }
        }
    }

    fun getFilter(): IntentFilter = INTENT_FILTER
}
