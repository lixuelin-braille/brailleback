/*
 * Copyright (C) 2023 Google Inc.
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

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.hardware.usb.UsbRequest

/**
 * Helps establish usb connection and provide BRLTTY native library requested methods.
 *
 * Port of Google TalkBack's UsbHelper, referenced by usb_android.c.
 */
object UsbHelper {
    /** Returns an iterator over all USB devices currently plugged in, as USBDevice instances. */
    @JvmStatic
    fun getDeviceIterator(context: Context): Iterator<UsbDevice> {
        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        return usbManager.deviceList.values.iterator()
    }

    /** Returns next usb device in the iterator. */
    @JvmStatic
    fun getNextDevice(iterator: Iterator<UsbDevice>): UsbDevice? {
        return if (iterator.hasNext()) iterator.next() else null
    }

    /** Gets the device interface that matches the identifier. */
    @JvmStatic
    fun getDeviceInterface(device: UsbDevice, identifier: Int): UsbInterface? {
        val count = device.interfaceCount
        for (index in 0 until count) {
            val intf = device.getInterface(index)
            if (identifier == intf.id) {
                return intf
            }
        }
        return null
    }

    /** Returns the value of an usb endpoint for a given endpoint interface. */
    @JvmStatic
    fun getInterfaceEndpoint(usbInterface: UsbInterface, address: Int): UsbEndpoint? {
        val count = usbInterface.endpointCount
        for (index in 0 until count) {
            val endpoint = usbInterface.getEndpoint(index)
            if (address == endpoint.address) {
                return endpoint
            }
        }
        return null
    }

    /** Opens usb connection for the requested device. */
    @JvmStatic
    fun openDeviceConnection(context: Context, device: UsbDevice): UsbDeviceConnection? {
        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        return usbManager.openDevice(device)
    }

    /** Cancels usb request. */
    @JvmStatic
    fun cancelRequest(request: UsbRequest) {
        request.cancel()
        request.close()
    }
}
