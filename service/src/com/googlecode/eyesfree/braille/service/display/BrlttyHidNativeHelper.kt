/*
 * Copyright 2024 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.googlecode.eyesfree.braille.service.display

import android.accessibilityservice.BrailleDisplayController
import timber.log.Timber
import java.io.IOException

/**
 * A helper class that lets `hid_android.c` in brltty reach the
 * [BrailleDisplayController] APIs.
 *
 * A port of Google TalkBack's BrlttyHidNativeHelper, adjusted to the BrailleBack
 * package structure. The native `hid_android.c` bridge looks up
 * [getReportDescriptor], [writeBrailleDisplay] and [readBrailleDisplay] on
 * `BrlttyHidNativeHelper` by name, so they are `@JvmStatic` members of an
 * `object`: JNI resolves them as static methods of that exact class. Renaming
 * or de-staticising any of the three fails at runtime with a missing method id.
 *
 * [setup] and [onInput] are only reached from the API 35+ HID connection path;
 * see `@RequiresApi` at the call sites in [HidDisplayConnection].
 */
object BrlttyHidNativeHelper {

    private const val TAG = "HidAndroidHelper"

    private val mLock = Any()

    /** Reports the display sent, in arrival order, already normalised. */
    private val mInputReports = ArrayDeque<ByteArray>()

    private var mReportDescriptor: ByteArray? = null

    /** Input report shape derived from [mReportDescriptor]. */
    private var mInputReport: HidInputReportNormalizer.InputReport? = null

    private var mController: BrailleDisplayController? = null

    /**
     * Prepares a report descriptor for BRLTTY to interpret the format of
     * incoming reports.
     */
    @JvmStatic
    fun setup(controller: BrailleDisplayController?, descriptor: ByteArray?) {
        synchronized(mLock) {
            mController = controller
            mReportDescriptor = descriptor?.clone()
            mInputReport = HidInputReportNormalizer.singleInputReport(mReportDescriptor)
        }
        val report = mInputReport
        Timber.tag(TAG).i("HID input report: %s",
                if (report == null) "unnumbered/ambiguous"
                else "id=" + report.id + " payload=" + report.payloadBytes + "B")
    }

    /**
     * Saves the braille display's input to the input cache. The cache will be
     * read very soon after when `hid_android.c` is notified that some input is
     * available and calls [readBrailleDisplay].
     */
    @JvmStatic
    fun onInput(input: ByteArray?) {
        val data = input ?: return
        val normalized = HidInputReportNormalizer.normalize(data, mInputReport) ?: data
        synchronized(mLock) {
            if (normalized !== data) {
                // Log at warn level: the display is sending reports that
                // violate its own descriptor, and BRLTTY would otherwise drop
                // every key.
                Timber.tag("HidInput").w("restored report id: %dB %s -> %dB %s",
                        data.size, HidInputReportNormalizer.toHex(data),
                        normalized.size, HidInputReportNormalizer.toHex(normalized))
            } else {
                Timber.tag("HidInput").v("normalized %dB: %s",
                        data.size, HidInputReportNormalizer.toHex(data))
            }
            // The caller may reuse its buffer after this callback returns.
            mInputReports.add(normalized.clone())
        }
    }

    /** Resets the cached state, e.g. when the HID connection is torn down. */
    @JvmStatic
    fun reset() {
        synchronized(mLock) {
            mController = null
            mReportDescriptor = null
            mInputReport = null
            mInputReports.clear()
        }
    }

    /**
     * Returns report descriptor to BRLTTY. Could be null if the braille display
     * is not connected or the report descriptor is not set up in [setup].
     */
    @JvmStatic
    fun getReportDescriptor(): ByteArray? = synchronized(mLock) { mReportDescriptor }

    /** Outputs text to the braille display. */
    @JvmStatic
    fun writeBrailleDisplay(buffer: ByteArray) {
        val controller = synchronized(mLock) { mController }
        if (controller == null) {
            Timber.tag(TAG)
                    .e("write while BrailleDisplayController is null (connection torn down?)")
            return
        }
        Timber.tag(TAG).d("write %d bytes", buffer.size)
        try {
            controller.write(buffer)
        } catch (e: IOException) {
            Timber.tag(TAG).e(e, "Error writing to BrailleDisplayController")
            try {
                controller.disconnect()
            } catch (ignored: RuntimeException) {
                // The connection is already gone; nothing else to release.
            }
        } catch (e: RuntimeException) {
            // e.g. IllegalArgumentException for an over-sized buffer (bad report
            // layout): report it but keep the connection alive.
            Timber.tag(TAG).e(e, "BrailleDisplayController.write rejected the report")
        }
    }

    /** Reads the braille display's input from the cache saved in [onInput]. */
    @JvmStatic
    fun readBrailleDisplay(): ByteArray? = synchronized(mLock) {
        if (mInputReports.isEmpty()) null else mInputReports.removeFirst()
    }
}
