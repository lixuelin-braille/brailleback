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


package com.googlecode.eyesfree.braille.service.display

import android.util.Log
import com.googlecode.eyesfree.braille.utils.ZipResourceExtractor
import java.io.File
import java.lang.ref.WeakReference

// One-shot keytables extraction; split out of DisplayService.kt.

class DataFileExtractor(service: DisplayService, rawResId: Int,
        outputDir: File) :
        ZipResourceExtractor(service.getApplicationContext(), rawResId,
                outputDir) {
    private val mService: WeakReference<DisplayService> =
            WeakReference(service)

    override fun onPostExecute(result: Int?) {
        val service = mService.get()
        if (service == null) {
            return
        }
        if (result == ZipResourceExtractor.RESULT_OK) {
            service.mDataFileState = DisplayService.FILES_EXTRACTED
            if (service.haveClients()) {
                service.connectBraille()
            }
            // The initial bind can arrive before data extraction
            // finishes, and the client callback may not be registered
            // yet. Check again shortly so startup never leaves the
            // display disconnected until the user taps "Status".
            service.mHandler.scheduleAutoReconnect(0)
        } else {
            Log.e(DisplayService.LOG_TAG, "Couldn't extract data files")
            // TODO: figure out a way to deal with this so a user
            // doesn't get stuck in this state.
            service.mDataFileState = DisplayService.FILES_ERROR
            service.broadcastConnectionState()
        }
    }
}
